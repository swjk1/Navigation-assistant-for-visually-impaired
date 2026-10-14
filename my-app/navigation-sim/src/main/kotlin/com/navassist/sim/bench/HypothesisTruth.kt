package com.navassist.sim.bench

import com.navassist.navcore.belief.Hypothesis
import com.navassist.navcore.belief.HypothesisFamily
import com.navassist.sim.Simulation
import com.navassist.sim.world.NumberingFamily

/**
 * Judges a hypothesis the agent confirmed against how the floor really numbers its rooms. The
 * evaluator's view only: it maps the agent's engine-frame claims onto the floor plan, which the
 * agent never sees.
 *
 * Returns null for claims it cannot judge: range signs (their truth depends on the sign), corridors
 * numbered as one run up one wall and back (no single direction or side to be right about).
 */
object HypothesisTruth {

    fun judge(sim: Simulation, h: Hypothesis, truth: NumberingTruth): Boolean? = when (h.family) {
        HypothesisFamily.LEADING_DIGIT -> h.proposition.contains("floor") == (truth.family != NumberingFamily.DIGIT_IS_WING)
        HypothesisFamily.NUMBERING_DIRECTION -> direction(sim, h, truth)
        HypothesisFamily.PARITY_SIDE -> side(sim, h, truth)
        else -> null
    }

    /** Where the corridor a hypothesis is about lies: main (true), wing (false), unknown (null). */
    private fun inMain(sim: Simulation, h: Hypothesis, truth: NumberingTruth): Boolean? {
        val corridor = sim.engine.beliefs.corridors.firstOrNull { it.id == h.scope } ?: return null
        val ys = corridor.plates.mapNotNull { sim.canonicalToPlan(it.position)?.second }
        if (ys.isEmpty()) return null
        val mean = ys.average().toFloat()
        return mean >= truth.mainCorridorY.start - 0.5f && mean <= truth.mainCorridorY.endInclusive + 0.5f
    }

    /** The hypothesis's direction on the floor plan. */
    private fun planDirection(sim: Simulation, h: Hypothesis): Pair<Float, Float>? {
        val d = h.direction ?: return null
        val corridor = sim.engine.beliefs.corridors.firstOrNull { it.id == h.scope } ?: return null
        val at = corridor.plates.firstOrNull()?.position ?: return null
        val (x0, y0) = sim.canonicalToPlan(at) ?: return null
        val (x1, y1) = sim.canonicalToPlan(at + d) ?: return null
        return (x1 - x0) to (y1 - y0)
    }

    private fun direction(sim: Simulation, h: Hypothesis, truth: NumberingTruth): Boolean? {
        if (truth.family == NumberingFamily.SEQUENTIAL_U) return null
        val (dx, dy) = planDirection(sim, h) ?: return null
        return when (inMain(sim, h, truth)) {
            true -> (dx > 0f) == truth.risingEast
            // Wings are numbered rising north, away from the main corridor.
            false -> dy > 0f
            null -> null
        }
    }

    private fun side(sim: Simulation, h: Hypothesis, truth: NumberingTruth): Boolean? {
        if (truth.family == NumberingFamily.SEQUENTIAL_U) return null
        val claimsLeft = h.proposition.contains("left")
        return when (inMain(sim, h, truth)) {
            // Rising east, left is north; rising west, left is south.
            true -> claimsLeft == (truth.risingEast == truth.oddNorth)
            // Wing: rising north, odd on the west wall - the left.
            false -> claimsLeft
            null -> null
        }
    }
}
