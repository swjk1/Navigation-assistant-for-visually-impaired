package com.navassist.sim.advice

import com.navassist.navcore.semantic.AdviceDirection
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.Situation
import com.navassist.sim.sensor.BodyPose
import com.navassist.sim.Simulation
import com.navassist.sim.bench.Benchmark
import com.navassist.sim.bench.GroundTruth
import com.navassist.sim.foxglove.arr
import com.navassist.sim.foxglove.json
import com.navassist.sim.foxglove.obj
import com.navassist.sim.world.Scene
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

/**
 * Records the questions an adviser would be asked, each labelled with the truth: the labelled
 * situation set that prompts and models are scored on without walking a single trip.
 *
 * At every consultation it writes one JSON line: the exact prompt [LlmAdviser] would send, the
 * openings in the order the prompt lists them, and for each opening the true walking distance from
 * it to the destination. The best opening is the one that leaves the shortest walk. It then passes
 * the question on to [inner] (the oracle, say) or gives no advice, so the trajectory is that
 * design's own: record on the runs of the adviser being improved, not only on the engine alone.
 *
 * Writes nothing, and costs nothing, beyond the file: no model is called.
 */
class SituationRecorder(
    private val scene: Scene,
    private val target: NavigationTarget,
    private val start: BodyPose,
    private val seed: Long,
    private val inner: Adviser? = null,
) : Adviser {
    private val goals = Benchmark.goals(scene, target)
    private val truth = truths.getOrPut(scene.name) { GroundTruth(scene) }
    private var lastKey: String? = null

    override fun advise(situation: Situation, sim: Simulation): List<AdviceDirection>? {
        record(situation, sim)
        return inner?.advise(situation, sim)
    }

    private fun record(situation: Situation, sim: Simulation) {
        val options = situation.frontiers.take(LlmAdviser.MAX_OPTIONS)
        if (options.size < 2) return
        val signs = AdvicePrompt.merge(situation.signs)
        // The same question as last time is not a new example: the LLM adviser would not ask it.
        val key = AdvicePrompt.questionKey(options.map { it.centroid }, signs)
        if (key == lastKey) return
        lastKey = key
        val body = sim.user.body
        val fromHere = truth.shortestPath(body.x, body.y, goals, Benchmark.SUCCESS_RADIUS) ?: return
        val remaining = options.map { f ->
            val at = sim.canonicalToPlan(f.centroid) ?: return
            val walk = truth.shortestPath(at.first, at.second, goals, Benchmark.SUCCESS_RADIUS)
            // Walk to the opening, then on from it: what choosing it costs in all.
            walk?.let { it + hypot(at.first - body.x, at.second - body.y) }
        }
        val best = remaining.withIndex().filter { it.value != null }.minByOrNull { it.value!! }?.index ?: return
        val line = json {
            "scene" to scene.name
            "target" to target.description
            "seed" to seed
            "start" to arr { add(start.x); add(start.y); add(start.heading) }
            "seconds" to situation.elapsedMillis / 1000.0
            "walked" to situation.walkedMeters
            "at" to arr { add(body.x); add(body.y) }
            "remainingFromHere" to fromHere
            "prompt" to AdvicePrompt.build(situation, options.map { it.centroid }, signs)
            "options" to arr {
                for ((i, f) in options.withIndex()) add(obj {
                    "label" to ('A' + i).toString()
                    "distance" to f.centroid.distanceTo(situation.pose.position2D)
                    "engineScore" to f.score
                    remaining[i]?.let { "trueRemaining" to it }
                })
            }
            "best" to ('A' + best).toString()
        }
        sink(line)
    }

    companion object {
        private val truths = ConcurrentHashMap<String, GroundTruth>()

        /** Where lines go: the file named by SITUATIONS_OUT, appended to; nowhere if unset. */
        private val file: File? = System.getenv("SITUATIONS_OUT")?.let { File(it) }

        @Synchronized
        private fun sink(line: String) {
            file?.appendText(line.replace("\n", " ") + "\n")
        }
    }
}
