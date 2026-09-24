package com.navassist.navcore

import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.NavigationEvent
import com.navassist.navcore.state.NavigationStateMachine
import com.navassist.navcore.state.NavigationStatus
import com.navassist.navcore.state.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapResetTest {

    private var clock = 0L
    private fun nextTimestamp(stepMillis: Long = 33): Long {
        clock += stepMillis * 1_000_000
        return clock
    }

    private fun pose(yawDegrees: Float) = Pose3D(0f, 1.4f, 0f, GeometryUtils.degreesToRadians(yawDegrees))

    private fun exploringEngine(world: SyntheticWorld): NavigationEngine {
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)
        var sweep = 0.0
        repeat(120) {
            sweep += 0.06
            engine.updateFrame(world.frame(nextTimestamp(), pose((kotlin.math.sin(sweep) * 70.0).toFloat())))
        }
        assertTrue(engine.status.isMoving || engine.status == NavigationStatus.NO_ROUTE, "setup: ${engine.status}")
        return engine
    }

    @Test
    fun `resetting the map sends the engine back through the scan`() {
        val world = SyntheticWorld.corridor()
        val engine = exploringEngine(world)

        engine.resetMap()
        val after = engine.updateFrame(world.frame(nextTimestamp(), pose(0f)))

        assertEquals(NavigationStatus.LOCALIZING, after.status)
        assertEquals(NavigationCommand.SCAN, after.command)
        assertEquals(StopReason.MAP_INCOMPLETE, after.stopReason)
        assertTrue((after.debug?.scanProgress ?: 1f) < 0.2f)
    }

    @Test
    fun `map reset from every guided state lands in LOCALIZING`() {
        for (status in listOf(
            NavigationStatus.EXPLORING,
            NavigationStatus.NAVIGATING,
            NavigationStatus.BACKTRACKING,
            NavigationStatus.NO_ROUTE,
            NavigationStatus.ARRIVED,
        )) {
            val machine = driveTo(status)
            assertEquals(NavigationStatus.LOCALIZING, machine.on(NavigationEvent.MapReset), "from $status")
        }
    }

    @Test
    fun `map reset during lost tracking returns to LOCALIZING, not to the interrupted state`() {
        val machine = driveTo(NavigationStatus.EXPLORING)
        machine.on(NavigationEvent.TrackingLost)
        assertEquals(NavigationStatus.LOST_TRACKING, machine.on(NavigationEvent.MapReset))
        assertEquals(NavigationStatus.LOCALIZING, machine.on(NavigationEvent.TrackingAcquired))
    }

    @Test
    fun `scan time is credited while depth is live even when it arrives every other frame`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(NavigationConfig())
        engine.start(NavigationTarget.Explore)
        // 3 s at 30 fps, depth on alternate frames - how ARCore delivers it on many phones.
        repeat(90) { i ->
            val t = nextTimestamp()
            val p = pose(((i % 30) - 15) * 4f)
            val f = if (i % 2 == 0) {
                world.frame(t, p)
            } else {
                NavigationFrame(t, p, DepthPointCloud.EMPTY, trackingConfidence = 1f, depthAvailable = false)
            }
            engine.updateFrame(f)
        }
        val progress = engine.snapshot.debug?.scanProgress ?: 0f
        assertTrue(progress > 0.27f, "3 s of scanning should be ~30% of a 10 s budget, was $progress")
    }

    @Test
    fun `the engine clock follows the frames it is fed`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)
        val t = nextTimestamp(500)
        engine.updateFrame(world.frame(t, pose(0f)))
        assertEquals(t / 1_000_000, engine.clockMillis)
    }

    private fun driveTo(status: NavigationStatus): NavigationStateMachine {
        val machine = NavigationStateMachine()
        machine.on(NavigationEvent.Start)
        machine.on(NavigationEvent.TrackingAcquired)
        machine.on(NavigationEvent.MapReady)
        when (status) {
            NavigationStatus.EXPLORING -> Unit
            NavigationStatus.NAVIGATING -> machine.on(NavigationEvent.TargetLocated)
            NavigationStatus.BACKTRACKING -> machine.on(NavigationEvent.BacktrackRequested)
            NavigationStatus.NO_ROUTE -> machine.on(NavigationEvent.RouteUnavailable)
            NavigationStatus.ARRIVED -> machine.on(NavigationEvent.Arrived)
            else -> error("unsupported $status")
        }
        assertEquals(status, machine.status)
        return machine
    }
}
