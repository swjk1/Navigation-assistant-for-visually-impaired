package com.navassist.sim.bench

import com.navassist.sim.SimSettings
import com.navassist.sim.advice.LlmAdviser
import com.navassist.sim.advice.LlmClient
import com.navassist.sim.advice.OracleAdviser
import com.navassist.sim.perception.PerceptionProfile
import java.io.File

/**
 * Design variants the benchmark can run. "current" is the code as it stands; others adjust the
 * settings of every run (an adviser switched on, an engine parameter changed) so two designs can be
 * compared on exactly the same episodes and seeds.
 */
object Designs {
    /**
     * One client for every run of a benchmark: one rate limit, one reply cache. LLM_PROVIDER picks
     * claude (ANTHROPIC_API_KEY, model ANTHROPIC_MODEL) or gemini (GEMINI_API_KEY, GEMINI_MODEL);
     * by default Claude when it has a key, else Gemini.
     */
    val llm: LlmClient by lazy {
        val claudeKey = LlmClient.keyFromEnvironment("ANTHROPIC_API_KEY")
        val provider = when (System.getenv("LLM_PROVIDER")?.lowercase()) {
            "claude" -> LlmClient.Provider.CLAUDE
            "gemini" -> LlmClient.Provider.GEMINI
            else -> if (claudeKey != null) LlmClient.Provider.CLAUDE else LlmClient.Provider.GEMINI
        }
        val (model, key) = when (provider) {
            LlmClient.Provider.CLAUDE -> (System.getenv("ANTHROPIC_MODEL") ?: "claude-opus-5") to claudeKey
            LlmClient.Provider.GEMINI ->
                (System.getenv("GEMINI_MODEL") ?: "gemini-3.6-flash") to LlmClient.keyFromEnvironment("GEMINI_API_KEY")
        }
        println("LLM adviser: ${provider.name.lowercase()} $model" + if (key == null) " (no key: cached answers only)" else "")
        LlmClient(
            provider = provider,
            model = model,
            apiKey = key,
            cacheDir = File("benchmarks/llm-cache"),
            minIntervalMillis = System.getenv("LLM_MIN_INTERVAL_MS")?.toLong() ?: 250,
            cacheOnly = System.getenv("LLM_CACHE_ONLY") != null,
            // Total across all runs with this model. Defaults low so nothing spends by accident.
            budgetUsd = System.getenv("LLM_BUDGET_USD")?.toDouble() ?: 1.0,
        )
    }

    /** Counts the questions an LLM design would ask, answering each with "no preference". */
    val dryRunQuestions = java.util.concurrent.atomic.AtomicInteger()

    val all: Map<String, (SimSettings) -> SimSettings> = linkedMapOf(
        "current" to { s -> s },
        // Upper bound: an adviser that knows the building's true shortest route.
        "oracle-advice" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target)) },
        // Sensitivity of high-sign ranging to its assumed mounting height (default 4.0 m).
        "mount-3.5" to { s -> s.copy(engineConfig = s.engineConfig.copy(heightRangingMountMeters = 3.5f)) },
        "mount-4.5" to { s -> s.copy(engineConfig = s.engineConfig.copy(heightRangingMountMeters = 4.5f)) },
        // No high-sign ranging: the engine before it existed.
        "no-ranging" to { s -> s.copy(engineConfig = s.engineConfig.copy(heightRangingMinElevationDegrees = 90f)) },
        "oracle-mount-3.5" to { s ->
            s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(heightRangingMountMeters = 3.5f))
        },
        "oracle-mount-4.5" to { s ->
            s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(heightRangingMountMeters = 4.5f))
        },
        "oracle-no-ranging" to { s ->
            s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(heightRangingMinElevationDegrees = 90f))
        },
        // The app before on-device perception ran every second: everything, Gemini included, every 2.5 s.
        "legacy-perception" to { s -> s.copy(perception = PerceptionProfile.LEGACY) },
        "oracle-legacy-perception" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), perception = PerceptionProfile.LEGACY) },
        // No phone-aim instructions: the usual hold throughout.
        "no-aim" to { s -> s.copy(engineConfig = s.engineConfig.copy(namedPlaceAimPitchDegrees = null, roomSearchSweepDegrees = 0f)) },
        "oracle-no-aim" to { s ->
            s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(namedPlaceAimPitchDegrees = null, roomSearchSweepDegrees = 0f))
        },
        // A full turn on the spot before setting off (off by default).
        "look-around" to { s -> s.copy(engineConfig = s.engineConfig.copy(initialLookAroundDegrees = 330f)) },
        "oracle-look-around" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(initialLookAroundDegrees = 330f)) },
        "oracle-sweep-25" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(roomSearchSweepDegrees = 25f)) },
        // Plates read without depth are not followed up.
        "oracle-no-glimpse" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(glimpseMaxAgeMillis = 0)) },
        "oracle-no-advice-turn" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(adviceTurnsToLook = false)) },
        "oracle-no-advice-release" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(adviceReleasesCommitment = false)) },
        "oracle-no-advice-either" to { s ->
            s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(adviceTurnsToLook = false, adviceReleasesCommitment = false))
        },
        // Floor ahead seen from any range counts as walkable (no close-range check).
        "oracle-no-near-check" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(nearFloorVerifyMeters = 100f)) },
        "no-near-check" to { s -> s.copy(engineConfig = s.engineConfig.copy(nearFloorVerifyMeters = 100f)) },
        "oracle-no-sweep" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(roomSearchSweepDegrees = 0f)) },
        "oracle-shop-level" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(namedPlaceAimPitchDegrees = 0f)) },
        "oracle-shop-up20" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(namedPlaceAimPitchDegrees = 20f)) },
        // A language model reading what the phone perceived (Gemini; see LlmAdviser).
        "llm-advice" to { s -> s.copy(adviser = LlmAdviser(llm)) },
        // How many questions llm-advice would ask, without asking any.
        "llm-dry-run" to { s -> s.copy(adviser = LlmAdviser(null) { dryRunQuestions.incrementAndGet() }) },
    )

    fun named(name: String) = all[name] ?: error("no design '$name'; known: ${all.keys.joinToString()}")
}

