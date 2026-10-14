package com.navassist.navcore.strategy

import com.navassist.navcore.world.WorldStateSnapshot

/**
 * Decides what to learn or achieve next, as one bounded [StrategicSubgoal]. Called on events -
 * no active subgoal, a subgoal ended, a new landmark, the destination located - never every frame.
 *
 * Implementations: rule-based (free, deterministic), hypothesis-driven, or a model behind a
 * network call (those live outside the core and submit through NavigationEngine.submitSubgoal).
 * Whatever it returns is validated against the snapshot before anything moves.
 */
interface LongTermPlanner {
    /** @return the next subgoal, or null to leave the tactical layer to its own frontier choice. */
    fun decide(context: PlannerContext): StrategicSubgoal?

    fun reset() {}
}

data class PlannerContext(
    val snapshot: WorldStateSnapshot,
    /** Every outcome so far this trip, oldest first. */
    val outcomes: List<ActionOutcome>,
    /** Landmarks a reading showed are not the destination. */
    val refuted: Set<String>,
    /** Landmarks whose label names the destination, nearest first. */
    val targetLandmarks: List<String>,
    /** What triggered this decision, for logs. */
    val trigger: String,
    /** The subgoal running now, if any. */
    val active: StrategicSubgoal? = null,
    /** The hypotheses and their evidence. */
    val beliefs: com.navassist.navcore.belief.HypothesisStore? = null,
    /** Where the room numbering puts the destination, when it is a numbered room and enough plates were read. */
    val numberingTarget: com.navassist.navcore.geometry.Vec2? = null,
    /** The destination's plate has been read at its door: what is left is walking up to it. */
    val destinationVerified: Boolean = false,
)

/**
 * B1 expressed as a planner: always leave the choice to the tactical layer. With it the engine
 * behaves exactly as with no planner at all - the parity check for the subgoal machinery.
 */
object DefaultTacticsPlanner : LongTermPlanner {
    override fun decide(context: PlannerContext): StrategicSubgoal? = null
}
