package com.navassist.navcore.exploration

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.mapping.OccupancyGrid
import kotlin.math.min

/**
 * Finds FREE cells that touch UNKNOWN cells, clusters them, and reports each cluster as a
 * [Frontier].
 *
 * Pipeline:
 *   occupancy grid -> frontier cells -> connected clusters -> reject noise -> centroid -> Frontier
 *
 * Noise rejection matters a lot in practice: depth is speckly, so a raw frontier map is full of
 * one- and two-cell specks that would make the engine oscillate between imaginary openings.
 */
class FrontierDetector(private val config: NavigationConfig) {

    private var cells = 0
    private lateinit var isFrontier: BooleanArray
    private lateinit var visited: BooleanArray
    private lateinit var stack: IntArray
    private lateinit var members: IntArray
    private var idCounter = 0

    private fun ensureCapacity(n: Int) {
        if (cells == n) return
        cells = n
        val size = n * n
        isFrontier = BooleanArray(size)
        visited = BooleanArray(size)
        stack = IntArray(size)
        members = IntArray(size)
    }

    fun resetIds() {
        idCounter = 0
    }

    /**
     * @param inflated when given, each frontier's representative cell is the member nearest the
     *        centroid that is NOT inside an obstacle's inflation halo. A frontier running along a
     *        wall is still reachable at its open end; judging it by a centroid that happens to sit
     *        in the halo discarded it, and with it often the only way forward.
     */
    fun detect(grid: OccupancyGrid, inflated: InflatedGrid? = null): List<Frontier> {
        val n = grid.cells
        ensureCapacity(n)
        isFrontier.fill(false)
        visited.fill(false)

        // Pass 1 - mark FREE cells with at least one 4-connected UNKNOWN neighbour.
        //
        // The border ring is deliberately excluded. Out-of-bounds reads report UNKNOWN, so
        // including it would ring the whole rolling window with phantom frontiers that are an
        // artefact of the window size rather than real unexplored space. Genuine unexplored space
        // always shows up as interior UNKNOWN cells, because the depth sensor's useful range is
        // shorter than half the window.
        for (gz in 1 until n - 1) {
            for (gx in 1 until n - 1) {
                if (grid.stateAt(gx, gz) != CellState.FREE) continue
                if (hasUnknownNeighbour(grid, gx, gz)) isFrontier[gz * n + gx] = true
            }
        }

        // Pass 2 - flood fill into 8-connected clusters.
        val frontiers = ArrayList<Frontier>()
        for (start in 0 until n * n) {
            if (!isFrontier[start] || visited[start]) continue
            var top = 0
            stack[top++] = start
            visited[start] = true
            var sumX = 0L
            var sumZ = 0L
            var count = 0
            while (top > 0) {
                val current = stack[--top]
                val cx = current % n
                val cz = current / n
                sumX += cx
                sumZ += cz
                members[count] = current
                count++
                var dz = -1
                while (dz <= 1) {
                    var dx = -1
                    while (dx <= 1) {
                        if (dx != 0 || dz != 0) {
                            val nx = cx + dx
                            val nz = cz + dz
                            if (nx in 0 until n && nz in 0 until n) {
                                val ni = nz * n + nx
                                if (isFrontier[ni] && !visited[ni]) {
                                    visited[ni] = true
                                    if (top < stack.size) stack[top++] = ni
                                }
                            }
                        }
                        dx++
                    }
                    dz++
                }
            }

            if (count < config.minFrontierCells) continue

            for ((from, size) in segments(n, count, grid.resolution)) {
                var segX = 0L
                var segZ = 0L
                for (k in from until from + size) {
                    segX += members[k] % n
                    segZ += members[k] / n
                }
                val centroidX = (segX.toDouble() / size).toInt()
                val centroidZ = (segZ.toDouble() / size).toInt()
                val centroidCell = inflated?.let { openMemberNearest(it, n, from, size, centroidX, centroidZ) }
                    ?: nearestMember(n, from, size, centroidX, centroidZ)
                frontiers.add(
                    Frontier(
                        id = "F${idCounter++}",
                        centroid = grid.gridToWorld(centroidCell),
                        centroidCell = centroidCell,
                        cellCount = size,
                        estimatedInformationGain = informationGain(grid, centroidCell),
                    ),
                )
            }
        }
        return frontiers
    }

