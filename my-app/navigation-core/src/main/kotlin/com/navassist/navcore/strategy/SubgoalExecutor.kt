package com.navassist.navcore.strategy

import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.topology.TopologicalMap
import com.navassist.navcore.world.Landmark
import com.navassist.navcore.world.WorldModel
import kotlin.math.abs

/**
 * Runs one [StrategicSubgoal] at a time on top of the engine's tactical machinery (A*, frontier
 * search, the controller). Each frame it says what the tactical layer should do - head for a
 * point, turn on the spot, hold still to read, or carry on as it would without a planner - and it
 * decides when the subgoal has ended and how.
 *
 * It never issues a forward movement itself: walking always goes through the planner and the
 * controller's safety gate. Turning on the spot and holding still are always safe.
 */
class SubgoalExecutor(private val world: WorldModel, private val topology: TopologicalMap) {

    sealed interface Directive {
        /** No subgoal, or one that leaves the choice to the tactical layer: behave as without a planner. */
        object Default : Directive

        /** Plan a path to [goal] and follow it. */
        data class HeadFor(val goal: Vec2) : Directive

        /** Turn on the spot (or hold, for [NavigationCommand.STOP]) without moving. */
        data class Turn(val command: NavigationCommand) : Directive
    }

    var active: StrategicSubgoal? = null
        private set

    private var startMillis = 0L
    private var startWalked = 0f
    private var startEvents = 0
    private var turnedRadians = 0f
    private var lastYaw: Float? = null
    private var arrivedAtMillis = 0L
    private var routeFailingSinceMillis = 0L
    private var tracked: Vec2? = null

    /** The landmark the last successful verification read, until consumed by the engine. */
    var verifiedLandmarkId: String? = null

    /** Readings that showed a candidate door was not the destination: landmark IDs. */
    val refuted = LinkedHashSet<String>()

    private val pending = ArrayList<ActionOutcome>()

    /** Outcomes since the last call, oldest first. */
    fun takeOutcomes(): List<ActionOutcome> = ArrayList(pending).also { pending.clear() }

    val history = ArrayList<ActionOutcome>()

    fun reset() {
        active = null
        pending.clear()
        history.clear()
        refuted.clear()
        verifiedLandmarkId = null
    }

    /** Starts [subgoal], ending the active one as superseded. */
    fun start(subgoal: StrategicSubgoal, nowMillis: Long, walkedMeters: Float) {
        active?.let { end(OutcomeStatus.SUPERSEDED, "replaced by ${subgoal.id}", nowMillis, walkedMeters) }
        active = subgoal
        startMillis = nowMillis
        startWalked = walkedMeters
        startEvents = world.events.size
        turnedRadians = 0f
        lastYaw = null
        arrivedAtMillis = 0L
        routeFailingSinceMillis = 0L
        tracked = null
        exitingLift = false
    }

    /** The tactical layer could (or could not) find a route this frame. */
    fun reportRoute(found: Boolean, nowMillis: Long, walkedMeters: Float) {
        val sub = active ?: return
        if (sub.targetPosition == null) return
        if (found) {
            routeFailingSinceMillis = 0L
        } else {
            if (routeFailingSinceMillis == 0L) routeFailingSinceMillis = nowMillis
            if (nowMillis - routeFailingSinceMillis >= ROUTE_GIVE_UP_MILLIS) end(OutcomeStatus.BLOCKED, "no route for ${ROUTE_GIVE_UP_MILLIS / 1000} s", nowMillis, walkedMeters)
        }
    }

