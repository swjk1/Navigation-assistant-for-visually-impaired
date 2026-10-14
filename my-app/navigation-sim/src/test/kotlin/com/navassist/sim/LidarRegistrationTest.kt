package com.navassist.sim

import com.navassist.navcore.mapping.CellState
import com.navassist.sim.bench.Benchmark
import com.navassist.sim.bench.Designs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The LiDAR is placed by the engine's own pose (NavigationEngine.integrateLidar): the obstacles it
 * maps must lie on real surfaces. A sign error or a frame mix-up in the beam angles would put walls
 * metres off, and the robot would steer into the real ones.
 */
class LidarRegistrationTest {
    @Test
    fun `obstacles mapped with the LiDAR lie on real surfaces`() {
        val e = Benchmark.episodeNamed("school-f2-lift-room218")
        val sim = Simulation(Designs.named("robot-rig-chest-c1")(SimSettings(e.scene(), e.start, target = e.target, maxSeconds = 30.0, seed = 1)))
        while (sim.seconds < 20.0) sim.step()
        val g = sim.engine.occupancyGrid()
        val errors = ArrayList<Float>()
        for (gz in 0 until g.cells) for (gx in 0 until g.cells) {
            if (g.stateAt(gx, gz) != CellState.OCCUPIED) continue
            val (x, y) = sim.canonicalToPlan(g.gridToWorld(gx, gz)) ?: continue
            errors += sim.scene.clearanceAt(x, -y)
        }
        errors.sort()
        assertTrue(errors.size > 200, "the LiDAR mapped walls (${errors.size} occupied cells)")
        val median = errors[errors.size / 2]
        val p90 = errors[errors.size * 9 / 10]
        assertTrue(median <= 0.05f && p90 <= 0.15f, "occupied cells off the real surfaces: median %.2f m, p90 %.2f m".format(median, p90))
    }
}
