package com.navassist.navcore.planning

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.StopReason
import kotlin.math.abs
import kotlin.math.min

data class ControllerOutput(
    val command: NavigationCommand,
    val headingErrorDegrees: Float?,
    val distanceToWaypointMeters: Float?,
    val waypoint: Vec2?,
    /** Set only when [command] is STOP, so the guidance layer can distinguish hazard from fault. */
    val stopReason: StopReason? = null,
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
    private var candidateSinceMillis: Long = 0
    private var emittedAtMillis: Long = 0
    /** Null, not 0: a timestamp of 0 is a legitimate value and must not read as 'unset'. */
    private var arrivalSinceMillis: Long? = null
    private var turning: Boolean = false
    private var nowMillis: Long = 0

    /** Smoothed rate at which the user's heading is changing, radians per second. */
    private var yawRate: Float = 0f
    private var lastYaw: Float? = null
    private var lastYawMillis: Long = 0

    val currentCommand: NavigationCommand get() = emittedCommand

    fun reset() {
        smoothedErrorRadians = null
        emittedCommand = NavigationCommand.STOP
        candidateCommand = NavigationCommand.STOP
        candidateSinceMillis = 0
        emittedAtMillis = 0
        arrivalSinceMillis = null
        turning = false
        yawRate = 0f
        lastYaw = null
    }

    /** Immediately forces a command, bypassing smoothing. Used for safety stops. */
    fun forceCommand(
        command: NavigationCommand,
        stopReason: StopReason? = null,
    ): ControllerOutput {
        emittedCommand = command
        candidateCommand = command
        candidateSinceMillis = nowMillis
        emittedAtMillis = nowMillis
        if (command != NavigationCommand.TURN_LEFT && command != NavigationCommand.TURN_RIGHT) {
            turning = false
        }
        return ControllerOutput(command, null, null, null, stopReason)
    }

    /**
     * @param path smoothed path in WORLD coordinates, starting at (or very near) the user.
     */
    fun update(
        pose: Pose3D,
        path: List<Vec2>,
        inflated: InflatedGrid,
        nowMillis: Long,
        /** The raw grid, for which floor was seen close up; without it, any free floor will do. */
        grid: OccupancyGrid? = null,
    ): ControllerOutput {
        this.nowMillis = nowMillis
        trackYawRate(pose.yawRadians, nowMillis)
        if (path.size < 2) return forceCommand(NavigationCommand.STOP, StopReason.NO_ROUTE)

        val waypoint = selectLookahead(pose.position2D, path, inflated)
            ?: return forceCommand(NavigationCommand.STOP, StopReason.NO_ROUTE)

        val toWaypoint = waypoint - pose.position2D
        val distance = toWaypoint.length()
        if (distance < 1e-3f) return forceCommand(NavigationCommand.STOP, StopReason.NO_ROUTE)

        val rawError = GeometryUtils.angleDifference(pose.yawRadians, toWaypoint.yawRadians())
        val smoothed = smoothedErrorRadians
            ?.let { GeometryUtils.blendAngle(it, rawError, config.headingSmoothingAlpha) }
            ?: rawError
        smoothedErrorRadians = smoothed

        val errorDegrees = GeometryUtils.radiansToDegrees(smoothed)
        val desired = decideCommand(errorDegrees)

        // Safety gate: never tell someone to walk into space we have not confirmed is clear.
        // Something solid ahead is a hazard (STOP). Floor that has simply never been seen is not:
        // the user is asked to look (SCAN), because standing still would never reveal it.
        val ahead = if (desired == NavigationCommand.STRAIGHT) {
            // Obstacles only matter as far as the user goes before the path turns: past its next
            // waypoint lies the wall the turn avoids. And a clear line to the waypoint - what a
            // path follower drives along - is the authority on obstacles: sampling along the
            // heading clips the corner cell of a wall the line passes, and with nothing to steer
            // away from, that stopped a robot for good. Unknown floor is still checked throughout.
            val gated = config.forwardGateToWaypoint
            val reach = remainingPathMeters(pose.position2D, path).let { if (gated) min(it, distance + inflated.resolution) else it }
            val lineClear = gated && escapingLineOfSight(inflated, inflated.worldToGrid(pose.position2D), inflated.worldToGrid(waypoint))
            forwardState(pose, inflated, if (lineClear) 0f else reach, grid)
        } else {
            Ahead.CLEAR
        }
        // Blocked ahead while the path itself bends away from the obstacle: the user has drifted
        // inside the "straight enough" tolerance towards a wall. Steering back onto the path is the
        // answer, not a hazard stop - a STOP gives them nothing to do, and in a doorway or a 1.5 m
        // corridor it froze them in place, being told about an obstacle that was only beside them.
        // Only when the path runs straight into it is it an obstacle ahead.
        val correcting = ahead == Ahead.BLOCKED && kotlin.math.abs(errorDegrees) >= config.driftCorrectionDegrees
        val emitted = when {
            correcting -> forceCommand(
                if (errorDegrees > 0f) NavigationCommand.TURN_RIGHT else NavigationCommand.TURN_LEFT,
            ).command
            // Fail-safe: emitted immediately, never held back by the stability counter.
            ahead == Ahead.BLOCKED -> forceCommand(NavigationCommand.STOP).command
            ahead == Ahead.UNKNOWN -> forceCommand(NavigationCommand.SCAN).command
            else -> stabilize(desired)
        }

        turning = emitted == NavigationCommand.TURN_LEFT || emitted == NavigationCommand.TURN_RIGHT
        val stopReason = when {
            correcting -> null
            ahead == Ahead.BLOCKED -> StopReason.OBSTACLE_AHEAD
            ahead == Ahead.UNKNOWN -> StopReason.UNKNOWN_AHEAD
            emitted == NavigationCommand.STOP -> StopReason.NO_ROUTE
            else -> null
        }
        return ControllerOutput(emitted, errorDegrees, distance, waypoint, stopReason)
    }

    private fun decideCommand(errorDegrees: Float): NavigationCommand {
        val magnitude = abs(errorDegrees)
        if (turning) {
            // Where the heading error will be once the user has reacted to "straight": they keep
            // turning at their current rate for that long. End the turn when THAT is on the path,
            // not when they already are - otherwise every turn overshoots by a reaction time.
            // Past the path counts as on it: the next decision corrects from there, with a
            // straight in between rather than a flip to the opposite turn.
            val lead = config.turnLeadMillis / 1000f
            val predicted = errorDegrees - GeometryUtils.radiansToDegrees(yawRate) * lead
            // Only past the path on the NEAR side: with the waypoint behind, the error wraps from
            // +179 to -179 as the user turns, which is not an overshoot - it is still behind them.
            // Taking it for one ended the turn, the drift correction turned them back the other
            // way, and a user by a wall stood flipping between "left" and "right" for minutes.
            val ended = if (emittedCommand == NavigationCommand.TURN_LEFT) {
                predicted >= -config.turnReleaseDegrees && predicted < OVERSHOOT_LIMIT_DEGREES
            } else {
                predicted <= config.turnReleaseDegrees && predicted > -OVERSHOOT_LIMIT_DEGREES
            }
            return if (ended) NavigationCommand.STRAIGHT else emittedCommand
        }
        if (magnitude <= config.straightThresholdDegrees) return NavigationCommand.STRAIGHT
        // Positive yaw error means the waypoint lies clockwise of the heading, i.e. to the right.
        return if (errorDegrees > 0f) NavigationCommand.TURN_RIGHT else NavigationCommand.TURN_LEFT
    }

    /**
     * Two gates before a new instruction reaches the user.
     *
     * STABILITY - the new command must be what we have wanted to say for a continuous
     * `commandStabilityMillis`. Kills single-frame jitter.
     *
     * DWELL - once a turn is announced, it stands for `commandMinDwellMillis` regardless. A person
     * needs a beat to hear "left" and begin turning; re-deciding before they have moved means we
     * are reacting to a world they have not acted on yet, and engine and user oscillate against
     * each other. Safety stops bypass both gates (see the caller).
     */
    private fun stabilize(desired: NavigationCommand): NavigationCommand {
        if (desired != candidateCommand) {
            candidateCommand = desired
            candidateSinceMillis = nowMillis
        }
        if (desired == emittedCommand) return emittedCommand

        val emittedIsTurn = emittedCommand == NavigationCommand.TURN_LEFT ||
            emittedCommand == NavigationCommand.TURN_RIGHT
        // Ending a turn is never delayed. The dwell exists so the engine does not re-decide before
        // the user has had time to act - but once they are coming round onto the path they HAVE
        // acted, and holding the turn only makes them overshoot. (With a 1.4 s dwell, a person
        // turning at 60 degrees a second swung 80 degrees past the path, then back, forever.)
        if (emittedIsTurn && desired == NavigationCommand.STRAIGHT) {
            if (nowMillis - candidateSinceMillis >= config.turnEndStabilityMillis) {
                emittedCommand = desired
                emittedAtMillis = nowMillis
            }
            return emittedCommand
        }
        // Flipping straight from one turn to the other still waits out the dwell.
        if (emittedIsTurn && nowMillis - emittedAtMillis < config.commandMinDwellMillis) {
            return emittedCommand
        }
        if (nowMillis - candidateSinceMillis >= config.commandStabilityMillis) {
            emittedCommand = desired
            emittedAtMillis = nowMillis
        }
        return emittedCommand
    }

    /**
     * Steering towards the next 10 cm cell would produce a useless twitch every step. Instead we
     * aim at the furthest point roughly [NavigationConfig.lookaheadMeters] along the path that is
     * still in direct line of sight.
     */
    private fun trackYawRate(yaw: Float, nowMillis: Long) {
        val previous = lastYaw
        val dtMillis = nowMillis - lastYawMillis
        if (previous != null && dtMillis in 1..500) {
            // Capped at what a person can do: a bigger jump is tracking noise, not a turn.
            val rate = (GeometryUtils.angleDifference(previous, yaw) / (dtMillis / 1000f))
                .coerceIn(-MAX_HUMAN_TURN_RATE, MAX_HUMAN_TURN_RATE)
            yawRate += (rate - yawRate) * YAW_RATE_SMOOTHING
        } else if (previous == null || dtMillis > 500) {
            yawRate = 0f
        }
        lastYaw = yaw
        lastYawMillis = nowMillis
    }

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
     * Line of sight that, like the planner (AStarPlanner.isPassable), lets the user leave the
     * inflation halo they are standing in: halo cells near the start are allowed while they are
     * physically clear of the body ([NavigationConfig.haloEscapeMinClearanceMeters]) or clearance
     * grows with every cell, and an occupied cell never is. Past the halo the line must be traversable.
     * Without it, a user who drifted inside a wall's halo - easy, once a 360-degree LiDAR maps the
     * walls beside them - had every line blocked and was stopped where they stood.
     */
    private fun escapingLineOfSight(inflated: InflatedGrid, from: GridCoordinate, to: GridCoordinate): Boolean {
        val escapeCells = config.inflationRadiusCells + 1
        var clear = true
        var escaping = true
        var lastClearance = inflated.clearanceCells(from.gx, from.gz)
        OccupancyGrid.traceLine(from.gx, from.gz, to.gx, to.gz) { gx, gz, _ ->
            if (!clear) return@traceLine
            if (inflated.isTraversable(gx, gz)) {
                escaping = false
                return@traceLine
            }
            val clearance = inflated.clearanceCells(gx, gz)
            // Far enough from the obstacle for the body (the halo adds a comfort margin on top), or
            // getting further from it.
            val bodySafe = clearance * inflated.resolution >= config.haloEscapeMinClearanceMeters
            val inHalo = escaping && !inflated.isUnknown(gx, gz) && clearance > 0 &&
                (bodySafe || clearance > lastClearance) && GridCoordinate(gx, gz).chebyshevTo(from) <= escapeCells
            if (!inHalo) clear = false
            lastClearance = clearance
        }
        return clear
    }

    private enum class Ahead { CLEAR, UNKNOWN, BLOCKED }

    /** Straight-line distance to the end of the path: where the user is actually going. */
    private fun remainingPathMeters(position: Vec2, path: List<Vec2>): Float =
        path.last().distanceTo(position)

    /**
     * Sweeps a short corridor straight ahead of the user. Any blocked OR unknown cell within
     * [NavigationConfig.forwardSafetyDistanceMeters] suppresses STRAIGHT; a blocked one anywhere
     * in the sweep makes it a hazard rather than a request to look.
     *
     * Beyond the end of the path, known obstacles are ignored - a destination on a wall sits
     * inside the wall's inflation halo, and a fixed sweep stopped the user 1.4 m short of it,
     * forever. UNKNOWN space is never ignored, at any distance: an exploration path ends exactly
     * where unknown space begins, and unknown can be a drop-off. (Ignoring it there walked a
     * simulated user off the end of a floor.)
     */
    private fun forwardState(pose: Pose3D, inflated: InflatedGrid, remainingMeters: Float, grid: OccupancyGrid?): Ahead {
        val forward = pose.forward2D
        val steps = (config.forwardSafetyDistanceMeters / inflated.resolution).toInt().coerceAtLeast(1)
        val start = inflated.worldToGrid(pose.position2D)
        var unknown = false
        for (step in 1..steps) {
            val distance = step * inflated.resolution
            val probe = pose.position2D + forward * distance
            val cell = inflated.worldToGrid(probe)
            if (cell == start) continue
            // Floor only ever seen from afar is not yet known to be floor: at 5 m depth noise is
            // bigger than a step down, and a phone held high for a shop sign never looks close.
            // The top of a descending escalator read as floor that way, and a user walked onto it.
            if (inflated.isUnknown(cell) || (grid != null && !grid.isNearSeen(probe))) {
                unknown = true
                continue
            }
            if (inflated.isBlocked(cell) && distance <= remainingMeters) return Ahead.BLOCKED
        }
        return if (unknown) Ahead.UNKNOWN else Ahead.CLEAR
    }

    /**
     * Arrival must be stable over several frames: a single noisy pose that happens to land inside
     * the arrival radius should not end the guidance session.
     */
    private companion object {
        /** Per-frame blend for the measured turn rate: settles in about a quarter second at 30 fps. */
        const val YAW_RATE_SMOOTHING = 0.25f

        /** Beyond this the other side of the path is not an overshoot but the error wrapping round. */
        const val OVERSHOOT_LIMIT_DEGREES = 90f

        /** ~200 degrees a second: a brisk turn on the spot. */
        const val MAX_HUMAN_TURN_RATE = 3.5f
    }

    fun updateArrival(distanceToTargetMeters: Float?, nowMillis: Long): Boolean {
        if (distanceToTargetMeters == null || distanceToTargetMeters > config.arrivalRadiusMeters) {
            arrivalSinceMillis = null
            return false
        }
        val since = arrivalSinceMillis ?: nowMillis.also { arrivalSinceMillis = it }
        return nowMillis - since >= config.arrivalStableMillis
    }
}