object BenchmarkCli {

    private val resultsDir = File("benchmarks")

    fun list(filter: String?) {
        val prepared = Benchmark.prepare(Benchmark.episodes.filter { filter == null || filter in it.id })
        for (p in prepared) {
            println("%-38s %-8s shortest %5.1f m  limit %3.0f s  %d destination(s)".format(
                p.episode.id, p.episode.target.description, p.shortest, p.maxSeconds, p.goals.size,
            ))
        }
        println("${prepared.size} episodes")
    }

    fun run(name: String, seeds: Int, filter: String?, design: String, pictures: File? = null) {
        val configure = Designs.named(design)
        val started = System.currentTimeMillis()
        val prepared = Benchmark.prepare(Benchmark.episodes.filter { filter == null || filter in it.id })
        val total = prepared.size * seeds
        println("benchmark '$name': design '$design', ${prepared.size} episodes x $seeds seeds = $total runs")
        var done = 0
        val runs = Benchmark.run(prepared, seeds, configure = configure, pictures = pictures) { r ->
            synchronized(this) {
                done++
                if (done % 20 == 0 || done == total) println("  $done/$total runs")
                if (r.fell) println("  FELL: ${r.episode} seed ${r.seed}")
            }
        }
        val file = File(resultsDir, "$name.json")
        Benchmark.write(file, name, seeds, prepared, runs)
        println()
        println("overall   ${Summary.of(runs).line()}")
        val building = prepared.associate { it.episode.id to it.episode.building }
        for ((b, group) in runs.groupBy { building.getValue(it.episode) }) println("%-9s %s".format(b, Summary.of(group).line()))
        println()
        for ((episode, group) in runs.groupBy { it.episode }) {
            val ok = group.count { it.success }
            val notes = group.filter { !it.success }.map { r ->
                when {
                    r.fell -> "fell"
                    r.falseArrival -> "wrong-arrival"
                    r.locatedSeconds == null -> "never-located"
                    else -> "located-not-reached"
                }
            }.groupingBy { it }.eachCount().entries.joinToString { "${it.key} x${it.value}" }
            println("  %-38s %d/%d  %s".format(episode, ok, group.size, notes))
        }
        val failed = runs.filter { !it.success }
        println()
        println("failures: never located the target %d, located but did not arrive %d, arrived in the wrong place %d, fell %d".format(
            failed.count { !it.fell && !it.falseArrival && it.locatedSeconds == null },
            failed.count { !it.fell && !it.falseArrival && it.locatedSeconds != null },
            failed.count { it.falseArrival }, failed.count { it.fell },
        ))
        val hits = runs.flatMap { it.hits }.groupingBy { it }.eachCount()
        if (hits.isNotEmpty()) {
            println()
            println("walked into: " + hits.entries.sortedByDescending { it.value }.joinToString { "${it.key} x${it.value}" })
        }
        if (design.startsWith("llm-advice")) {
            val llm = Designs.llm
            println()
            println("LLM: ${llm.calls.get()} calls, ${llm.cacheHits.get()} cached answers, ${llm.failures.get()} failures" +
                (llm.latencySummary()?.let { (median, p90) -> ", latency median $median ms, p90 $p90 ms" } ?: ""))
            println("LLM spend with this model so far (all runs): %d input + %d output tokens = \$%.3f".format(
                llm.inputTokens.get(), llm.outputTokens.get(), llm.spentUsd()))
            if (llm.refusedOverBudget.get() > 0) {
                println("WARNING: ${llm.refusedOverBudget.get()} questions went unasked because the budget ran out; " +
                    "those runs had no advice, so this result understates the adviser.")
            }
        }
        if (design == "llm-dry-run") println("\nllm-advice would ask ${Designs.dryRunQuestions.get()} questions")
        println()
        println("saved ${file.path} in ${(System.currentTimeMillis() - started) / 1000} s")
    }

    fun compare(baseline: File, candidate: File) {
        val (a, runsA) = Benchmark.read(baseline)
        val (b, runsB) = Benchmark.read(candidate)
        print(Benchmark.compare(a, runsA, b, runsB).report())
    }
}