    /**
     * What to do this frame for the active subgoal.
     *
     * @param targetMatches whether a landmark's label names the destination.
     */
    fun directive(
        pose: Pose3D,
        nowMillis: Long,
        walkedMeters: Float,
        /** The current frontier near a point, if one still exists there. */
        frontierNear: (Vec2) -> Vec2?,
        targetMatches: (Landmark) -> Boolean,
    ): Directive {
        val sub = active ?: return Directive.Default
        val elapsed = nowMillis - startMillis
        if (elapsed > sub.maxDurationMillis) {
            end(OutcomeStatus.TIMED_OUT, "over ${sub.maxDurationMillis / 1000} s", nowMillis, walkedMeters)
            return Directive.Default
        }
        if (walkedMeters - startWalked > sub.maxTravelMeters) {
            end(OutcomeStatus.FAILED, "walked over ${sub.maxTravelMeters} m", nowMillis, walkedMeters)
            return Directive.Default
        }
        lastYaw?.let { turnedRadians += GeometryUtils.angleDifference(it, pose.yawRadians) }
        lastYaw = pose.yawRadians

        return when (sub.skill) {
            Skill.EXPLORE_FRONTIER -> {
                // Frontier IDs are not stable between detection passes; follow it by position.
                val at = tracked ?: sub.targetPosition ?: return Directive.Default
                val now = frontierNear(at)
                if (now == null) {
                    end(OutcomeStatus.SUCCEEDED, "frontier ${sub.targetId} explored", nowMillis, walkedMeters)
                    Directive.Default
                } else {
                    tracked = now
                    Directive.HeadFor(now)
                }
            }
            Skill.ROTATE_IN_PLACE, Skill.SCAN_INTERSECTION -> {
                val want = if (sub.skill == Skill.SCAN_INTERSECTION) FULL_TURN else (sub.angleRadians ?: 0f)
                if (abs(turnedRadians) >= abs(want) - TURN_TOLERANCE) {
                    end(OutcomeStatus.SUCCEEDED, "turned %.0f degrees".format(GeometryUtils.radiansToDegrees(turnedRadians)), nowMillis, walkedMeters)
                    Directive.Default
                } else {
                    Directive.Turn(if (want >= 0f) NavigationCommand.TURN_RIGHT else NavigationCommand.TURN_LEFT)
                }
            }
            Skill.GO_TO_NODE -> {
                val goal = sub.targetPosition ?: return endFailed("no position", nowMillis, walkedMeters)
                if (pose.position2D.distanceTo(goal) <= REACHED_METERS) {
                    end(OutcomeStatus.SUCCEEDED, "reached", nowMillis, walkedMeters)
                    Directive.Default
                } else Directive.HeadFor(goal)
            }
            Skill.GO_TO_LANDMARK, Skill.APPROACH_VIEWPOINT, Skill.INSPECT_SIGN, Skill.INSPECT_ROOM_LABEL, Skill.VERIFY_DESTINATION ->
                landmarkSkill(sub, pose, nowMillis, walkedMeters, targetMatches)
            Skill.FOLLOW_NUMBERING -> Directive.Default
            Skill.STOP -> Directive.Turn(NavigationCommand.STOP)
            Skill.TAKE_LIFT -> liftSkill(sub, pose, nowMillis, walkedMeters)
        }
    }

