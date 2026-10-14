package com.navassist.sim.report

import com.navassist.navcore.world.ObservationEvent
import com.navassist.navcore.world.WorldModel
import com.navassist.sim.Simulation
import com.navassist.sim.foxglove.arr
import com.navassist.sim.foxglove.json
import com.navassist.sim.foxglove.obj
import java.io.File

/**
 * Writes a run's evidence ledger as JSON lines: one `event` line per observation, in order, then
 * one `landmark` line per landmark, then one `check` line saying whether replaying the events
 * rebuilt the same landmarks (it must). Positions are given in the engine's frame and, where the
 * simulator knows the mapping, on the floor plan too.
 */
object LedgerWriter {

    fun write(sim: Simulation, file: File) {
        file.parentFile?.mkdirs()
        val world = sim.engine.world
        file.bufferedWriter().use { out ->
            for (e in world.events) out.appendLine(eventLine(sim, e))
            for (l in world.landmarks) {
                val plan = sim.canonicalToPlan(l.position)
                out.appendLine(json {
                    "type" to "landmark"
                    "id" to l.id
                    "kind" to l.kind.name
                    "label" to l.label
                    "x" to l.position.x; "z" to l.position.z
                    if (plan != null) { "planX" to plan.first; "planY" to plan.second }
                    "timesSeen" to l.timesSeen
                    "readCount" to l.readCount
                    "conflictingReads" to l.conflictingReads
                    "firstSeenMillis" to l.firstSeenMillis
                    "lastSeenMillis" to l.lastSeenMillis
                    "events" to arr { for (id in l.eventIds) add(id) }
                })
            }
            for (o in sim.engine.executor.history) out.appendLine(json {
                "type" to "outcome"
                "subgoal" to o.subgoalId
                "skill" to o.skill.name
                "target" to o.targetId
                "status" to o.status.name
                "reason" to o.reason
                "travelled" to o.travelledMeters
                "elapsedMillis" to o.elapsedMillis
                "endedAtMillis" to o.endedAtMillis
                "evidence" to o.evidenceEventIds.size
            })
            for (r in sim.engine.rejections) out.appendLine(json {
                "type" to "rejection"
                "skill" to r.subgoal.skill.name
                "target" to r.subgoal.targetId
                "reason" to r.reason
            })
            for (h in sim.engine.beliefs.hypotheses) out.appendLine(json {
                "type" to "hypothesis"
                "id" to h.id
                "family" to h.family.name
                "proposition" to h.proposition
                "scope" to h.scope
                "rival" to h.rivalId
                "status" to h.status.name
                "belief" to h.belief
                "support" to arr { for (id in h.support) add(id) }
                "against" to arr { for (id in h.against) add(id) }
                "history" to arr { for ((t, what) in h.history) add("$t $what") }
            })
            out.appendLine(json {
                "type" to "check"
                "replayMatches" to replayMatches(world)
                "events" to world.events.size
                "landmarks" to world.landmarks.size
            })
        }
    }

    fun replayMatches(world: WorldModel): Boolean {
        val again = WorldModel.replay(world.events)
        return world.landmarks.map { Triple(it.id, it.label, it.timesSeen) } == again.landmarks.map { Triple(it.id, it.label, it.timesSeen) } &&
            world.events.map { it.landmarkId } == again.events.map { it.landmarkId }
    }

    private fun eventLine(sim: Simulation, e: ObservationEvent): String = json {
        "type" to "event"
        "id" to e.id
        "timeMillis" to e.timeMillis
        "kind" to e.kind.name
        "value" to e.value
        "direction" to e.direction?.name
        "placement" to e.placement.name
        e.position?.let { p ->
            "x" to p.x; "z" to p.z
            sim.canonicalToPlan(p)?.let { (x, y) -> "planX" to x; "planY" to y }
        }
        "observer" to obj { "x" to e.observerPosition.x; "z" to e.observerPosition.z; "yaw" to e.observerYawRadians }
        e.bearing?.let { b -> "bearing" to obj { "x" to b.x; "z" to b.z } }
        "score" to e.score
        "landmark" to e.landmarkId
    }
}
