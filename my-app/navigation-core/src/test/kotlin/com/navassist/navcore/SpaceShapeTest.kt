package com.navassist.navcore

import com.navassist.navcore.exploration.SpaceShape
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.OccupancyGrid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpaceShapeTest {
    private val config = NavigationConfig()

    /** A 12 m grid centred on the origin: free floor where [free] says, obstacles where [obstacle] says. */
    private fun grid(free: (Float, Float) -> Boolean, obstacle: (Float, Float) -> Boolean = { _, _ -> false }): InflatedGrid {
        val grid = OccupancyGrid(config, originX = -6f, originZ = -6f)
        for (gz in 0 until grid.cells) for (gx in 0 until grid.cells) {
            val world = grid.gridToWorld(gx, gz)
            when {
                obstacle(world.x, world.z) -> grid.setState(gx, gz, CellState.OCCUPIED)
                free(world.x, world.z) -> grid.setState(gx, gz, CellState.FREE)
            }
        }
        return TestSupport.inflate(grid, config)
    }

    @Test
    fun `a corridor along x has its long axis along x`() {
        val inflated = grid(free = { _, z -> abs(z) < 1.5f })
        val axis = assertNotNull(SpaceShape.axis(inflated, Vec2(0f, 0f), 8f, config.gridResolutionMeters))
        assertTrue(abs(axis.direction.x) > 0.95f, "axis ${axis.direction}")
        assertTrue(axis.elongation > 0.5f, "elongation ${axis.elongation}")
        assertTrue(SpaceShape.alignment(axis, Vec2(5f, 0f)) > 0.95f)
        assertTrue(SpaceShape.alignment(axis, Vec2(0f, 5f)) < 0.1f)
    }

    @Test
    fun `a square room is not elongated`() {
        val inflated = grid(free = { x, z -> abs(x) < 4f && abs(z) < 4f })
        val axis = assertNotNull(SpaceShape.axis(inflated, Vec2(0f, 0f), 8f, config.gridResolutionMeters))
        assertTrue(axis.elongation < 0.15f, "elongation ${axis.elongation}")
    }

    @Test
    fun `shelving makes a frontier cluttered, open floor does not`() {
        val inflated = grid(
            free = { x, z -> abs(x) < 6f && abs(z) < 6f },
            obstacle = { x, z -> x > 2f && (abs(z - 1f) < 0.3f || abs(z + 1f) < 0.3f) },
        )
        val shelves = SpaceShape.clutter(inflated, Vec2(3.5f, 0f), 2f, config.gridResolutionMeters)
        val open = SpaceShape.clutter(inflated, Vec2(-3.5f, 0f), 2f, config.gridResolutionMeters)
        assertTrue(shelves > 0.3f, "shelves $shelves")
        assertEquals(0f, open)
    }
}
