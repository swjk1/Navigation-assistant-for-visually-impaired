package com.navassist.navcore.strategy

import com.navassist.navcore.geometry.Vec2

/**
 * The bounded actions a long-term planner can hand the tactical layer. Each one names what it acts
 * on by ID (a landmark, a frontier, a graph node), so it can be checked against the snapshot it was
 * chosen from, and each ends in an [ActionOutcome].
 */
enum class Skill {
    /** Explore: a named frontier, or with no target the tactical layer's own frontier choice. */
    EXPLORE_FRONTIER,

    /** Walk to a known landmark. */
    GO_TO_LANDMARK,

    /** Walk back to a place on the walked graph. */
    GO_TO_NODE,

    /** Turn on the spot by [StrategicSubgoal.angleRadians] to bring the camera round. */
    ROTATE_IN_PLACE,

    /** Look all round where one stands: a full turn. */
    SCAN_INTERSECTION,

    /** Walk to a viewing position for a landmark, facing it. */
    APPROACH_VIEWPOINT,

    /** Approach a sign to reading distance and face it, until it is read or found unreadable. */
    INSPECT_SIGN,

    /** As [INSPECT_SIGN], for a door's plate. */
    INSPECT_ROOM_LABEL,

    /** Walk the corridor the way the numbering rises or falls towards the target. */
    FOLLOW_NUMBERING,

    /** Go to the candidate door and read its plate: the only way to arrive at a numbered room. */
    VERIFY_DESTINATION,

    /** Give up: nothing feasible is left. */
    STOP,

    /** Go to a lift's door and ride it to [StrategicSubgoal.floorLabel]; succeeds on arriving there. */
    TAKE_LIFT,
}

/** Why a subgoal ended. */
enum class OutcomeStatus { SUCCEEDED, FAILED, BLOCKED, TIMED_OUT, INTERRUPTED, SUPERSEDED }

data class StrategicSubgoal(
    val id: String,
    val skill: Skill,
    /** Landmark, frontier or node ID, as named in the snapshot. Null for "your own choice" or a turn. */
    val targetId: String? = null,
    /** Resolved when accepted: where the tactical layer is heading. */
    val targetPosition: Vec2? = null,
    /** For [Skill.ROTATE_IN_PLACE] (positive = right) and [Skill.FOLLOW_NUMBERING] (a heading). */
    val angleRadians: Float? = null,
    /** Free text for logs and prompts: what the planner wants to learn or achieve. */
    val purpose: String = "",
    /** The hypotheses this subgoal tests or relies on. */
    val hypothesisIds: List<String> = emptyList(),
    val maxTravelMeters: Float = 60f,
    /** For [Skill.TAKE_LIFT]: the floor to press, as the lift labels it ("3", "G"). */
    val floorLabel: String? = null,
    /**
     * For [Skill.EXPLORE_FRONTIER]: what to steer exploration towards instead of the destination -
     * a lift, when the destination is believed to be on another floor. Arrival is unaffected.
     */
    val searchFor: com.navassist.navcore.semantic.NavigationTarget? = null,
    val maxDurationMillis: Long = 90_000,
    /** World-model revision the planner saw. */
    val revision: Int = 0,
)

data class ActionOutcome(
    val subgoalId: String,
    val skill: Skill,
    val targetId: String?,
    val status: OutcomeStatus,
    val reason: String,
    val travelledMeters: Float,
    val elapsedMillis: Long,
    /** Ledger events recorded while it ran. */
    val evidenceEventIds: List<String>,
    val endedAtMillis: Long,
)

/** Why a proposed subgoal was refused. */
data class Rejection(val subgoal: StrategicSubgoal, val reason: String)
