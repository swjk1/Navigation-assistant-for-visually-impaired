package com.navassist.navcore

import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.planning.AStarPlanner
import com.navassist.navcore.planning.PathSmoother
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AStarPlannerTest {

    private val config = TestSupport.bareConfig()
    private val planner = AStarPlanner(config)

    private fun plan(
        rows: List<String>,
        start: GridCoordinate,
        goal: GridCoordinate,
        allowUnknownNearGoal: Boolean = false,
    ): List<GridCoordinate>? {
        val grid = TestSupport.gridFromAscii(rows)
        val inflated = TestSupport.inflate(grid, config)
        return planner.plan(inflated, start, goal, allowUnknownNearGoal)
    }

    /** Scenario: empty room. */
    @Test
    fun `finds a direct route across an empty room`() {
        val rows = List(10) { ".".repeat(10) }
        val path = plan(rows, GridCoordinate(0, 0), GridCoordinate(9, 9))
        assertNotNull(path)
        assertEquals(GridCoordinate(0, 0), path.first())
        assertEquals(GridCoordinate(9, 9), path.last())
        // Pure diagonal: 10 cells including both ends.
        assertEquals(10, path.size, "expected the diagonal, got $path")
    }

    /** Scenario: straight corridor. */
    @Test
    fun `follows a straight corridor`() {
        val rows = listOf(
            "##########",
            "..........",
            "##########",
        ) + List(7) { "##########" }
        val path = plan(rows, GridCoordinate(0, 1), GridCoordinate(9, 1))
        assertNotNull(path)
        assertEquals(10, path.size)
        assertTrue(path.all { it.gz == 1 }, "the path must stay in the corridor: $path")
    }

    /** Scenario: wall blocking the direct route, with a gap. */
    @Test
    fun `routes around a wall through the only gap`() {
        val rows = listOf(
            "..........",
            "..........",
            "..........",
            "#####.####",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
        )
        val path = plan(rows, GridCoordinate(1, 1), GridCoordinate(8, 8))
        assertNotNull(path)
        val crossing = path.filter { it.gz == 3 }
        assertTrue(crossing.isNotEmpty(), "the path has to cross the wall row")
        assertTrue(crossing.all { it.gx == 5 }, "it may only cross through the gap: $crossing")
    }

    /** Scenario: obstacle in the middle with free space around it (spec scenario 5). */
    @Test
    fun `routes around a free standing obstacle`() {
        val rows = MutableList(11) { ".".repeat(11) }
        // A 3x3 block in the middle of the room.
        for (gz in 4..6) {
            rows[gz] = rows[gz].toCharArray().also { row -> for (gx in 4..6) row[gx] = '#' }
                .concatToString()
        }
        val path = plan(rows, GridCoordinate(0, 5), GridCoordinate(10, 5))
        assertNotNull(path)
        assertTrue(
            path.none { it.gx in 4..6 && it.gz in 4..6 },
            "the path must not cross the obstacle: $path",
        )
        assertEquals(GridCoordinate(10, 5), path.last())
    }

    /** Scenario: no route at all. */
    @Test
    fun `reports no route when the goal is walled off`() {
        val rows = listOf(
            "..........",
            "..........",
            "##########",
            "..........",
            "..........",
        ) + List(5) { ".........." }
        assertNull(plan(rows, GridCoordinate(0, 0), GridCoordinate(9, 4)))
    }

    @Test
    fun `never plans through unknown space by default`() {
        val rows = listOf(
            "..........",
            "..........",
            "??????????",
            "..........",
            "..........",
        ) + List(5) { ".........." }
        assertNull(
            plan(rows, GridCoordinate(0, 0), GridCoordinate(9, 4)),
            "unknown space must not silently become free space",
        )
    }

    @Test
    fun `may touch unknown space right at an exploration goal`() {
        val rows = listOf(
            "..........",
            "..........",
            "..........",
            "..........",
            "..?.......",
        ) + List(5) { ".........." }
        val path = plan(rows, GridCoordinate(0, 0), GridCoordinate(2, 4), allowUnknownNearGoal = true)
        assertNotNull(path, "an exploration goal on the frontier must stay reachable")
        assertEquals(GridCoordinate(2, 4), path.last())
    }

    @Test
    fun `a goal swallowed by inflation snaps to the nearest reachable cell`() {
        val grid = OccupancyGrid(cells = 21, resolution = 0.1f)
        for (gz in 0 until 21) for (gx in 0 until 21) grid.setState(gx, gz, CellState.FREE)
        grid.setState(15, 10, CellState.OCCUPIED)
        val inflatedConfig = NavigationConfig(
            gridResolutionMeters = 0.1f,
            inflationRadiusMeters = 0.2f,
            clearanceCostRadiusMeters = 0.2f,
        )
        val inflated = TestSupport.inflate(grid, inflatedConfig)
        val path = AStarPlanner(inflatedConfig).plan(inflated, GridCoordinate(0, 10), GridCoordinate(15, 10))
        assertNotNull(path, "the planner should approach an unreachable goal rather than give up")
        assertTrue(path.last().chebyshevTo(GridCoordinate(15, 10)) <= 4)
    }

    @Test
    fun `path smoothing removes the stair stepping`() {
        val rows = List(20) { ".".repeat(20) }
        val grid = TestSupport.gridFromAscii(rows)
        val inflated = TestSupport.inflate(grid, config)
        val raw = planner.plan(inflated, GridCoordinate(0, 0), GridCoordinate(19, 7))
        assertNotNull(raw)
        val smoothed = PathSmoother.smooth(inflated, raw)
        assertTrue(smoothed.size < raw.size, "smoothing should shorten ${raw.size} points")
        assertEquals(raw.first(), smoothed.first())
        assertEquals(raw.last(), smoothed.last())
    }

    @Test
    fun `smoothing never shortcuts through an obstacle`() {
        val rows = listOf(
            "..........",
            "..........",
            "#####.####",
            "..........",
            "..........",
        ) + List(5) { ".........." }
        val grid = TestSupport.gridFromAscii(rows)
        val inflated = TestSupport.inflate(grid, config)
        val raw = planner.plan(inflated, GridCoordinate(1, 0), GridCoordinate(8, 4))
        assertNotNull(raw)
        val smoothed = PathSmoother.smooth(inflated, raw)
        for (i in 0 until smoothed.size - 1) {
            assertTrue(
                inflated.hasLineOfSight(smoothed[i], smoothed[i + 1]),
                "segment ${smoothed[i]} -> ${smoothed[i + 1]} crosses an obstacle",
            )
        }
    }
}
