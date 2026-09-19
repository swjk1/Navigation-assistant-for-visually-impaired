package com.navassist.navcore.planning

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.state.NavigationCommand
import kotlin.math.abs

data class ControllerOutput(
    val command: NavigationCommand,
    val headingErrorDegrees: Float?,
    val distanceToWaypointMeters: Float?,
    val waypoint: Vec2?,
)

/**
 * Converts a geometric path into the one instruction a walking person can act on.
 *
 * Three things make this harder than "compare two angles":
 *
 *  - NOISE. A single-frame heading estimate jitters by several degrees. Emitting commands from it
 *    directly produces a stream of contradictory instructions. The error is smoothed circularly
 *    (never linearly - that breaks at the +-180 seam) and a command must survive several
 *    consecutive decisions before it is emitted.
 *
 *  - HYSTERESIS. Using one threshold for both entering and leaving a turn makes the command
 *    chatter around that threshold. Turning starts at `straightThresholdDegrees` and only ends
 *    below the smaller `turnReleaseDegrees`.
 *
 *  - FAIL-SAFE. STRAIGHT is only ever emitted when the cells immediately ahead are known free.
 *    Unknown space counts as blocked. STOP bypasses the stability counter entirely: a safety stop
 *    must never be delayed by three frames of smoothing.
 */
class NavigationController(private val config: NavigationConfig) {

    private var smoothedErrorRadians: Float? = null
    private var emittedCommand: NavigationCommand = NavigationCommand.STOP
    private var candidateCommand: NavigationCommand = NavigationCommand.STOP
    private var candidateFrames: Int = 0
    private var arrivalFrames: Int = 0
    private var turning: Boolean = false

    val currentCommand: NavigationCommand get() = emittedCommand

    fun reset() {
        smoothedErrorRadians = null
        emittedCommand = NavigationCommand.STOP
        candidateCommand = NavigationCommand.STOP
        candidateFrames = 0
        arrivalFrames = 0
        turning = false
    }

    /** Immediately forces a command, bypassing smoothing. Used for safety stops. */
    fun forceCommand(command: NavigationCommand): ControllerOutput {
        emittedCommand = command
        candidateCommand = command
        candidateFrames = config.commandStabilityFrames
        if (command != NavigationCommand.TURN_LEFT && command != NavigationCommand.TURN_RIGHT) {
            turning = false
        }
        return ControllerOutput(command, null, null, null)
    }

    /**
     * @param path smoothed path in WORLD coordinates, starting at (or very near) the user.
     */
    fun update(pose: Pose3D, path: List<Vec2>, inflated: InflatedGrid): ControllerOutput {
        if (path.size < 2) return forceCommand(NavigationCommand.STOP)

        val waypoint = selectLookahead(pose.position2D, path, inflated)
            ?: return forceCommand(NavigationCommand.STOP)

        val toWaypoint = waypoint - pose.position2D
        val distance = toWaypoint.length()
        if (distance < 1e-3f) return forceCommand(NavigationCommand.STOP)

        val rawError = GeometryUtils.angleDifference(pose.yawRadians, toWaypoint.yawRadians())
        val smoothed = smoothedErrorRadians
            ?.let { GeometryUtils.blendAngle(it, rawError, config.headingSmoothingAlpha) }
            ?: rawError
        smoothedErrorRadians = smoothed

        val errorDegrees = GeometryUtils.radiansToDegrees(smoothed)
        val desired = decideCommand(errorDegrees)

        // Safety gate: never tell someone to walk into space we have not confirmed is clear.
        val gated = if (desired == NavigationCommand.STRAIGHT && !isForwardClear(pose, inflated)) {
            NavigationCommand.STOP
        } else {
            desired
        }

        val emitted = if (gated == NavigationCommand.STOP) {
            // Fail-safe: emit immediately, do not wait for the stability counter.
            forceCommand(NavigationCommand.STOP).command
        } else {
            stabilize(gated)
        }

        turning = emitted == NavigationCommand.TURN_LEFT || emitted == NavigationCommand.TURN_RIGHT
        return ControllerOutput(emitted, errorDegrees, distance, waypoint)
    }

    private fun decideCommand(errorDegrees: Float): NavigationCommand {
        val magnitude = abs(errorDegrees)
        val threshold = if (turning) config.turnReleaseDegrees else config.straightThresholdDegrees
        if (magnitude <= threshold) return NavigationCommand.STRAIGHT
        // Positive yaw error means the waypoint lies clockwise of the heading, i.e. to the right.
        return if (errorDegrees > 0f) NavigationCommand.TURN_RIGHT else NavigationCommand.TURN_LEFT
    }

    private fun stabilize(desired: NavigationCommand): NavigationCommand {
        if (desired == candidateCommand) {
            candidateFrames++
        } else {
            candidateCommand = desired
            candidateFrames = 1
        }
        if (desired == emittedCommand) return emittedCommand
        if (candidateFrames >= config.commandStabilityFrames) {
            emittedCommand = desired
        }
        return emittedCommand
    }

    /**
     * Steering towards the next 10 cm cell would produce a useless twitch every step. Instead we
     * aim at the furthest point roughly [NavigationConfig.lookaheadMeters] along the path that is
     * still in direct line of sight.
     */
    private fun selectLookahead(position: Vec2, path: List<Vec2>, inflated: InflatedGrid): Vec2? {
        val startCell = inflated.worldToGrid(position)
        var fallback: Vec2? = null
        var best: Vec2? = null

        for (index in path.indices.reversed()) {
            val candidate = path[index]
            val distance = candidate.distanceTo(position)
            if (distance > config.lookaheadMeters) continue
            if (distance < config.minLookaheadMeters && index != path.lastIndex) continue
            if (hasLineOfSight(inflated, startCell, candidate)) {
                best = candidate
                break
            }
        }

        if (best == null) {
            // Nothing at the preferred range is visible: fall back to the nearest visible point
            // further along the path, and finally to the next path point at all.
            for (index in 1 until path.size) {
                val candidate = path[index]
                if (hasLineOfSight(inflated, startCell, candidate)) {
                    fallback = candidate
                } else {
                    break
                }
            }
            best = fallback ?: path.getOrNull(1)
        }
        return best
    }

    private fun hasLineOfSight(inflated: InflatedGrid, from: GridCoordinate, to: Vec2): Boolean =
        inflated.hasLineOfSight(from, inflated.worldToGrid(to))

    /**
     * Sweeps a short corridor straight ahead of the user. Any blocked OR unknown cell within
     * [NavigationConfig.forwardSafetyDistanceMeters] suppresses STRAIGHT.
     */
    private fun isForwardClear(pose: Pose3D, inflated: InflatedGrid): Boolean {
        val forward = pose.forward2D
        val steps = (config.forwardSafetyDistanceMeters / inflated.resolution).toInt().coerceAtLeast(1)
        val start = inflated.worldToGrid(pose.position2D)
        for (step in 1..steps) {
            val probe = pose.position2D + forward * (step * inflated.resolution)
            val cell = inflated.worldToGrid(probe)
            if (cell == start) continue
            if (!inflated.isTraversable(cell)) return false
        }
        return true
    }

    /**
     * Arrival must be stable over several frames: a single noisy pose that happens to land inside
     * the arrival radius should not end the guidance session.
     */
    fun updateArrival(distanceToTargetMeters: Float?): Boolean {
        if (distanceToTargetMeters == null || distanceToTargetMeters > config.arrivalRadiusMeters) {
            arrivalFrames = 0
            return false
        }
        arrivalFrames++
        return arrivalFrames >= config.arrivalStableFrames
    }
}
