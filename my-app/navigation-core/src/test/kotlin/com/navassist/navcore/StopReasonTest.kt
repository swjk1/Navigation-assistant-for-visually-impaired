package com.navassist.navcore

import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.planning.NavigationController
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.navcore.state.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every halt must say WHY.
 *
 * The guidance layer plays a hazard pattern for "something is in your way" and a different
 * message for "tracking died". Both arrive as STOP, so without an explicit reason the difference
 * has to be guessed from (status, command) downstream - and guessing wrong means either a hazard
 * alert for a software fault, or silence for a real obstacle.
 */
class StopReasonTest {

    private val config = NavigationConfig()
    private val deviceHeight = 1.4f
    private var clock = 0L

    private fun nextTimestamp(stepMillis: Long = 50) = run {
        clock += stepMillis * 1_000_000
        clock
    }

    private fun pose(x: Float, z: Float, yawDegrees: Float = 0f) =
        Pose3D(x, deviceHeight, z, GeometryUtils.degreesToRadians(yawDegrees))

    private fun openGrid(): OccupancyGrid {
        val grid = OccupancyGrid(config, originX = -6f, originZ = -6f)
        for (gz in 0 until grid.cells) for (gx in 0 until grid.cells) {
            grid.setState(gx, gz, CellState.FREE)
        }
        return grid
    }

    private fun scanInPlace(engine: NavigationEngine, world: SyntheticWorld, at: Pose3D, steps: Int = 24) {
        for (i in 0 until steps) {
            val t = i.toFloat() / (steps - 1)
            val yaw = GeometryUtils.degreesToRadians(-100f + t * 200f)
            engine.updateFrame(world.frame(nextTimestamp(), Pose3D(at.x, at.y, at.z, yaw)))
        }
    }

    // ---------------------------------------------------------------- controller level

    @Test
    fun `an obstacle in the immediate path is reported as a hazard`() {
        val grid = openGrid()
        val wall = grid.worldToGrid(Vec2(0f, 0.6f))
        for (dx in -3..3) grid.setState(wall.gx + dx, wall.gz, CellState.OCCUPIED)
        val inflated = TestSupport.inflate(grid, config)

        val controller = NavigationController(config)
        val path = listOf(Vec2(0f, 0f), Vec2(0f, 1f), Vec2(0f, 2f))
        var output = controller.update(pose(0f, 0f), path, inflated)
        repeat(4) { output = controller.update(pose(0f, 0f), path, inflated) }

        assertEquals(NavigationCommand.STOP, output.command)
        assertEquals(
            StopReason.OBSTACLE_AHEAD,
            output.stopReason,
            "a blocked path is a hazard the user must be warned about",
        )
    }

    @Test
    fun `unknown space ahead is also a hazard, not a fault`() {
        // Free only a small patch; everything beyond is UNKNOWN, which must never read as clear.
        val grid = OccupancyGrid(config, originX = -6f, originZ = -6f)
        val here = grid.worldToGrid(Vec2(0f, 0f))
        for (dz in -2..2) for (dx in -2..2) grid.setState(here.gx + dx, here.gz + dz, CellState.FREE)
        val inflated = TestSupport.inflate(grid, config)

        val controller = NavigationController(config)
        val path = listOf(Vec2(0f, 0f), Vec2(0f, 1f), Vec2(0f, 2f))
        var output = controller.update(pose(0f, 0f), path, inflated)
        repeat(4) { output = controller.update(pose(0f, 0f), path, inflated) }

        assertEquals(NavigationCommand.STOP, output.command)
        assertEquals(StopReason.OBSTACLE_AHEAD, output.stopReason)
    }

    @Test
    fun `an empty path is a routing failure, not a hazard`() {
        val inflated = TestSupport.inflate(openGrid(), config)
        val output = NavigationController(config).update(pose(0f, 0f), emptyList(), inflated)
        assertEquals(NavigationCommand.STOP, output.command)
        assertEquals(
            StopReason.NO_ROUTE,
            output.stopReason,
            "no route is the system failing, and must not trigger a hazard alert",
        )
    }

    @Test
    fun `a movement command carries no stop reason`() {
        val inflated = TestSupport.inflate(openGrid(), config)
        val controller = NavigationController(config)
        val path = listOf(Vec2(0f, 0f), Vec2(0f, 1f), Vec2(0f, 2f), Vec2(0f, 3f))
        var output = controller.update(pose(0f, 0f), path, inflated)
        repeat(5) { output = controller.update(pose(0f, 0f), path, inflated) }
        assertEquals(NavigationCommand.STRAIGHT, output.command)
        assertNull(output.stopReason)
    }

    // ---------------------------------------------------------------- engine level

    @Test
    fun `an unstarted engine says so`() {
        val world = SyntheticWorld.corridor()
        val snapshot = NavigationEngine().updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertEquals(NavigationCommand.STOP, snapshot.command)
        assertEquals(StopReason.NOT_STARTED, snapshot.stopReason)
    }

    @Test
    fun `pausing reports PAUSED rather than a hazard`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine()
        engine.start(NavigationTarget.Explore)
        scanInPlace(engine, world, pose(0f, 0f))
        engine.pause()
        val snapshot = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertEquals(NavigationCommand.STOP, snapshot.command)
        assertEquals(StopReason.PAUSED, snapshot.stopReason)
    }

    @Test
    fun `losing tracking reports a fault, never a hazard`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine()
        engine.start(NavigationTarget.Explore)
        scanInPlace(engine, world, pose(0f, 0f))

        val snapshot = engine.updateFrame(
            NavigationFrame(
                timestampNanos = nextTimestamp(),
                pose = pose(0f, 0f),
                points = DepthPointCloud.EMPTY,
                trackingConfidence = 0f,
                tracking = false,
                depthAvailable = false,
            ),
        )
        assertEquals(NavigationCommand.STOP, snapshot.command)
        assertEquals(
            StopReason.TRACKING_LOST,
            snapshot.stopReason,
            "the phone failing is not an obstacle and must not sound like one",
        )
    }

    @Test
    fun `depth starvation asks for a scan and says why`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine()
        engine.start(NavigationTarget.Explore)
        scanInPlace(engine, world, pose(0f, 0f))

        var snapshot: NavigationSnapshot? = null
        repeat(40) {
            snapshot = engine.updateFrame(
                NavigationFrame(
                    timestampNanos = nextTimestamp(),
                    pose = pose(0f, 0.2f),
                    points = DepthPointCloud.EMPTY,
                    trackingConfidence = 1f,
                    tracking = true,
                    depthAvailable = false,
                ),
            )
        }
        assertEquals(NavigationCommand.SCAN, snapshot!!.command)
        assertEquals(StopReason.NO_DEPTH, snapshot!!.stopReason)
    }

    @Test
    fun `the first frames report an incomplete map`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine()
        engine.start(NavigationTarget.Explore)
        val snapshot = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertEquals(NavigationCommand.SCAN, snapshot.command)
        assertEquals(StopReason.MAP_INCOMPLETE, snapshot.stopReason)
    }
}
