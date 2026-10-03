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
)
