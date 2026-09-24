package com.navassist.sim

import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.state.NavigationCommand
import com.navassist.sim.sensor.BodyPose
import com.navassist.sim.user.UserProfile
import com.navassist.sim.world.Buildings
import kotlin.math.hypot

/**
 * What a scenario is expected to show.
 *
 * KNOWN_LIMITATION scenarios reproduce a failure the engine is known to have. They are run and
 * reported, not asserted, and the day one starts passing it should be promoted to PASS.
 */
enum class Expectation { PASS, KNOWN_LIMITATION }

class Scenario(
    val name: String,
    val description: String,
    val expectation: Expectation,
    val settings: (seed: Long) -> SimSettings,
    /** Returns the criteria that were NOT met; empty means the run passed. */
    val check: (SimResult, Simulation) -> List<String>,
    /** For a KNOWN_LIMITATION: what the simulator showed, and what would fix it. */
    val limitation: String? = null,
)

object Scenarios {

    private val start = BodyPose(1f, 0f, 0f)

    private fun noCollisions(r: SimResult) =
        if (r.collisions > 0) listOf("walked into something ${r.collisions} time(s)") else emptyList()

    private fun notFallen(r: SimResult) =
        if (r.outcome == Outcome.FELL) listOf("fell into a drop-off") else emptyList()

    private fun reachedNorth(sim: Simulation, y: Float): List<String> {
        val furthest = sim.trace.maxOfOrNull { it.body.y } ?: 0f
        return if (furthest < y) listOf("only got ${"%.1f".format(furthest)} m north (wanted $y)") else emptyList()
    }

    private fun arrivedNear(r: SimResult, x: Float, y: Float, radius: Float): List<String> {
        val failures = ArrayList<String>()
        if (r.outcome != Outcome.ARRIVED) failures += "never announced ARRIVED (${r.outcome}, ${r.finalStatus})"
        val miss = hypot(r.finalBody.x - x, r.finalBody.y - y)
        if (r.outcome == Outcome.ARRIVED && miss > radius) {
            failures += "announced ARRIVED ${"%.1f".format(miss)} m from the destination"
        }
        return failures
    }

