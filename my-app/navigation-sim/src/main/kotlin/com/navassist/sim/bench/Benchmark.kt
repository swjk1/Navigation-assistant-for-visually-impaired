package com.navassist.sim.bench

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
        val maxSeconds: Double get() = (120.0 + 6.0 * shortest).coerceAtMost(480.0)
    }

    fun prepare(episodes: List<Episode>): List<Prepared> {
        val truths = ConcurrentHashMap<String, GroundTruth>()
        return episodes.map { e ->
            val scene = e.scene()
            val truth = truths.getOrPut(scene.name) { GroundTruth(scene) }
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
            SimSettings(p.episode.scene(), p.episode.start, target = p.episode.target, maxSeconds = p.maxSeconds, seed = seed),
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
        val success = arrived && miss <= SUCCESS_RADIUS
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
        )
    }

    // ------------------------------------------------------------------ results files

    fun write(file: File, name: String, seeds: Int, prepared: List<Prepared>, runs: List<RunRecord>) {
        val byEpisode = runs.groupBy { it.episode }
        file.parentFile?.mkdirs()
        file.writeText(
            json {
                "name" to name
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
            val before = baselineSummary.meanChangesPerMinute ?: return false
            val after = candidateSummary.meanChangesPerMinute ?: return false
            return after > before * (1 + CHURN_TOLERANCE)
        }

        fun report(): String = buildString {
            appendLine("baseline  $baseline: ${baselineSummary.line()}")
            appendLine("candidate $candidate: ${candidateSummary.line()}")
            appendLine("paired runs: $pairs")
            appendLine("success rate change: %+.1f points (95%% CI %+.1f to %+.1f)".format(delta * 100, low * 100, high * 100))
            appendLine("verdict: $verdict")
            if (gained.isNotEmpty()) appendLine("episodes that improved: ${gained.joinToString()}")
            if (lost.isNotEmpty()) appendLine("episodes that got worse: ${lost.joinToString()}")
        }
    }

    /** A design may raise the false-arrival rate by at most this much (absolute) before it is rejected. */
    const val FALSE_ARRIVAL_TOLERANCE = 0.01

    /** A design may raise instruction changes per minute by at most this fraction before it is rejected. */
    const val CHURN_TOLERANCE = 0.05

    fun compare(baselineName: String, baseline: List<RunRecord>, candidateName: String, candidate: List<RunRecord>): Comparison {
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
        return Comparison(
            baselineName, candidateName, paired.size,
            Summary.of(paired.map { it.first }), Summary.of(paired.map { it.second }),
            delta, means[249], means[9750],
            gained = perEpisode.filter { it.value > 0 }.keys.sorted(),
            lost = perEpisode.filter { it.value < 0 }.keys.sorted(),
        )
    }
}
