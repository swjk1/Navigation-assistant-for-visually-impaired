package com.navassist.navcore.mapping

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.Vec2

/**
 * The planning view of the world: obstacles grown by the user's body radius, plus a clearance
 * field used to keep planned paths away from walls.
 *
 * A cell is traversable only when it is FREE in the source grid AND outside every obstacle's
 * inflation radius. UNKNOWN is never traversable here - unknown space must not silently become
 * free space (see the frontier machinery for the one place unknown space is deliberately
 * approached).
 */
class InflatedGrid(
    val cells: Int,
    val resolution: Float,
    val originX: Float,
    val originZ: Float,
    private val blocked: BooleanArray,
    private val unknown: BooleanArray,
    /** Chebyshev distance in cells to the nearest OCCUPIED cell, capped at [maxClearanceCells]. */
    private val clearance: IntArray,
    private val maxClearanceCells: Int,
    private val clearanceWeight: Float,
) {
    private fun index(gx: Int, gz: Int) = gz * cells + gx

    fun inBounds(gx: Int, gz: Int): Boolean = gx >= 0 && gz >= 0 && gx < cells && gz < cells

    fun inBounds(coord: GridCoordinate): Boolean = inBounds(coord.gx, coord.gz)

    /** True when the cell is inside an obstacle or its inflation halo. */
    fun isBlocked(gx: Int, gz: Int): Boolean = !inBounds(gx, gz) || blocked[index(gx, gz)]

    fun isBlocked(coord: GridCoordinate): Boolean = isBlocked(coord.gx, coord.gz)

    fun isUnknown(gx: Int, gz: Int): Boolean = !inBounds(gx, gz) || unknown[index(gx, gz)]

    fun isUnknown(coord: GridCoordinate): Boolean = isUnknown(coord.gx, coord.gz)

    /** Ordinary local navigation: known free space only. */
    fun isTraversable(gx: Int, gz: Int): Boolean =
        inBounds(gx, gz) && !blocked[index(gx, gz)] && !unknown[index(gx, gz)]

    fun isTraversable(coord: GridCoordinate): Boolean = isTraversable(coord.gx, coord.gz)

    fun clearanceCells(gx: Int, gz: Int): Int =
        if (inBounds(gx, gz)) clearance[index(gx, gz)] else 0

    /**
     * Extra A* cost for hugging an obstacle. Zero once the cell is further than the clearance
     * radius; grows linearly as the cell approaches the inflation boundary.
     */
    fun clearancePenalty(gx: Int, gz: Int): Float {
        if (maxClearanceCells <= 0) return 0f
        val d = clearanceCells(gx, gz)
        if (d >= maxClearanceCells) return 0f
        val closeness = 1f - d.toFloat() / maxClearanceCells.toFloat()
        return clearanceWeight * closeness * closeness
    }

    fun gridToWorld(coord: GridCoordinate): Vec2 =
        Vec2(originX + (coord.gx + 0.5f) * resolution, originZ + (coord.gz + 0.5f) * resolution)

    fun worldToGrid(world: Vec2): GridCoordinate = GridCoordinate(
        kotlin.math.floor((world.x - originX) / resolution).toInt(),
        kotlin.math.floor((world.z - originZ) / resolution).toInt(),
    )

    /**
     * Straight-line traversability check between two cells, used by the path smoother and by the
     * forward-safety check. Conservative: any blocked or unknown cell on the line fails.
     */
    fun hasLineOfSight(from: GridCoordinate, to: GridCoordinate): Boolean {
        var clear = true
        OccupancyGrid.traceLine(from.gx, from.gz, to.gx, to.gz) { gx, gz, _ ->
            if (clear && !isTraversable(gx, gz)) clear = false
        }
        return clear
    }
}

/**
 * Builds [InflatedGrid]s from an [OccupancyGrid]. Buffers are allocated once and reused so that
 * re-inflating at ~5 Hz does not churn the heap.
 */
class ObstacleInflator(private val config: NavigationConfig) {

    private var cells = 0
    private lateinit var blocked: BooleanArray
    private lateinit var unknown: BooleanArray
    private lateinit var clearance: IntArray
    private lateinit var queue: IntArray

    private fun ensureCapacity(n: Int) {
        if (cells == n) return
        cells = n
        val size = n * n
        blocked = BooleanArray(size)
        unknown = BooleanArray(size)
        clearance = IntArray(size)
        queue = IntArray(size)
    }

    fun inflate(grid: OccupancyGrid): InflatedGrid {
        val n = grid.cells
        ensureCapacity(n)
        val size = n * n
        val inflationCells = config.inflationRadiusCells
        val maxClearance = maxOf(config.clearanceCostRadiusCells, inflationCells + 1)

        // Multi-source BFS seeded with every OCCUPIED cell yields the Chebyshev distance field.
        var head = 0
        var tail = 0
        for (gz in 0 until n) {
            for (gx in 0 until n) {
                val i = gz * n + gx
                val state = grid.stateAt(gx, gz)
                unknown[i] = state == CellState.UNKNOWN
                if (state == CellState.OCCUPIED) {
                    clearance[i] = 0
                    queue[tail++] = i
                } else {
                    clearance[i] = maxClearance
                }
            }
        }

        while (head < tail) {
            val i = queue[head++]
            val d = clearance[i]
            if (d >= maxClearance) continue
            val gx = i % n
            val gz = i / n
            var dz = -1
            while (dz <= 1) {
                var dx = -1
                while (dx <= 1) {
                    if (dx != 0 || dz != 0) {
                        val nx = gx + dx
                        val nz = gz + dz
                        if (nx in 0 until n && nz in 0 until n) {
                            val ni = nz * n + nx
                            if (clearance[ni] > d + 1) {
                                clearance[ni] = d + 1
                                if (tail < size) queue[tail++] = ni
                            }
                        }
                    }
                    dx++
                }
                dz++
            }
        }

        for (i in 0 until size) {
            blocked[i] = clearance[i] <= inflationCells
        }

        return InflatedGrid(
            cells = n,
            resolution = grid.resolution,
            originX = grid.originX,
            originZ = grid.originZ,
            blocked = blocked.copyOf(),
            unknown = unknown.copyOf(),
            clearance = clearance.copyOf(),
            maxClearanceCells = maxClearance,
            clearanceWeight = config.clearanceCostWeight,
        )
    }
}