    /**
     * Splits the cluster in members[0, count) into pieces no longer than
     * [NavigationConfig.frontierSegmentMeters], reordering [members] so each piece is contiguous.
     * Returns (start, size) of every piece big enough to keep. A short cluster is one piece.
     *
     * Pieces are square buckets of the cluster's bounding box: cheap, deterministic, and enough to
     * turn one arc round the user into waypoints spread along it.
     */
    private fun segments(n: Int, count: Int, resolution: Float): List<Pair<Int, Int>> {
        var minX = Int.MAX_VALUE; var minZ = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (k in 0 until count) {
            val x = members[k] % n
            val z = members[k] / n
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
        }
        val side = kotlin.math.max(1, (config.frontierSegmentMeters / resolution).toInt())
        if (maxX - minX < side && maxZ - minZ < side) return listOf(0 to count)
        val across = (maxX - minX) / side + 1
        fun bucket(cell: Int) = ((cell / n - minZ) / side) * across + (cell % n - minX) / side
        val sorted = (0 until count).map { members[it] }.sortedWith(compareBy({ bucket(it) }, { it }))
        val pieces = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (k in sorted.indices) {
            members[k] = sorted[k]
            val last = k == sorted.lastIndex || bucket(sorted[k + 1]) != bucket(sorted[k])
            if (last) {
                val size = k + 1 - start
                if (size >= config.minFrontierCells) pieces += start to size
                start = k + 1
            }
        }
        return pieces
    }

    private fun nearestMember(n: Int, from: Int, size: Int, gx: Int, gz: Int): GridCoordinate {
        var best = members[from]
        var bestDistance = Int.MAX_VALUE
        for (k in from until from + size) {
            val x = members[k] % n
            val z = members[k] / n
            val d = (x - gx) * (x - gx) + (z - gz) * (z - gz)
            if (d < bestDistance) { bestDistance = d; best = members[k] }
        }
        return GridCoordinate(best % n, best / n)
    }

    private fun openMemberNearest(inflated: InflatedGrid, n: Int, from: Int, size: Int, gx: Int, gz: Int): GridCoordinate? {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (k in from until from + size) {
            val cell = members[k]
            val x = cell % n
            val z = cell / n
            if (inflated.isBlocked(x, z)) continue
            val d = (x - gx) * (x - gx) + (z - gz) * (z - gz)
            if (d < bestDistance) {
                bestDistance = d
                best = cell
            }
        }
        return if (best < 0) null else GridCoordinate(best % n, best / n)
    }

    private fun hasUnknownNeighbour(grid: OccupancyGrid, gx: Int, gz: Int): Boolean =
        grid.stateAt(gx + 1, gz) == CellState.UNKNOWN ||
            grid.stateAt(gx - 1, gz) == CellState.UNKNOWN ||
            grid.stateAt(gx, gz + 1) == CellState.UNKNOWN ||
            grid.stateAt(gx, gz - 1) == CellState.UNKNOWN

    /** Fraction of cells around the frontier that are still unknown, normalised to 0..1. */
    private fun informationGain(grid: OccupancyGrid, cell: GridCoordinate): Float {
        val r = config.informationGainRadiusCells
        var unknown = 0
        var total = 0
        for (dz in -r..r) {
            for (dx in -r..r) {
                val gx = cell.gx + dx
                val gz = cell.gz + dz
                if (!grid.inBounds(gx, gz)) continue
                total++
                if (grid.stateAt(gx, gz) == CellState.UNKNOWN) unknown++
            }
        }
        if (total == 0) return 0f
        return min(1f, unknown.toFloat() / total.toFloat())
    }
}
