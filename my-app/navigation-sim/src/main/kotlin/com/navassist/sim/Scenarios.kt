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

    private fun reachedWest(sim: Simulation, x: Float): List<String> {
        val furthest = sim.trace.minOfOrNull { it.body.x } ?: 0f
        return if (furthest > x) listOf("only got to x = ${"%.1f".format(furthest)} (wanted $x or further west)") else emptyList()
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
            "l-corridor",
            "Explore round a blind corner: 11 m north, then the corridor turns 90 degrees west.",
            Expectation.KNOWN_LIMITATION,
            { seed -> SimSettings(Buildings.lCorridor(), start, maxSeconds = 120.0, seed = seed) },
            { r, sim -> noCollisions(r) + reachedWest(sim, -5f) },
            limitation = "Passes 1 in 5. The corner itself is handled (the drift correction fixed a freeze in the " +
                "west leg), but the engine often turns back at the corner instead of committing to the new " +
                "leg: the side opening competes as a frontier with ones behind the user, and the " +
                "commitment / blacklist bookkeeping (review item 12) lets it lose.",
        ),
        Scenario(
            "narrow-office-corridor",
            "A 1.5 m office corridor: the safety envelope must still leave room to walk.",
            Expectation.PASS,
            { seed -> SimSettings(Buildings.narrowOfficeCorridor(), BodyPose(0.75f, 0f, 0f), maxSeconds = 90.0, seed = seed) },
            { r, sim -> noCollisions(r) + reachedNorth(sim, 10f) },
        ),
        Scenario(
            "cluttered-corridor",
            "A bench, a bin, a water dispenser and a planter along the walls of a 2.4 m corridor.",
            Expectation.PASS,
            { seed -> SimSettings(Buildings.clutteredCorridor(), BodyPose(1.2f, 0f, 0f), maxSeconds = 120.0, seed = seed) },
            { r, sim -> noCollisions(r) + reachedNorth(sim, 16f) },
        ),
        Scenario(
            "lobby-exit",
            "Cross an open lobby with four columns and stop at the exit door on the far wall.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(Buildings.lobbyWithPillars(), BodyPose(0f, 0f, 0f), target = NavigationTarget.Exit, maxSeconds = 180.0, seed = seed)
            },
            { r, _ -> noCollisions(r) + arrivedNear(r, 0f, 11.3f, 2.0f) },
            limitation = "Passes 3 in 5. One seed times out exploring; one announced ARRIVED 5 m short: the exit " +
                "sign was seen past a column edge and placed on the column. The size check narrows but " +
                "cannot close this - a 0.6 m sign on a column 4 m away is plausible. Sampling depth over " +
                "the whole detection box, not its centre, would.",
        ),
        Scenario(
            "elevator-lobby",
            "Walk up a corridor into a lift lobby and stop at an elevator door.",
            Expectation.PASS,
            { seed ->
                SimSettings(Buildings.elevatorLobby(), start, target = NavigationTarget.Elevator, maxSeconds = 180.0, seed = seed)
            },
            { r, _ ->
                val nearest = listOf(-0.5f, 2.5f).minOf { hypot(r.finalBody.x - it, r.finalBody.y - 13.3f) }
                noCollisions(r) + if (r.outcome != Outcome.ARRIVED) {
                    listOf("never announced ARRIVED (${r.outcome}, ${r.finalStatus})")
                } else if (nearest > 2f) {
                    listOf("announced ARRIVED ${"%.1f".format(nearest)} m from either lift")
                } else {
                    emptyList()
                }
            },
        ),
        Scenario(
            "corridor-ending-at-stairs",
            "The corridor ends at an open stairwell across its full width. Stopping is right; falling is not.",
            Expectation.PASS,
            { seed -> SimSettings(Buildings.corridorEndingAtStairs(), start, maxSeconds = 90.0, seed = seed) },
            { r, _ -> notFallen(r) + noCollisions(r) },
        ),
        Scenario(
            "dark-carpet",
            "4 m of black carpet, which returns little depth. Guidance must cope and carry on.",
            Expectation.KNOWN_LIMITATION,
            { seed -> SimSettings(Buildings.darkCarpetCorridor(), start, maxSeconds = 90.0, seed = seed) },
            { r, sim -> noCollisions(r) + reachedNorth(sim, 12f) },
            limitation = "Passes 4 in 5. Sparse returns on the carpet can leave the floor ahead unknown long " +
                "enough for the run to end in NO_ROUTE; the user is never walked onto unseen floor.",
        ),
        Scenario(
            "signposted-junction",
            "At a T, a 'Rooms 300-349 ->' sign points right; room 314 is 6 m down the right branch.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(Buildings.signpostedJunction(), start, target = NavigationTarget.Room("314"), maxSeconds = 180.0, seed = seed)
            },
            { r, _ -> noCollisions(r) + arrivedNear(r, 8.75f, 10.6f, 2.0f) },
            limitation = "Passes 2-3 in 5. The sign's arrow is now read (it used to be ignored in favour of where " +
                "the sign sat in the picture), but the plate for 314 is on the side wall: unreadable at a " +
                "grazing angle from down the corridor, and outside the camera's ~50 degree view when " +
                "passing it. Failing runs pass within 1.5 m of it and never read it. Guidance should ask " +
                "for a sideways look when passing doors while hunting a room number.",
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
