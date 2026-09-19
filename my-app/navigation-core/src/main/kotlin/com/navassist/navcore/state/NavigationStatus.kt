package com.navassist.navcore.state

enum class NavigationStatus {
    /** Engine constructed but not started. */
    IDLE,
    /** Started; waiting for the platform to report valid tracking. */
    INITIALIZING,
    /** Tracking is up but the local map is still too sparse to move on. */
    LOCALIZING,
    /** Target location unknown: heading for the best-scoring frontier. */
    EXPLORING,
    /** Target location known: routing to it. */
    NAVIGATING,
    /** Local branch exhausted: routing back to a previously seen junction. */
    BACKTRACKING,
    ARRIVED,
    /** Tracking lost from a moving state. Always paired with NavigationCommand.STOP. */
    LOST_TRACKING,
    /** No traversable route to the current goal. */
    NO_ROUTE,
    PAUSED,
    ERROR,
    ;

    /** States in which the user is expected to be moving. */
    val isMoving: Boolean
        get() = this == EXPLORING || this == NAVIGATING || this == BACKTRACKING
}
