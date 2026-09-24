package com.navassist.sim

import java.io.File

private const val USAGE = """
Navigation simulator

  --all                  run every scenario (default)
  --scenario NAME        run one scenario (repeatable)
  --seeds N              seeds 1..N per scenario (default 3)
  --report DIR           where PNG snapshots go (default sim-reports)
  --snapshots all|fail   picture every run, or only failures (default fail)
  --trace                print every change of engine status / instruction
  --list                 list scenarios and exit
"""

fun main(args: Array<String>) {
    val names = ArrayList<String>()
    var seeds = 3
    var reportDir = File("sim-reports")
    var snapshotAll = false
    var trace = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--all" -> Unit
            "--scenario" -> names += args[++i]
            "--seeds" -> seeds = args[++i].toInt()
            "--report" -> reportDir = File(args[++i])
            "--snapshots" -> snapshotAll = args[++i] == "all"
            "--trace" -> trace = true
            "--list" -> {
                Scenarios.all.forEach { println("${it.name.padEnd(18)} ${it.expectation.name.padEnd(17)} ${it.description}") }
                return
            }
            "--help", "-h" -> { println(USAGE.trimIndent()); return }
            else -> error("unknown argument ${args[i]}\n$USAGE")
        }
        i++
    }
    val scenarios = if (names.isEmpty()) Scenarios.all else names.map { Scenarios.named(it) }

    var unexpected = 0
    for (scenario in scenarios) {
        val runs = (1..seeds).map { Runner.run(scenario, it.toLong(), if (trace) ::printTransition else null) }
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
    if (key == lastKey) return
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
