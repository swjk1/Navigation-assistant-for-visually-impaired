package com.navassist.navcore.semantic

import com.navassist.navcore.exploration.Frontier
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.state.NavigationStatus

/**
 * A direction an adviser outside the engine suggests exploring: a language model reading the
 * building, a learned decision model, a floor plan. Advice only biases which frontier is chosen,
 * exactly as a sign does. It never plans a path, steers, or lifts a safety stop, and it expires.
 *
 * @param origin where the user stood when the advice was asked for (canonical x, z).
 * @param direction unit direction suggested from there (canonical x, z).
 * @param weight from -1 ("that way is a shop, not the way on") to +1 ("go this way").
 */
data class AdviceDirection(val origin: Vec2, val direction: Vec2, val weight: Float)

/**
 * A long-term planner's guess at where the destination is: "the 300s run east past the lifts, so
 * Room 319 is about here". Like advice it only biases which frontier is chosen - towards the
 * guess - and it fades with age. Unlike advice it is a place, not a direction, so it stays
 * meaningful as the user moves; and once the user has searched that place without finding the
 * destination, the guess is spent.
 *
 * @param position where the destination should be (canonical x, z).
 * @param radiusMeters how precise the guess is; searching within this of [position] spends it.
 * @param confidence 0..1, the planner's own.
 * @param check what would confirm or refute it ("the next plate should be about 320"), for logs
 *        and for the planner's next turn; the engine does not read it.
 */
data class PlannerHypothesis(
    val position: Vec2,
    val radiusMeters: Float,
    val confidence: Float,
    val check: String? = null,
)

/** A sign or landmark the engine has been told about, as an adviser may see it. */
data class SeenSign(
    /** ROOM, ROOM_RANGE, EXIT, DOOR, STAIRS, ELEVATOR. */
    val kind: String,
    /** Room label or name, "300-349" for a range; null when there is no text. */
    val label: String?,
    /** For ranges and exits: which way the sign points, as read. */
    val pointing: SemanticDirection?,
    /** Where it is, when depth placed it. */
    val position: Vec2?,
    /** Where the user stood, and which way it was seen, when it was read. */
    val observerPosition: Vec2,
    val bearing: Vec2?,
    val ageMillis: Long,
)

/**
 * What the engine knows, for an adviser to reason over: only what it has perceived, never ground
 * truth. Frontiers are the engine's own candidates, best-scored first.
 */
data class Situation(
    val target: NavigationTarget,
    val status: NavigationStatus,
    val pose: Pose3D,
    val targetLocated: Boolean,
    val walkedMeters: Float,
    val elapsedMillis: Long,
    val frontiers: List<Frontier>,
    val signs: List<SeenSign>,
    /** Where the room numbering puts the destination, when it is a numbered room and enough plates were read. */
    val numbering: RoomNumberModel.Estimate? = null,
    /** The destination's number is in another hundreds block from every plate read: likely another floor. */
    val targetLikelyOnAnotherFloor: Boolean = false,
    /** The planner's guesses the engine still holds, with how much each still counts (0..1). */
    val plan: List<Pair<PlannerHypothesis, Float>> = emptyList(),
)
