package com.navassist.sim.advice

import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.AdviceDirection
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.Situation
import com.navassist.sim.Simulation
import com.navassist.sim.bench.Benchmark
import com.navassist.sim.world.Scene
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The mechanism of Follow the Signs (Cao, Kumar and Curtis, 2026, arXiv 2601.06652), rebuilt so it
 * can be compared with this engine's own advice channel at a matched accuracy.
 *
 * Their language model answers, every step, one of four regions relative to the agent along the
 * map's axes: left, right, up or down. Each answer adds 1 to every cell of a confidence grid on
 * that side of the agent; the grid decays by [decay] per answer, and cells the agent has searched
 * are set back to 0. The agent's subgoal is the centroid of the highest-confidence region; when
 * that is where the agent already stands, it falls back to frontier exploration.
 *
 * Here the language model is replaced by a stand-in of known [accuracy]: right, it names the side
 * the nearest true destination lies on; wrong, one of the other three at random. The subgoal is
 * handed to the engine as advice towards it - with a dominant advice weight, the subgoal decides
 * the frontier, as in their method; with the usual weight, it is only fused with the rest.
 */
class FollowTheSignsAdviser(
    private val scene: Scene,
    target: NavigationTarget,
    private val accuracy: Float,
    seed: Long,
    private val decay: Float = 0.9f,
    private val cellMeters: Float = 1f,
    private val searchedRadiusMeters: Float = 2f,
) : Adviser {
    private val goals = Benchmark.goals(scene, target)
    private val random = kotlin.random.Random(seed * 3571 + 11)

    // The grid over the floor plan (x, y), y = -world z.
    private val bounds = scene.bounds()
    private val x0 = bounds[0]
    private val y0 = -bounds[3]
    private val columns = ((bounds[2] - bounds[0]) / cellMeters).toInt() + 1
    private val rows = ((bounds[3] - bounds[1]) / cellMeters).toInt() + 1
    private val confidence = FloatArray(columns * rows)
    private val onFloor = BooleanArray(columns * rows) { i -> scene.hasFloorAt(cellX(i), -cellY(i)) }
    private val searched = BooleanArray(columns * rows)

    private fun cellX(i: Int) = x0 + (i % columns + 0.5f) * cellMeters
    private fun cellY(i: Int) = y0 + (i / columns + 0.5f) * cellMeters

    override fun advise(situation: Situation, sim: Simulation): List<AdviceDirection>? {
        val body = sim.user.body
        val goal = goals.minByOrNull { hypot(it.first - body.x, it.second - body.y) } ?: return null

        // The answer: which side of the agent, along the plan's axes.
        val dx = goal.first - body.x
        val dy = goal.second - body.y
        val right = if (abs(dx) >= abs(dy)) (if (dx >= 0f) 0 else 1) else (if (dy >= 0f) 2 else 3)
        val answer = if (random.nextFloat() < accuracy) right else (listOf(0, 1, 2, 3) - right).random(random)

        for (i in confidence.indices) {
            confidence[i] *= decay
            if (!onFloor[i]) continue
            val cx = cellX(i)
            val cy = cellY(i)
            val onSide = when (answer) {
                0 -> cx > body.x
                1 -> cx < body.x
                2 -> cy > body.y
                else -> cy < body.y
            }
            if (onSide) confidence[i] += 1f
            if (hypot(cx - body.x, cy - body.y) <= searchedRadiusMeters) searched[i] = true
            if (searched[i]) confidence[i] = 0f
        }

        val best = confidence.max()
        if (best <= 0f) return null
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in confidence.indices) {
            if (confidence[i] >= HIGH_REGION * best) { sx += cellX(i); sy += cellY(i); n++ }
        }
        val cx = sx / n
        val cy = sy / n
        val length = hypot(cx - body.x, cy - body.y)
        // The subgoal is where the agent stands: no guidance, plain frontier exploration.
        if (length < 1f) return null
        val direction = sim.planDirectionToCanonical((cx - body.x) / length, (cy - body.y) / length) ?: return null
        return listOf(AdviceDirection(situation.pose.position2D, direction, 1f))
    }

    private companion object {
        /** Cells within this share of the best form the highest-confidence region. */
        const val HIGH_REGION = 0.9f
    }
}