    val all: List<Scenario> = listOf(
        Scenario(
            "corridor-explore",
            "Scan, then walk down an empty 2 m corridor.",
            Expectation.PASS,
            { seed -> SimSettings(Buildings.corridor().build(), start, maxSeconds = 90.0, seed = seed) },
            { r, sim -> noCollisions(r) + reachedNorth(sim, 10f) },
        ),
        Scenario(
            "low-box",
            "A 0.4 m box in the corridor, 5 m ahead in the walking line. The old map erased it.",
            Expectation.KNOWN_LIMITATION,
            { seed -> SimSettings(Buildings.lowBoxInCorridor(), start, maxSeconds = 90.0, seed = seed) },
            { r, sim -> noCollisions(r) + reachedNorth(sim, 8f) },
            limitation = "The box is mapped and never touched (the safety half passes). Getting past it " +
                "stalls in exploration: the unseen patch behind the box keeps winning frontier selection, " +
                "fails to plan, is blacklisted, and the engine ends in NO_ROUTE - the frontier " +
                "commitment / blacklisting bookkeeping from the architecture review (item 12).",
        ),
        Scenario(
            "doorway",
            "Leave a room through a standard 0.85 m doorway into the next one.",
            Expectation.KNOWN_LIMITATION,
            { seed -> SimSettings(Buildings.doorway(), start, maxSeconds = 120.0, seed = seed) },
            { r, sim -> noCollisions(r) + reachedNorth(sim, 6.5f) },
            limitation = "The map shows the doorway open and a route through it. The user is walked " +
                "to within ~1 m and then stopped repeatedly: the forward check is a straight 0.9 m line " +
                "along the phone's heading, which clips a jamb's safety envelope unless the heading is " +
                "within ~8 degrees, while guidance only steers to 15 and a walking phone sways. Needs " +
                "finer steering near narrow gaps, or a forward check that follows the planned path.",
        ),
        Scenario(
            "table-floor",
            "A room with a table: the floor estimate must be the floor, not the table top.",
            Expectation.PASS,
            { seed -> SimSettings(Buildings.roomWithTable(), BodyPose(1f, 0f, 0.4f), maxSeconds = 40.0, seed = seed) },
            { r, sim ->
                // Canonical y is relative to where the phone was (1.3 m up), so the floor is -1.3.
                val floorY = sim.engine.floorEstimate().floorY
                noCollisions(r) + if (kotlin.math.abs(floorY + 1.3f) > 0.1f) {
                    listOf("floor estimated at ${"%.2f".format(floorY + 1.3f)} m above the real floor")
                } else {
                    emptyList()
                }
            },
        ),
        Scenario(
            "stairwell",
            "A stairwell opening beside a ledge too narrow to pass. Stopping is fine; falling is not.",
            Expectation.PASS,
            { seed -> SimSettings(Buildings.stairwellDrop(), start, maxSeconds = 90.0, seed = seed) },
            { r, _ -> notFallen(r) + noCollisions(r) },
        ),
        Scenario(
            "tracking-loss",
            "Tracking drops for 3 s mid-corridor. Nothing may be said but STOP until it recovers.",
            Expectation.PASS,
            { seed ->
                SimSettings(Buildings.corridor().build(), start, maxSeconds = 60.0, trackingLoss = listOf(25.0..28.0), seed = seed)
            },
            { r, sim ->
                val moving = sim.trace.any {
                    it.seconds in 25.05..28.0 && it.command != NavigationCommand.STOP
                }
                noCollisions(r) + if (moving) listOf("gave a movement instruction without tracking") else emptyList()
            },
        ),
        Scenario(
            "never-tilts",
            "A user who sweeps the phone but never points it at the floor must still get going.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(Buildings.corridor().build(), start, user = UserProfile.NEVER_TILTS, maxSeconds = 90.0, seed = seed)
            },
            { r, sim ->
                val slow = r.firstMoveSeconds?.let { it > 30.0 } ?: true
                noCollisions(r) + reachedNorth(sim, 6f) +
                    if (slow) listOf("first movement instruction after ${r.firstMoveSeconds ?: "never"} s") else emptyList()
            },
            limitation = "Never leaves LOCALIZING. Held at 5 degrees of pitch in a 2 m corridor the " +
                "floor is only visible from ~1.7 m out, so the known fraction within 3 m never reaches " +
                "minMapConfidenceToNavigate. The scan prompt should ask users to tilt the phone down, " +
                "and/or map confidence should be measured over the walkable area ahead, not a disc.",
        ),
        Scenario(
            "t-junction-exit",
            "Find the exit: straight to a T, one dead-end branch, the exit door at the end of the other.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(Buildings.tJunctionWithExit(), start, target = NavigationTarget.Exit, maxSeconds = 240.0, seed = seed)
            },
            { r, _ -> noCollisions(r) + arrivedNear(r, 9.5f, 11f, 2.0f) },
            limitation = "Passes on some seeds. When the first branch taken is the dead end, recovery " +
                "via BACKTRACKING is unreliable (NO_ROUTE, or BACKTRACKING for minutes): the topology " +
                "bookkeeping from the architecture review (item 12) - junction types, self-blacklisting " +
                "while backtracking, edges never marked blocked.",
        ),
        Scenario(
            "find-room-314",
            "Walk a corridor of numbered doors and stop at 314, reading plates with realistic OCR.",
            Expectation.PASS,
            { seed ->
                SimSettings(Buildings.roomCorridor(), BodyPose(1.2f, 0f, 0f), target = NavigationTarget.Room("314"), maxSeconds = 240.0, seed = seed)
            },
            { r, _ ->
                val (x, y) = Buildings.roomPlatePlan(314)
                noCollisions(r) + arrivedNear(r, x, y, 2.0f)
            },
        ),
        Scenario(
            "glass-pane",
            "A glass pane across the corridor. Depth sees through glass, so the map calls it free.",
            Expectation.KNOWN_LIMITATION,
            { seed -> SimSettings(Buildings.glassAcrossCorridor(), start, maxSeconds = 90.0, seed = seed) },
            { r, _ -> noCollisions(r) },
            limitation = "Depth mostly sees through glass, so no depth-only map can know the pane is " +
                "there. Has not caused a collision in these seeds only because the user rarely gets that " +
                "far. Needs a non-depth cue: perception (door frames, glass-door signage) or the pane's " +
                "occasional returns accumulated rather than outvoted.",
        ),
    )

    fun named(name: String): Scenario = all.firstOrNull { it.name == name }
        ?: error("no scenario '$name'; known: ${all.joinToString { it.name }}")
}
