package com.navassist.navcore.mapping

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.Vec2
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Rolling local 2D occupancy grid over the canonical X/Z navigation plane.
 *
 * Design notes
 * ------------
 * - Evidence is stored as LOG ODDS in a flat FloatArray, not as a hard enum. A single noisy depth
 *   reading therefore cannot permanently mark a corridor as blocked, and repeated observations
 *   reinforce each other. [CellState] is derived from thresholds.
 * - The grid is LOCAL and ROLLING: it covers `gridSizeMeters` around the user and shifts in whole
 *   cells as the user walks, so a whole building never has to be held at 10 cm resolution. Long
 *   range memory is the job of the topological map.
 * - Cell (0,0) occupies the world square [originX, originX+res) x [originZ, originZ+res).
 *   `gridToWorld` returns the CENTRE of a cell.
 *
 * This class is pure Kotlin: no Android, no ARCore, no platform clock.
 */
class OccupancyGrid(
    val cells: Int,
    val resolution: Float,
    originX: Float = 0f,
    originZ: Float = 0f,
    private val logOddsHit: Float = 0.85f,
    private val logOddsMiss: Float = -0.45f,
    private val logOddsMin: Float = -4f,
    private val logOddsMax: Float = 4f,
    private val occupiedThreshold: Float = 0.9f,
    private val freeThreshold: Float = -0.5f,
    private val decayPerSecondUnconfirmed: Float = 0.8f,
) {
    constructor(config: NavigationConfig, originX: Float = 0f, originZ: Float = 0f) : this(
        cells = config.gridCells,
        resolution = config.gridResolutionMeters,
        originX = originX,
        originZ = originZ,
        logOddsHit = config.logOddsHit,
        logOddsMiss = config.logOddsMiss,
        logOddsMin = config.logOddsMin,
        logOddsMax = config.logOddsMax,
        occupiedThreshold = config.logOddsOccupiedThreshold,
        freeThreshold = config.logOddsFreeThreshold,
        decayPerSecondUnconfirmed = config.decayPerSecondUnconfirmed,
    )

    var originX: Float = originX
        private set

    var originZ: Float = originZ
        private set

    /** Flat log-odds evidence buffer, reused for the lifetime of the grid (no per-frame alloc). */
    private val logOdds = FloatArray(cells * cells)

    val sizeMeters: Float get() = cells * resolution

    /** World-space centre of the grid. */
    fun centerWorld(): Vec2 = Vec2(originX + sizeMeters * 0.5f, originZ + sizeMeters * 0.5f)

    // ------------------------------------------------------------------ coordinate conversion

    fun worldToGrid(world: Vec2): GridCoordinate = GridCoordinate(
        floor((world.x - originX) / resolution).toInt(),
        floor((world.z - originZ) / resolution).toInt(),
    )

    /** Returns the world-space CENTRE of the given cell. */
    fun gridToWorld(coord: GridCoordinate): Vec2 = gridToWorld(coord.gx, coord.gz)

    fun gridToWorld(gx: Int, gz: Int): Vec2 = Vec2(
        originX + (gx + 0.5f) * resolution,
        originZ + (gz + 0.5f) * resolution,
    )

    fun inBounds(gx: Int, gz: Int): Boolean = gx >= 0 && gz >= 0 && gx < cells && gz < cells

    fun inBounds(coord: GridCoordinate): Boolean = inBounds(coord.gx, coord.gz)

    fun contains(world: Vec2): Boolean = inBounds(worldToGrid(world))

    private fun index(gx: Int, gz: Int): Int = gz * cells + gx

    // ------------------------------------------------------------------ reads

    fun logOddsAt(gx: Int, gz: Int): Float = if (inBounds(gx, gz)) logOdds[index(gx, gz)] else 0f

    fun logOddsAt(coord: GridCoordinate): Float = logOddsAt(coord.gx, coord.gz)

    fun stateAt(gx: Int, gz: Int): CellState {
        if (!inBounds(gx, gz)) return CellState.UNKNOWN
        val value = logOdds[index(gx, gz)]
        return when {
            value >= occupiedThreshold -> CellState.OCCUPIED
            value <= freeThreshold -> CellState.FREE
            else -> CellState.UNKNOWN
        }
    }

    fun stateAt(coord: GridCoordinate): CellState = stateAt(coord.gx, coord.gz)

    fun stateAtWorld(world: Vec2): CellState = stateAt(worldToGrid(world))

    /**
     * Fraction of cells within [radiusMeters] of [center] that have been observed.
     *
     * This - not the whole-window fraction - is the engine's map confidence. A 12 m window is
     * mostly out of sensor range at any instant, so the global fraction stays low forever and
     * would never clear a sensible threshold. What actually matters before issuing a movement
     * command is whether the user's IMMEDIATE surroundings are known.
     */
    fun knownFractionWithin(center: Vec2, radiusMeters: Float): Float {
        val centerCell = worldToGrid(center)
        val radiusCells = kotlin.math.ceil(radiusMeters / resolution).toInt()
        val radiusSq = radiusCells * radiusCells
        var known = 0
        var total = 0
        for (dz in -radiusCells..radiusCells) {
            for (dx in -radiusCells..radiusCells) {
                if (dx * dx + dz * dz > radiusSq) continue
                val gx = centerCell.gx + dx
                val gz = centerCell.gz + dz
                if (!inBounds(gx, gz)) continue
                total++
                if (stateAt(gx, gz) != CellState.UNKNOWN) known++
            }
        }
        return if (total == 0) 0f else known.toFloat() / total.toFloat()
    }

    fun stats(): GridStats {
        var free = 0
        var occupied = 0
        var unknown = 0
        for (value in logOdds) {
            when {
                value >= occupiedThreshold -> occupied++
                value <= freeThreshold -> free++
                else -> unknown++
            }
        }
        return GridStats(free, occupied, unknown)
    }

    // ------------------------------------------------------------------ writes

    fun addEvidence(gx: Int, gz: Int, delta: Float) {
        if (!inBounds(gx, gz)) return
        val i = index(gx, gz)
        var value = logOdds[i] + delta
        if (value > logOddsMax) value = logOddsMax
        if (value < logOddsMin) value = logOddsMin
        logOdds[i] = value
    }

    fun markOccupied(gx: Int, gz: Int) = addEvidence(gx, gz, logOddsHit)

    fun markFree(gx: Int, gz: Int) = addEvidence(gx, gz, logOddsMiss)

    /**
     * Test / bootstrap helper: forces a cell to a hard state. Real sensor data should go through
     * [integrateRay] so that free space is accumulated as evidence rather than asserted.
     */
    fun setState(gx: Int, gz: Int, state: CellState) {
        if (!inBounds(gx, gz)) return
        logOdds[index(gx, gz)] = when (state) {
            CellState.OCCUPIED -> logOddsMax
            CellState.FREE -> logOddsMin
            CellState.UNKNOWN -> 0f
        }
    }

    fun clear() {
        logOdds.fill(0f)
    }

    // ------------------------------------------------------------------ ray integration

    /**
     * Integrates one depth observation: every cell between the sensor and the measured point gets
     * FREE evidence, and the endpoint gets OCCUPIED evidence when [endpointOccupied] is true.
     *
     * Without this, the map would contain obstacle dots and no observed free space at all, and
     * frontier detection (which needs a FREE/UNKNOWN boundary) could never work.
     *
     * Cells outside the grid are skipped rather than clamped, so a ray that leaves the local
     * window does not smear evidence along the border.
     */
    fun integrateRay(from: Vec2, to: Vec2, endpointOccupied: Boolean) {
        val start = worldToGrid(from)
        val end = worldToGrid(to)
        traceLine(start.gx, start.gz, end.gx, end.gz) { gx, gz, isEndpoint ->
            if (isEndpoint) {
                if (endpointOccupied) markOccupied(gx, gz) else markFree(gx, gz)
            } else {
                markFree(gx, gz)
            }
        }
    }

    // ------------------------------------------------------------------ decay

    /**
     * Fades UNCONFIRMED evidence back towards UNKNOWN so a stray depth return does not linger as
     * a phantom obstacle.
     *
     * Cells that reached a decided state (FREE or OCCUPIED) are deliberately left alone. Decay
     * runs over the whole window every frame, but the depth sensor only ever sees a narrow cone
     * of it, so a time-based rule erases the corridor behind the user - and nothing out of view
     * can re-observe those cells to offset it. That asymmetry is what made the map hold only the
     * last few seconds of scans.
     *
     * Confirmed map data therefore changes in exactly two ways: counter-evidence from looking at
     * the place again (a departed obstacle is cleared by rays passing through it - see
     * [integrateRay]), and scrolling out of the rolling window (see [recenter]). Memory beyond
     * the window stays the topological map's job.
     */
    fun applyDecay(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return
        val factor = decayPerSecondUnconfirmed.toDouble().pow(deltaSeconds.toDouble()).toFloat()
        for (i in logOdds.indices) {
            val value = logOdds[i]
            if (value == 0f) continue
            // Decided cells are map, not noise: only re-observation may overturn them.
            if (value >= occupiedThreshold || value <= freeThreshold) continue
            val decayed = value * factor
            logOdds[i] = if (abs(decayed) < 0.02f) 0f else decayed
        }
    }

    // ------------------------------------------------------------------ rolling window

    /**
     * Shifts the window so that [center] sits at the middle of the grid, preserving the
     * overlapping evidence and clearing everything that scrolled in. Shifts happen in whole cells
     * so no resampling error accumulates.
     *
     * Returns true when the window actually moved.
     */
    fun recenter(center: Vec2): Boolean {
        val half = sizeMeters * 0.5f
        val desiredOriginX = center.x - half
        val desiredOriginZ = center.z - half
        val shiftX = kotlin.math.round((desiredOriginX - originX) / resolution).toInt()
        val shiftZ = kotlin.math.round((desiredOriginZ - originZ) / resolution).toInt()
        if (shiftX == 0 && shiftZ == 0) return false

        if (abs(shiftX) >= cells || abs(shiftZ) >= cells) {
            // Teleport (or tracking reset): nothing overlaps, start clean.
            logOdds.fill(0f)
        } else {
            shiftContents(shiftX, shiftZ)
        }
        originX += shiftX * resolution
        originZ += shiftZ * resolution
        return true
    }

    /** Moves cell contents by (-shiftX, -shiftZ) because the window origin moved by (+shift). */
    private fun shiftContents(shiftX: Int, shiftZ: Int) {
        val zRange = if (shiftZ > 0) 0 until cells else cells - 1 downTo 0
        for (gz in zRange) {
            val srcZ = gz + shiftZ
            val xRange = if (shiftX > 0) 0 until cells else cells - 1 downTo 0
            for (gx in xRange) {
                val srcX = gx + shiftX
                logOdds[index(gx, gz)] =
                    if (srcX in 0 until cells && srcZ in 0 until cells) logOdds[index(srcX, srcZ)] else 0f
            }
        }
    }

    companion object {
        /**
         * Integer Bresenham line walk. [visit] receives every cell on the line; the final cell is
         * flagged as the endpoint so callers can treat it as the measured obstacle.
         */
        inline fun traceLine(x0: Int, z0: Int, x1: Int, z1: Int, visit: (Int, Int, Boolean) -> Unit) {
            var x = x0
            var z = z0
            val dx = abs(x1 - x0)
            val dz = abs(z1 - z0)
            val sx = if (x0 < x1) 1 else -1
            val sz = if (z0 < z1) 1 else -1
            var err = dx - dz
            val guard = max(dx, dz) + 1
            var steps = 0
            while (true) {
                val isEndpoint = x == x1 && z == z1
                visit(x, z, isEndpoint)
                if (isEndpoint || steps++ > guard) break
                val e2 = 2 * err
                if (e2 > -dz) {
                    err -= dz
                    x += sx
                }
                if (e2 < dx) {
                    err += dx
                    z += sz
                }
            }
        }
    }
}
