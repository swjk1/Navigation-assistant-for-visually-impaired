package com.navassist.sim.advice

import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.PlannerHypothesis
import com.navassist.navcore.semantic.Situation
import com.navassist.sim.Simulation
import com.navassist.sim.bench.Benchmark
import com.navassist.sim.bench.GroundTruth
import com.navassist.sim.world.Scene
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

/**
 * The long-term tier: asked rarely, it says where it believes the destination is, as places
 * rather than directions (see [PlannerHypothesis]). A language model reasoning over the whole
 * trip, eventually; for now oracle and noisy stand-ins that cost nothing. One instance per run.
 */
interface Planner {
    /** The current belief, or null to stay silent. Called on the simulation thread. */
    fun plan(situation: Situation, sim: Simulation): List<PlannerHypothesis>?
}

/**
 * Knows where the destination is: the end of the true shortest walk. The ceiling for any planner.
 */
class OraclePlanner(
    scene: Scene,
    target: NavigationTarget,
    private val confidence: Float = 1f,
    private val radiusMeters: Float = 3f,
) : Planner {
    private val goals = Benchmark.goals(scene, target)
    private val truth = truths.getOrPut(scene.name) { GroundTruth(scene) }

    /** The destination nearest by walking from where the body is now, in plan coordinates. */
    fun nearestGoal(sim: Simulation): Pair<Float, Float>? =
        truth.route(sim.user.body.x, sim.user.body.y, goals, Benchmark.SUCCESS_RADIUS)?.last()

    override fun plan(situation: Situation, sim: Simulation): List<PlannerHypothesis>? {
        val (x, y) = nearestGoal(sim) ?: return null
        val position = sim.planToCanonical(x, y) ?: return null
        return listOf(PlannerHypothesis(position, radiusMeters, confidence))
    }

    companion object {
        internal val truths = ConcurrentHashMap<String, GroundTruth>()
    }
}

/**
 * A planner with a stated accuracy: each answer names the true destination with probability
 * [accuracy], and otherwise its one wrong belief for this run - the spot in front of another
 * sign in the building, at least [DECOY_MIN_METERS] from any true destination and reachable. A
 * real planner's mistakes persist (it misreads the numbering once, and keeps reasoning from it),
 * so the wrong answer is the same wrong place each time, not a fresh random one.
 */
class NoisyPlanner(
    private val scene: Scene,
    target: NavigationTarget,
    private val accuracy: Float,
    private val confidence: Float,
    seed: Long,
) : Planner {
    private val oracle = OraclePlanner(scene, target, confidence)
    private val goals = Benchmark.goals(scene, target)
    private val random = kotlin.random.Random(seed * 6151 + 29)
    private var decoy: Pair<Float, Float>? = null
    private var decoyChosen = false

    override fun plan(situation: Situation, sim: Simulation): List<PlannerHypothesis>? {
        if (random.nextFloat() < accuracy) return oracle.plan(situation, sim)
        val (x, y) = decoy(sim) ?: return oracle.plan(situation, sim)
        val position = sim.planToCanonical(x, y) ?: return null
        return listOf(PlannerHypothesis(position, 3f, confidence))
    }

    private fun decoy(sim: Simulation): Pair<Float, Float>? {
        if (decoyChosen) return decoy
        decoyChosen = true
        val truth = OraclePlanner.truths.getOrPut(scene.name) { GroundTruth(scene) }
        val candidates = scene.signs
            .filter { !it.hanging && it.y > 0f && it.y < scene.ceilingHeight }
            .map { (it.x + it.normalX * STANDING_OFF) to (-it.z - it.normalZ * STANDING_OFF) }
            .filter { (x, y) -> goals.all { (gx, gy) -> hypot(x - gx, y - gy) >= DECOY_MIN_METERS } }
            .shuffled(random)
        val body = sim.user.body
        decoy = candidates.take(MAX_TRIES).firstOrNull { truth.shortestPath(body.x, body.y, listOf(it), 1f) != null }
        return decoy
    }

    private companion object {
        const val DECOY_MIN_METERS = 15f
        const val STANDING_OFF = 0.6f
        const val MAX_TRIES = 8
    }
}
