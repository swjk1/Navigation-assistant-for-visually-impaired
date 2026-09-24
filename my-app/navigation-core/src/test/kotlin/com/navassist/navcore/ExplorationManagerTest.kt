package com.navassist.navcore

import com.navassist.navcore.exploration.ExplorationManager
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticHintStore
import com.navassist.navcore.topology.TopologicalMap
import kotlin.test.*

class ExplorationManagerTest {
    private val config = TestSupport.bareConfig(12f)
    private val grid = OccupancyGrid(config, -6f, -6f)
    private val manager = ExplorationManager(config)
    private val pose = Pose3D(0f, 1.4f, 0f, 0f)

    private fun patch(x: Float, z: Float, state: CellState = CellState.FREE) {
        val cell = grid.worldToGrid(Vec2(x, z))
        for (dz in -4..4) for (dx in -4..4) grid.setState(cell.gx + dx, cell.gz + dz, state)
    }

    private fun update(time: Long, user: Pose3D = pose) = manager.update(
        grid, TestSupport.inflate(grid, config), user, NavigationTarget.Room("314"),
        SemanticHintStore(config), TopologicalMap(), time,
    )

    @Test fun `unknown room selects exploration and holds waypoint across frontier changes`() {
        patch(-3f, 2f)
        val initial = assertNotNull(update(1000).selected)
        patch(-3f, 2.7f) // expand cluster and shift its centroid
        patch(1.5f, 1.5f) // a closer competing branch
        val next = assertNotNull(update(8000).selected) // beyond old five-second commitment
        assertEquals(initial.centroid, next.centroid)
        assertEquals(initial.id, next.id)
        assertFalse(update(8500).exhausted)
    }

    @Test fun `temporary disappearance of frontiers does not end a committed branch`() {
        patch(-3f, 2f)
        val initial = assertNotNull(update(1000).selected)
        // Frontier detection comes back empty - a sweep of returns rims the patch, leaving only
        // one-cell openings, which the size filter rejects as speckle - while the openings are
        // still there. Nothing has been seen that makes the branch pointless, so it must hold.
        val center = grid.worldToGrid(Vec2(-3f, 2f))
        for (dz in -5..5) for (dx in -5..5) {
            val onRing = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) == 5
            val opening = dz == 0 && (dx == 5 || dx == -5)
            if (onRing && !opening) grid.setState(center.gx + dx, center.gz + dz, CellState.OCCUPIED)
        }
        val next = update(2000)
        assertTrue(next.frontiers.isEmpty())
        assertEquals(initial.centroid, assertNotNull(next.selected).centroid)
        assertFalse(next.exhausted)
    }

    @Test fun `a waypoint whose surroundings have been mapped is released`() {
        patch(-3f, 2f)
        assertNotNull(update(1000).selected)
        // A sweep fills in everything around the waypoint before the user gets there.
        for (z in 0 until grid.cells) for (x in 0 until grid.cells) grid.setState(x, z, CellState.FREE)
        val next = update(2000)
        assertNull(next.selected, "there is nothing left to see at the old waypoint")
        assertTrue(next.exhausted)
    }

    @Test fun `blocked waypoint is abandoned immediately`() {
        patch(-3f, 2f)
        val initial = assertNotNull(update(1000).selected)
        patch(3f, 2f)
        val cell = grid.worldToGrid(initial.centroid)
        grid.setState(cell.gx, cell.gz, CellState.OCCUPIED)
        assertTrue(assertNotNull(update(1100).selected).centroid.x > 0)
    }

    @Test fun `arrival selects another branch instead of steering at reached waypoint`() {
        patch(-3f, 2f)
        val initial = assertNotNull(update(1000).selected)
        patch(3f, 2f)
        val atGoal = Pose3D(initial.centroid.x, 1.4f, initial.centroid.z, 0f)
        assertTrue(assertNotNull(update(2000, atGoal).selected).centroid.x > 0)
    }

    @Test fun `lack of progress eventually releases commitment`() {
        patch(-3f, 2f)
        assertNotNull(update(1000).selected)
        patch(3f, 2f)
        assertTrue(assertNotNull(update(1000 + config.frontierNoProgressMillis).selected).centroid.x > 0)
    }

    @Test fun `walking towards waypoint renews progress budget`() {
        patch(-3f, 2f)
        val initial = assertNotNull(update(1000).selected)
        val closer = Pose3D(initial.centroid.x * 0.3f, 1.4f, initial.centroid.z * 0.3f, 0f)
        update(19000, closer)
        assertEquals(initial.centroid, assertNotNull(update(22000, closer).selected).centroid)
    }

    @Test fun `render frame failures cannot blacklist branch before user can turn`() {
        patch(-3f, 2f)
        val initial = assertNotNull(update(1000).selected)
        repeat(30) { manager.reportPlanningFailure(1000L + it * 16) }
        assertEquals(initial.centroid, assertNotNull(manager.selected).centroid)
        manager.reportPlanningFailure(2000)
        manager.reportPlanningFailure(3000)
        assertNull(manager.selected)
        assertNull(update(3100).selected)
    }
}
