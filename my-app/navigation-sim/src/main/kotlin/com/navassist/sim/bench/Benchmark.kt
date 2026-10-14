package com.navassist.sim.bench

import com.navassist.sim.world.NumberingFamily
import com.navassist.sim.world.ProceduralFloor
import com.navassist.sim.world.SignPolicy
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.TargetMatcher
import com.navassist.navcore.state.NavigationStatus
import com.navassist.sim.Outcome
import com.navassist.sim.SimSettings
import com.navassist.sim.Simulation
import com.navassist.sim.foxglove.MiniJson
import com.navassist.sim.foxglove.arr
import com.navassist.sim.foxglove.json
import com.navassist.sim.foxglove.obj
import com.navassist.sim.report.TopDownRenderer
import com.navassist.sim.sensor.BodyPose
import com.navassist.sim.world.Buildings
import com.navassist.sim.world.Mall
import com.navassist.sim.world.MallVariant
import com.navassist.sim.world.Scene
import com.navassist.sim.world.School
import com.navassist.sim.world.Sign
import com.navassist.sim.world.SignKind
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * One navigation task: a building, a start pose, and what the user asked for.
 *
 * Where "there" is comes from the building itself: every sign that names the target (a room's
 * plate, an exit sign, a lift door, a stair sign; never a hanging pointer) is a destination, and a
 * run succeeds if it announces ARRIVED within [SUCCESS_RADIUS] of the spot in front of one.
 */
class Episode(
    val id: String,
    val building: String,
    val scene: () -> Scene,
    val start: BodyPose,
    val target: NavigationTarget,
    /** How the building really numbers its rooms, when known (procedural floors): for judging hypotheses, never for the agent. */
    val truth: NumberingTruth? = null,
    /** Floors joined by a lift, when the trip may change floor: [scene] is floor [startFloor]. */
    val multiFloor: com.navassist.sim.world.Building? = null,
    val startFloor: Int = 0,
    /** The floor the destination is on. */
    val goalFloor: Int = 0,
)

/** Ground truth about a floor's numbering, to score the hypotheses an agent confirmed. */
data class NumberingTruth(
    val family: NumberingFamily,
    val risingEast: Boolean,
    val oddNorth: Boolean,
    /** The main corridor's plan y range: plates in it are judged against [risingEast] and [oddNorth]. */
    val mainCorridorY: ClosedFloatingPointRange<Float>,
)

/** What the long-term planner did in one run. */
data class PlannerStats(
    val subgoals: Int,
    /** "SKILL/STATUS" -> count. */
    val outcomes: Map<String, Int>,
    val inspections: Int,
    val inspectionsRead: Int,
    val verified: Boolean,
    val rejections: Int,
    val hypothesesConfirmed: Int,
    /** Confirmed hypotheses the ground truth contradicts; null where the truth is not known. */
    val hypothesesConfirmedWrong: Int?,
    /** Confirmed hypotheses that could be judged. */
    val hypothesesJudged: Int,
)

/** What one run of one episode came to. */
data class RunRecord(
    val episode: String,
    val seed: Long,
    val success: Boolean,
    val arrived: Boolean,
    val falseArrival: Boolean,
    val fell: Boolean,
    val collisions: Int,
    val seconds: Double,
    val walked: Float,
    val shortest: Float,
    /** Success weighted by path length: shortest / max(walked, shortest) when successful, else 0. */
    val spl: Float,
    val finalStatus: String,
    /** What the user walked into, by name ("shop front GLASS"), once per contact. */
    val hits: List<String> = emptyList(),
    /** Seconds until the engine first believed it knew where the target was (NAVIGATING); null if never. */
    val locatedSeconds: Double? = null,
    /** Closest the user ever came to a true destination, in metres (diagnostic). */
    val closestMeters: Float? = null,
    /** Seconds spent in each engine state, as STATUS or STATUS/STOP_REASON (diagnostic). */
    val secondsByState: Map<String, Double> = emptyMap(),
    /** How often the spoken instruction changed, per minute: what a listener has to keep up with. */
    val changesPerMinute: Double? = null,
    /** Smallest gap between the user's body and anything solid, in metres. */
    val minClearanceMeters: Float? = null,
    /** What the long-term planner did; null with no planner. */
    val planner: PlannerStats? = null,
)

