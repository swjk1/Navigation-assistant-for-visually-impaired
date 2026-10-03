package com.navassist.sim.bench

import com.navassist.navcore.semantic.TargetMatcher
import com.navassist.sim.Simulation
import com.navassist.sim.SimSettings
import kotlin.math.hypot

/**
 * Why a run did or did not recognise its destination: at every capture while the user was near a
 * destination sign, which perception gate stopped it (out of frame, too oblique, too small...), and
 * what the engine got of the readings that passed (placed by depth, or only a bearing).
 */
object Diagnose {

    private const val NEAR_METERS = 8f

    /** Drops the repeats of a line whose state was already reported in the last few lines. */
    private fun collapse(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var skipped = 0
        for (line in lines) {
            val key = line.trim().substringAfter(' ').substringBefore(" at ")
            if (out.takeLast(6).any { it.trim().substringAfter(' ').substringBefore(" at ") == key }) { skipped++; continue }
            if (skipped > 0) { out += "        ... $skipped repeats"; skipped = 0 }
            out += line
        }
        if (skipped > 0) out += "        ... $skipped repeats"
        return out
    }

    fun run(filter: String?, seeds: List<Long>, design: String) {
        val configure = Designs.named(design)
        val prepared = Benchmark.prepare(Benchmark.select(filter))
        val total = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors() - 1))
        val jobs = prepared.flatMap { p -> seeds.map { seed -> p to seed } }.map { (p, seed) -> pool.submit<String> {
            val out = StringBuilder()
            fun println(line: String) { out.appendLine(line) }
            val settings = configure(
                SimSettings(p.episode.scene(), p.episode.start, target = p.episode.target, maxSeconds = p.maxSeconds, seed = seed),
            )
            val sim = Simulation(settings)
            val signs = Benchmark.goalSigns(settings.scene, settings.target).toSet()
            val gates = LinkedHashMap<String, Int>()
            val lines = ArrayList<String>()
            sim.perception.probe = { sign, why ->
                if (sign in signs) {
                    val b = sim.user.body
                    // Plan: y north = -z world.
                    val d = hypot(sign.x - b.x, -sign.z - b.y)
                    if (d <= NEAR_METERS) {
                        val band = if (d < 2f) "<2m" else if (d < 4f) "2-4m" else "4-8m"
                        gates.merge("$why@$band", 1, Int::plus)
                    }
                }
            }
            if (System.getenv("DIAG_DEPTH") != null) sim.perception.seenAt = { sign, x, y ->
                val d = sim.deliveredDepth
                if (sign in signs && d != null) {
                    val u = (x * d.width).toInt()
                    val v = (y * d.height).toInt()
                    val rows = (-3..3).joinToString(" | ") { dv ->
                        (-3..3).joinToString(" ") { du -> "%.1f".format(d.metersAt((u + du).coerceIn(0, d.width - 1), (v + dv).coerceIn(0, d.height - 1))) }
                    }
                    lines += "      %6.1fs capture at pixel (%d, %d): %s".format(sim.seconds, u, v, rows)
                }
            }
            var matched = 0
            var placed = 0
            sim.onLocated = { located ->
                for (l in located) if (TargetMatcher.matches(l.observation, settings.target)) {
                    matched++
                    if (l.observation.worldPosition != null) placed++
                    val sign = signs.minByOrNull { val b = sim.user.body; hypot(it.x - b.x, -it.z - b.y) }
                    lines += "      %6.1fs read target: %s, elevation %s, true range %.1f m".format(
                        sim.seconds, l.observation.worldPosition?.let { w ->
                            sim.canonicalToPlan(w.toVec2())?.let { (px, py) ->
                                val b = sim.user.body
                                val tx = (sign?.x ?: px) - b.x
                                val tz = (sign?.z ?: -py) + b.y
                                val ty = (sign?.y ?: 1.5f) - 1.4f
                                val hit = settings.scene.raycast(b.x, 1.4f, -b.y, tx, ty, tz, 1f)
                                "placed by depth at (%.1f, %.1f, h%.1f) from (%.1f, %.1f); line to sign hits %s".format(
                                    px, py, w.y, b.x, b.y, hit?.let { "${it.box.name} ${it.box.material} at %.2f".format(it.t) } ?: "nothing")
                            }
                        } ?: "bearing only",
                        l.elevationTan?.let { "%.0f deg".format(Math.toDegrees(kotlin.math.atan(it.toDouble()))) } ?: "?",
                        sign?.let { val b = sim.user.body; hypot(it.x - b.x, -it.z - b.y) } ?: -1f,
                    )
                }
            }
            var lastGoal: Pair<Float, Float>? = null
            if (System.getenv("DIAG_GOAL") != null) sim.listeners += { s, sample, snap ->
                val g = s.engine.currentGoalWorld()?.let { s.canonicalToPlan(it) }
                val prev = lastGoal
                if (g != null && (prev == null || hypot(g.first - prev.first, g.second - prev.second) > 0.5f)) {
                    lines += "      %6.1fs goal (%.1f, %.1f) %s %s heading %.0f".format(s.seconds, g.first, g.second, snap.status, snap.command,
                        Math.toDegrees(sample.body.heading.toDouble()))
                    lastGoal = g
                }
            }
            if (System.getenv("DIAG_AHEAD") != null) sim.listeners += { s, sample, snap ->
                val b = sample.body
                if (s.seconds >= (System.getenv("DIAG_AHEAD")!!.toDouble()) && s.seconds % 0.2 < 0.034) {
                    val grid = s.engine.occupancyGrid()
                    val cells = (0..12).joinToString(" ") { k ->
                        val d = k * 0.1f
                        val px = b.x + kotlin.math.sin(b.heading) * d
                        val py = b.y + kotlin.math.cos(b.heading) * d
                        val floor = if (settings.scene.hasFloorAt(px, -py)) "F" else "_"
                        val canon = s.planDirectionToCanonical(0f, 0f)
                        // Engine canonical point for this plan point: invert canonicalToPlan numerically.
                        val (ox, oy, yaw) = s.canonicalOriginPlan() ?: return@joinToString "?"
                        val rx = px - ox; val ry = py - oy
                        val cx = rx * kotlin.math.cos(yaw) - ry * kotlin.math.sin(yaw)
                        val cz = rx * kotlin.math.sin(yaw) + ry * kotlin.math.cos(yaw)
                        val w = com.navassist.navcore.geometry.Vec2(cx, cz)
                        val st = grid.stateAtWorld(w).name.first()
                        val near = if (grid.isNearSeen(w)) "n" else "."
                        "$floor$st$near"
                    }
                    lines += "      %6.2fs %s at (%.2f, %.2f) h%.0f ahead: %s".format(s.seconds, snap.command, b.x, b.y, Math.toDegrees(b.heading.toDouble()), cells)
                }
            }
            val window = System.getenv("DIAG_FRAMES")?.split('-')?.map { it.toDouble() }
            if (window != null) sim.listeners += { s, sample, snap ->
                if (s.seconds in window[0]..window[1]) lines += "      %6.2fs %s %s %s err %s path %d acting %s heading %.0f pos (%.2f, %.2f) floor %.2f@%.2f pitch %.0f".format(
                    s.seconds, snap.status, snap.command, snap.stopReason ?: "", snap.headingErrorDegrees?.let { "%.0f".format(it) },
                    snap.debug?.pathLength ?: -1, sample.acting, Math.toDegrees(sample.body.heading.toDouble()), sample.body.x, sample.body.y, snap.debug?.floorY ?: 0f, snap.debug?.floorConfidence ?: 0f, Math.toDegrees(s.user.phonePitch.toDouble()))
            }
            var lastStatus = ""
            var closest = Float.MAX_VALUE
            sim.listeners += { s, sample, snap ->
                closest = minOf(closest, p.goals.minOf { (x, y) -> hypot(sample.body.x - x, sample.body.y - y) })
                val st = "${snap.status}/${snap.command}" + (snap.stopReason?.let { "/$it" } ?: "")
                if (st != lastStatus && (snap.status.name != "EXPLORING" || !lastStatus.startsWith("EXPLORING"))) {
                    lines += "      %6.1fs %s at (%.1f, %.1f), nearest goal %.1f m".format(
                        s.seconds, st, sample.body.x, sample.body.y,
                        p.goals.minOf { (x, y) -> hypot(sample.body.x - x, sample.body.y - y) },
                    )
                }
                lastStatus = st
            }
            var arrivalNote = ""
            sim.listeners += { s, sample, snap ->
                if (snap.command == com.navassist.navcore.state.NavigationCommand.ARRIVED && arrivalNote.isEmpty()) {
                    val sighting = s.engine.targetSighting()
                    val at = sighting?.let { s.canonicalToPlan(it.worldPosition.toVec2()) }
                    val nearest = at?.let { (x, y) ->
                        settings.scene.signs.filter { Benchmark.names(it, settings.target) }
                            .minByOrNull { hypot(it.x - x, -it.z - y) }
                    }
                    arrivalNote = "arrived on a ${sighting?.source} sighting at %s; nearest matching sign %s".format(
                        at?.let { "(%.1f, %.1f)".format(it.first, it.second) },
                        nearest?.let { "'${it.text}' ${it.kind}${if (it.hanging) " HANGING" else ""} at (%.1f, %.1f) h%.1f, %.1f m away".format(
                            it.x, -it.z, it.y, hypot(it.x - at.first, -it.z - at.second)) },
                    ) + " user at (%.1f, %.1f)".format(sample.body.x, sample.body.y)
                }
            }
            val result = sim.run()
            val miss = p.goals.minOf { (x, y) -> hypot(result.finalBody.x - x, result.finalBody.y - y) }
            val ok = result.outcome == com.navassist.sim.Outcome.ARRIVED && miss <= Benchmark.SUCCESS_RADIUS
            println("${p.episode.id} seed $seed: ${if (ok) "pass" else "FAIL"} ${result.outcome} %.0fs closest %.1f m, target read %d (%d placed)"
                .format(result.seconds, closest, matched, placed))
            if (arrivalNote.isNotEmpty()) {
                println("    $arrivalNote")
                total.merge((if (ok) "arrival-ok:" else "arrival-WRONG:") + arrivalNote.substringAfter("on a ").substringBefore(" sighting"), 1, Int::plus)
            }
            println("    near-sign gates: " + gates.entries.sortedByDescending { it.value }.joinToString { "${it.key} x${it.value}" })
            if (!ok || System.getenv("DIAG_ALL") != null) (if (window != null) lines else collapse(lines).take(60)).forEach { println(it) }
            gates.forEach { (k, v) -> total.merge(k.substringBefore('@'), v, Int::plus) }
            out.toString()
        } }
        for (job in jobs) print(job.get())
        pool.shutdown()
        println()
        println("all gates near destination signs: " + total.entries.sortedByDescending { it.value }.joinToString { "${it.key} x${it.value}" })
    }
}
