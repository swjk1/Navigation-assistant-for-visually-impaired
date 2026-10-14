package com.navassist.sim.bench

import com.navassist.navcore.strategy.CandidateFeatures
import com.navassist.navcore.strategy.CandidatePlanner
import com.navassist.sim.Outcome
import com.navassist.sim.SimSettings
import com.navassist.sim.Simulation
import com.navassist.sim.foxglove.arr
import com.navassist.sim.foxglove.json
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Training data for the cost-to-go scorer (TI section 7.1), by forking runs.
 *
 * The simulator is deterministic, so a fork needs no saved state: re-run the same trip and seed,
 * and at decision k take option o instead of the policy's choice. Everything before k replays
 * exactly; at the fork the noise streams are reseeded, so the [continuations] of one option see
 * different sensing, and the base policy (disclosed: [baseDesign]) carries on from there.
 *
 * Each record: the features of option o at decision k - computed from what the agent knew then,
 * by the same code the scorer uses - and its cost-to-go: seconds from the fork to a successful
 * arrival, or the time left plus [FAIL_PENALTY_SECONDS] when the run did not succeed. The label
 * uses the simulator's truth; the features never do.
 */
object ForkData {

    const val FAIL_PENALTY_SECONDS = 60.0

    class Decision(val index: Int, val atDecisionPoint: Boolean, val options: List<FloatArray>, val chosen: Int)

    fun generate(
        filter: String?,
        seeds: List<Long>,
        baseDesign: String,
        out: File,
        maxDecisions: Int = System.getenv("FORK_DECISIONS")?.toInt() ?: 6,
        continuations: Int = System.getenv("FORK_K")?.toInt() ?: 2,
    ) {
        val configure = Designs.named(baseDesign)
        val prepared = Benchmark.prepare(Benchmark.select(filter))
        val threads = System.getenv("BENCH_THREADS")?.toInt() ?: maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
        val pool = Executors.newFixedThreadPool(threads)
        out.parentFile?.mkdirs()
        val writer = out.bufferedWriter()
        var records = 0
        var forks = 0
        println("fork data: ${prepared.size} trips x ${seeds.size} seeds, base $baseDesign, up to $maxDecisions decisions, $continuations continuations")
        val jobs = prepared.flatMap { p -> seeds.map { seed -> p to seed } }.map { (p, seed) ->
            pool.submit<List<String>> {
                val lines = ArrayList<String>()
                val base = runOne(p, seed, configure, null)
                for (d in base.second.take(maxDecisions)) {
                    // d.options holds the candidates, then exploring last; force each by its index, exploring as -1.
                    val options = (0 until d.options.size - 1).toList() + listOf(-1)
                    for (o in options) {
                        val costs = ArrayList<Double>()
                        var successes = 0
                        for (j in 1..continuations) {
                            val fork = runOne(p, seed, configure, Fork(d.index, o, seed * 1_000_003 + d.index * 101L + j))
                            costs += fork.first.cost
                            if (fork.first.success) successes++
                        }
                        val features = if (o >= 0) d.options[o] else d.options.last()
                        val mean = costs.average()
                        val std = sqrt(costs.sumOf { (it - mean) * (it - mean) } / costs.size)
                        lines += json {
                            "episode" to p.episode.id
                            "building" to p.episode.building
                            "seed" to seed
                            "decision" to d.index
                            "option" to o
                            "chosenByPolicy" to (o == d.chosen)
                            "atDecisionPoint" to d.atDecisionPoint
                            "features" to arr { for (f in features) add(f) }
                            "costMean" to mean
                            "costStd" to std
                            "successRate" to successes.toDouble() / continuations
                            "continuations" to continuations
                        }
                    }
                }
                lines
            }
        }
        for (job in jobs) {
            val lines = job.get()
            synchronized(writer) { for (l in lines) writer.appendLine(l) }
            records += lines.size
            forks += lines.size * continuations
            if (records > 0 && records % 200 < lines.size) println("  $records records")
        }
        writer.close()
        pool.shutdown()
        println("fork data: $records records from $forks forked runs -> ${out.path}")
        println("features: ${CandidateFeatures.names.joinToString()}")
    }

    private class Fork(val at: Int, val option: Int, val noiseSeed: Long)

    private class Result(val success: Boolean, val cost: Double)

    /**
     * One run; with [fork], forced at its decision, reseeded there, and costed from there. Returns
     * the result and the decisions the planner made (each option's features, explore last).
     */
    private fun runOne(p: Benchmark.Prepared, seed: Long, configure: (SimSettings) -> SimSettings, fork: Fork?): Pair<Result, List<Decision>> {
        val settings = configure(SimSettings(p.episode.scene(), p.episode.start, target = p.episode.target, maxSeconds = p.maxSeconds, seed = seed))
        val sim = Simulation(settings)
        val planner = sim.engine.longTermPlanner as? CandidatePlanner ?: error("fork data needs a CandidatePlanner design, not ${sim.engine.longTermPlanner}")
        val decisions = ArrayList<Decision>()
        var forkSeconds = 0.0
        if (fork == null) {
            planner.onDecision = { index, context, candidates, atPoint, chosen ->
                val options = candidates.map { CandidateFeatures.of(context, it, atPoint) } + listOf(CandidateFeatures.of(context, null, atPoint))
                decisions += Decision(index, atPoint, options, chosen)
            }
        } else {
            planner.forceAt = fork.at
            planner.forceOption = fork.option
            planner.afterForce = {
                forkSeconds = sim.seconds
                sim.reseedNoise(fork.noiseSeed)
            }
        }
        val result = sim.run()
        val miss = p.goals.minOf { (x, y) -> hypot(result.finalBody.x - x, result.finalBody.y - y) }
        val success = result.outcome == Outcome.ARRIVED && miss <= Benchmark.SUCCESS_RADIUS
        val cost = if (success) result.seconds - forkSeconds else (p.maxSeconds - forkSeconds) + FAIL_PENALTY_SECONDS
        return Result(success, cost) to decisions
    }
}
