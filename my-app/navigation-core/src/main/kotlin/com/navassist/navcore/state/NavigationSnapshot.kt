package com.navassist.navcore.state

/**
 * The COMPLETE, compact result of one engine update - and the only thing that ever crosses the
 * native bridge to JavaScript.
 *
 * Explicitly not here: depth maps, camera frames, point clouds, occupancy grids, coordinate
 * lists. All high-frequency data stays native (architecture Rule 2).
 */
data class NavigationSnapshot(
    /** Platform timestamp of the frame this snapshot was derived from, in milliseconds. */
    val timestampMillis: Long,
    val status: NavigationStatus,
    val command: NavigationCommand,
    /** Why the user is being held still. Set only alongside STOP and SCAN. */
    val stopReason: StopReason? = null,
    /**
     * Signed angle between the user's heading and the next waypoint, in degrees.
     * Negative = waypoint is to the LEFT, positive = to the RIGHT (canonical yaw convention).
     */
    val headingErrorDegrees: Float? = null,
    val distanceToWaypointMeters: Float? = null,
    /** Distance to the final destination when it is known. */
    val distanceToTargetMeters: Float? = null,
    val trackingConfidence: Float = 0f,
    /** How much of the local window has been observed, 0..1. */
    val mapConfidence: Float = 0f,
    val selectedFrontierId: String? = null,
    val targetDescription: String? = null,
    val debug: NavigationDebugInfo? = null,
)

data class NavigationDebugInfo(
    val freeCells: Int,
    val occupiedCells: Int,
    val unknownCells: Int,
    val frontierCount: Int,
    val topologicalNodeCount: Int,
    val pathLength: Int,
    val floorY: Float,
    val floorConfidence: Float,
    val depthPointsLastFrame: Int,
    val depthAvailable: Boolean,
    val poseX: Float,
    val poseY: Float,
    val poseZ: Float,
    val yawDegrees: Float,
    val lastError: String? = null,
)
