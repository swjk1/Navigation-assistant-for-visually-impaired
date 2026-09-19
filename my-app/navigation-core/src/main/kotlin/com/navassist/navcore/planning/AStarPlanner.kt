package com.navassist.navcore.planning

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.mapping.InflatedGrid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 8-connected A* over an [InflatedGrid].
 *
 * The planner knows nothing about ARCore, cameras or frames - only cells, traversability and
 * cost. That is what makes it unit-testable against synthetic maps and reusable on iOS.
 *
 * Cost model:
 *   step distance (metres)
 *   + obstacle-proximity penalty (keeps paths off walls)
 *   + turn penalty (discourages stair-stepping, which matters because the output is spoken
 *     LEFT / RIGHT guidance for a human, not motor commands)
 */
class AStarPlanner(private val config: NavigationConfig) {

    private var capacity = 0
    private lateinit var gScore: FloatArray
    private lateinit var cameFrom: IntArray
    private lateinit var arrivalDir: IntArray
    private lateinit var closed: BooleanArray
    private val open = IntBinaryHeap()

    /** dx/dz of the 8 neighbour directions, indexed by direction id. */
    private val dirX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val dirZ = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    private fun ensureCapacity(n: Int) {
        val size = n * n
        if (capacity == size) return
        capacity = size
        gScore = FloatArray(size)
        cameFrom = IntArray(size)
        arrivalDir = IntArray(size)
        closed = BooleanArray(size)
    }

    /**
     * @param allowUnknownNearGoal when true, UNKNOWN cells within
     *        [NavigationConfig.allowUnknownNearGoalCells] of the goal become passable. This is the
     *        ONLY situation in which the engine plans into unknown space, and it exists so that an
     *        exploration goal that sits exactly on a FREE/UNKNOWN frontier stays reachable.
     *        Ordinary navigation to a known target always passes false.
     * @return the cell path from start to goal inclusive, or null when no route exists.
     */
    fun plan(
        grid: InflatedGrid,
        start: GridCoordinate,
        goal: GridCoordinate,
        allowUnknownNearGoal: Boolean = false,
    ): List<GridCoordinate>? {
        val n = grid.cells
        if (!grid.inBounds(start)) return null
        ensureCapacity(n)

        val resolvedGoal = resolveGoal(grid, start, goal, allowUnknownNearGoal) ?: return null
        val startIndex = start.gz * n + start.gx
        val goalIndex = resolvedGoal.gz * n + resolvedGoal.gx
        if (startIndex == goalIndex) return listOf(start)

        val unknownBudget = if (allowUnknownNearGoal) config.allowUnknownNearGoalCells else 0

        gScore.fill(Float.MAX_VALUE)
        closed.fill(false)
        cameFrom.fill(-1)
        arrivalDir.fill(-1)
        open.clear()

        gScore[startIndex] = 0f
        open.push(startIndex, heuristic(start, resolvedGoal, grid.resolution))

        var expansions = 0
        val diagonalStep = sqrt(2f) * grid.resolution
        val straightStep = grid.resolution

        while (!open.isEmpty) {
            val currentIndex = open.pop()
            if (closed[currentIndex]) continue
            closed[currentIndex] = true
            if (currentIndex == goalIndex) return reconstruct(currentIndex, startIndex, n)
            if (++expansions > config.maxAStarExpansions) return null

            val cx = currentIndex % n
            val cz = currentIndex / n
            val currentG = gScore[currentIndex]
            val currentDir = arrivalDir[currentIndex]

            for (dir in 0 until 8) {
                val nx = cx + dirX[dir]
                val nz = cz + dirZ[dir]
                if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue
                val neighborIndex = nz * n + nx
                if (closed[neighborIndex]) continue
                if (!isPassable(grid, nx, nz, start, resolvedGoal, unknownBudget)) continue

                val diagonal = dirX[dir] != 0 && dirZ[dir] != 0
                if (diagonal) {
                    // Never cut a corner between two blocked cells.
                    if (!isPassable(grid, cx + dirX[dir], cz, start, resolvedGoal, unknownBudget)) continue
                    if (!isPassable(grid, cx, cz + dirZ[dir], start, resolvedGoal, unknownBudget)) continue
                }

                var stepCost = if (diagonal) diagonalStep else straightStep
                stepCost += grid.clearancePenalty(nx, nz) * grid.resolution
                if (currentDir >= 0 && currentDir != dir) {
                    val turn = min(abs(currentDir - dir), 8 - abs(currentDir - dir))
                    stepCost += config.turnPenalty * turn * grid.resolution
                }

                val tentative = currentG + stepCost
                if (tentative < gScore[neighborIndex]) {
                    gScore[neighborIndex] = tentative
                    cameFrom[neighborIndex] = currentIndex
                    arrivalDir[neighborIndex] = dir
                    val f = tentative + heuristic(GridCoordinate(nx, nz), resolvedGoal, grid.resolution)
                    open.push(neighborIndex, f)
                }
            }
        }
        return null
    }

