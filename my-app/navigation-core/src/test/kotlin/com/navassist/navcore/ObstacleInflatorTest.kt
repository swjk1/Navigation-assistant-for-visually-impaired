package com.navassist.navcore

import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.ObstacleInflator
import com.navassist.navcore.mapping.OccupancyGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObstacleInflatorTest {

    private fun freeGrid(cells: Int = 21): OccupancyGrid {
        val grid = OccupancyGrid(cells = cells, resolution = 0.1f)
        for (gz in 0 until cells) for (gx in 0 until cells) grid.setState(gx, gz, CellState.FREE)
        return grid
    }

    @Test
    fun `a single obstacle grows by the configured radius`() {
        val grid = freeGrid()
        grid.setState(10, 10, CellState.OCCUPIED)
        // 0.2 m radius at 0.1 m resolution = 2 cells.
        val config = NavigationConfig(inflationRadiusMeters = 0.2f, gridResolutionMeters = 0.1f)
        val inflated = ObstacleInflator(config).inflate(grid)

        assertTrue(inflated.isBlocked(10, 10), "the obstacle itself")
        assertTrue(inflated.isBlocked(12, 10), "2 cells away is inside the radius")
        assertTrue(inflated.isBlocked(12, 12), "diagonal within the radius")
        assertFalse(inflated.isBlocked(13, 10), "3 cells away is outside the radius")
        assertTrue(inflated.isTraversable(13, 10))
    }

    @Test
    fun `zero inflation blocks only the obstacle cells themselves`() {
        val grid = freeGrid()
        grid.setState(5, 5, CellState.OCCUPIED)
        val inflated = TestSupport.inflate(grid, TestSupport.bareConfig())
        assertTrue(inflated.isBlocked(5, 5))
        assertFalse(inflated.isBlocked(6, 5))
    }

    @Test
    fun `unknown space is never traversable`() {
        val grid = OccupancyGrid(cells = 10, resolution = 0.1f)
        val inflated = TestSupport.inflate(grid, TestSupport.bareConfig(1.0f))
        for (gz in 0 until 10) for (gx in 0 until 10) {
            assertTrue(inflated.isUnknown(gx, gz))
            assertFalse(inflated.isTraversable(gx, gz), "unknown must not be traversable")
        }
    }

    @Test
    fun `clearance penalty falls off with distance from the obstacle`() {
        val grid = freeGrid(31)
        grid.setState(15, 15, CellState.OCCUPIED)
        val config = NavigationConfig(
            gridResolutionMeters = 0.1f,
            inflationRadiusMeters = 0.1f,
            clearanceCostRadiusMeters = 0.5f,
        )
        val inflated = ObstacleInflator(config).inflate(grid)

        val near = inflated.clearancePenalty(17, 15)
        val far = inflated.clearancePenalty(18, 15)
        assertTrue(near > far, "penalty should shrink with distance: near=$near far=$far")
        assertEquals(0f, inflated.clearancePenalty(25, 15), "outside the radius the penalty is zero")
    }

    @Test
    fun `line of sight is blocked by an obstacle and clear otherwise`() {
        val grid = freeGrid()
        val config = TestSupport.bareConfig(2.1f)
        assertTrue(
            TestSupport.inflate(grid, config).hasLineOfSight(GridCoordinate(0, 0), GridCoordinate(20, 0)),
        )
        grid.setState(10, 0, CellState.OCCUPIED)
        assertFalse(
            TestSupport.inflate(grid, config).hasLineOfSight(GridCoordinate(0, 0), GridCoordinate(20, 0)),
        )
    }
}
