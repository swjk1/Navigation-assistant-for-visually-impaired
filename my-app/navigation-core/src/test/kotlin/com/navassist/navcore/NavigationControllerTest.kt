package com.navassist.navcore

import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.planning.ControllerOutput
import com.navassist.navcore.planning.NavigationController
import com.navassist.navcore.state.NavigationCommand
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationControllerTest {

    private val config = NavigationConfig()

    private fun openGrid(): OccupancyGrid {
        val grid = OccupancyGrid(config, originX = -6f, originZ = -6f)
        for (gz in 0 until grid.cells) for (gx in 0 until grid.cells) {
            grid.setState(gx, gz, CellState.FREE)
        }
        return grid
    }

    /** A path running straight along +Z from the origin. */
    private val straightPath = listOf(Vec2(0f, 0f), Vec2(0f, 1f), Vec2(0f, 2f), Vec2(0f, 3f))

    /** Runs enough updates for the command stability counter to settle. */
    private fun settle(
        controller: NavigationController,
        pose: Pose3D,
        path: List<Vec2>,
        inflated: InflatedGrid,
        frames: Int = 5,
    ): ControllerOutput {
        var output: ControllerOutput? = null
        repeat(frames) { output = controller.update(pose, path, inflated) }
        return output!!
    }

    @Test
    fun `aligned with the path yields STRAIGHT`() {
        val inflated = TestSupport.inflate(openGrid(), config)
        val output = settle(NavigationController(config), Pose3D(0f, 1.4f, 0f, 0f), straightPath, inflated)
        assertEquals(NavigationCommand.STRAIGHT, output.command)
        assertTrue(abs(output.headingErrorDegrees ?: 99f) < 1f, "error was ${output.headingErrorDegrees}")
    }

    @Test
    fun `waypoint 30 degrees to the right yields TURN_RIGHT`() {
        val inflated = TestSupport.inflate(openGrid(), config)
        // The user faces 30 degrees to the LEFT of the path, so the path is 30 degrees RIGHT.
        val pose = Pose3D(0f, 1.4f, 0f, GeometryUtils.degreesToRadians(-30f))
        val output = settle(NavigationController(config), pose, straightPath, inflated)
        assertEquals(NavigationCommand.TURN_RIGHT, output.command)
        assertTrue((output.headingErrorDegrees ?: 0f) > 20f, "error was ${output.headingErrorDegrees}")
    }

    @Test
    fun `waypoint 30 degrees to the left yields TURN_LEFT`() {
        val inflated = TestSupport.inflate(openGrid(), config)
        val pose = Pose3D(0f, 1.4f, 0f, GeometryUtils.degreesToRadians(30f))
        val output = settle(NavigationController(config), pose, straightPath, inflated)
        assertEquals(NavigationCommand.TURN_LEFT, output.command)
        assertTrue((output.headingErrorDegrees ?: 0f) < -20f, "error was ${output.headingErrorDegrees}")
    }

    @Test
    fun `an empty path is an immediate STOP`() {
        val inflated = TestSupport.inflate(openGrid(), config)
        val controller = NavigationController(config)
        assertEquals(
            NavigationCommand.STOP,
            controller.update(Pose3D.IDENTITY, emptyList(), inflated).command,
        )
    }

    @Test
    fun `forcing a command bypasses the stability counter`() {
        // Safety stops must not wait three frames of smoothing.
        val controller = NavigationController(config)
        assertEquals(NavigationCommand.STOP, controller.forceCommand(NavigationCommand.STOP).command)
        assertEquals(NavigationCommand.STOP, controller.currentCommand)
    }

    @Test
    fun `STRAIGHT is suppressed when the cells ahead are not known free`() {
        val grid = openGrid()
        // Drop a wall 0.6 m in front of the user, inside the forward safety window.
        val wall = grid.worldToGrid(Vec2(0f, 0.6f))
        for (dx in -3..3) grid.setState(wall.gx + dx, wall.gz, CellState.OCCUPIED)
        val inflated = TestSupport.inflate(grid, config)

        val output = settle(NavigationController(config), Pose3D(0f, 1.4f, 0f, 0f), straightPath, inflated)
        assertEquals(
            NavigationCommand.STOP,
            output.command,
            "never tell a blind user to walk forward into an obstacle",
        )
    }

    @Test
    fun `STRAIGHT is suppressed when the space ahead is merely unknown`() {
        val grid = OccupancyGrid(config, originX = -6f, originZ = -6f)
        // Free only a narrow patch around the user; everything beyond stays UNKNOWN.
        val here = grid.worldToGrid(Vec2(0f, 0f))
        for (dz in -2..2) for (dx in -2..2) grid.setState(here.gx + dx, here.gz + dz, CellState.FREE)
        val inflated = TestSupport.inflate(grid, config)

        val output = settle(NavigationController(config), Pose3D(0f, 1.4f, 0f, 0f), straightPath, inflated)
        assertEquals(
            NavigationCommand.STOP,
            output.command,
            "unknown space must not silently become free space",
        )
    }

    @Test
    fun `turn hysteresis prevents chattering around the threshold`() {
        val inflated = TestSupport.inflate(openGrid(), config)
        val controller = NavigationController(config)

        // Start well past the turn threshold.
        var pose = Pose3D(0f, 1.4f, 0f, GeometryUtils.degreesToRadians(-30f))
        assertEquals(NavigationCommand.TURN_RIGHT, settle(controller, pose, straightPath, inflated).command)

        // Now sit between the release threshold (8 deg) and the entry threshold (15 deg):
        // an implementation with a single threshold would flip to STRAIGHT here.
        pose = Pose3D(0f, 1.4f, 0f, GeometryUtils.degreesToRadians(-12f))
        assertEquals(
            NavigationCommand.TURN_RIGHT,
            settle(controller, pose, straightPath, inflated).command,
            "should keep turning until the error drops below the release threshold",
        )

        // Below the release threshold it finally goes straight.
        pose = Pose3D(0f, 1.4f, 0f, GeometryUtils.degreesToRadians(-3f))
        assertEquals(NavigationCommand.STRAIGHT, settle(controller, pose, straightPath, inflated).command)
    }

    @Test
    fun `arrival requires several stable frames`() {
        val controller = NavigationController(config)
        repeat(config.arrivalStableFrames - 1) {
            assertTrue(!controller.updateArrival(0.5f), "must not declare arrival on the first frame")
        }
        assertTrue(controller.updateArrival(0.5f), "arrival after the stability window")
    }

    @Test
    fun `a single frame inside the arrival radius does not count`() {
        val controller = NavigationController(config)
        controller.updateArrival(0.5f)
        controller.updateArrival(5f) // pose noise pushed us back out
        repeat(config.arrivalStableFrames - 1) { controller.updateArrival(0.5f) }
        assertTrue(!controller.updateArrival(5f))
    }
}
