package com.navassist.sim.advice

import com.navassist.navcore.belief.HypothesisStatus
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.strategy.Candidate
import com.navassist.navcore.strategy.CandidateScorer
import com.navassist.navcore.strategy.PlannerContext
import com.navassist.navcore.strategy.Scores
import com.navassist.navcore.world.LandmarkKind
import com.navassist.sim.foxglove.MiniJson
import kotlin.math.roundToInt

/**
 * The planner's state as text for a model: what was asked, what has been read and where, what the
 * hypotheses are and how they stand, and the candidate actions. Perceived state only - never the
 * building or the route.
 */
object SnapshotText {

    fun describe(context: PlannerContext, candidates: List<Candidate>, withBeliefs: Boolean): String = buildString {
        val s = context.snapshot
        val here = s.pose.position2D
        appendLine("Task: find ${s.goal.description} in an unfamiliar building, with no map.")
        appendLine("So far: walked %.0f m in %.0f s.".format(s.walkedMeters, s.elapsedMillis / 1000.0))
        val plates = s.landmarks.filter { it.kind == LandmarkKind.PLATE && it.label != null }
        if (plates.isNotEmpty()) {
            appendLine("Room plates read (label, distance, direction as a clock face, 12 = straight ahead):")
            for (p in plates.take(20)) appendLine("  ${p.label}: ${where(here, s.pose.yawRadians, p.position)}")
        } else {
            appendLine("No room plates read yet.")
        }
        val signs = s.landmarks.filter { it.kind == LandmarkKind.SIGN && it.label != null }
        for (sign in signs.take(10)) appendLine("Sign '${sign.label}': ${where(here, s.pose.yawRadians, sign.position)}")
        if (withBeliefs) {
            val beliefs = context.beliefs?.hypotheses.orEmpty().filter { it.status != HypothesisStatus.RETRACTED }
            if (beliefs.isNotEmpty()) {
                appendLine("Hypotheses (status, readings for/against):")
                for (h in beliefs) appendLine("  ${h.proposition}: ${h.status.name.lowercase()}, ${h.support.size}/${h.against.size}")
            }
            context.numberingTarget?.let { appendLine("The numbering so far puts the destination ${where(here, s.pose.yawRadians, it)}.") }
        }
        appendLine("Possible next actions:")
        for (c in candidates) appendLine("  ${c.id}: ${c.description} (about ${c.seconds.roundToInt()} s)")
        appendLine("  explore: keep exploring unexplored openings instead")
    }

    private fun where(here: Vec2, yaw: Float, p: Vec2): String {
        val d = p - here
        val bearing = GeometryUtils.angleDifference(yaw, d.yawRadians())
        val clock = ((GeometryUtils.radiansToDegrees(bearing) / 30f).roundToInt() + 12) % 12
        return "%.0f m at %d o'clock".format(d.length(), if (clock == 0) 12 else clock)
    }
}

/**
 * B5: an off-the-shelf decision model scores the candidates - OpenAI's Decisions API, one choice
 * question over the candidates and "keep exploring". Its probabilities are the utilities; they are
 * logged next to outcomes, to be calibrated before they are ever read as likelihoods.
 */
class DecisionsScorer(private val client: OpenAiClient) : CandidateScorer {
    override fun score(context: PlannerContext, candidates: List<Candidate>): Scores? {
        val input = SnapshotText.describe(context, candidates, withBeliefs = true)
        val choices = candidates.map { it.id to it.description } + ("explore" to "keep exploring unexplored openings instead")
        val p = client.choose(
            input, "next_action",
            "Which next action makes it most likely to reach ${context.snapshot.goal.description} soonest?",
            choices,
        ) ?: return null
        return Scores(candidates.map { p[it.id] ?: 0f }, p["explore"] ?: 0f, note = "decisions")
    }
}

/**
 * B2 and B3: a generative model chooses one candidate. B3 sees the hypotheses; B2 sees only what
 * was read, the candidates and the task - the same perception, no belief graph.
 */
class ChooserScorer(private val client: OpenAiClient, private val withBeliefs: Boolean) : CandidateScorer {
    override fun score(context: PlannerContext, candidates: List<Candidate>): Scores? {
        val ids = candidates.map { it.id } + "explore"
        val prompt = SnapshotText.describe(context, candidates, withBeliefs) +
            "\nChoose the one next action most likely to reach ${context.snapshot.goal.description} soonest. " +
            "Answer with its id and a reason of at most 15 words."
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "choice" to mapOf("type" to "string", "enum" to ids),
                "reason" to mapOf("type" to "string"),
            ),
            "required" to listOf("choice", "reason"),
            "additionalProperties" to false,
        )
        val reply = client.respond(prompt, "next_action", schema) ?: return null
        @Suppress("UNCHECKED_CAST")
        val choice = runCatching { (MiniJson.parse(reply) as Map<String, Any?>)["choice"] as String }.getOrNull() ?: return null
        val explore = choice == "explore"
        return Scores(candidates.map { if (it.id == choice) 1f else 0f }, if (explore) 2f else 0.5f, note = "chooser")
    }
}