    private fun landmarkSkill(
        sub: StrategicSubgoal,
        pose: Pose3D,
        nowMillis: Long,
        walkedMeters: Float,
        targetMatches: (Landmark) -> Boolean,
    ): Directive {
        val landmark = sub.targetId?.let { world.landmark(it) } ?: return endFailed("unknown landmark ${sub.targetId}", nowMillis, walkedMeters)
        val viewpoint = sub.targetPosition ?: viewpointFor(landmark)
        val here = pose.position2D

        // Reading-based endings come first: they can happen on the way.
        val readSinceStart = readsSince(landmark)
        when (sub.skill) {
            Skill.INSPECT_SIGN, Skill.INSPECT_ROOM_LABEL -> if (readSinceStart > 0) {
                end(OutcomeStatus.SUCCEEDED, "read '${landmark.label}'", nowMillis, walkedMeters)
                return Directive.Default
            }
            Skill.VERIFY_DESTINATION -> if (readSinceStart > 0 && closeReading(landmark)) {
                if (targetMatches(landmark)) {
                    verifiedLandmarkId = landmark.id
                    end(OutcomeStatus.SUCCEEDED, "verified '${landmark.label}'", nowMillis, walkedMeters)
                } else {
                    refuted += landmark.id
                    end(OutcomeStatus.FAILED, "refuted: reads '${landmark.label}'", nowMillis, walkedMeters)
                }
                return Directive.Default
            }
            else -> Unit
        }

        if (here.distanceTo(viewpoint) > REACHED_METERS && arrivedAtMillis == 0L) return Directive.HeadFor(viewpoint)
        if (arrivedAtMillis == 0L) arrivedAtMillis = nowMillis
        if (sub.skill == Skill.GO_TO_LANDMARK || sub.skill == Skill.APPROACH_VIEWPOINT) {
            end(OutcomeStatus.SUCCEEDED, "reached", nowMillis, walkedMeters)
            return Directive.Default
        }
        // At the viewpoint: face the landmark, then hold still while perception reads it.
        val toward = landmark.position - here
        val error = GeometryUtils.angleDifference(pose.yawRadians, toward.yawRadians())
        if (abs(error) > FACING_RADIANS) {
            return Directive.Turn(if (error > 0f) NavigationCommand.TURN_RIGHT else NavigationCommand.TURN_LEFT)
        }
        if (nowMillis - arrivedAtMillis > READ_WAIT_MILLIS) {
            end(OutcomeStatus.FAILED, "not readable from here", nowMillis, walkedMeters)
            return Directive.Default
        }
        return Directive.Turn(NavigationCommand.STOP)
    }

    /**
     * A reading of the landmark: its text, for a plate or sign; for an exit, a lift or a door,
     * which carry no text, a fresh detection of it is the reading.
     */
    private fun isReading(landmark: Landmark, e: com.navassist.navcore.world.ObservationEvent): Boolean =
        e.landmarkId == landmark.id && (e.value != null || !hasText(landmark))

    private fun hasText(landmark: Landmark) =
        landmark.kind == com.navassist.navcore.world.LandmarkKind.PLATE || landmark.kind == com.navassist.navcore.world.LandmarkKind.SIGN

    /** The floor asked for at a lift door, while waiting there; null otherwise. */
    var liftRequest: String? = null
        private set
    private var floorAtStart: String? = null

    /** Arrived on the floor asked for: turning to face out of the lift before the ride counts as done. */
    private var exitingLift = false

    private fun liftSkill(sub: StrategicSubgoal, pose: Pose3D, nowMillis: Long, walkedMeters: Float): Directive {
        if (exitingLift) {
            // Facing the lift doors at the back of the lobby, the camera sees no way out; people turn
            // round to step out of a lift, and so does this.
            if (kotlin.math.abs(turnedRadians) >= HALF_TURN - TURN_TOLERANCE) {
                exitingLift = false
                end(OutcomeStatus.SUCCEEDED, "arrived on floor ${sub.floorLabel}", nowMillis, walkedMeters)
                return Directive.Default
            }
            return Directive.Turn(NavigationCommand.TURN_RIGHT)
        }
        val door = sub.targetPosition ?: return endFailed("no lift door", nowMillis, walkedMeters)
        if (arrivedAtMillis == 0L && pose.position2D.distanceTo(door) > REACHED_METERS) {
            liftRequest = null
            return Directive.HeadFor(door)
        }
        if (arrivedAtMillis == 0L) {
            arrivedAtMillis = nowMillis
            floorAtStart = world.floor
        }
        if (nowMillis - arrivedAtMillis > LIFT_WAIT_MILLIS) {
            liftRequest = null
            end(OutcomeStatus.FAILED, "the lift did not take us to ${sub.floorLabel}", nowMillis, walkedMeters)
            return Directive.Default
        }
        liftRequest = sub.floorLabel
        return Directive.Turn(NavigationCommand.STOP)
    }

