package com.navassist.navcore.state

/**
 * Why the engine is holding the user still.
 *
 * Accompanies [NavigationCommand.STOP] and [NavigationCommand.SCAN], and is null otherwise.
 *
 * This exists because the guidance layer needs to tell two very different situations apart:
 * "something is in your way" is a hazard the user must react to, while "I have lost tracking" is
 * the system failing and needs a different message and a different haptic pattern. Inferring the
 * difference from (status, command) downstream would be guesswork, and getting it wrong means a
 * blind user receives a hazard alert for a software problem, or worse, no hazard alert for a real
 * obstacle.
 */
enum class StopReason {
    /** Something blocked - or not yet known to be clear - directly ahead. A real hazard. */
    OBSTACLE_AHEAD,

    /** The planner has no traversable route to the current goal. */
    NO_ROUTE,

    /** 6DoF tracking is not trustworthy. A system failure, not a hazard. */
    TRACKING_LOST,

    /** Depth has been unavailable long enough that the map is going stale. */
    NO_DEPTH,

    /** Still building enough of a map to move safely. */
    MAP_INCOMPLETE,

    /** The session has not been started, or has been stopped. */
    NOT_STARTED,

    /** The session is paused by the user. */
    PAUSED,
}
