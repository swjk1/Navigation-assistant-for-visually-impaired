package com.navassist.sim.advice

import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.AdviceDirection
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SeenSign
import com.navassist.navcore.semantic.SemanticDirection
import com.navassist.navcore.semantic.Situation
import com.navassist.sim.Simulation
import com.navassist.sim.foxglove.MiniJson
import kotlin.math.roundToInt

/**
 * Asks a language model which unexplored opening most likely leads to the destination, given only
 * what the phone has perceived: signs read, landmarks detected, and the engine's own candidate
 * frontiers described by clock direction, distance and width. The answer - a probability per
 * opening - becomes advice that biases frontier choice. The model never sees the building.
 *
 * Simulator only so far: the app has no adviser yet. When it gets one, it should send this same
 * prompt, so the benchmark measures what ships.
 */
class LlmAdviser(private val client: LlmClient?, private val onQuestion: () -> Unit = {}) : Adviser {

    /** Every exchange of this run, for the report: prompt size, latency, and what came back. */
    val exchanges = ArrayList<LlmClient.Reply>()

    /** What the last question was about, and the advice it produced. */
    private var lastKey: String? = null
    private var lastAdvice: List<AdviceDirection>? = null

    /**
     * Asks only when the question has changed: a new set of openings, or a sign not seen before.
     * Otherwise the last answer still stands - asking again would cost a call (and on a phone,
     * battery and a network round trip) to hear the same thing.
     */
    override fun advise(situation: Situation, sim: Simulation): List<AdviceDirection>? {
        val options = situation.frontiers.take(MAX_OPTIONS)
        if (options.size < 2) return null
        val signs = AdvicePrompt.merge(situation.signs)
        val key = AdvicePrompt.questionKey(options.map { it.centroid }, signs)
        if (key == lastKey) return lastAdvice
        lastKey = key
        onQuestion()
        if (client == null) return null.also { lastAdvice = null }
        val prompt = AdvicePrompt.build(situation, options.map { it.centroid }, signs)
        val reply = client.complete(prompt, AdvicePrompt.schema(options.size)) ?: return null.also { lastAdvice = null }
        exchanges += reply
        val probabilities = AdvicePrompt.parse(reply.text, options.size) ?: return null.also { lastAdvice = null }
        val origin = situation.pose.position2D
        val n = options.size
        lastAdvice = options.mapIndexedNotNull { i, frontier ->
            val toward = frontier.centroid - origin
            if (toward.lengthSquared() < 1e-4f) return@mapIndexedNotNull null
            AdviceDirection(origin, toward.normalized(), AdvicePrompt.weight(probabilities[i], n))
        }
        return lastAdvice
    }

    companion object {
        const val MAX_OPTIONS = 8
    }
}

/** The prompt, and reading the answer. Pure functions so the app can mirror them exactly. */
object AdvicePrompt {

    /** A sign as the prompt lists it: repeated sightings of the same thing folded into one. */
    data class MergedSign(val sign: SeenSign, val times: Int)

    /**
     * Folds repeated sightings of one sign together, keeping the most recent. Perception reports
     * the same lift door every cycle it is in view; listed six times it crowds out the rest and
     * reads as six lifts. Same kind and text, and either placed within [SAME_PLACE_METERS] or
     * seen from nearby along a similar bearing, count as the same sign.
     */
    fun merge(signs: List<SeenSign>): List<MergedSign> {
        val merged = ArrayList<MergedSign>()
        for (sign in signs) {
            val i = merged.indexOfFirst { sameSign(it.sign, sign) }
            if (i < 0) merged += MergedSign(sign, 1) else merged[i] = merged[i].copy(times = merged[i].times + 1)
        }
        return merged
    }

    private fun sameSign(a: SeenSign, b: SeenSign): Boolean {
        if (a.kind != b.kind || a.label != b.label || a.pointing != b.pointing) return false
        val pa = a.position
        val pb = b.position
        if (pa != null && pb != null) return pa.distanceTo(pb) <= SAME_PLACE_METERS
        val ba = a.bearing ?: pa?.let { it - a.observerPosition }
        val bb = b.bearing ?: pb?.let { it - b.observerPosition }
        if (ba == null || bb == null) return a.observerPosition.distanceTo(b.observerPosition) <= SAME_PLACE_METERS
        val apart = kotlin.math.abs(GeometryUtils.angleDifference(ba.yawRadians(), bb.yawRadians()))
        return a.observerPosition.distanceTo(b.observerPosition) <= SAME_PLACE_METERS * 2 &&
            apart <= GeometryUtils.degreesToRadians(SAME_BEARING_DEGREES)
    }

    /** Identifies the question: openings to the nearest 2 m, and which signs are known. */
    fun questionKey(options: List<Vec2>, signs: List<MergedSign>): String =
        options.map { "${(it.x / 2f).roundToInt()},${(it.z / 2f).roundToInt()}" }.sorted().joinToString(";") +
            "|" + signs.map { "${it.sign.kind}:${it.sign.label}:${it.sign.pointing}" }.sorted().joinToString(";")