/** Averages over a set of runs. */
data class Summary(
    val runs: Int,
    val successRate: Double,
    val spl: Double,
    val falseArrivalRate: Double,
    val collisionRunRate: Double,
    val fallRate: Double,
    val meanSecondsToSuccess: Double?,
    /** Mean instruction changes per minute, over runs that recorded it; null for older results. */
    val meanChangesPerMinute: Double? = null,
) {
    fun line(): String = "success %.1f%%  SPL %.3f  false-arrival %.1f%%  runs-with-collision %.1f%%  falls %.1f%%  n=%d%s%s".format(
        successRate * 100, spl, falseArrivalRate * 100, collisionRunRate * 100, fallRate * 100, runs,
        meanSecondsToSuccess?.let { "  mean time-to-goal %.0f s".format(it) } ?: "",
        meanChangesPerMinute?.let { "  changes/min %.0f".format(it) } ?: "",
    )

    companion object {
        fun of(runs: List<RunRecord>): Summary {
            val n = runs.size.coerceAtLeast(1)
            val successes = runs.filter { it.success }
            return Summary(
                runs = runs.size,
                successRate = successes.size.toDouble() / n,
                spl = runs.sumOf { it.spl.toDouble() } / n,
                falseArrivalRate = runs.count { it.falseArrival }.toDouble() / n,
                collisionRunRate = runs.count { it.collisions > 0 }.toDouble() / n,
                fallRate = runs.count { it.fell }.toDouble() / n,
                meanSecondsToSuccess = if (successes.isEmpty()) null else successes.sumOf { it.seconds } / successes.size,
                meanChangesPerMinute = runs.mapNotNull { it.changesPerMinute }.takeIf { it.isNotEmpty() }?.average(),
            )
        }
    }
}

object Benchmark {

    const val SUCCESS_RADIUS = 2.0f

    /** Where a person reading a sign stands: 0.6 m out from it. */
    private const val STANDING_OFF = 0.6f

    private val N = 0f
    private val E = (PI / 2).toFloat()
    private val S = PI.toFloat()
    private val W = (-PI / 2).toFloat()

    private fun room(label: String) = NavigationTarget.Room(label)

    /**
     * The episode set. Every start stands on open floor and every target has a reachable sign on
     * the start's storey (both are checked before a run). Cross-floor tasks are not in it yet: the
     * engine and the simulated user stay on one floor.
     */
    val episodes: List<Episode> by lazy {
        val list = ArrayList<Episode>()
        fun school(floor: Int, name: String, x: Float, y: Float, h: Float, target: NavigationTarget, what: String) {
            list += Episode("school-f$floor-$name-$what", "school", { School.build(floor) }, BodyPose(x, y, h), target)
        }
        fun mall(level: Int, name: String, x: Float, y: Float, h: Float, target: NavigationTarget, what: String) {
            list += Episode("mall-l$level-$name-$what", "mall", { Mall.build(level) }, BodyPose(x, y, h), target)
        }
        fun simple(id: String, scene: () -> Scene, start: BodyPose, target: NavigationTarget) {
            list += Episode(id, "simple", scene, start, target)
        }

        // Simple scenes: one feature each.
        simple("simple-room-corridor-314", { Buildings.roomCorridor() }, BodyPose(1.2f, 0f, N), room("314"))
        simple("simple-elevator-lobby", { Buildings.elevatorLobby() }, BodyPose(1f, 0f, N), NavigationTarget.Elevator)
        simple("simple-t-junction-exit", { Buildings.tJunctionWithExit() }, BodyPose(1f, 0f, N), NavigationTarget.Exit)
        simple("simple-lobby-exit", { Buildings.lobbyWithPillars() }, BodyPose(0f, 0f, N), NavigationTarget.Exit)
        simple("simple-signposted-314", { Buildings.signpostedJunction() }, BodyPose(1f, 0f, N), room("314"))

        // School, floor 1: entrance lobby, main corridor, wing.
        school(1, "lobby", 30f, -8f, N, room("104"), "room104")
        school(1, "lobby", 30f, -8f, N, room("111"), "room111")
        school(1, "lobby", 30f, -8f, N, room("washroom"), "washroom")
        school(1, "lobby", 30f, -8f, N, NavigationTarget.Elevator, "elevator")
        school(1, "wing", 45.5f, 14f, S, NavigationTarget.Exit, "exit")
        school(1, "west", 3f, 1.5f, E, room("114"), "room114")
        school(1, "east", 57f, 1.5f, W, room("102"), "room102")
        school(1, "mid", 20f, 1.5f, E, NavigationTarget.Stairs, "stairs")
        school(1, "mid", 20f, 1.5f, E, room("119"), "room119")
        school(1, "lift", 30f, 4.5f, S, room("116"), "room116")
        // Floor 2.
        school(2, "lift", 30f, 4.5f, S, room("218"), "room218")
        school(2, "lift", 30f, 4.5f, S, room("203"), "room203")
        school(2, "west", 3f, 1.5f, E, NavigationTarget.Elevator, "elevator")
        school(2, "east", 57f, 1.5f, W, room("206"), "room206")
        school(2, "mid", 20f, 1.5f, E, NavigationTarget.Stairs, "stairs")
        school(2, "wing", 45.5f, 14f, S, NavigationTarget.Exit, "exit")
        school(2, "west", 3f, 1.5f, E, room("washroom"), "washroom")
        school(2, "lift", 30f, 4.5f, S, room("212"), "room212")
        // Floor 3.
        school(3, "mid", 18f, 1.5f, E, NavigationTarget.Stairs, "stairs")
        school(3, "lift", 30f, 4.5f, S, room("320"), "room320")
        school(3, "east", 57f, 1.5f, W, room("301"), "room301")
        school(3, "wing", 45.5f, 14f, S, NavigationTarget.Elevator, "elevator")
        school(3, "west", 3f, 1.5f, E, room("309"), "room309")
        school(3, "lift", 30f, 4.5f, S, NavigationTarget.Exit, "exit")

        // Mall, ground floor.
        mall(1, "west", 3f, 7f, E, room("Pharmacy"), "pharmacy")
        mall(1, "west", 3f, 7f, E, NavigationTarget.Elevator, "elevator")
        mall(1, "mid", 30f, 3f, E, NavigationTarget.Exit, "exit")
        mall(1, "mid", 30f, 3f, E, room("washroom"), "washroom")
        mall(1, "east", 80f, 7f, W, NavigationTarget.Elevator, "elevator")
        mall(1, "east", 80f, 7f, W, room("Toy World"), "toyworld")
        mall(1, "services", 47f, 12f, S, NavigationTarget.Exit, "exit")
        mall(1, "mid", 30f, 3f, E, room("Music"), "music")
        // Upper floor.
        mall(2, "west", 6f, 2.5f, E, NavigationTarget.Elevator, "elevator")
        mall(2, "west", 6f, 2.5f, E, room("Florist"), "florist")
        mall(2, "landing", 33.5f, 7f, E, room("washroom"), "washroom")
        mall(2, "foodcourt", 81.5f, 7f, W, NavigationTarget.Elevator, "elevator")
        mall(2, "north", 30f, 11.5f, E, room("Pizza"), "pizza")
        mall(2, "bridge", 42f, 7f, N, room("Cinema"), "cinema")
        mall(2, "foodcourt", 81.5f, 7f, W, room("Pet Shop"), "petshop")
        list
    }

