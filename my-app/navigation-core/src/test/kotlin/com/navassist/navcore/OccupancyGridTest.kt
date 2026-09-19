package com.navassist.navcore

import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.OccupancyGrid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OccupancyGridTest {

    private fun grid(cells: Int = 20, resolution: Float = 0.1f) =
        OccupancyGrid(cells = cells, resolution = resolution)

    @Test
    fun `world to grid maps a point into the containing cell`() {
        val g = grid()
        assertEquals(GridCoordinate(0, 0), g.worldToGrid(Vec2(0.00f, 0.00f)))
        assertEquals(GridCoordinate(0, 0), g.worldToGrid(Vec2(0.09f, 0.09f)))
        assertEquals(GridCoordinate(1, 0), g.worldToGrid(Vec2(0.10f, 0.00f)))
        assertEquals(GridCoordinate(3, 7), g.worldToGrid(Vec2(0.35f, 0.72f)))
    }

    @Test
    fun `grid to world returns the cell centre and round trips`() {
        val g = grid()
        val world = g.gridToWorld(GridCoordinate(3, 7))
        assertTrue(abs(world.x - 0.35f) < 1e-5f, "x was ${world.x}")
        assertTrue(abs(world.z - 0.75f) < 1e-5f, "z was ${world.z}")
        assertEquals(GridCoordinate(3, 7), g.worldToGrid(world))
    }

    @Test
    fun `negative world coordinates map correctly relative to the origin`() {
        val g = OccupancyGrid(cells = 20, resolution = 0.1f, originX = -1f, originZ = -1f)
        assertEquals(GridCoordinate(0, 0), g.worldToGrid(Vec2(-1.0f, -1.0f)))
        assertEquals(GridCoordinate(10, 10), g.worldToGrid(Vec2(0.0f, 0.0f)))
        assertEquals(GridCoordinate(9, 9), g.worldToGrid(Vec2(-0.05f, -0.05f)))
    }

    @Test
    fun `out of bounds access is safe and reports unknown`() {
        val g = grid()
        assertFalse(g.inBounds(-1, 0))
        assertFalse(g.inBounds(0, 20))
        assertEquals(CellState.UNKNOWN, g.stateAt(-5, -5))
        assertEquals(CellState.UNKNOWN, g.stateAt(100, 100))
        // Writing out of bounds must not throw or corrupt anything.
        g.markOccupied(-3, 40)
        assertEquals(0, g.stats().occupied)
    }

    @Test
    fun `ray integration marks free space along the ray and occupied at the endpoint`() {
        val g = grid()
        val from = Vec2(0.05f, 0.05f)
        val to = Vec2(0.95f, 0.05f)
        // Several passes so the evidence crosses the thresholds.
        repeat(3) { g.integrateRay(from, to, endpointOccupied = true) }

        assertEquals(CellState.OCCUPIED, g.stateAt(9, 0), "endpoint should be occupied")
        for (gx in 0..8) {
            assertEquals(CellState.FREE, g.stateAt(gx, 0), "cell $gx should be free")
        }
        assertEquals(CellState.UNKNOWN, g.stateAt(10, 0), "beyond the endpoint stays unknown")
    }

    @Test
    fun `a ray with no obstacle leaves the endpoint free`() {
        val g = grid()
        repeat(3) { g.integrateRay(Vec2(0.05f, 0.05f), Vec2(0.95f, 0.05f), endpointOccupied = false) }
        assertEquals(CellState.FREE, g.stateAt(9, 0))
        assertEquals(0, g.stats().occupied)
    }

    @Test
    fun `diagonal rays are traced without gaps`() {
        val g = grid()
        repeat(3) { g.integrateRay(Vec2(0.05f, 0.05f), Vec2(0.95f, 0.95f), endpointOccupied = true) }
        assertEquals(CellState.OCCUPIED, g.stateAt(9, 9))
        for (i in 0..8) assertEquals(CellState.FREE, g.stateAt(i, i), "diagonal cell $i")
    }

    @Test
    fun `evidence is clamped so a single surface cannot dominate forever`() {
        val g = grid()
        repeat(100) { g.markOccupied(5, 5) }
        assertTrue(g.logOddsAt(5, 5) <= 4.0f + 1e-4f)
        repeat(100) { g.markFree(6, 6) }
        assertTrue(g.logOddsAt(6, 6) >= -4.0f - 1e-4f)
    }

    @Test
    fun `temporal decay returns weak evidence to unknown but preserves confirmed structure`() {
        val g = grid()
        // A brief sighting (a person walking past) versus a repeatedly confirmed wall.
        repeat(2) { g.markOccupied(2, 2) }
        repeat(10) { g.markOccupied(4, 4) }
        assertEquals(CellState.OCCUPIED, g.stateAt(2, 2))
        assertEquals(CellState.OCCUPIED, g.stateAt(4, 4))

        g.applyDecay(deltaSeconds = 3f)

        assertEquals(CellState.UNKNOWN, g.stateAt(2, 2), "transient obstacle should fade")
        assertEquals(CellState.OCCUPIED, g.stateAt(4, 4), "confirmed wall should persist")
    }

    @Test
    fun `recentering preserves overlapping evidence and clears what scrolled in`() {
        val g = OccupancyGrid(cells = 20, resolution = 0.1f)
        g.setState(10, 10, CellState.OCCUPIED)
        val worldOfMark = g.gridToWorld(GridCoordinate(10, 10))

        g.recenter(Vec2(1.5f, 1.0f))

        val moved = g.worldToGrid(worldOfMark)
        assertTrue(g.inBounds(moved), "mark should still be inside the window")
        assertEquals(CellState.OCCUPIED, g.stateAt(moved), "evidence should survive the shift")
    }

    @Test
    fun `recentering far away discards the whole window`() {
        val g = OccupancyGrid(cells = 20, resolution = 0.1f)
        g.setState(10, 10, CellState.OCCUPIED)
        g.recenter(Vec2(500f, 500f))
        assertEquals(0, g.stats().occupied)
        assertEquals(g.cells * g.cells, g.stats().unknown)
    }

    @Test
    fun `stats count every cell exactly once`() {
        val g = grid(cells = 10)
        g.setState(0, 0, CellState.FREE)
        g.setState(1, 0, CellState.OCCUPIED)
        val stats = g.stats()
        assertEquals(100, stats.total)
        assertEquals(1, stats.free)
        assertEquals(1, stats.occupied)
        assertEquals(98, stats.unknown)
        assertTrue(abs(stats.knownFraction - 0.02f) < 1e-6f)
    }
}
