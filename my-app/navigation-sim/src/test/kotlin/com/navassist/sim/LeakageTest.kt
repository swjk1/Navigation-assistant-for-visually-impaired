package com.navassist.sim

import com.navassist.navcore.strategy.CandidatePlanner
import com.navassist.sim.bench.Benchmark
import com.navassist.sim.bench.Designs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent must know only what it could have perceived (technical_implementation.md section
 * 10.5). These check the three ways that could fail in the simulator: perception reporting what the
 * camera could not see, the planner's snapshot holding anything not backed by evidence, and a forked
 * run (training data) differing from its parent before the fork.
 */
class LeakageTest {

    private fun settings(design: String, seconds: Double = 60.0): SimSettings {
        val e = Benchmark.episodeNamed("school-f2-lift-room218")
        return Designs.named(design)(SimSettings(e.scene(), e.start, target = e.target, maxSeconds = seconds, seed = 3))
    }

    @Test
    fun `every reported sign was in front of the camera and not behind a wall`() {
        val sim = Simulation(settings("robot-rig-chest-c1+b4"))
        var checked = 0
        sim.perception.onCapture = { capture ->
            val m = capture.cameraToWorld
            for (s in capture.sightings.filter { it.fate == "seen" }) {
                val tx = s.sign.x - m[12]; val ty = s.sign.y - m[13]; val tz = s.sign.z - m[14]
                // In front of the camera: positive depth along its viewing axis (-Z).
                val depth = -(tx * m[8] + ty * m[9] + tz * m[10])
                assertTrue(depth > 0.1f, "${s.sign.text} reported from behind the camera")
                // Nothing solid between the camera and the sign.
                val hit = sim.scene.raycast(m[12], m[13], m[14], tx, ty, tz, 1f, skipGlass = true)
                val distance = sqrt(tx * tx + ty * ty + tz * tz)
                assertTrue(hit == null || hit.t >= 1f - 0.12f / distance, "${s.sign.text} reported through ${hit?.box?.name}")
                checked++
            }
        }
        sim.run()
        assertTrue(checked > 20, "enough signs were reported to mean something ($checked)")
    }

    @Test
    fun `every landmark the planner can name rests on recorded evidence`() {
        val sim = Simulation(settings("robot-rig-chest-c1+b4"))
        sim.run()
        val snapshot = sim.engine.worldState()
        val events = sim.engine.world.events.associateBy { it.id }
        for (l in snapshot.landmarks + snapshot.unreadSigns) {
            val landmark = sim.engine.world.landmark(l.id)!!
            assertTrue(landmark.eventIds.isNotEmpty(), "${l.id} has no evidence")
            for (id in landmark.eventIds) assertTrue(events[id]?.landmarkId == l.id, "$id does not back ${l.id}")
        }
        for (h in sim.engine.beliefs.hypotheses) for (id in h.support + h.against) assertTrue(id in events, "${h.id} cites $id, not in the ledger")
    }

    @Test
    fun `a forked run replays its parent exactly up to the fork`() {
        val base = Simulation(settings("robot-rig-chest-c1+b4"))
        val baseTrace = ArrayList<String>()
        base.listeners += { _, s, _ -> baseTrace += "%.2f %.4f %.4f %s".format(s.seconds, s.body.x, s.body.y, s.command) }
        var forkDecision = -1
        (base.engine.longTermPlanner as CandidatePlanner).onDecision = { i, _, candidates, _, _ -> if (forkDecision < 0 && candidates.size >= 1 && i >= 1) forkDecision = i }
        base.run()
        assertTrue(forkDecision >= 0, "the planner made a decision to fork at")

        val fork = Simulation(settings("robot-rig-chest-c1+b4"))
        val forkTrace = ArrayList<String>()
        var forkedAt = Double.MAX_VALUE
        fork.listeners += { _, s, _ -> if (s.seconds < forkedAt) forkTrace += "%.2f %.4f %.4f %s".format(s.seconds, s.body.x, s.body.y, s.command) }
        (fork.engine.longTermPlanner as CandidatePlanner).apply {
            forceAt = forkDecision
            forceOption = -1
            afterForce = { forkedAt = fork.seconds; fork.reseedNoise(424242) }
        }
        fork.run()
        assertTrue(forkTrace.isNotEmpty())
        assertEquals(baseTrace.take(forkTrace.size - 1), forkTrace.dropLast(1), "the fork differs from its parent before the fork")
    }
}
