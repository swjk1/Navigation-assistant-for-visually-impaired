package com.navassist.sim.advice

import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.AdviceDirection
import com.navassist.navcore.semantic.Situation
import com.navassist.sim.Simulation
import com.navassist.sim.bench.Benchmark
import com.navassist.sim.bench.GroundTruth
import com.navassist.sim.world.Scene
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Something outside the engine asked which way to explore: a language model, a decision model,
 * a floor plan. It sees what the engine perceived ([Situation]); only the oracle also looks at
 * the building. One instance per run.
 */
interface Adviser {
    /** Advice for now, or null to stay silent. Called on the simulation thread. */
    fun advise(situation: Situation, sim: Simulation): List<AdviceDirection>?
}

/**
 * Knows the building: advises the first stretch of the true shortest walk to the destination.
 * Not a model anyone could deploy - it is the CEILING on what any adviser (LLM, Jev, a world
 * model) could add through the advice channel, measured on the same episodes. If the oracle
 * barely helps, no adviser will.
 */
class OracleAdviser(
    scene: Scene,
    target: com.navassist.navcore.semantic.NavigationTarget,
    /** How much each answer counts, as a real adviser's confidence would: 1 is certain. */
    private val weight: Float = 1f,
) : Adviser {
    private val goals = Benchmark.goals(scene, target)
    private val truth = truths.getOrPut(scene.name) { GroundTruth(scene) }

    override fun advise(situation: Situation, sim: Simulation): List<AdviceDirection>? {
        val body = sim.user.body
        val route = truth.route(body.x, body.y, goals, Benchmark.SUCCESS_RADIUS) ?: return null
        // Aim at the point LOOKAHEAD metres along the walk, or the end if it is nearer.
        var walked = 0f
        var aim = route.last()
        for (k in 1 until route.size) {
            walked += hypot(route[k].first - route[k - 1].first, route[k].second - route[k - 1].second)
            if (walked >= LOOKAHEAD_METERS) { aim = route[k]; break }
        }
        val dx = aim.first - body.x
        val dy = aim.second - body.y
        val length = hypot(dx, dy)
        if (length < 0.5f) return null
        val direction = sim.planDirectionToCanonical(dx / length, dy / length) ?: return null
        return listOf(AdviceDirection(situation.pose.position2D, direction.normalizedOrSelf(), weight))
    }

    private fun Vec2.normalizedOrSelf(): Vec2 {
        val l = hypot(x, z)
        return if (l < 1e-6f) this else Vec2(x / l, z / l)
    }

    companion object {
        private const val LOOKAHEAD_METERS = 5f
        private val truths = ConcurrentHashMap<String, GroundTruth>()
    }
}

/**
 * The oracle with a stated accuracy and confidence: a stand-in for any real adviser, for free.
 *
 * Each answer is right with probability [accuracy] and then points along the true route, as the
 * oracle does. Otherwise it points at one of the engine's own openings that the true route does
 * not lead through (more than [WRONG_MIN_DEGREES] off it), or in a random such direction when
 * there is none: a plausible mistake, not a useless one. Every answer carries [weight], which
 * plays the part of the adviser's confidence. Sweeping both says how good an LLM adviser has to
 * be to help, and how much its answers should count once its accuracy is measured.
 */
class NoisyOracleAdviser(
    scene: Scene,
    target: com.navassist.navcore.semantic.NavigationTarget,
    private val accuracy: Float,
    private val weight: Float,
    seed: Long,
) : Adviser {
    private val oracle = OracleAdviser(scene, target, weight)
    private val random = kotlin.random.Random(seed * 7919 + 17)

    override fun advise(situation: Situation, sim: Simulation): List<AdviceDirection>? {
        val right = oracle.advise(situation, sim) ?: return null
        if (random.nextFloat() < accuracy) return right
        val truth = right.first().direction.yawRadians()
        val minOff = GeometryUtils.degreesToRadians(WRONG_MIN_DEGREES)
        val origin = situation.pose.position2D
        val wrong = situation.frontiers
            .map { it.centroid - origin }
            .filter { it.lengthSquared() > 1e-4f && abs(GeometryUtils.angleDifference(it.yawRadians(), truth)) > minOff }
        val yaw = if (wrong.isNotEmpty()) {
            wrong[random.nextInt(wrong.size)].yawRadians()
        } else {
            truth + minOff + random.nextFloat() * (2f * PI.toFloat() - 2f * minOff)
        }
        return listOf(AdviceDirection(origin, Vec2.fromYaw(yaw), weight))
    }

    companion object {
        private const val WRONG_MIN_DEGREES = 60f
    }
}

/**
 * Any adviser behind a connection that drops out: in a lift, a stairwell, a dead spot. Between
 * outages it answers as [inner] does; during one it says nothing, and the engine keeps whatever
 * advice it last had until that expires. Spells online last [upSeconds], outages [downSeconds],
 * each drawn at random.
 */
class OutageAdviser(
    private val inner: Adviser,
    seed: Long,
    private val upSeconds: ClosedFloatingPointRange<Double> = 60.0..120.0,
    private val downSeconds: ClosedFloatingPointRange<Double> = 30.0..60.0,
) : Adviser {
    private val random = kotlin.random.Random(seed * 104729 + 3)
    private var online = true
    private var spellEnds = draw(upSeconds)

    override fun advise(situation: Situation, sim: Simulation): List<AdviceDirection>? {
        val now = situation.elapsedMillis / 1000.0
        while (now >= spellEnds) {
            online = !online
            spellEnds += draw(if (online) upSeconds else downSeconds)
        }
        return if (online) inner.advise(situation, sim) else null
    }

    private fun draw(range: ClosedFloatingPointRange<Double>) =
        range.start + random.nextDouble() * (range.endInclusive - range.start)
}
