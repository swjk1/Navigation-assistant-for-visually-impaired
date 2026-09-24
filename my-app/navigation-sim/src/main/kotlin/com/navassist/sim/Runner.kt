package com.navassist.sim

import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.sim.foxglove.FoxglovePublisher
import com.navassist.sim.foxglove.McapSink
import com.navassist.sim.foxglove.McapWriter
import com.navassist.sim.report.TopDownRenderer
import com.navassist.sim.webots.WebotsExporter
import java.io.File

/** One scenario run with one seed, and whether it met its criteria. */
class ScenarioRun(
    val scenario: Scenario,
    val seed: Long,
    val result: SimResult,
    val failures: List<String>,
    val simulation: Simulation,
) {
    val passed: Boolean get() = failures.isEmpty()
}

object Runner {

    fun run(
        scenario: Scenario,
        seed: Long,
        onFrame: ((Simulation, TraceSample, NavigationSnapshot) -> Unit)? = null,
        recordTo: File? = null,
        webotsTo: File? = null,
    ): ScenarioRun {
        val simulation = Simulation(scenario.settings(seed))
        onFrame?.let { simulation.listeners += it }
        val recording = recordTo?.let { file ->
            McapSink(McapWriter.open(file)).also { sink ->
                val publisher = FoxglovePublisher(sink)
                simulation.listeners += publisher::onFrame
            }
        }
        val webots = webotsTo?.let { WebotsExporter.Recorder().also { r -> simulation.listeners += r::onFrame } }
        val result = try {
            simulation.run()
        } finally {
            recording?.close()
        }
        val run = ScenarioRun(scenario, seed, result, scenario.check(result, simulation), simulation)
        if (webots != null) WebotsExporter.write(webotsTo, run, webots)
        return run
    }

    /** Where a recording of this scenario and seed goes. */
    fun recordingFile(directory: File, scenario: Scenario, seed: Long) =
        File(directory, "${scenario.name}-seed$seed.mcap")

    /** Writes the top-down picture of a run and returns where it went. */
    fun snapshot(run: ScenarioRun, directory: File): File {
        val status = if (run.passed) "pass" else "FAIL"
        val file = File(directory, "${run.scenario.name}-seed${run.seed}-$status.png")
        val title = "${run.scenario.name}  seed ${run.seed}  ${if (run.passed) "PASS" else "FAIL: " + run.failures.joinToString("; ")}"
        TopDownRenderer.render(run.simulation, run.result, title, file)
        return file
    }
}