    /**
     * Trips in malls nothing was tuned on ([MallVariant.heldOut]). Kept out of [episodes] so the
     * reference totals stay comparable; run only when a filter names "heldout".
     */
    val heldOut: List<Episode> by lazy {
        val list = ArrayList<Episode>()
        for (mall in MallVariant.heldOut) {
            fun add(what: String, start: Pair<Float, Float>, heading: Float, target: NavigationTarget) {
                list += Episode(
                    "heldout-mall-${mall.name}-$what", "heldout-mall", { mall.build() },
                    BodyPose(start.first, start.second, heading), target,
                )
            }
            val names = mall.shopNames
            add("west-elevator", mall.westStart, E, NavigationTarget.Elevator)
            add("west-washroom", mall.westStart, E, room("washroom"))
            add("west-shop", mall.westStart, E, room(names[names.size / 2]))
            add("mid-exit", mall.concoursePoint(0.45f, -2f), E, NavigationTarget.Exit)
            add("mid-shop", mall.concoursePoint(0.45f, -2f), W, room(names.last()))
        }
        list
    }

    /**
     * Families whose rules a planner may be tuned on; every other [NumberingFamily] is held out and
     * only ever used to judge. Fixed before any results: the paper's split, by family, not by seed.
     */
    val tuneFamilies = setOf(NumberingFamily.FLOOR_DIGIT, NumberingFamily.WING_BLOCK)

    /** Buildings per family, and trips per building. */
    private const val PROC_BUILDINGS = 6
    private const val PROC_TRIPS = 3

    /**
     * Trips on procedurally generated floors (world/ProceduralFloor.kt): [PROC_BUILDINGS] buildings
     * per numbering family, [PROC_TRIPS] trips each to a random room from a random start. Signs are
     * accurate in most buildings, missing or misleading in some. Ids say the split:
     * proc-tune-... or proc-test-..., so "--filter proc-test" runs only the held-out families.
     */
    val procedural: List<Episode> by lazy {
        val list = ArrayList<Episode>()
        for (family in NumberingFamily.entries) {
            val split = if (family in tuneFamilies) "tune" else "test"
            for (b in 1..PROC_BUILDINGS) {
                val r = kotlin.random.Random(b * 7919L + family.ordinal)
                val policy = when (r.nextInt(10)) { in 0..5 -> SignPolicy.ACCURATE; in 6..7 -> SignPolicy.MISSING; else -> SignPolicy.MISLEADING }
                val floor = ProceduralFloor(family, b.toLong(), policy)
                val rooms = floor.rooms.filter { it.label.last().isDigit() }
                for (t in 1..PROC_TRIPS) {
                    val room = rooms[r.nextInt(rooms.size)]
                    // A trip worth taking: start at least 15 m from the plate.
                    var start = floor.corridorPoint(0.1f + 0.8f * r.nextFloat())
                    repeat(20) { if (kotlin.math.hypot(start.first - room.plateX, start.second - room.plateY) < 15f) start = floor.corridorPoint(0.1f + 0.8f * r.nextFloat()) }
                    val (sx, sy) = start
                    val heading = if (r.nextBoolean()) E else W
                    list += Episode(
                        "proc-$split-${floor.name}-$t-${room.label}", "proc-$split-${family.name.lowercase()}",
                        { floor.build() }, BodyPose(sx, sy, heading), NavigationTarget.Room(room.label),
                        truth = NumberingTruth(family, floor.risingEast, floor.oddNorth, 0f..ProceduralFloor.CORRIDOR),
                    )
                }
            }
        }
        list
    }

