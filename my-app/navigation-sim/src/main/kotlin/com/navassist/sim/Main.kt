package com.navassist.sim

import com.navassist.sim.bench.BenchmarkCli
import java.io.File

private const val USAGE = """
Navigation simulator

  --all                  run every scenario (default)
  --scenario NAME        run one scenario (repeatable)
  --seeds N              seeds 1..N per scenario (default 3)
  --report DIR           where PNG snapshots go (default sim-reports)
  --snapshots all|fail   picture every run, or only failures (default fail)
  --trace                print every change of engine status / instruction
  --mcap DIR             record every run to DIR/<scenario>-seed<N>.mcap for Foxglove
  --ledger DIR           write every run's evidence ledger to DIR/<scenario>-seed<N>.ledger.jsonl
  --webots DIR           export every run as a Webots project, DIR/<scenario>-seed<N>/worlds/scene.wbt
  --live PORT            run the first scenario in real time, streaming to Foxglove at
                         ws://localhost:PORT; publish a Twist on /cmd_vel to drive it yourself
  --list                 list scenarios and exit
  --episode ID           run one benchmark trip as a scenario (repeatable), with --design; works
                         with --live, --mcap, --webots, --trace and --snapshots

Benchmark (start -> destination episodes, success rate, paired comparison):
  --benchmark            run the episode set on --seeds seeds (default 5 here)
  --name NAME            label for the results (default "run"); saved to benchmarks/NAME.json
  --filter TEXT          only episodes whose id contains TEXT
  --design NAME          design variant to run (see Designs.kt; default "current")
  --compare A.json B.json  paired comparison of two saved results, and a verdict
  --episodes             list the episodes with their shortest walks and exit
  --pictures DIR         also draw a top-down picture of every benchmark run into DIR
"""

fun main(args: Array<String>) {
    val names = ArrayList<String>()
    val episodeIds = ArrayList<String>()
    var seeds = 3
    var reportDir = File("sim-reports")
    var snapshotAll = false
    var trace = false
    var mcapDir: File? = null
    var ledgerDir: File? = null
    var livePort: Int? = null
    var webotsDir: File? = null
    var benchmark = false
    var seedsGiven = false
    var benchName = "run"
    var filter: String? = null
    var design = "current"
    var pictures: File? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--all" -> Unit
            "--scenario" -> names += args[++i]
            "--episode" -> episodeIds += args[++i]
            "--seeds" -> { seeds = args[++i].toInt(); seedsGiven = true }
            "--benchmark" -> benchmark = true
            "--name" -> benchName = args[++i]
            "--filter" -> filter = args[++i]
            "--design" -> design = args[++i]
            "--pictures" -> pictures = File(args[++i])
            "--compare" -> {
                BenchmarkCli.compare(File(args[i + 1]), File(args[i + 2]))
                return
            }
            "--episodes" -> { BenchmarkCli.list(filter); return }
            "--train-scorer" -> {
                // --train-scorer DATA.jsonl MODEL.json: fit the cost-to-go scorer (bench/TrainScorer.kt).
                com.navassist.sim.bench.TrainScorer.train(File(args[i + 1]), File(args[i + 2]))
                return
            }
            "--fork-data" -> {
                // Training data for the cost-to-go scorer: --fork-data FILE [--filter F --seeds N --design D] (flags before it).
                val file = File(args[++i])
                com.navassist.sim.bench.ForkData.generate(filter, (1..seeds).map { it.toLong() }, if (design == "current") "robot-rig-chest-c1+b4" else design, file)
                return
            }
            "--diagnose" -> {
                val seedList = args[++i].split(',').map { it.toLong() }
                com.navassist.sim.bench.Diagnose.run(filter, seedList, design)
                return
            }
            "--report" -> reportDir = File(args[++i])
            "--snapshots" -> snapshotAll = args[++i] == "all"
            "--trace" -> trace = true
            "--mcap" -> mcapDir = File(args[++i])
            "--ledger" -> ledgerDir = File(args[++i])
            "--live" -> livePort = args[++i].toInt()
            "--webots" -> webotsDir = File(args[++i])
            "--list" -> {
                Scenarios.all.forEach { println("${it.name.padEnd(24)} ${it.expectation.name.padEnd(17)} ${it.description}") }
                return
            }
            "--help", "-h" -> { println(USAGE.trimIndent()); return }
            else -> error("unknown argument ${args[i]}\n$USAGE")
        }
        i++
    }
    if (benchmark) {
        BenchmarkCli.run(benchName, if (seedsGiven) seeds else 5, filter, design, pictures)
        return
    }
    val trips = episodeIds.map { id ->
        com.navassist.sim.bench.Benchmark.asScenario(
            com.navassist.sim.bench.Benchmark.episodeNamed(id), design, com.navassist.sim.bench.Designs.named(design),
        )
    }
    val scenarios = when {
        names.isEmpty() && trips.isNotEmpty() -> trips
        names.isEmpty() -> Scenarios.all
        else -> names.map { Scenarios.named(it) } + trips
    }
    livePort?.let { port ->
        LiveRunner.run(scenarios.first(), seed = 1, port = port)
        return
    }

    var unexpected = 0
    for (scenario in scenarios) {
        val runs = (1..seeds).map { seed ->
            val recording = mcapDir?.let { Runner.recordingFile(it, scenario, seed.toLong()) }
            val project = webotsDir?.let { File(it, "${scenario.name}-seed$seed") }
            Runner.run(scenario, seed.toLong(), if (trace) ::printTransition else null, recording, project).also {
                if (recording != null) println("   recording: ${recording.path}")
                if (project != null) println("   webots: ${File(project, "worlds/scene.wbt").path}")
                ledgerDir?.let { dir ->
                    val file = File(dir, "${scenario.name}-seed$seed.ledger.jsonl")
                    com.navassist.sim.report.LedgerWriter.write(it.simulation, file)
                    println("   ledger: ${file.path} (replay ${if (com.navassist.sim.report.LedgerWriter.replayMatches(it.simulation.engine.world)) "matches" else "DIFFERS"})")
                }
            }
        }
        val passed = runs.count { it.passed }
        val verdict = when {
            scenario.expectation == Expectation.KNOWN_LIMITATION -> "known limitation"
            passed == runs.size -> "ok"
            else -> { unexpected++; "FAILING" }
        }
        println("== ${scenario.name}: $passed/${runs.size} passed ($verdict)")
        scenario.limitation?.let { println("   known limitation: $it") }
        for (run in runs) {
            val mark = if (run.passed) "  pass" else "  FAIL"
            println("$mark seed ${run.seed}: ${run.result.summary()}")
            run.failures.forEach { println("         - $it") }
            if (snapshotAll || !run.passed) println("         picture: ${Runner.snapshot(run, reportDir).path}")
        }
    }
    if (unexpected > 0) {
        println("\n$unexpected scenario(s) failing that are expected to pass.")
        kotlin.system.exitProcess(1)
    }
}