    fun build(situation: Situation, options: List<Vec2>, signs: List<MergedSign> = merge(situation.signs)): String = buildString {
        val pose = situation.pose
        val here = pose.position2D
        appendLine("You are guiding a blind person to ${describe(situation.target)} in a building they do not know.")
        appendLine("You cannot see. You get only what their phone has detected. Directions are clock positions")
        appendLine("relative to where they face now: 12 is straight ahead, 3 is to their right, 9 to their left, 6 behind.")
        appendLine()
        appendLine("So far they have walked ${situation.walkedMeters.roundToInt()} m in ${situation.elapsedMillis / 1000} s.")
        appendLine()
        appendLine("Signs and landmarks detected, most recent first:")
        if (signs.isEmpty()) appendLine("- none yet")
        for ((sign, times) in signs.take(MAX_SIGNS)) {
            val what = when (sign.kind) {
                "ROOM" -> "a sign reading \"${sign.label}\""
                "ROOM_RANGE" -> "a direction sign \"Rooms ${sign.label}\"" + (sign.pointing?.let { " pointing ${pointing(it)}" } ?: "")
                "EXIT" -> "an exit sign" + (sign.pointing?.takeIf { it != SemanticDirection.FORWARD }?.let { " pointing ${pointing(it)}" } ?: "")
                "DOOR" -> "a door"
                "STAIRS" -> "a stairs sign"
                "ELEVATOR" -> "a lift door"
                else -> sign.kind.lowercase()
            }
            val where = sign.position?.let { at ->
                val d = at.distanceTo(here)
                "${clock(at - here, pose.yawRadians)} o'clock, ${d.roundToInt()} m away"
            } ?: sign.bearing?.let { b -> "towards ${clock(b, pose.yawRadians)} o'clock, too far to judge the distance" }
            ?: "direction unknown"
            val seen = if (times > 1) "seen $times times, last" else "seen"
            appendLine("- $what, $where ($seen ${sign.ageMillis / 1000} s ago)")
        }
        appendLine()
        appendLine("Unexplored openings they could walk to next:")
        for ((i, at) in options.withIndex()) {
            val d = at.distanceTo(here)
            appendLine("${label(i)}: ${clock(at - here, pose.yawRadians)} o'clock, ${"%.1f".format(d)} m")
        }
        appendLine()
        appendLine("Which opening most likely leads towards ${describe(situation.target)}? Use how buildings are laid out:")
        appendLine("corridors and concourses connect places; rooms, classrooms and shops are dead ends unless the")
        appendLine("destination is inside; room numbers run in order along a corridor; direction signs point the way;")
        appendLine("lifts and washrooms are often off side corridors; exits are at the ends of main corridors and")
        appendLine("concourses. Prefer continuing along a corridor or concourse over entering a room or shop.")
        appendLine("Reply with JSON only, probabilities summing to 1:")
        append("{\"probabilities\": {")
        append(options.indices.joinToString(", ") { "\"${label(it)}\": 0.0" })
        append("}, \"reason\": \"at most 15 words\"}")
    }

    /** The reply's shape as a JSON schema: one probability per opening, and a short reason. */
    fun schema(options: Int): Map<String, Any?> {
        val labels = (0 until options).map { label(it) }
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "probabilities" to mapOf(
                    "type" to "object",
                    "properties" to labels.associateWith { mapOf("type" to "number") },
                    "required" to labels,
                    "additionalProperties" to false,
                ),
                "reason" to mapOf("type" to "string"),
            ),
            "required" to listOf("probabilities", "reason"),
            "additionalProperties" to false,
        )
    }

    /** Probabilities per option in order, or null when the reply is unusable. */
    @Suppress("UNCHECKED_CAST")
    fun parse(text: String, options: Int): FloatArray? {
        val json = text.substringAfter('{', "").let { if (it.isEmpty()) return null else "{$it" }.substringBeforeLast('}') + "}"
        val root = runCatching { MiniJson.parse(json) as? Map<String, Any?> }.getOrNull() ?: return null
        val probabilities = root["probabilities"] as? Map<String, Any?> ?: return null
        val values = FloatArray(options) { (probabilities[label(it)] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0f }
        val sum = values.sum()
        if (sum <= 0f) return null
        for (i in values.indices) values[i] /= sum
        return values
    }

    /**
     * Probability to advice weight: 0 at an even share, up to +1 for certainty, down to -0.5 for
     * an option judged hopeless. Avoidance is capped lower than preference: a model's "no" should
     * not bury an option the map still finds promising.
     */
    fun weight(p: Float, options: Int): Float {
        val even = 1f / options
        return if (p >= even) (p - even) / (1f - even) else -0.5f * (even - p) / even
    }

    private fun label(i: Int) = ('A' + i).toString()

    private fun clock(v: Vec2, yaw: Float): Int {
        val relative = GeometryUtils.radiansToDegrees(GeometryUtils.angleDifference(yaw, v.yawRadians()))
        val hour = ((relative / 30f).roundToInt() % 12 + 12) % 12
        return if (hour == 0) 12 else hour
    }

    private fun pointing(direction: SemanticDirection) = when (direction) {
        SemanticDirection.LEFT -> "left"
        SemanticDirection.RIGHT -> "right"
        SemanticDirection.FORWARD -> "ahead"
    }

    private fun describe(target: NavigationTarget) = when (target) {
        is NavigationTarget.Room -> if (target.number != null) "room ${target.label}" else "\"${target.label}\""
        NavigationTarget.Exit -> "an exit"
        NavigationTarget.Door -> "a door"
        NavigationTarget.Stairs -> "the stairs"
        NavigationTarget.Elevator -> "a lift (elevator)"
        NavigationTarget.Explore -> "somewhere new"
    }

    private const val MAX_SIGNS = 12
    private const val SAME_PLACE_METERS = 2.5f
    private const val SAME_BEARING_DEGREES = 25f
}
