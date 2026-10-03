package com.navassist.sim

import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.state.NavigationCommand
import com.navassist.sim.sensor.BodyPose
import com.navassist.sim.user.UserProfile
import com.navassist.sim.world.Buildings
import com.navassist.sim.world.Mall
import com.navassist.sim.world.School
import kotlin.math.PI
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

    /**
     * Collisions with anything but glass. Glass is invisible to depth (see glass-pane); scenarios
     * about something else check this instead, so a glass contact does not hide what they test.
     */
    private fun noVisibleCollisions(sim: Simulation): List<String> {
        val visible = sim.user.collidedWith.filter { it.material != com.navassist.sim.world.Material.GLASS }
        return if (visible.isNotEmpty()) listOf("walked into ${visible.joinToString { it.name }}") else emptyList()
    }

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

    private fun reachedEast(sim: Simulation, x: Float): List<String> {
        val furthest = sim.trace.maxOfOrNull { it.body.x } ?: 0f
        return if (furthest < x) listOf("only got to x = ${"%.1f".format(furthest)} (wanted $x or further east)") else emptyList()
    }

    private fun arrivedNear(r: SimResult, x: Float, y: Float, radius: Float): List<String> =
        arrivedNearAny(r, listOf(x to y), radius)

    /** ARRIVED within [radius] of any of [places] - either stairwell, either lift. */
    private fun arrivedNearAny(r: SimResult, places: List<Pair<Float, Float>>, radius: Float): List<String> {
        val failures = ArrayList<String>()
        if (r.outcome != Outcome.ARRIVED) failures += "never announced ARRIVED (${r.outcome}, ${r.finalStatus})"
        val miss = places.minOf { (x, y) -> hypot(r.finalBody.x - x, r.finalBody.y - y) }
        if (r.outcome == Outcome.ARRIVED && miss > radius) {
            failures += "announced ARRIVED ${"%.1f".format(miss)} m from the destination"
        }
        return failures
    }

    private const val EAST = (PI / 2).toFloat()
    private const val SOUTH = PI.toFloat()
    private const val WEST = (-PI / 2).toFloat()

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

        // ---------------------------------------------------------------- school, three storeys

        Scenario(
            "school-find-room",
            "School, floor 1: from the entrance lobby, follow the range signs west and stop at room 104.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(School.build(1), BodyPose(30f, -8f, 0f), target = NavigationTarget.Room("104"), maxSeconds = 300.0, seed = seed)
            },
            { r, _ ->
                val (x, y) = School.platePlan(104)
                noCollisions(r) + arrivedNear(r, x, y, 2.0f)
            },
            limitation = "Never takes a step from the 12 x 10 m lobby: the open-space stall. After the scan, the only frontier left is one long cluster - " +
                "the edge of the scanned half-disc - which passes by the user, so its representative point is ~0.6 m " +
                "away: inside frontierReachedMeters, never chosen. With no choice, backtrackGoal() blacklists the " +
                "user's own position (every frame), which blacklists that frontier for good, and the engine sits in " +
                "NO_ROUTE. Corridors escape it because their walls cut the edge into short separate frontiers. Needs " +
                "long frontier clusters split into segments, and no self-blacklisting before a first move.",
        ),
        Scenario(
            "school-upper-room",
            "School, floor 2: step out of the lift, turn east, take the wing the signs point to, stop at 218.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(School.build(2), BodyPose(30f, 4.5f, SOUTH), target = NavigationTarget.Room("218"), maxSeconds = 300.0, seed = seed)
            },
            { r, _ ->
                val (x, y) = School.platePlan(218)
                noCollisions(r) + arrivedNear(r, x, y, 2.0f)
            },
            limitation = "Passes 4 in 5. The failing seed times out still exploring, having walked 165 m.",
        ),
        Scenario(
            "school-stairs",
            "School, floor 3: from mid-corridor, find a stairwell (one 18 m behind, one 42 m ahead).",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(School.build(3), BodyPose(18f, 1.5f, EAST), target = NavigationTarget.Stairs, maxSeconds = 300.0, seed = seed)
            },
            { r, _ -> notFallen(r) + noCollisions(r) + arrivedNearAny(r, listOf(School.westStairDoor, School.eastStairDoor), 2.5f) },
            limitation = "Passes 1 in 5. Failing runs reach a stairwell - one walks through its doorway and back out - " +
                "without ever announcing ARRIVED, and time out exploring. The stair signs are 0.3 m pictograms beside " +
                "the doorway (recall 0.55); why they are not acted on is not yet diagnosed.",
        ),
        Scenario(
            "school-exit",
            "School, floor 1: from the wing, find an exit - the wing's end door 7 m behind, or the main doors.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(School.build(1), BodyPose(45.5f, 20f, SOUTH), target = NavigationTarget.Exit, maxSeconds = 300.0, seed = seed)
            },
            { r, _ -> noCollisions(r) + arrivedNearAny(r, listOf(School.wingDoor, School.lobbyDoor), 2.5f) },
            limitation = "Passes 1 in 5. Failing runs walk the building for 5 minutes with 50-100 detections, passing within " +
                "~3 m of the main doors and within ~5 m of the wing's end door, and never lock onto an exit. Not yet " +
                "diagnosed.",
        ),
        Scenario(
            "school-elevator",
            "School, floor 2: from the west stairwell door, walk 28 m of corridor to the lift niche.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(School.build(2), BodyPose(1.5f, 1.5f, EAST), target = NavigationTarget.Elevator, maxSeconds = 300.0, seed = seed)
            },
            { r, _ -> noCollisions(r) + arrivedNearAny(r, School.lifts.map { (x, y) -> x to y - 0.7f }, 2.0f) },
            limitation = "Passes 4 in 5. The failing seed times out still exploring, having walked 158 m.",
        ),
        Scenario(
            "school-stairwell",
            "School, floor 2: exploring from a stairwell doorway, with the flight down beside the landing. Never fall.",
            Expectation.PASS,
            { seed -> SimSettings(School.build(2), BodyPose(1.2f, 1.5f, WEST), maxSeconds = 120.0, seed = seed) },
            { r, _ -> notFallen(r) + noCollisions(r) },
        ),

        // ---------------------------------------------------------------- mall, two levels

        Scenario(
            "mall-exit",
            "Mall, ground floor: from the concourse, past a kiosk, to the entrance doors 24 m east.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(Mall.build(1), BodyPose(60f, 3f, EAST), target = NavigationTarget.Exit, maxSeconds = 300.0, seed = seed)
            },
            { r, _ ->
                noCollisions(r) + arrivedNearAny(r, listOf(Mall.eastEntrance, Mall.westEntrance, Mall.carParkExit), 2.5f)
            },
            limitation = "Never takes a step: the open-space stall. After the scan, the only frontier left is one long cluster - " +
                "the edge of the scanned half-disc - which passes by the user, so its representative point is ~0.6 m " +
                "away: inside frontierReachedMeters, never chosen. With no choice, backtrackGoal() blacklists the " +
                "user's own position (every frame), which blacklists that frontier for good, and the engine sits in " +
                "NO_ROUTE. Corridors escape it because their walls cut the edge into short separate frontiers. Needs " +
                "long frontier clusters split into segments, and no self-blacklisting before a first move.",
        ),
        Scenario(
            "mall-elevator",
            "Mall, ground floor: walk the concourse and find the lifts in the services corridor off its north side.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(Mall.build(1), BodyPose(30f, 3f, EAST), target = NavigationTarget.Elevator, maxSeconds = 300.0, seed = seed)
            },
            { r, _ -> noCollisions(r) + arrivedNearAny(r, Mall.lifts.map { (x, y) -> x - 0.7f to y }, 2.0f) },
            limitation = "Never takes a step: the open-space stall. After the scan, the only frontier left is one long cluster - " +
                "the edge of the scanned half-disc - which passes by the user, so its representative point is ~0.6 m " +
                "away: inside frontierReachedMeters, never chosen. With no choice, backtrackGoal() blacklists the " +
                "user's own position (every frame), which blacklists that frontier for good, and the engine sits in " +
                "NO_ROUTE. Corridors escape it because their walls cut the edge into short separate frontiers. Needs " +
                "long frontier clusters split into segments, and no self-blacklisting before a first move.",
        ),
        Scenario(
            "mall-washroom",
            "Mall, upper floor: a hanging washroom sign marks the services corridor; stop at a washroom door.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(Mall.build(2), BodyPose(36f, 11.5f, EAST), target = NavigationTarget.Room("washroom"), maxSeconds = 300.0, seed = seed)
            },
            { r, _ -> noCollisions(r) + arrivedNearAny(r, Mall.washroomDoors.map { (x, y) -> x + 0.7f to y }, 2.5f) },
            limitation = "Never arrives. Exploration goes in through the nearest shop entrance and spends the run inside the " +
                "shop (the 3 m entrances are just openings to it), or stalls as in mall-exit; one seed walked into a " +
                "glass shop front. The hanging sign is readable only from in front of the corridor mouth, which the " +
                "user rarely reaches.",
        ),
        Scenario(
            "mall-upper-walkway",
            "Mall, upper floor: explore a 5 m walkway between glass shop fronts and the atrium's glass balustrade.",
            Expectation.KNOWN_LIMITATION,
            { seed -> SimSettings(Mall.build(2), BodyPose(6f, 2.5f, EAST), maxSeconds = 180.0, seed = seed) },
            { r, sim -> notFallen(r) + noCollisions(r) + reachedEast(sim, 30f) },
            limitation = "Never takes a step: the open-space stall. After the scan, the only frontier left is one long cluster - " +
                "the edge of the scanned half-disc - which passes by the user, so its representative point is ~0.6 m " +
                "away: inside frontierReachedMeters, never chosen. With no choice, backtrackGoal() blacklists the " +
                "user's own position (every frame), which blacklists that frontier for good, and the engine sits in " +
                "NO_ROUTE. Corridors escape it because their walls cut the edge into short separate frontiers. Needs " +
                "long frontier clusters split into segments, and no self-blacklisting before a first move.",
        ),
        Scenario(
            "mall-escalator-edge",
            "Mall, upper floor: exploring from the escalator landing, facing the open head of two escalators. Never fall.",
            Expectation.PASS,
            { seed -> SimSettings(Mall.build(2), BodyPose(34.5f, 7.2f, WEST), maxSeconds = 90.0, seed = seed) },
            // Glass shop fronts line this walkway; contact with them is the glass-pane limitation.
            { r, sim -> notFallen(r) + noVisibleCollisions(sim) },
        ),
        Scenario(
            "mall-food-court",
            "Mall, upper floor: explore through a food court of tables; the floor estimate must stay on the floor.",
            Expectation.KNOWN_LIMITATION,
            { seed -> SimSettings(Mall.build(2), BodyPose(64f, 2.5f, EAST), maxSeconds = 180.0, seed = seed) },
            { r, sim ->
                val floorY = sim.engine.floorEstimate().floorY
                notFallen(r) + noCollisions(r) + reachedEast(sim, 76f) + if (kotlin.math.abs(floorY + 1.3f) > 0.1f) {
                    listOf("floor estimated at ${"%.2f".format(floorY + 1.3f)} m above the real floor")
                } else {
                    emptyList()
                }
            },
            limitation = "Passes 1 in 5. Failing runs cross part of the food court without collisions, then go in through " +
                "a shop entrance and explore the shop instead of the rest of the concourse.",
        ),
        Scenario(
            "mall-find-store",
            "Mall, ground floor: find the Pharmacy by the name over its entrance.",
            Expectation.KNOWN_LIMITATION,
            { seed ->
                SimSettings(Mall.build(1), BodyPose(8f, 7f, EAST), target = NavigationTarget.Room("Pharmacy"), maxSeconds = 240.0, seed = seed)
            },
            { r, _ ->
                val (x, y) = Mall.shopEntrance("Pharmacy")
                noCollisions(r) + arrivedNear(r, x, y, 2.5f)
            },
            limitation = "The name hangs 4.1 m up on the fascia, facing across the concourse. A portrait camera walking " +
                "the concourse has it in frame only from ~6-10 m ahead at an angle: beyond depth range, often not at all " +
                "(one reading per pass, or none). Names are observations now, and a single reading is ranged from its " +
                "elevation (SemanticHintStore.rangeByHeight), but runs that never face the shop front still never see it.",
        ),
    )

    fun named(name: String): Scenario = all.firstOrNull { it.name == name }
        ?: error("no scenario '$name'; known: ${all.joinToString { it.name }}")
}
