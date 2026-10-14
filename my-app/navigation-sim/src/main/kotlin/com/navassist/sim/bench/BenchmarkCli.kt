package com.navassist.sim.bench

import com.navassist.sim.SimSettings
import com.navassist.sim.advice.FollowTheSignsAdviser
import com.navassist.sim.advice.LlmAdviser
import com.navassist.sim.advice.NoisyPlanner
import com.navassist.sim.advice.OraclePlanner
import com.navassist.sim.advice.LlmClient
import com.navassist.sim.advice.NoisyOracleAdviser
import com.navassist.sim.advice.OracleAdviser
import com.navassist.sim.advice.OutageAdviser
import com.navassist.sim.advice.SituationRecorder
import com.navassist.sim.perception.PerceptionProfile
import com.navassist.sim.sensor.DriftModel
import com.navassist.sim.sensor.SensorRig
import com.navassist.sim.user.UserProfile
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

    val all: Map<String, (SimSettings) -> SimSettings> = linkedMapOf<String, (SimSettings) -> SimSettings>(
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
        // Sweep the camera side to side while hunting a numbered room: plates sit on the side walls.
        "sweep-25" to { s -> s.copy(engineConfig = s.engineConfig.copy(roomSearchSweepDegrees = 25f)) },
        "sweep-40" to { s -> s.copy(engineConfig = s.engineConfig.copy(roomSearchSweepDegrees = 40f)) },
        // Walking or driving speed, and how fast a requested sweep goes (period of one side-to-side).
        "speed-0.7" to { s -> s.copy(user = s.user.copy(walkMetersPerSecond = 0.7f)) },
        "speed-0.5" to { s -> s.copy(user = s.user.copy(walkMetersPerSecond = 0.5f)) },
        "pan-2.6" to { s -> s.copy(user = s.user.copy(scanPeriodSeconds = 2.6f)) },
        "pan-3.3" to { s -> s.copy(user = s.user.copy(scanPeriodSeconds = 3.3f)) },
        "pan-4" to { s -> s.copy(user = s.user.copy(scanPeriodSeconds = 4f)) },
        "sweep-40-fast" to { s ->
            s.copy(engineConfig = s.engineConfig.copy(roomSearchSweepDegrees = 40f), user = s.user.copy(scanPeriodSeconds = 2f))
        },
        "oracle-sweep-25" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(roomSearchSweepDegrees = 25f)) },
        // Plates read without depth are not followed up.
        "oracle-no-glimpse" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(glimpseMaxAgeMillis = 0)) },
        // Advice acting outside the frontier score (both off by default): turning to look where it
        // points, and dropping a committed frontier it does not point at.
        "oracle-advice-turn" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(adviceTurnsToLook = true)) },
        "oracle-advice-release" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(adviceReleasesCommitment = true)) },
        "oracle-advice-either" to { s ->
            s.copy(adviser = OracleAdviser(s.scene, s.target), engineConfig = s.engineConfig.copy(adviceTurnsToLook = true, adviceReleasesCommitment = true))
        },
        // Room numbers judged from the first and last plate only, as before the numbering model.
        "numbering-off" to { s -> s.copy(engineConfig = s.engineConfig.copy(roomNumberModel = false)) },
        // Classic frontier exploration: signs never steer, only a sighting of the goal itself does.
        "frontier-only" to { s -> s.copy(engineConfig = s.engineConfig.copy(weightSemantic = 0f)) },
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
        // The oracle behind a connection that drops out for 30-60 s at a time: the engine must
        // carry on, and stale advice must expire.
        "oracle-outage" to { s -> s.copy(adviser = OutageAdviser(OracleAdviser(s.scene, s.target), s.seed)) },
        // Candidate sensor rigs (sensor/SensorRig.kt), alone and with the oracle.
        "rig-wide-phone" to { s -> SensorRig.WIDE_PHONE.applyTo(s) },
        "rig-stereo-chest" to { s -> SensorRig.STEREO_CHEST.applyTo(s) },
        "rig-stereo-chest-glass" to { s -> SensorRig.STEREO_CHEST_GLASS.applyTo(s) },
        "rig-lidar-chest" to { s -> SensorRig.LIDAR_CHEST.applyTo(s) },
        // The chest harness (LAYERED_NAVIGATION_DESIGN.md section 2): phone alone, then with the LiDAR.
        "rig-chest-phone" to { s -> SensorRig.CHEST_PHONE.applyTo(s) },
        "rig-chest-c1" to { s -> SensorRig.CHEST_C1.applyTo(s) },
        "rig-chest-c1-noclear" to { s -> SensorRig.CHEST_C1.applyTo(s).let { r -> r.copy(engineConfig = r.engineConfig.copy(lidarClearsPlane = false)) } },
        "rig-chest-phone-grid28" to { s -> SensorRig.CHEST_PHONE.applyTo(s).let { r -> r.copy(engineConfig = r.engineConfig.copy(gridSizeMeters = 28f)) } },
        "rig-chest-phone-grid28-d48" to { s -> SensorRig.CHEST_PHONE.applyTo(s).let { r -> r.copy(engineConfig = r.engineConfig.copy(gridSizeMeters = 28f, maxDepthMeters = 4.8f)) } },
        "rig-chest-c1-lidarfrontiers" to { s -> SensorRig.CHEST_C1.applyTo(s).let { r -> r.copy(engineConfig = r.engineConfig.copy(frontiersFromCamera = false)) } },

        "rig-chest-c1-grid28" to { s -> SensorRig.CHEST_C1.applyTo(s).let { r -> r.copy(engineConfig = r.engineConfig.copy(gridSizeMeters = 28f)) } },
        "rig-chest-c1-pitch20" to { s -> SensorRig.CHEST_C1.copy(pitchDegrees = 20f).applyTo(s) },
        "rig-chest-c1-landscape" to { s -> SensorRig.CHEST_C1.copy(hold = SensorRig.CHEST_C1.hold.copy(portrait = false)).applyTo(s) },
        "oracle-rig-chest-c1" to { s -> SensorRig.CHEST_C1.applyTo(s).copy(adviser = OracleAdviser(s.scene, s.target)) },
        "oracle-rig-stereo-chest" to { s -> SensorRig.STEREO_CHEST.applyTo(s).copy(adviser = OracleAdviser(s.scene, s.target)) },
        "oracle-rig-lidar-chest" to { s -> SensorRig.LIDAR_CHEST.applyTo(s).copy(adviser = OracleAdviser(s.scene, s.target)) },
        // Record every question an adviser would be asked, labelled with the truth (SITUATIONS_OUT).
        "record" to { s -> s.copy(adviser = SituationRecorder(s.scene, s.target, s.start, s.seed)) },
        "record-oracle" to { s -> s.copy(adviser = SituationRecorder(s.scene, s.target, s.start, s.seed, OracleAdviser(s.scene, s.target))) },
        // A tracker that drifts: the engine's map no longer lines up with itself over a long walk.
        "drift-mild" to { s -> s.copy(drift = DriftModel.MILD) },
        "drift-strong" to { s -> s.copy(drift = DriftModel.STRONG) },
        "oracle-drift-mild" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), drift = DriftModel.MILD) },
        "oracle-drift-strong" to { s -> s.copy(adviser = OracleAdviser(s.scene, s.target), drift = DriftModel.STRONG) },
        // Shape cues for exploration, no adviser: favour the space's long axis, avoid clutter.
        "axis-1" to { s -> s.copy(engineConfig = s.engineConfig.copy(weightAxis = 1f)) },
        "axis-2" to { s -> s.copy(engineConfig = s.engineConfig.copy(weightAxis = 2f)) },
        "clutter-1" to { s -> s.copy(engineConfig = s.engineConfig.copy(weightClutter = -1f)) },
        "clutter-3" to { s -> s.copy(engineConfig = s.engineConfig.copy(weightClutter = -3f)) },
        "axis-1-clutter-1" to { s -> s.copy(engineConfig = s.engineConfig.copy(weightAxis = 1f, weightClutter = -1f)) },
        // The long-term planner alone: the ceiling, then guesses of known accuracy.
        "planner-oracle" to { s -> s.copy(planner = OraclePlanner(s.scene, s.target)) },
    ).apply {
        for (accuracy in listOf(40, 60, 80)) {
            put("planner-noisy-a$accuracy") { s -> s.copy(planner = NoisyPlanner(s.scene, s.target, accuracy / 100f, 1f, s.seed)) }
            // Both tiers at the same accuracy: openings scored at half weight, plus the planner.
            put("two-tier-a$accuracy") { s ->
                s.copy(
                    adviser = NoisyOracleAdviser(s.scene, s.target, accuracy / 100f, 0.5f, s.seed),
                    planner = NoisyPlanner(s.scene, s.target, accuracy / 100f, 1f, s.seed),
                )
            }
        }
        // Follow the Signs' mechanism at a given accuracy (see FollowTheSignsAdviser): asked every
        // 2 s. "fts" lets the subgoal decide the frontier, as in their method; "fts-soft" fuses it
        // with the usual advice weight, so the two differ only in how much authority it has.
        for (accuracy in listOf(60, 80, 100)) {
            put("fts-a$accuracy") { s ->
                s.copy(
                    adviser = FollowTheSignsAdviser(s.scene, s.target, accuracy / 100f, s.seed),
                    adviceIntervalSeconds = 2.0,
                    engineConfig = s.engineConfig.copy(weightAdvice = FTS_DOMINANT_WEIGHT),
                )
            }
            put("fts-soft-a$accuracy") { s ->
                s.copy(adviser = FollowTheSignsAdviser(s.scene, s.target, accuracy / 100f, s.seed), adviceIntervalSeconds = 2.0)
            }
        }
        // An adviser of known quality, for free: right a given share of the time, each answer at a
        // given weight. noisy-a60-w50 is right 60% of the time, at half weight.
        for (accuracy in listOf(40, 50, 60, 70, 80, 90)) {
            for (weight in listOf(25, 50, 100)) {
                put("noisy-a$accuracy-w$weight") { s ->
                    s.copy(adviser = NoisyOracleAdviser(s.scene, s.target, accuracy / 100f, weight / 100f, s.seed))
                }
            }
        }
    }

    /**
     * A design by name. "robot-<design>" runs that design with a path-following robot in place of
     * the person (UserProfile.ROBOT): "robot-current", "robot-noisy-a60-w50".
     */
    fun named(name: String): (SimSettings) -> SimSettings {
        // "a+b+c": design a, then modifiers b and c applied in order (see [modifiers]).
        if ('+' in name) {
            val parts = name.split('+')
            val base = named(parts.first())
            val mods = parts.drop(1).map { m -> modifiers[m] ?: error("no modifier '$m'; known: ${modifiers.keys.joinToString()}") }
            return { s -> mods.fold(base(s)) { acc, mod -> mod(acc) } }
        }
        if (name.startsWith(ROBOT_PREFIX)) {
            val base = named(name.removePrefix(ROBOT_PREFIX))
            // The robot first, so the design can still adjust it (its speed, say). A robot drives
            // past plates without the sway and pauses that let a person read them, so it sweeps its
            // camera across the walls while hunting a room (measured: school 52.9% -> 62.5%).
            return { s ->
                base(s.copy(user = UserProfile.ROBOT, engineConfig = s.engineConfig.copy(roomSearchSweepDegrees = ROBOT_SWEEP_DEGREES)))
            }
        }
        return all[name] ?: error("no design '$name'; known: ${all.keys.joinToString()}, each also as $ROBOT_PREFIX<design>")
    }

    /** The trained scorer, loaded once. */
    val learned: com.navassist.navcore.strategy.LearnedScorer by lazy {
        TrainScorer.load(File(System.getenv("NAV_SCORER_MODEL") ?: "datasets/scorer-v1.json"))
    }

    /** One OpenAI client for every run in this process: one cache, one budget, one set of counts. */
    val openAi: com.navassist.sim.advice.OpenAiClient by lazy { com.navassist.sim.advice.OpenAiClient() }

    /** A long-term planner with unread signs reported and arrival verified at the door, as b4 has. */
    private fun withPlanner(s: SimSettings, planner: () -> com.navassist.navcore.strategy.LongTermPlanner) = s.copy(
        longTermPlanner = planner,
        perception = s.perception.copy(reportUnreadable = true),
        engineConfig = s.engineConfig.copy(arrivalRequiresVerification = true),
    )

    /** Changes that stack on any design: "robot-rig-chest-c1+unread". */
    val modifiers: Map<String, (SimSettings) -> SimSettings> = mapOf(
        // Perception reports text seen but not read (SemanticObservation.UnreadSign).
        "unread" to { s -> s.copy(perception = s.perception.copy(reportUnreadable = true)) },
        // Perfect-if-visible perception: every sign in view and big enough is read, correctly, at once.
        "perfect" to { s -> s.copy(perception = com.navassist.sim.perception.PerceptionProfile.PERFECT.copy(reportUnreadable = s.perception.reportUnreadable)) },
        // The tactical fixes made for the chest rigs (forward gate to the waypoint, halo escape only
        // from inside a halo, a 0.35 m margin), on any design.
        "tactics" to { s -> s.copy(engineConfig = s.engineConfig.copy(forwardGateToWaypoint = true, haloEscapeOnlyFromHalo = true, inflationRadiusMeters = 0.35f)) },
        // Arrival only once the destination's plate is read at its door.
        "verify" to { s -> s.copy(engineConfig = s.engineConfig.copy(arrivalRequiresVerification = true)) },
        // B1 through the subgoal machinery: a planner that always leaves the choice to the tactics.
        "b1" to { s -> s.copy(longTermPlanner = { com.navassist.navcore.strategy.DefaultTacticsPlanner }) },
        // The model baselines (paid; see advice/OpenAiClient.kt for the gates). Same candidates,
        // skills, perception and verification as b4; only the scorer differs.
        "b5" to { s -> withPlanner(s) { com.navassist.navcore.strategy.CandidatePlanner(com.navassist.sim.advice.DecisionsScorer(openAi)) } },
        "b3" to { s -> withPlanner(s) { com.navassist.navcore.strategy.CandidatePlanner(com.navassist.sim.advice.ChooserScorer(openAi, withBeliefs = true)) } },
        // P: the cost-to-go scorer trained on fork data (NAV_SCORER_MODEL, default datasets/scorer-v1.json).
        "p" to { s -> withPlanner(s) { com.navassist.navcore.strategy.CandidatePlanner(learned) } },
        "b2" to { s -> withPlanner(s) { com.navassist.navcore.strategy.CandidatePlanner(com.navassist.sim.advice.ChooserScorer(openAi, withBeliefs = false)) } },
        // B4: hypotheses + one-step information gain; sees unread signs and verifies at the door.
        "b4" to { s ->
            s.copy(
                longTermPlanner = { com.navassist.navcore.strategy.HypothesisPlanner() },
                perception = s.perception.copy(reportUnreadable = true),
                engineConfig = s.engineConfig.copy(arrivalRequiresVerification = true),
            )
        },
    )

    private const val ROBOT_PREFIX = "robot-"
    private const val ROBOT_SWEEP_DEGREES = 40f

    /** Advice weight at which the subgoal outweighs every other frontier term combined. */
    private const val FTS_DOMINANT_WEIGHT = 10f
}

