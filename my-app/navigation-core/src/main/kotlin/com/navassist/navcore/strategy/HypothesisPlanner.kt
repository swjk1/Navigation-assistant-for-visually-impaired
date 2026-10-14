package com.navassist.navcore.strategy

import com.navassist.navcore.belief.HypothesisStatus
import com.navassist.navcore.world.LandmarkKind
import com.navassist.navcore.world.LandmarkView

/** One action the long-term planner could take next, with what is known about it. */
data class Candidate(
    /** Short stable ID for prompts and logs: "c1". */
    val id: String,
    val subgoal: StrategicSubgoal,
    /** Plain-language description, for a model and for people. */
    val description: String,
    val distanceMeters: Float,
    /** Uncertainty it would resolve in the hypotheses it tests, in bits. */
    val bits: Float,
    /** How much it may advance the trip directly (a plate where the destination should be). */
    val progress: Float,
    /** Estimated time it costs, walking and looking. */
    val seconds: Float,
)

/**
 * Turns candidates into utilities; higher is better. Carrying on exploring is always an option,
 * valued at [Scores.exploreUtility]. Returns null when it cannot score (a model call failed or was
 * not made): the planner then falls back to its fallback scorer.
 */
interface CandidateScorer {
    fun score(context: PlannerContext, candidates: List<Candidate>): Scores?
}

data class Scores(val utilities: List<Float>, val exploreUtility: Float, val note: String = "")

/**
 * B4's scorer: one-step information gain with hand-set numbers.
 *
 *   U(x) = p_readable * (W_INFO * bits(x) + progress(x)) - W_TIME * seconds(x)
 *
 * Every number is an assumption, to be replaced by likelihoods measured in the simulator - marked
 * as such, not tuned to win.
 */
object HeuristicScorer : CandidateScorer {
    const val P_READABLE = 0.7f
    const val W_INFO = 1.0f
    const val W_TIME = 0.04f
    const val U_EXPLORE = 0.25f

    override fun score(context: PlannerContext, candidates: List<Candidate>): Scores =
        Scores(candidates.map { P_READABLE * (W_INFO * it.bits + it.progress) - W_TIME * it.seconds }, U_EXPLORE)
}

/**
 * The long-term planner that tests hypotheses: verify a door that names the destination first; else
 * generate candidate observations (an unread plate or sign nearby, a plate read one character off
 * the destination), score them, and take the best if it beats exploring.
 *
 * With [HeuristicScorer] this is B4. With a decision model as the scorer it is B5, and with the
 * trained cost-to-go scorer it is P: the same candidates, skills and tactics - only the judgement
 * of which observation is worth the walk changes.
 */