    /** Multi-floor buildings per family, and trips per building. */
    private const val MF_BUILDINGS = 4
    private const val MF_TRIPS = 3

    /**
     * Trips in multi-floor buildings joined by a lift (world/MultiFloorBuilding.kt): the destination
     * usually on another floor. FLOOR_DIGIT is tuned on; OFFSET (lift labels one higher than the
     * rooms' digits) and DIGIT_IS_WING (the lift is a trap: the room is on this floor) are held out.
     * Ids say the split: mf-tune-... or mf-test-....
     */
    val multiFloor: List<Episode> by lazy {
        val list = ArrayList<Episode>()
        for (family in com.navassist.sim.world.MultiFloorFamily.entries) {
            val split = if (family == com.navassist.sim.world.MultiFloorFamily.FLOOR_DIGIT) "tune" else "test"
            for (b in 1..MF_BUILDINGS) {
                val r = kotlin.random.Random(b * 104729L + family.ordinal)
                val building = com.navassist.sim.world.MultiFloorBuilding(family, 100L + b, floors = 3 + r.nextInt(2))
                for (t in 1..MF_TRIPS) {
                    val startFloor = r.nextInt(building.floors)
                    val goalFloor = if (family == com.navassist.sim.world.MultiFloorFamily.DIGIT_IS_WING) startFloor
                        else (startFloor + 1 + r.nextInt(building.floors - 1)) % building.floors
                    val plan = building.plans[goalFloor]
                    val rooms = plan.rooms.filter { it.label.last().isDigit() && (family != com.navassist.sim.world.MultiFloorFamily.DIGIT_IS_WING || it.inWing) }
                    val room = rooms[r.nextInt(rooms.size)]
                    val (sx, sy) = building.plans[startFloor].corridorPoint(0.1f + 0.8f * r.nextFloat())
                    list += Episode(
                        "mf-$split-${building.name}-$t-f${startFloor + 1}-${room.label}", "mf-$split-${family.name.lowercase()}",
                        { building.scene(startFloor) }, BodyPose(sx, sy, if (r.nextBoolean()) E else W), NavigationTarget.Room(room.label),
                        multiFloor = building, startFloor = startFloor, goalFloor = goalFloor,
                    )
                }
            }
        }
        list
    }

    /** One trip by its exact id, from the main set, the held-out malls, the procedural or multi-floor buildings. */
    fun episodeNamed(id: String): Episode =
        (episodes + heldOut + procedural + multiFloor).firstOrNull { it.id == id }
            ?: error("no episode '$id'; see --episodes (and --episodes --filter heldout)")

    /**
     * A trip with a design, as a [com.navassist.sim.Scenario], so everything built for scenarios -
     * the live Foxglove stream, MCAP recordings, Webots, traces, pictures - works for it. Passes
     * when the run counts as a benchmark success.
     */
    fun asScenario(episode: Episode, design: String, configure: (SimSettings) -> SimSettings): com.navassist.sim.Scenario {
        val p = prepare(listOf(episode)).single()
        return com.navassist.sim.Scenario(
            name = "${episode.id}-$design",
            description = "${episode.id}: ${episode.target.description}, design $design (shortest walk ${"%.1f".format(p.shortest)} m)",
            expectation = com.navassist.sim.Expectation.KNOWN_LIMITATION,
            settings = { seed ->
                configure(
                    SimSettings(
                        episode.scene(), episode.start, target = episode.target, maxSeconds = p.maxSeconds, seed = seed,
                        building = episode.multiFloor, startFloor = episode.startFloor,
                    ),
                )
            },
            check = { result, _ ->
                val miss = p.goals.minOf { (x, y) -> hypot(result.finalBody.x - x, result.finalBody.y - y) }
                when {
                    result.outcome == Outcome.FELL -> listOf("fell")
                    result.outcome != Outcome.ARRIVED -> listOf("did not arrive (${result.outcome}), %.1f m from the nearest destination".format(miss))
                    result.finalFloor != episode.goalFloor -> listOf("arrived on floor ${result.finalFloor + 1}, the destination is on ${episode.goalFloor + 1}")
                    miss > SUCCESS_RADIUS -> listOf("arrived %.1f m from the nearest destination".format(miss))
                    else -> emptyList()
                }
            },
        )
    }

    fun select(filter: String?): List<Episode> =
        if (filter != null && "heldout" in filter) {
            heldOut.filter { filter == "heldout" || filter in it.id }
        } else if (filter != null && filter.startsWith("proc")) {
            procedural.filter { filter in it.id }
        } else if (filter != null && filter.startsWith("mf")) {
            multiFloor.filter { filter in it.id }
        } else {
            episodes.filter { filter == null || filter in it.id }
        }

    /** The places that satisfy [target] in [scene]: in front of each sign that names it. */
    fun goals(scene: Scene, target: NavigationTarget): List<Pair<Float, Float>> =
        goalSigns(scene, target).map {
            // Sign normal in plan: world z is minus north.
            (it.x + it.normalX * STANDING_OFF) to (-it.z - it.normalZ * STANDING_OFF)
        }