    /**
     * A floor was read (a lift display): ends a lift ride waiting at the door, as it should or not -
     * a lift that cannot go where asked shows the floor it stayed on.
     */
    fun onFloorRead(label: String, nowMillis: Long, walkedMeters: Float) {
        val sub = active ?: return
        if (sub.skill != Skill.TAKE_LIFT || liftRequest == null) return
        liftRequest = null
        if (label == sub.floorLabel) {
            // Done once turned to face out of the lift.
            exitingLift = true
            turnedRadians = 0f
            lastYaw = null
        } else {
            end(OutcomeStatus.FAILED, "arrived on floor $label, not ${sub.floorLabel}", nowMillis, walkedMeters)
        }
    }

    /** Readings of the landmark recorded since the subgoal began. */
    private fun readsSince(landmark: Landmark): Int {
        var n = 0
        for (i in startEvents until world.events.size) if (isReading(landmark, world.events[i])) n++
        return n
    }

    /** The latest reading was taken close enough to trust which landmark it belongs to. */
    private fun closeReading(landmark: Landmark): Boolean {
        for (i in world.events.size - 1 downTo startEvents) {
            val e = world.events[i]
            if (!isReading(landmark, e)) continue
            return e.observerPosition.distanceTo(landmark.position) <= VERIFY_RANGE_METERS
        }
        return false
    }

    private fun endFailed(reason: String, nowMillis: Long, walkedMeters: Float): Directive {
        end(OutcomeStatus.FAILED, reason, nowMillis, walkedMeters)
        return Directive.Default
    }

    fun end(status: OutcomeStatus, reason: String, nowMillis: Long, walkedMeters: Float) {
        val sub = active ?: return
        if (sub.skill == Skill.TAKE_LIFT) liftRequest = null
        val evidence = (startEvents until world.events.size).map { world.events[it].id }
        val outcome = ActionOutcome(
            subgoalId = sub.id, skill = sub.skill, targetId = sub.targetId, status = status, reason = reason,
            travelledMeters = walkedMeters - startWalked, elapsedMillis = nowMillis - startMillis,
            evidenceEventIds = evidence, endedAtMillis = nowMillis,
        )
        pending += outcome
        history += outcome
        active = null
    }

    companion object {
        const val REACHED_METERS = 0.6f
        const val VERIFY_RANGE_METERS = 3.5f
        const val READ_WAIT_MILLIS = 4_000L
        /** A lift is waited for at its door this long, ride included, before giving up. */
        const val LIFT_WAIT_MILLIS = 60_000L
        const val ROUTE_GIVE_UP_MILLIS = 6_000L
        val FULL_TURN = (2.0 * Math.PI).toFloat()
        val HALF_TURN = Math.PI.toFloat()
        val TURN_TOLERANCE = GeometryUtils.degreesToRadians(10f)
        val FACING_RADIANS = GeometryUtils.degreesToRadians(15f)

        /** Reading distance in front of a landmark. */
        const val VIEW_DISTANCE_METERS = 1.5f

        /**
         * Where to stand to read a landmark: square in front of it, at reading distance.
         *
         * In front means along the normal of the wall it is on, when the map knows that wall
         * ([wallNormal], oriented towards where it was seen from). The places it was seen from are
         * only a fallback: seen down a corridor, their mean points along the wall, and standing
         * there reads a plate edge-on - exactly why it was unread.
         */
        fun viewpointFor(landmark: Landmark, world: WorldModel, wallNormal: Vec2? = null): Vec2 {
            var sum = Vec2.ZERO
            for (id in landmark.eventIds) {
                val e = world.event(id) ?: continue
                val d = e.observerPosition - landmark.position
                if (d.lengthSquared() > 1e-4f) sum += d.normalized()
            }
            val seenFrom = if (sum.lengthSquared() > 1e-6f) sum.normalized() else Vec2(0f, -1f)
            val direction = wallNormal?.let { n -> if (n.dot(seenFrom) >= 0f) n else n * -1f } ?: seenFrom
            return landmark.position + direction * VIEW_DISTANCE_METERS
        }
    }

    private fun viewpointFor(landmark: Landmark): Vec2 = viewpointFor(landmark, world)
}
