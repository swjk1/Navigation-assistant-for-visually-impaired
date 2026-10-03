package com.navassist.sim.advice

import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.AdviceDirection
import com.navassist.navcore.semantic.Situation
import com.navassist.sim.Simulation
import com.navassist.sim.bench.Benchmark
import com.navassist.sim.bench.GroundTruth
import com.navassist.sim.world.Scene
import java.util.concurrent.ConcurrentHashMap
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
class OracleAdviser(scene: Scene, target: com.navassist.navcore.semantic.NavigationTarget) : Adviser {
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
        return listOf(AdviceDirection(situation.pose.position2D, direction.normalizedOrSelf(), 1f))
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
