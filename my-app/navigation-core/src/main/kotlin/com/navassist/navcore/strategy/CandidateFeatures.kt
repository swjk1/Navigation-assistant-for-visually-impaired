package com.navassist.navcore.strategy

import com.navassist.navcore.belief.HypothesisFamily
import com.navassist.navcore.belief.HypothesisStatus

/**
 * The numbers a learned scorer sees for one option at one decision: what the option is, what it
 * costs, what it could reveal, and the state of the beliefs when it is weighed. Computed the same
 * way when training data is generated and when the scorer is used, from what the agent knew at
 * that moment only - never from the simulator's truth.
 *
 * [candidate] null is the option of carrying on exploring.
 */
object CandidateFeatures {

    val names = listOf(
        "bias", "is_explore", "is_plate", "is_sign", "is_reread",
        "distance_m", "seconds", "bits", "progress",
        "at_decision_point", "frontiers", "plates_read", "unread_nearby",
        "direction_confirmed", "numbering_known", "distance_to_estimate_m", "elapsed_s",
    )

    fun of(context: PlannerContext, candidate: Candidate?, atDecisionPoint: Boolean): FloatArray {
        val s = context.snapshot
        val plates = s.landmarks.count { it.label != null && it.kind == com.navassist.navcore.world.LandmarkKind.PLATE }
        val beliefs = context.beliefs
        val directionConfirmed = beliefs?.hypotheses?.any {
            it.family == HypothesisFamily.NUMBERING_DIRECTION && it.status == HypothesisStatus.CONFIRMED
        } ?: false
        val estimate = context.numberingTarget
        val target = candidate?.subgoal?.targetId?.let { id -> s.landmarks.firstOrNull { it.id == id } ?: s.unreadSigns.firstOrNull { it.id == id } }
        val toEstimate = if (estimate != null && target != null) target.position.distanceTo(estimate) else -1f
        val isReread = candidate != null && candidate.progress >= CandidatePlanner.W_MISREAD - 1e-3f
        return floatArrayOf(
            1f,
            if (candidate == null) 1f else 0f,
            if (candidate?.subgoal?.skill == Skill.INSPECT_ROOM_LABEL && !isReread) 1f else 0f,
            if (candidate?.subgoal?.skill == Skill.INSPECT_SIGN) 1f else 0f,
            if (isReread) 1f else 0f,
            candidate?.distanceMeters ?: 0f,
            candidate?.seconds ?: 0f,
            candidate?.bits ?: 0f,
            candidate?.progress ?: 0f,
            if (atDecisionPoint) 1f else 0f,
            s.frontiers.size.toFloat(),
            plates.toFloat(),
            s.unreadSigns.count { it.distanceMeters <= CandidatePlanner.INSPECT_RADIUS_METERS }.toFloat(),
            if (directionConfirmed) 1f else 0f,
            if (estimate != null) 1f else 0f,
            toEstimate,
            s.elapsedMillis / 1000f,
        )
    }
}
