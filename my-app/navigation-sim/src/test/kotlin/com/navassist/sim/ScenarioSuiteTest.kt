package com.navassist.sim

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every scenario expected to pass, on several seeds. A failure writes a top-down picture of the
 * run to build/sim-reports and says where it is.
 *
 * KNOWN_LIMITATION scenarios are not asserted here; `./gradlew run` reports them.
 */
class ScenarioSuiteTest {

    private val seeds = 1L..3L
    private val reportDir = File(System.getProperty("sim.reportDir") ?: "build/sim-reports")

    @Test
    fun `scenarios expected to pass do, on every seed`() {
        val failures = ArrayList<String>()
        for (scenario in Scenarios.all.filter { it.expectation == Expectation.PASS }) {
            for (seed in seeds) {
                val run = Runner.run(scenario, seed)
                println("${if (run.passed) "pass" else "FAIL"} ${scenario.name} seed $seed: ${run.result.summary()}")
                if (!run.passed) {
                    val picture = Runner.snapshot(run, reportDir)
                    failures += "${scenario.name} seed $seed: ${run.failures.joinToString("; ")} (picture: ${picture.path})"
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `the same seed gives the same run`() {
        val scenario = Scenarios.named("corridor-explore")
        val a = Runner.run(scenario, 7).result
        val b = Runner.run(scenario, 7).result
        if (a != b) fail("non-deterministic:\n$a\n$b")
    }
}
