package com.navassist.navcore.state

import com.navassist.navcore.exploration.Frontier
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.AdviceDirection
import com.navassist.navcore.semantic.PlannerHypothesis
import com.navassist.navcore.semantic.RoomNumberModel
import com.navassist.navcore.semantic.SeenSign
import com.navassist.navcore.semantic.TargetSighting
import com.navassist.navcore.topology.TopologicalEdge
import com.navassist.navcore.topology.TopologicalNode

/**
 * Everything the engine is remembering right now, in canonical coordinates: for viewers and
 * diagnostics only. Like [com.navassist.navcore.semantic.Situation] it is perceived state, never
 * ground truth; unlike it, it is not meant for an adviser and never crosses the native bridge.
 */
data class NavigationMemory(
    val nowMillis: Long,
    /** Signs and landmarks still held, newest first. */
    val signs: List<SeenSign>,
    /** Every numbered plate read this trip, where it was placed. */
    val plates: List<RoomNumberModel.Plate>,
    /** Where the numbering puts the destination, when it is a numbered room and enough plates were read. */
    val numbering: RoomNumberModel.Estimate?,
    val targetLikelyOnAnotherFloor: Boolean,
    /** Where the engine believes the destination is, and how it placed it. */
    val sighting: TargetSighting?,
    /** The engine's scored frontiers, best first, with every term of the score. */
    val frontiers: List<Frontier>,
    /** The frontier it is committed to, if any. */
    val selectedFrontier: Frontier?,
    /** Where it is steering now. */
    val goal: Vec2?,
    /** Areas ruled out for exploration, with how many failures put them there. */
    val blacklisted: List<Pair<Vec2, Int>>,
    /** The walked graph of places. */
    val nodes: List<TopologicalNode>,
    val edges: List<TopologicalEdge>,
    /** The adviser's current answer and its age; empty when none or stale. */
    val advice: List<AdviceDirection>,
    val adviceAgeMillis: Long,
    /** The planner's guesses and what each still counts. */
    val plan: List<Pair<PlannerHypothesis, Float>>,
)
