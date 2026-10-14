package com.navassist.navcore.strategy

/**
 * P, first version: a linear model of each option's cost-to-go relative to the others at the same
 * decision, learned from forked simulator runs (navigation-sim bench/ForkData.kt). Utility is the
 * negated predicted relative cost, so the option predicted to save the most time wins - exploring
 * included, scored from its own features.
 *
 * Features are standardised with the training set's means and spreads before the weights apply;
 * all three come with the model. The model only ever sees [CandidateFeatures] - what the agent knew
 * at the decision - never the simulator's truth, which only labelled the training data.
 */
class LearnedScorer(
    private val featureNames: List<String>,
    private val mean: FloatArray,
    private val scale: FloatArray,
    private val weights: FloatArray,
) : CandidateScorer {

    init {
        require(featureNames == CandidateFeatures.names) {
            "model trained on features $featureNames, but the engine computes ${CandidateFeatures.names}"
        }
        require(mean.size == featureNames.size && scale.size == featureNames.size && weights.size == featureNames.size)
    }

    /** Predicted cost-to-go relative to the decision's average, in seconds: lower is better. */
    fun predict(x: FloatArray): Float {
        var y = 0f
        for (i in x.indices) {
            val z = if (scale[i] > 0f) (x[i] - mean[i]) / scale[i] else x[i]
            y += weights[i] * z
        }
        return y
    }

    override fun score(context: PlannerContext, candidates: List<Candidate>): Scores {
        val atPoint = CandidatePlanner.atDecisionPointOf(context)
        val utilities = candidates.map { -predict(CandidateFeatures.of(context, it, atPoint)) }
        val explore = -predict(CandidateFeatures.of(context, null, atPoint))
        return Scores(utilities, explore, note = "learned")
    }
}
