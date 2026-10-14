package com.navassist.navcore.belief

import com.navassist.navcore.geometry.Vec2

enum class HypothesisStatus { PROPOSED, SUPPORTED, CONTRADICTED, CONFIRMED, RETRACTED }

/** What a hypothesis is about; members of one family compete. */
enum class HypothesisFamily {
    /** Room numbers rise along a direction in one corridor. */
    NUMBERING_DIRECTION,

    /** Odd numbers are on one wall of a corridor. */
    PARITY_SIDE,

    /** What a plate's leading digit encodes: the floor, or the wing. */
    LEADING_DIGIT,

    /** Following a range sign's arrow leads to rooms in its range. */
    RANGE_SIGN,

    /** A particular door is the destination. */
    DESTINATION,
}

/**
 * A claim the agent holds about the building, with the predictions that test it.
 *
 * Status is set ONLY by the deterministic verifier ([HypothesisStore]) from ledger evidence:
 * [support] predictions met, [against] predictions failed, each citing the event that tested it.
 * A model may propose a hypothesis or another interpretation, never its status. Probabilities are
 * kept only for closed sets (two directions, two sides, floor vs wing) via [belief].
 */
class Hypothesis(
    val id: String,
    val family: HypothesisFamily,
    /** Human-readable claim: "numbers rise towards (0.0, 1.0) in corridor c1". */
    val proposition: String,
    /** Where it applies: a corridor cluster, a sign, the floor. */
    val scope: String,
    /** The hypothesis it competes with in its family and scope, if one. */
    rivalId: String? = null,
    /** Hypotheses it depends on: retracting one of them reconsiders this one. */
    val dependsOn: List<String> = emptyList(),
    /** A direction it is about, when it has one (numbering, a sign's arrow). */
    val direction: Vec2? = null,
    val createdAtMillis: Long,
) {
    var rivalId: String? = rivalId
        internal set
    var status: HypothesisStatus = HypothesisStatus.PROPOSED
        internal set
    val support = ArrayList<String>()
    val against = ArrayList<String>()
    val history = ArrayList<Pair<Long, String>>()

    /** Laplace estimate that this member of a two-way family is the true one, 0..1. */
    val belief: Float get() = (support.size + 1f) / (support.size + against.size + 2f)

    val tests: Int get() = support.size + against.size

    internal fun record(event: String, met: Boolean) {
        if (event in support || event in against) return
        if (met) support += event else against += event
    }

    internal fun setStatus(next: HypothesisStatus, nowMillis: Long, why: String) {
        if (next == status) return
        history += nowMillis to "$status -> $next: $why"
        status = next
    }
}