private var lastKey: String? = null

/** One line whenever what the engine says, or why, changes. */
private fun printTransition(sim: Simulation, sample: TraceSample, snapshot: com.navassist.navcore.state.NavigationSnapshot) {
    val key = "${snapshot.status}/${snapshot.command}/${snapshot.stopReason}"
    val window = System.getenv("TRACE_FRAMES")?.split('-')?.map { it.toDouble() }
    val everyFrame = window != null && sample.seconds in window[0]..window[1]
    if (key == lastKey && !everyFrame) return
    if (everyFrame) println("    %6.2fs err %s heading %.0f acting %s goal %s frontiers %s".format(sample.seconds, snapshot.headingErrorDegrees?.let { "%.0f".format(it) }, Math.toDegrees(sample.body.heading.toDouble()), sample.acting,
        sim.engine.currentGoalWorld()?.let { sim.canonicalToPlan(it) }?.let { "(%.1f, %.1f)".format(it.first, it.second) },
        sim.engine.frontiers().joinToString { f -> sim.canonicalToPlan(f.centroid)?.let { "(%.1f, %.1f)x%d s%.2f[ig%.2f d%.1f rv%.2f rk%.2f]".format(it.first, it.second, f.cellCount, f.score, f.estimatedInformationGain, f.distanceMeters, f.revisitPenalty, f.riskPenalty) } ?: "?" }))
    lastKey = key
    val d = snapshot.debug
    println(
        "    %6.2fs  %-13s %-10s %-15s frontiers %d  path %d  map %.2f  scan %.2f  floor %.2f@%.2f  at (%.1f, %.1f)".format(
            sample.seconds, snapshot.status, snapshot.command, snapshot.stopReason ?: "",
            d?.frontierCount ?: -1, d?.pathLength ?: -1, snapshot.mapConfidence, d?.scanProgress ?: 0f,
            d?.floorY ?: 0f, d?.floorConfidence ?: 0f, sample.body.x, sample.body.y,
        ),
    )
}
