package com.navassist.navcore.exploration

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.mapping.CellState
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
    private var idCounter = 0

    private fun ensureCapacity(n: Int) {
        if (cells == n) return
        cells = n
        val size = n * n
        isFrontier = BooleanArray(size)
        visited = BooleanArray(size)
        stack = IntArray(size)
    }

    fun resetIds() {
        idCounter = 0
    }

    fun detect(grid: OccupancyGrid): List<Frontier> {
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

            val centroidX = (sumX.toDouble() / count).toInt()
            val centroidZ = (sumZ.toDouble() / count).toInt()
            val centroidCell = nearestFrontierCell(n, centroidX, centroidZ)
            val centroidWorld = grid.gridToWorld(centroidCell)
            frontiers.add(
                Frontier(
                    id = "F${idCounter++}",
                    centroid = centroidWorld,
                    centroidCell = centroidCell,
                    cellCount = count,
                    estimatedInformationGain = informationGain(grid, centroidCell),
                ),
            )
        }
        return frontiers
    }

    private fun hasUnknownNeighbour(grid: OccupancyGrid, gx: Int, gz: Int): Boolean =
        grid.stateAt(gx + 1, gz) == CellState.UNKNOWN ||
            grid.stateAt(gx - 1, gz) == CellState.UNKNOWN ||
            grid.stateAt(gx, gz + 1) == CellState.UNKNOWN ||
            grid.stateAt(gx, gz - 1) == CellState.UNKNOWN

    /**
     * The arithmetic centroid of a curved cluster can land off the cluster itself (e.g. an
     * L-shaped opening), which would hand the planner a goal inside a wall. Snap back to the
     * closest actual frontier cell.
     */
    private fun nearestFrontierCell(n: Int, gx: Int, gz: Int): GridCoordinate {
        if (gx in 0 until n && gz in 0 until n && isFrontier[gz * n + gx]) {
            return GridCoordinate(gx, gz)
        }
        for (radius in 1..n) {
            for (dz in -radius..radius) {
                for (dx in -radius..radius) {
                    if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dz)) != radius) continue
                    val nx = gx + dx
                    val nz = gz + dz
                    if (nx in 0 until n && nz in 0 until n && isFrontier[nz * n + nx]) {
                        return GridCoordinate(nx, nz)
                    }
                }
            }
        }
        return GridCoordinate(gx, gz)
    }

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