    /** The signs that mark a destination for [target]. */
    fun goalSigns(scene: Scene, target: NavigationTarget): List<Sign> =
        scene.signs.filter { !it.hanging && it.y > 0f && it.y < scene.ceilingHeight && names(it, target) }

    fun names(sign: Sign, target: NavigationTarget): Boolean = when (target) {
        is NavigationTarget.Room ->
            if (target.normalizedLabel == "WASHROOM") {
                sign.kind == SignKind.WASHROOM
            } else {
                sign.kind == SignKind.ROOM_PLATE && TargetMatcher.normalizeLabel(sign.text) == target.normalizedLabel
            }
        NavigationTarget.Exit -> sign.kind == SignKind.EXIT_SIGN
        NavigationTarget.Stairs -> sign.kind == SignKind.STAIR_SIGN
        NavigationTarget.Elevator -> sign.kind == SignKind.ELEVATOR
        NavigationTarget.Door -> sign.kind == SignKind.DOOR
        NavigationTarget.Explore -> false
    }

    /** An episode, checked and priced: its destinations and the shortest walk to one. */
    class Prepared(val episode: Episode, val goals: List<Pair<Float, Float>>, val shortest: Float) {
        /** Time allowed: two minutes to get going plus six seconds per metre of the shortest walk. */
        val maxSeconds: Double get() =
            (120.0 + 6.0 * shortest + if (episode.goalFloor != episode.startFloor) 60.0 else 0.0).coerceAtMost(540.0)
    }

    fun prepare(episodes: List<Episode>): List<Prepared> {
        val truths = ConcurrentHashMap<String, GroundTruth>()
        return episodes.map { e ->
            val scene = e.scene()
            val truth = truths.getOrPut(scene.name) { GroundTruth(scene) }
            val building = e.multiFloor
            if (building != null && e.goalFloor != e.startFloor) {
                // To the lift on this floor, then from the lift to the destination on its floor.
                val goalScene = building.scene(e.goalFloor)
                val goalTruth = truths.getOrPut(goalScene.name) { GroundTruth(goalScene) }
                val goals = goals(goalScene, e.target)
                require(goals.isNotEmpty()) { "${e.id}: no sign names ${e.target.description} on floor ${e.goalFloor + 1}" }
                val (lx, ly) = building.liftDoor
                val spot = listOf(lx to ly + 1.5f)
                val toLift = truth.shortestPath(e.start.x, e.start.y, spot, 1.0f) ?: error("${e.id}: no route to the lift")
                val fromLift = goalTruth.shortestPath(lx, ly + 1.5f, goals, SUCCESS_RADIUS) ?: error("${e.id}: no route from the lift")
                return@map Prepared(e, goals, toLift + fromLift)
            }
            val goals = goals(scene, e.target)
            require(goals.isNotEmpty()) { "${e.id}: no sign names ${e.target.description} on this storey" }
            val shortest = truth.shortestPath(e.start.x, e.start.y, goals, SUCCESS_RADIUS)
                ?: error("${e.id}: no walkable route from the start to ${e.target.description}")
            Prepared(e, goals, shortest)
        }
    }

