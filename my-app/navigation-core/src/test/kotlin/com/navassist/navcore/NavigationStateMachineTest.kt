package com.navassist.navcore

import com.navassist.navcore.state.NavigationEvent
import com.navassist.navcore.state.NavigationStateMachine
import com.navassist.navcore.state.NavigationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationStateMachineTest {

    private fun exploring(): NavigationStateMachine {
        val machine = NavigationStateMachine()
        machine.on(NavigationEvent.Start)
        machine.on(NavigationEvent.TrackingAcquired)
        machine.on(NavigationEvent.MapReady)
        assertEquals(NavigationStatus.EXPLORING, machine.status)
        return machine
    }

    @Test
    fun `the happy path runs IDLE to ARRIVED`() {
        val machine = NavigationStateMachine()
        assertEquals(NavigationStatus.IDLE, machine.status)
        assertEquals(NavigationStatus.INITIALIZING, machine.on(NavigationEvent.Start))
        assertEquals(NavigationStatus.LOCALIZING, machine.on(NavigationEvent.TrackingAcquired))
        assertEquals(NavigationStatus.EXPLORING, machine.on(NavigationEvent.MapReady))
        assertEquals(NavigationStatus.NAVIGATING, machine.on(NavigationEvent.TargetLocated))
        assertEquals(NavigationStatus.ARRIVED, machine.on(NavigationEvent.Arrived))
    }

    @Test
    fun `EXPLORING to NAVIGATING when the target is located`() {
        val machine = exploring()
        assertEquals(NavigationStatus.NAVIGATING, machine.on(NavigationEvent.TargetLocated))
    }

    @Test
    fun `NAVIGATING to LOST_TRACKING and back to NAVIGATING`() {
        val machine = exploring()
        machine.on(NavigationEvent.TargetLocated)
        assertEquals(NavigationStatus.LOST_TRACKING, machine.on(NavigationEvent.TrackingLost))
        // Recovery returns to what the user was doing, not to the beginning.
        assertEquals(NavigationStatus.NAVIGATING, machine.on(NavigationEvent.TrackingAcquired))
    }

    @Test
    fun `tracking loss beats every other event from any moving state`() {
        for (setup in listOf(NavigationStatus.EXPLORING, NavigationStatus.NAVIGATING, NavigationStatus.BACKTRACKING)) {
            val machine = exploring()
            when (setup) {
                NavigationStatus.NAVIGATING -> machine.on(NavigationEvent.TargetLocated)
                NavigationStatus.BACKTRACKING -> machine.on(NavigationEvent.BacktrackRequested)
                else -> Unit
            }
            assertEquals(setup, machine.status)
            assertEquals(
                NavigationStatus.LOST_TRACKING,
                machine.on(NavigationEvent.TrackingLost),
                "tracking loss from $setup",
            )
        }
    }

    @Test
    fun `losing the target falls back to exploring`() {
        val machine = exploring()
        machine.on(NavigationEvent.TargetLocated)
        assertEquals(NavigationStatus.EXPLORING, machine.on(NavigationEvent.TargetLost))
    }

    @Test
    fun `a dead end goes to BACKTRACKING and a new frontier resumes exploring`() {
        val machine = exploring()
        assertEquals(NavigationStatus.BACKTRACKING, machine.on(NavigationEvent.BacktrackRequested))
        assertEquals(NavigationStatus.EXPLORING, machine.on(NavigationEvent.RouteFound))
    }

    @Test
    fun `NO_ROUTE remembers what it interrupted`() {
        val machine = exploring()
        machine.on(NavigationEvent.TargetLocated)
        assertEquals(NavigationStatus.NO_ROUTE, machine.on(NavigationEvent.RouteUnavailable))
        assertEquals(NavigationStatus.NAVIGATING, machine.on(NavigationEvent.RouteFound))
    }

    @Test
    fun `pause and resume re-localize before moving again`() {
        val machine = exploring()
        assertEquals(NavigationStatus.PAUSED, machine.on(NavigationEvent.Pause))
        // Nothing but Resume leaves PAUSED.
        assertEquals(NavigationStatus.PAUSED, machine.on(NavigationEvent.MapReady))
        assertEquals(NavigationStatus.LOCALIZING, machine.on(NavigationEvent.Resume))
    }

    @Test
    fun `stop returns to IDLE from anywhere`() {
        val machine = exploring()
        machine.on(NavigationEvent.TrackingLost)
        assertEquals(NavigationStatus.IDLE, machine.on(NavigationEvent.Stop))
    }

    @Test
    fun `a failure is terminal until the session is restarted`() {
        val machine = exploring()
        assertEquals(NavigationStatus.ERROR, machine.on(NavigationEvent.Failed("camera died")))
        assertEquals("camera died", machine.lastError)
        assertEquals(NavigationStatus.ERROR, machine.on(NavigationEvent.MapReady))
        assertEquals(NavigationStatus.INITIALIZING, machine.on(NavigationEvent.Start))
    }

    @Test
    fun `changing the destination sends a navigating session back to exploring`() {
        val machine = exploring()
        machine.on(NavigationEvent.TargetLocated)
        assertEquals(NavigationStatus.NAVIGATING, machine.status)
        machine.onTargetChanged()
        assertEquals(NavigationStatus.EXPLORING, machine.status)
        // And the new destination is not treated as already known.
        machine.on(NavigationEvent.TrackingLost)
        machine.on(NavigationEvent.TrackingAcquired)
        assertEquals(NavigationStatus.EXPLORING, machine.status)
    }

    @Test
    fun `localizing remembers a target discovered before the map was ready`() {
        val machine = NavigationStateMachine()
        machine.on(NavigationEvent.Start)
        machine.on(NavigationEvent.TrackingAcquired)
        assertEquals(NavigationStatus.LOCALIZING, machine.on(NavigationEvent.TargetLocated))
        assertEquals(NavigationStatus.NAVIGATING, machine.on(NavigationEvent.MapReady))
    }
}