    private fun isPassable(
        grid: InflatedGrid,
        gx: Int,
        gz: Int,
        start: GridCoordinate,
        goal: GridCoordinate,
        unknownBudget: Int,
    ): Boolean {
        if (!grid.inBounds(gx, gz)) return false
        if (grid.isBlocked(gx, gz)) {
            // The user routinely stands INSIDE the inflation halo (walls are closer than the body
            // radius in a real corridor). Refusing those cells would report NO_ROUTE while the
            // user is simply near a wall, so we let the path escape the halo around the start -
            // but never through a cell that is genuinely occupied (clearance 0).
            val escapeCells = config.inflationRadiusCells + 1
            if (GridCoordinate(gx, gz).chebyshevTo(start) > escapeCells) return false
            if (grid.clearanceCells(gx, gz) <= 0) return false
        }
        if (!grid.isUnknown(gx, gz)) return true
        if (unknownBudget <= 0) return false
        return GridCoordinate(gx, gz).chebyshevTo(goal) <= unknownBudget
    }

    /**
     * Inflation frequently swallows the exact goal cell (a frontier centroid sits right at the
     * edge of known space). Rather than reporting NO_ROUTE we snap to the closest passable cell
     * within a small window.
     */
    private fun resolveGoal(
        grid: InflatedGrid,
        start: GridCoordinate,
        goal: GridCoordinate,
        allowUnknownNearGoal: Boolean,
    ): GridCoordinate? {
        val budget = if (allowUnknownNearGoal) config.allowUnknownNearGoalCells else 0
        if (isPassable(grid, goal.gx, goal.gz, start, goal, budget)) return goal
        val maxRadius = max(2, config.inflationRadiusCells + 2)
        for (radius in 1..maxRadius) {
            var best: GridCoordinate? = null
            for (dz in -radius..radius) {
                for (dx in -radius..radius) {
                    if (max(abs(dx), abs(dz)) != radius) continue
                    val gx = goal.gx + dx
                    val gz = goal.gz + dz
                    if (isPassable(grid, gx, gz, start, goal, budget)) {
                        best = GridCoordinate(gx, gz)
                        break
                    }
                }
                if (best != null) break
            }
            if (best != null) return best
        }
        return null
    }

    /** Octile distance: the exact 8-connected metric, therefore admissible and consistent. */
    private fun heuristic(from: GridCoordinate, to: GridCoordinate, resolution: Float): Float {
        val dx = abs(from.gx - to.gx)
        val dz = abs(from.gz - to.gz)
        val diagonal = min(dx, dz)
        val straight = max(dx, dz) - diagonal
        return (diagonal * sqrt(2f) + straight) * resolution
    }

    private fun reconstruct(goalIndex: Int, startIndex: Int, n: Int): List<GridCoordinate> {
        val reversed = ArrayList<GridCoordinate>()
        var current = goalIndex
        while (current != -1) {
            reversed.add(GridCoordinate(current % n, current / n))
            if (current == startIndex) break
            current = cameFrom[current]
        }
        reversed.reverse()
        return reversed
    }
}