object BenchmarkCli {

    private val resultsDir = File("benchmarks")

    fun list(filter: String?) {
        val prepared = Benchmark.prepare(Benchmark.select(filter))
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
        val prepared = Benchmark.prepare(Benchmark.select(filter))
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
        Benchmark.write(file, name, seeds, prepared, runs, design)
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
        val planned = runs.mapNotNull { it.planner }
        if (planned.isNotEmpty()) {
            val inspections = planned.sumOf { it.inspections }
            val judged = planned.sumOf { it.hypothesesJudged }
            println("planner: %.1f subgoals/run, %d inspections (%d read), verified in %d of %d runs, %d rejected subgoals".format(
                planned.sumOf { it.subgoals }.toDouble() / planned.size, inspections, planned.sumOf { it.inspectionsRead },
                planned.count { it.verified }, planned.size, planned.sumOf { it.rejections },
            ))
            println("hypotheses: %d confirmed, %d judged against the truth, %d of those wrong".format(
                planned.sumOf { it.hypothesesConfirmed }, judged, planned.sumOf { it.hypothesesConfirmedWrong ?: 0 },
            ))
        }
        val hits = runs.flatMap { it.hits }.groupingBy { it }.eachCount()
        if (hits.isNotEmpty()) {
            println()
            println("walked into: " + hits.entries.sortedByDescending { it.value }.joinToString { "${it.key} x${it.value}" })
        }
        if (listOf("+b2", "+b3", "+b5").any { it in design }) println(Designs.openAi.report())
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
        val robots = Benchmark.agentOf(baseline) == "robot" && Benchmark.agentOf(candidate) == "robot"
        print(Benchmark.compare(a, runsA, b, runsB, judgeChurn = !robots).report())
    }
}