open class CandidatePlanner(
    private val scorer: CandidateScorer = HeuristicScorer,
    private val fallback: CandidateScorer = HeuristicScorer,
) : LongTermPlanner {

    private val attempted = HashSet<String>()

    /** Decisions made so far this trip, counting only those with candidates to choose between. */
    var decisions = 0
        private set

    /**
     * Told about every decision with candidates: its index, the context, the candidates, whether it
     * was at a decision point, and what was chosen (an index into the candidates, or -1 to explore).
     */
    var onDecision: ((Int, PlannerContext, List<Candidate>, Boolean, Int) -> Unit)? = null

    /** Forked runs: at decision [forceAt], choose [forceOption] (-1 = explore) instead, then call [afterForce]. */
    var forceAt: Int = -1
    var forceOption: Int = -1
    var afterForce: (() -> Unit)? = null

    /** The last decision's candidates and scores, for logs. */
    var lastCandidates: List<Candidate> = emptyList()
        private set
    var lastScores: Scores? = null
        private set

    override fun reset() {
        attempted.clear()
        decisions = 0
        lastCandidates = emptyList()
        lastScores = null
    }

    override fun decide(context: PlannerContext): StrategicSubgoal? {
        for (o in context.outcomes) if (o.status != OutcomeStatus.SUPERSEDED && o.targetId != null) attempted += o.targetId
        if (context.destinationVerified) return null
        val snapshot = context.snapshot

        // A landmark that names the destination: read it there.
        context.targetLandmarks.firstOrNull { it !in context.refuted }?.let { id ->
            if (context.active?.skill == Skill.VERIFY_DESTINATION && context.active.targetId == id) return null
            return StrategicSubgoal(id = "", skill = Skill.VERIFY_DESTINATION, targetId = id, purpose = "read the destination's sign at the door", maxTravelMeters = 40f)
        }
        // Located but not yet a landmark: the tactical layer is already heading there.
        if (snapshot.targetPosition != null) return null

        // Believed to be on another floor: to a lift, and up or down.
        liftDecision(context)?.let { s ->
            val a = context.active
            val same = a != null && s.skill == a.skill && s.targetId == a.targetId && s.floorLabel == a.floorLabel && s.searchFor == a.searchFor
            return if (same) null else s
        }

        val candidates = candidates(context)
        lastCandidates = candidates
        if (candidates.isEmpty()) return null
        val index = decisions++
        val pick = if (index == forceAt) {
            afterForce?.invoke()
            forceOption
        } else {
            val scores = scorer.score(context, candidates) ?: fallback.score(context, candidates) ?: return null
            lastScores = scores
            val bestIndex = scores.utilities.indices.maxByOrNull { scores.utilities[it] } ?: return null
            if (scores.utilities[bestIndex] <= scores.exploreUtility) -1 else bestIndex
        }
        onDecision?.invoke(index, context, candidates, atDecisionPoint(context), pick)
        if (pick < 0) return null
        val choice = candidates[pick]
        if (context.active?.targetId == choice.subgoal.targetId) return null
        return choice.subgoal
    }

    /**
     * The hypothesis "the leading digit is the floor" acted on: when the plates read here share a
     * leading digit other than the destination's, and the hypothesis is not contradicted, ride a lift
     * to the destination's digit plus the offset readings have shown between lift labels and room
     * digits. The ride is the test: arriving, the plates there confirm the floor or show the offset
     * is not what was assumed, and the next decision rides again with the corrected offset - or,
     * if the hypothesis has fallen, stays and searches this floor.
     */
    private fun liftDecision(context: PlannerContext): StrategicSubgoal? {
        val number = (context.snapshot.goal as? com.navassist.navcore.semantic.NavigationTarget.Room)?.number ?: return null
        if (number < 100) return null
        val beliefs = context.beliefs ?: return null
        if (beliefs.floorHypothesis()?.status == HypothesisStatus.CONTRADICTED) return null
        val want = number / 100
        val (here, reads) = beliefs.digitHere() ?: return null
        if (here == want || reads < 2) return null
        val label = (want + beliefs.liftOffset()).let { if (it == 0 && context.snapshot.floor.equals("G", ignoreCase = true)) "G" else it.toString() }
        if (label == context.snapshot.floor) return null
        val rides = context.outcomes.filter { it.skill == Skill.TAKE_LIFT }
        if (rides.size >= MAX_LIFT_RIDES) return null
        // Do not press the same wrong button twice.
        if (rides.any { it.status != OutcomeStatus.SUCCEEDED && it.reason.endsWith(" $label") }) return null
        val lift = context.snapshot.landmarks.firstOrNull { it.kind == LandmarkKind.ELEVATOR }
            ?: return StrategicSubgoal(
                id = "", skill = Skill.EXPLORE_FRONTIER, searchFor = com.navassist.navcore.semantic.NavigationTarget.Elevator,
                purpose = "find a lift: the plates here are ${here}xx, the destination is ${want}xx",
                hypothesisIds = listOfNotNull(beliefs.floorHypothesis()?.id), maxDurationMillis = 120_000,
            )
        return StrategicSubgoal(
            id = "", skill = Skill.TAKE_LIFT, targetId = lift.id, floorLabel = label,
            purpose = "ride to $label: the plates here are ${here}xx, the destination is ${want}xx (lift offset ${beliefs.liftOffset()})",
            hypothesisIds = listOfNotNull(beliefs.floorHypothesis()?.id), maxTravelMeters = 80f, maxDurationMillis = 120_000,
        )
    }

    /** What could be looked at next: unread plates and signs nearby, and plates one off the destination. */
    fun candidates(context: PlannerContext): List<Candidate> {
        val snapshot = context.snapshot
        val out = ArrayList<Candidate>()
        fun add(l: LandmarkView, skill: Skill, bits: Float, progress: Float, what: String, tests: List<String>) {
            val seconds = l.distanceMeters * DETOUR_FACTOR / WALK_SPEED + LOOK_SECONDS
            val subgoal = StrategicSubgoal(
                id = "", skill = skill, targetId = l.id, hypothesisIds = tests,
                purpose = what, maxTravelMeters = 20f, maxDurationMillis = 45_000,
            )
            out += Candidate("c${out.size + 1}", subgoal, what, l.distanceMeters, bits, progress, seconds)
        }
        val beliefs = context.beliefs
        // Information is worth the walk only where it can change what is done next: at a decision
        // point, where the ways on lead in clearly different directions. Elsewhere, walking on
        // reads the same plates for free.
        val relevance = if (atDecisionPoint(context)) 1f else OFF_DECISION_RELEVANCE
        for (l in snapshot.unreadSigns) {
            if (l.id in attempted || l.distanceMeters > INSPECT_RADIUS_METERS) continue
            if (l.kind != LandmarkKind.PLATE && l.kind != LandmarkKind.SIGN) continue
            val nearEstimate = context.numberingTarget?.let { it.distanceTo(l.position) <= NEAR_ESTIMATE_METERS } ?: false
            val tests = beliefs?.corridorNear(l.position)?.let { c ->
                beliefs.hypotheses.filter { it.scope == c.id && it.status != HypothesisStatus.CONFIRMED }.map { it.id }
            }.orEmpty()
            if (l.kind == LandmarkKind.SIGN) {
                add(l, Skill.INSPECT_SIGN, SIGN_BITS * relevance, 0f, "read the unread sign ${l.id}, %.0f m away".format(l.distanceMeters), tests)
            } else {
                // Progress, not information, and so not gated by the decision point: the plate may
                // be the destination itself.
                val maybeTarget = W_TARGET * targetChance(l, context)
                add(
                    l, Skill.INSPECT_ROOM_LABEL, plateBits(l, context) * relevance, (if (nearEstimate) W_PROGRESS else 0f) + maybeTarget,
                    "read the unread door plate ${l.id}, %.0f m away%s".format(l.distanceMeters, if (nearEstimate) ", where the numbering puts the destination" else ""),
                    tests,
                )
            }
        }
        // A plate read as one character off the destination ("31A" for 314) may be it.
        val wanted = (snapshot.goal as? com.navassist.navcore.semantic.NavigationTarget.Room)?.normalizedLabel
        if (wanted != null) for (l in snapshot.landmarks) {
            val label = l.label ?: continue
            if (l.kind != LandmarkKind.PLATE || l.id in attempted || l.distanceMeters > INSPECT_RADIUS_METERS) continue
            if (!oneOff(com.navassist.navcore.semantic.TargetMatcher.normalizeLabel(label), wanted)) continue
            add(l, Skill.INSPECT_ROOM_LABEL, 0f, W_MISREAD, "re-read plate ${l.id}, read as '$label' - one character off the destination", emptyList())
        }
        return out
    }

    /**
     * Two ways on that part by more than [DECISION_ANGLE_DEGREES], both within reach: a junction,
     * or the two ends of a corridor. Where every way on leads the same way, there is nothing to decide.
     */
    fun atDecisionPoint(context: PlannerContext): Boolean = atDecisionPointOf(context)

    /**
     * How likely an unread plate is the destination: likely where the plates read around it are
     * numbered close to the target, unlikely where they are far off, in between where none have
     * been read yet. Hand-set, like every number here.
     */
    private fun targetChance(l: LandmarkView, context: PlannerContext): Float {
        val number = (context.snapshot.goal as? com.navassist.navcore.semantic.NavigationTarget.Room)?.number ?: return 0f
        val beliefs = context.beliefs ?: return UNREAD_CHANCE_UNKNOWN
        val corridor = beliefs.corridorNear(l.position) ?: return UNREAD_CHANCE_UNKNOWN
        val nearest = corridor.plates.minOfOrNull { kotlin.math.abs(it.number - number) } ?: return UNREAD_CHANCE_UNKNOWN
        return when {
            nearest <= NEAR_NUMBER -> UNREAD_CHANCE_NEAR
            nearest <= 2 * NEAR_NUMBER -> UNREAD_CHANCE_UNKNOWN
            else -> UNREAD_CHANCE_FAR
        }
    }

    /** Same length, exactly one character different: the shape of an OCR misread. */
    private fun oneOff(a: String, b: String): Boolean = a.length == b.length && a != b && a.zip(b).count { (x, y) -> x != y } == 1

    /** Bits an unread plate would resolve: its corridor's undecided numbering direction and side. */
    private fun plateBits(l: LandmarkView, context: PlannerContext): Float {
        val beliefs = context.beliefs ?: return UNKNOWN_CORRIDOR_BITS
        val corridor = beliefs.corridorNear(l.position) ?: return UNKNOWN_CORRIDOR_BITS
        val direction = corridor.directionPlus?.let { beliefs.hypothesis(it) }
            ?: return if (corridor.plates.size < 2) UNKNOWN_CORRIDOR_BITS else 0.5f
        var bits = if (direction.status == HypothesisStatus.CONFIRMED) 0f else beliefs.entropyBits(direction)
        corridor.oddLeft?.let { beliefs.hypothesis(it) }?.let { side ->
            if (side.status != HypothesisStatus.CONFIRMED) bits += 0.5f * beliefs.entropyBits(side)
        }
        return bits
    }

    companion object {
        /**
         * Two ways on that part by more than [DECISION_ANGLE_DEGREES], both within reach: a junction,
         * or the two ends of a corridor. Where every way on leads the same way, there is nothing to decide.
         */
        fun atDecisionPointOf(context: PlannerContext): Boolean {
            val here = context.snapshot.pose.position2D
            val ways = context.snapshot.frontiers.filter { it.distanceMeters in 1.5f..DECISION_REACH_METERS }.map { (it.centroid - here).normalized() }
            val cos = kotlin.math.cos(Math.toRadians(DECISION_ANGLE_DEGREES.toDouble())).toFloat()
            for (i in ways.indices) for (j in i + 1 until ways.size) if (ways[i].dot(ways[j]) < cos) return true
            return false
        }

        // Assumptions, not measurements.
        const val INSPECT_RADIUS_METERS = 10f
        const val W_PROGRESS = 1.5f
        /** Worth of re-reading a plate one character off the destination: it may be it. */
        const val W_MISREAD = 2.5f
        const val SIGN_BITS = 1.0f
        const val UNKNOWN_CORRIDOR_BITS = 1.0f
        const val NEAR_ESTIMATE_METERS = 4f
        const val DETOUR_FACTOR = 1.3f
        const val WALK_SPEED = 1.0f
        const val LOOK_SECONDS = 7f
        const val MAX_LIFT_RIDES = 3
        const val W_TARGET = 2.0f
        const val NEAR_NUMBER = 6
        const val UNREAD_CHANCE_NEAR = 0.5f
        const val UNREAD_CHANCE_UNKNOWN = 0.2f
        const val UNREAD_CHANCE_FAR = 0.02f
        const val DECISION_ANGLE_DEGREES = 100f
        const val DECISION_REACH_METERS = 15f
        /** What information is worth away from a decision point, as a fraction. */
        const val OFF_DECISION_RELEVANCE = 0.15f
    }
}

/** B4: [CandidatePlanner] with the hand-set information-gain scorer. */
class HypothesisPlanner : CandidatePlanner(HeuristicScorer)