    /**
     * Runs every episode on seeds 1..[seeds], in parallel, and returns one record per run.
     * [configure] lets a design variant adjust the settings (engine config, advisers) per run.
     */
    fun run(
        prepared: List<Prepared>,
        seeds: Int,
        // BENCH_THREADS caps it, for a machine short of memory.
        threads: Int = System.getenv("BENCH_THREADS")?.toIntOrNull()
            ?: max(1, Runtime.getRuntime().availableProcessors() - 1),
        configure: (SimSettings) -> SimSettings = { it },
        pictures: File? = null,
        progress: (RunRecord) -> Unit = {},
    ): List<RunRecord> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val jobs = prepared.flatMap { p -> (1..seeds).map { seed -> p to seed.toLong() } }
                .shuffled(Random(0)) // mix long and short runs across threads
                .map { (p, seed) -> Callable { runOne(p, seed, configure, pictures).also(progress) } }
            return pool.invokeAll(jobs).map { it.get() }
                .sortedWith(compareBy({ it.episode }, { it.seed }))
        } finally {
            pool.shutdown()
        }
    }

    fun runOne(p: Prepared, seed: Long, configure: (SimSettings) -> SimSettings = { it }, pictures: File? = null): RunRecord {
        val settings = configure(
            SimSettings(
                p.episode.scene(), p.episode.start, target = p.episode.target, maxSeconds = p.maxSeconds, seed = seed,
                building = p.episode.multiFloor, startFloor = p.episode.startFloor,
            ),
        )
        val sim = Simulation(settings)
        val hits = ArrayList<String>()
        var touching: Any? = null
        var located: Double? = null
        var closest = Float.MAX_VALUE
        val byState = LinkedHashMap<String, Double>()
        var lastSeconds = 0.0
        sim.listeners += { s, sample, _ ->
            closest = minOf(closest, p.goals.minOf { (x, y) -> hypot(sample.body.x - x, sample.body.y - y) })
            val state = sample.status.name + (sample.stopReason?.let { "/${it.name}" } ?: "")
            byState[state] = (byState[state] ?: 0.0) + (sample.seconds - lastSeconds)
            lastSeconds = sample.seconds
            val box = s.user.touching
            if (box != null && box !== touching) hits += "${box.name} ${box.material}"
            touching = box
            if (located == null && sample.status == NavigationStatus.NAVIGATING) located = sample.seconds
        }
        val result = sim.run()
        val miss = p.goals.minOf { (x, y) -> hypot(result.finalBody.x - x, result.finalBody.y - y) }
        val arrived = result.outcome == Outcome.ARRIVED
        val success = arrived && miss <= SUCCESS_RADIUS && result.finalFloor == p.episode.goalFloor
        if (pictures != null) {
            val verdict = if (success) "pass" else "FAIL"
            TopDownRenderer.render(
                sim, result, "${p.episode.id}  seed $seed  $verdict  (shortest ${"%.1f".format(p.shortest)} m)",
                File(pictures, "${p.episode.id}-seed$seed-$verdict.png"),
            )
        }
        return RunRecord(
            episode = p.episode.id,
            seed = seed,
            success = success,
            arrived = arrived,
            falseArrival = arrived && !success,
            fell = result.outcome == Outcome.FELL,
            collisions = result.collisions,
            seconds = result.seconds,
            walked = result.distanceWalked,
            shortest = p.shortest,
            spl = if (success) p.shortest / max(result.distanceWalked, p.shortest) else 0f,
            finalStatus = result.finalStatus.name,
            hits = hits,
            locatedSeconds = located,
            closestMeters = closest.takeIf { it < Float.MAX_VALUE },
            secondsByState = byState,
            changesPerMinute = result.instructionChangesPerMinute,
            minClearanceMeters = result.minClearanceMeters.takeIf { it < Float.MAX_VALUE },
            planner = plannerStats(sim, p.episode.truth),
        )
    }

    /** The planner's run in numbers, and its confirmed hypotheses judged against [truth]. */
    fun plannerStats(sim: Simulation, truth: NumberingTruth?): PlannerStats? {
        val e = sim.engine
        if (e.longTermPlanner == null && e.executor.history.isEmpty()) return null
        val history = e.executor.history
        val inspections = history.filter { it.skill == com.navassist.navcore.strategy.Skill.INSPECT_ROOM_LABEL || it.skill == com.navassist.navcore.strategy.Skill.INSPECT_SIGN }
        val confirmed = e.beliefs.hypotheses.filter {
            it.status == com.navassist.navcore.belief.HypothesisStatus.CONFIRMED && it.family != com.navassist.navcore.belief.HypothesisFamily.DESTINATION
        }
        var judged = 0
        var wrong = 0
        if (truth != null) for (h in confirmed) {
            val verdict = HypothesisTruth.judge(sim, h, truth) ?: continue
            judged++
            if (!verdict) wrong++
        }
        return PlannerStats(
            subgoals = history.size,
            outcomes = history.groupingBy { "${it.skill}/${it.status}" }.eachCount(),
            inspections = inspections.size,
            inspectionsRead = inspections.count { it.status == com.navassist.navcore.strategy.OutcomeStatus.SUCCEEDED },
            verified = history.any { it.skill == com.navassist.navcore.strategy.Skill.VERIFY_DESTINATION && it.status == com.navassist.navcore.strategy.OutcomeStatus.SUCCEEDED },
            rejections = e.rejections.size,
            hypothesesConfirmed = confirmed.size,
            hypothesesConfirmedWrong = if (truth != null) wrong else null,
            hypothesesJudged = judged,
        )
    }

    // ------------------------------------------------------------------ results files

    fun write(file: File, name: String, seeds: Int, prepared: List<Prepared>, runs: List<RunRecord>, design: String? = null) {
        val byEpisode = runs.groupBy { it.episode }
        file.parentFile?.mkdirs()
        file.writeText(
            json {
                "name" to name
                design?.let {
                    "design" to it
                    // Who acted on the instructions: a robot following the path, or a person.
                    "agent" to if (it.startsWith("robot-")) "robot" else "person"
                }
                gitCommit()?.let { "commit" to it }
                "seeds" to seeds
                "successRadius" to SUCCESS_RADIUS
                "summary" to summaryJson(Summary.of(runs))
                "buildings" to obj {
                    for ((building, group) in runs.groupBy { r -> prepared.first { it.episode.id == r.episode }.episode.building }) {
                        building to summaryJson(Summary.of(group))
                    }
                }
                "episodes" to arr {
                    for (p in prepared) add(obj {
                        "id" to p.episode.id
                        "building" to p.episode.building
                        "target" to p.episode.target.description
                        "start" to arr { add(p.episode.start.x); add(p.episode.start.y); add(p.episode.start.heading) }
                        "shortest" to p.shortest
                        "runs" to arr {
                            for (r in byEpisode[p.episode.id].orEmpty()) add(obj {
                                "seed" to r.seed
                                "success" to r.success
                                "arrived" to r.arrived
                                "falseArrival" to r.falseArrival
                                "fell" to r.fell
                                "collisions" to r.collisions
                                "seconds" to r.seconds
                                "walked" to r.walked
                                "spl" to r.spl
                                "finalStatus" to r.finalStatus
                                if (r.hits.isNotEmpty()) "hits" to arr { r.hits.forEach { add(it) } }
                                r.planner?.let { ps ->
                                    "planner" to obj {
                                        "subgoals" to ps.subgoals
                                        "outcomes" to obj { for ((k, v) in ps.outcomes) k to v }
                                        "inspections" to ps.inspections
                                        "inspectionsRead" to ps.inspectionsRead
                                        "verified" to ps.verified
                                        "rejections" to ps.rejections
                                        "hypothesesConfirmed" to ps.hypothesesConfirmed
                                        ps.hypothesesConfirmedWrong?.let { "hypothesesConfirmedWrong" to it }
                                        "hypothesesJudged" to ps.hypothesesJudged
                                    }
                                }
                                r.locatedSeconds?.let { "locatedSeconds" to it }
                                r.closestMeters?.let { "closestMeters" to it }
                                if (r.secondsByState.isNotEmpty()) "secondsByState" to obj { r.secondsByState.forEach { (k, v) -> k to v } }
                                r.changesPerMinute?.let { "changesPerMinute" to it }
                                r.minClearanceMeters?.let { "minClearanceMeters" to it }
                            })
                        }
                    })
                }
            } + "\n",
        )
    }

    private fun summaryJson(s: Summary) = obj {
        "runs" to s.runs
        "successRate" to s.successRate
        "spl" to s.spl
        "falseArrivalRate" to s.falseArrivalRate
        "collisionRunRate" to s.collisionRunRate
        "fallRate" to s.fallRate
        s.meanSecondsToSuccess?.let { "meanSecondsToSuccess" to it }
        s.meanChangesPerMinute?.let { "meanChangesPerMinute" to it }
    }

    /**
     * The commit the engine and simulator were built from, so a result can be reproduced; "+dirty"
     * when their sources had uncommitted changes. Null outside a git checkout.
     */
    private fun gitCommit(): String? = runCatching {
        fun git(vararg args: String): String {
            val process = ProcessBuilder("git", *args).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText().trim()
            return if (process.waitFor() == 0) out else error(out)
        }
        val head = git("rev-parse", "--short", "HEAD")
        val dirty = git("status", "--porcelain", "--", "src", "../navigation-core/src").isNotEmpty()
        if (dirty) "$head+dirty" else head
    }.getOrNull()

    /** "robot" or "person" for files that record it; null for older results. */
    @Suppress("UNCHECKED_CAST")
    fun agentOf(file: File): String? = (MiniJson.parse(file.readText()) as Map<String, Any?>)["agent"] as String?

    /** Reads a results file back as run records. */
    @Suppress("UNCHECKED_CAST")
    fun read(file: File): Pair<String, List<RunRecord>> {
        val root = MiniJson.parse(file.readText()) as Map<String, Any?>
        val runs = ArrayList<RunRecord>()
        for (e in root["episodes"] as List<Map<String, Any?>>) {
            val id = e["id"] as String
            val shortest = (e["shortest"] as Number).toFloat()
            for (r in e["runs"] as List<Map<String, Any?>>) {
                runs += RunRecord(
                    episode = id,
                    seed = (r["seed"] as Number).toLong(),
                    success = r["success"] as Boolean,
                    arrived = r["arrived"] as Boolean,
                    falseArrival = r["falseArrival"] as Boolean,
                    fell = r["fell"] as Boolean,
                    collisions = (r["collisions"] as Number).toInt(),
                    seconds = (r["seconds"] as Number).toDouble(),
                    walked = (r["walked"] as Number).toFloat(),
                    shortest = shortest,
                    spl = (r["spl"] as Number).toFloat(),
                    finalStatus = r["finalStatus"] as String,
                    hits = (r["hits"] as List<*>?)?.map { it as String }.orEmpty(),
                    locatedSeconds = (r["locatedSeconds"] as Number?)?.toDouble(),
                    changesPerMinute = (r["changesPerMinute"] as Number?)?.toDouble(),
                    minClearanceMeters = (r["minClearanceMeters"] as Number?)?.toFloat(),
                )
            }
        }
        return (root["name"] as String) to runs
    }

    // ------------------------------------------------------------------ comparing designs

    /** A paired comparison of two designs over the runs they share (same episode, same seed). */
    class Comparison(
        val baseline: String,
        val candidate: String,
        val pairs: Int,
        val baselineSummary: Summary,
        val candidateSummary: Summary,
        /** Candidate minus baseline success rate, and its 95% bootstrap interval over episodes. */
        val delta: Double,
        val low: Double,
        val high: Double,
        val gained: List<String>,
        val lost: List<String>,
        /** The same interval resampling whole buildings, when there are enough to; null otherwise. */
        val buildingInterval: Triple<Int, Double, Double>? = null,
        /** Instruction churn matters to a person being guided, not to a robot following the path. */
        private val judgeChurn: Boolean = true,
    ) {
        val verdict: String get() = when {
            candidateSummary.fallRate > baselineSummary.fallRate -> "REJECT: more falls"
            candidateSummary.falseArrivalRate > baselineSummary.falseArrivalRate + FALSE_ARRIVAL_TOLERANCE ->
                "REJECT: more false arrivals"
            moreChurn -> "REJECT: more instruction churn"
            low > 0.0 -> "IMPROVEMENT"
            high < 0.0 -> "REGRESSION"
            else -> "NO SIGNIFICANT CHANGE"
        }

        /** Instruction changes per minute up by more than [CHURN_TOLERANCE]; unknown for older results. */
        private val moreChurn: Boolean get() {
            if (!judgeChurn) return false
            val before = baselineSummary.meanChangesPerMinute ?: return false
            val after = candidateSummary.meanChangesPerMinute ?: return false
            return after > before * (1 + CHURN_TOLERANCE)
        }

        fun report(): String = buildString {
            appendLine("baseline  $baseline: ${baselineSummary.line()}")
            appendLine("candidate $candidate: ${candidateSummary.line()}")
            appendLine("paired runs: $pairs")
            appendLine("success rate change: %+.1f points (95%% CI %+.1f to %+.1f)".format(delta * 100, low * 100, high * 100))
            buildingInterval?.let { (n, lo, hi) ->
                appendLine("  resampling the %d buildings instead of trips: 95%% CI %+.1f to %+.1f".format(n, lo * 100, hi * 100))
            }
            appendLine("verdict: $verdict")
            if (gained.isNotEmpty()) appendLine("episodes that improved: ${gained.joinToString()}")
            if (lost.isNotEmpty()) appendLine("episodes that got worse: ${lost.joinToString()}")
        }
    }

    /** A design may raise the false-arrival rate by at most this much (absolute) before it is rejected. */
    const val FALSE_ARRIVAL_TOLERANCE = 0.01

    /** A design may raise instruction changes per minute by at most this fraction before it is rejected. */
    const val CHURN_TOLERANCE = 0.05

    private const val MIN_BUILDINGS_FOR_CLUSTERING = 8

    /** Which building a trip is in: a procedural or multi-floor building by its id, else the set's first word. */
    fun buildingOf(episode: String): String =
        Regex("^((proc|mf)-(tune|test)-(proc|mf)-[a-z-]+?-\\d+)-").find(episode)?.groupValues?.get(1) ?: episode.substringBefore('-')

    fun compare(
        baselineName: String,
        baseline: List<RunRecord>,
        candidateName: String,
        candidate: List<RunRecord>,
        judgeChurn: Boolean = true,
    ): Comparison {
        val a = baseline.associateBy { it.episode to it.seed }
        val b = candidate.associateBy { it.episode to it.seed }
        val keys = a.keys.intersect(b.keys)
        val paired = keys.map { a.getValue(it) to b.getValue(it) }
        // Per episode: success rate difference over the shared seeds. Episodes are the unit of
        // resampling, since seeds of one episode are not independent tests of the design.
        val perEpisode = paired.groupBy { it.first.episode }.mapValues { (_, runs) ->
            runs.count { it.second.success }.toDouble() / runs.size - runs.count { it.first.success }.toDouble() / runs.size
        }
        val diffs = perEpisode.values.toDoubleArray()
        val delta = diffs.average()
        val random = Random(12345)
        val means = DoubleArray(10_000) {
            var sum = 0.0
            repeat(diffs.size) { sum += diffs[random.nextInt(diffs.size)] }
            sum / diffs.size
        }.sorted()
        // Trips in one building are not independent either: resample buildings, each carrying all of
        // its trips, when there are enough buildings for that to mean something.
        val byBuilding = perEpisode.entries.groupBy({ buildingOf(it.key) }, { it.value })
        val buildingInterval = if (byBuilding.size >= MIN_BUILDINGS_FOR_CLUSTERING) {
            val clusters = byBuilding.values.toList()
            val r = Random(54321)
            val sorted = DoubleArray(10_000) {
                var sum = 0.0
                var count = 0
                repeat(clusters.size) { val c = clusters[r.nextInt(clusters.size)]; sum += c.sum(); count += c.size }
                sum / count
            }.sorted()
            Triple(byBuilding.size, sorted[249], sorted[9750])
        } else null
        return Comparison(
            baselineName, candidateName, paired.size,
            Summary.of(paired.map { it.first }), Summary.of(paired.map { it.second }),
            delta, means[249], means[9750],
            gained = perEpisode.filter { it.value > 0 }.keys.sorted(),
            lost = perEpisode.filter { it.value < 0 }.keys.sorted(),
            buildingInterval = buildingInterval,
            judgeChurn = judgeChurn,
        )
    }
}
