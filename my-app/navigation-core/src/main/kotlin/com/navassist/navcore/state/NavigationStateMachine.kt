package com.navassist.navcore.state

/**
 * Events that can change navigation status. The engine translates sensor conditions and planner
 * results into these; the transition table itself is pure and deterministic, which is what makes
 * it testable and auditable for a safety-relevant behaviour.
 */
sealed interface NavigationEvent {
    data object Start : NavigationEvent
    data object Stop : NavigationEvent
    data object Pause : NavigationEvent
    data object Resume : NavigationEvent

    data object TrackingAcquired : NavigationEvent
    data object TrackingLost : NavigationEvent

    /** Enough of the local map is known to plan. */
    data object MapReady : NavigationEvent

    /** Target position became known (semantic match with a world location). */
    data object TargetLocated : NavigationEvent

    /** Target position is no longer usable - fall back to exploring. */
    data object TargetLost : NavigationEvent

    /** A traversable route to the current goal exists. */
    data object RouteFound : NavigationEvent

    /** No traversable route to the current goal. */
    data object RouteUnavailable : NavigationEvent

    /** Local exploration exhausted; heading back to an earlier junction. */
    data object BacktrackRequested : NavigationEvent

    data object Arrived : NavigationEvent

    data class Failed(val message: String) : NavigationEvent
}

/**
 * Deterministic navigation status machine.
 *
 * Two rules dominate the table:
 *  1. Losing tracking from ANY moving state goes straight to LOST_TRACKING (and the engine pairs
 *     that with STOP). There is no "carry on and hope" path.
 *  2. Transient conditions (LOST_TRACKING, NO_ROUTE) remember the state they interrupted, so
 *     recovery returns the user to what they were doing instead of restarting exploration.
 */
class NavigationStateMachine(initial: NavigationStatus = NavigationStatus.IDLE) {

    var status: NavigationStatus = initial
        private set

    /** The moving state to return to once a transient interruption clears. */
    private var interruptedStatus: NavigationStatus = NavigationStatus.EXPLORING

    /** True once the target's world position is known; decides EXPLORING vs NAVIGATING. */
    private var targetKnown: Boolean = false

    var lastError: String? = null
        private set

    fun reset() {
        status = NavigationStatus.IDLE
        interruptedStatus = NavigationStatus.EXPLORING
        targetKnown = false
        lastError = null
    }

    fun on(event: NavigationEvent): NavigationStatus {
        status = transition(event)
        return status
    }

    private fun transition(event: NavigationEvent): NavigationStatus {
        // Global transitions that apply from (almost) every state.
        when (event) {
            is NavigationEvent.Failed -> {
                lastError = event.message
                return NavigationStatus.ERROR
            }
            NavigationEvent.Stop -> {
                targetKnown = false
                return NavigationStatus.IDLE
            }
            NavigationEvent.Start -> {
                lastError = null
                return NavigationStatus.INITIALIZING
            }
            NavigationEvent.Pause -> {
                if (status.isMoving) interruptedStatus = status
                return NavigationStatus.PAUSED
            }
            else -> Unit
        }

        if (status == NavigationStatus.PAUSED) {
            return when (event) {
                NavigationEvent.Resume -> NavigationStatus.LOCALIZING
                else -> NavigationStatus.PAUSED
            }
        }

        if (status == NavigationStatus.IDLE || status == NavigationStatus.ERROR) {
            // Only Start (handled above) leaves these states.
            return status
        }

        // Rule 1: tracking loss beats everything else.
        if (event == NavigationEvent.TrackingLost) {
            if (status.isMoving) interruptedStatus = status
            return NavigationStatus.LOST_TRACKING
        }

        return when (status) {
            NavigationStatus.INITIALIZING -> when (event) {
                NavigationEvent.TrackingAcquired -> NavigationStatus.LOCALIZING
                else -> NavigationStatus.INITIALIZING
            }

            NavigationStatus.LOCALIZING -> when (event) {
                NavigationEvent.MapReady ->
                    if (targetKnown) NavigationStatus.NAVIGATING else NavigationStatus.EXPLORING
                NavigationEvent.TargetLocated -> {
                    targetKnown = true
                    NavigationStatus.LOCALIZING
                }
                else -> NavigationStatus.LOCALIZING
            }

            NavigationStatus.EXPLORING -> when (event) {
                NavigationEvent.TargetLocated -> {
                    targetKnown = true
                    NavigationStatus.NAVIGATING
                }
                NavigationEvent.BacktrackRequested -> NavigationStatus.BACKTRACKING
                NavigationEvent.RouteUnavailable -> {
                    interruptedStatus = NavigationStatus.EXPLORING
                    NavigationStatus.NO_ROUTE
                }
                NavigationEvent.Arrived -> NavigationStatus.ARRIVED
                else -> NavigationStatus.EXPLORING
            }

            NavigationStatus.NAVIGATING -> when (event) {
                NavigationEvent.Arrived -> NavigationStatus.ARRIVED
                NavigationEvent.TargetLost -> {
                    targetKnown = false
                    NavigationStatus.EXPLORING
                }
                NavigationEvent.BacktrackRequested -> NavigationStatus.BACKTRACKING
                NavigationEvent.RouteUnavailable -> {
                    interruptedStatus = NavigationStatus.NAVIGATING
                    NavigationStatus.NO_ROUTE
                }
                else -> NavigationStatus.NAVIGATING
            }

            NavigationStatus.BACKTRACKING -> when (event) {
                NavigationEvent.TargetLocated -> {
                    targetKnown = true
                    NavigationStatus.NAVIGATING
                }
                NavigationEvent.RouteFound -> NavigationStatus.EXPLORING
                NavigationEvent.RouteUnavailable -> {
                    interruptedStatus = NavigationStatus.BACKTRACKING
                    NavigationStatus.NO_ROUTE
                }
                NavigationEvent.Arrived -> NavigationStatus.ARRIVED
                else -> NavigationStatus.BACKTRACKING
            }

            NavigationStatus.LOST_TRACKING -> when (event) {
                NavigationEvent.TrackingAcquired -> interruptedStatus
                else -> NavigationStatus.LOST_TRACKING
            }

            NavigationStatus.NO_ROUTE -> when (event) {
                NavigationEvent.RouteFound -> interruptedStatus
                NavigationEvent.TargetLocated -> {
                    targetKnown = true
                    NavigationStatus.NAVIGATING
                }
                NavigationEvent.BacktrackRequested -> NavigationStatus.BACKTRACKING
                else -> NavigationStatus.NO_ROUTE
            }

            NavigationStatus.ARRIVED -> when (event) {
                NavigationEvent.TargetLost -> {
                    targetKnown = false
                    NavigationStatus.EXPLORING
                }
                NavigationEvent.TargetLocated -> {
                    targetKnown = true
                    NavigationStatus.NAVIGATING
                }
                else -> NavigationStatus.ARRIVED
            }

            else -> status
        }
    }

    /** Called when the caller changes target, so a new destination re-enters exploration. */
    fun onTargetChanged() {
        targetKnown = false
        if (status == NavigationStatus.ARRIVED || status == NavigationStatus.NAVIGATING) {
            status = NavigationStatus.EXPLORING
        }
        interruptedStatus = NavigationStatus.EXPLORING
    }
}
