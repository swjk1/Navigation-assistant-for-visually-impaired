package com.navassist.navcore

import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec3
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.navcore.state.NavigationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests of the whole core pipeline:
 *
 *   synthetic NavigationFrame -> occupancy update -> frontier detection -> A* -> NavigationCommand
 *
 * No ARCore session, no Android context, no Expo module is created. These same tests will verify
 * the iOS adapter's behaviour once it feeds the same NavigationFrame type.
 */
class NavigationEngineScenarioTest {

    private val deviceHeight = 1.4f
    private var clock = 0L

    private fun nextTimestamp(stepMillis: Long = 100): Long {
        clock += stepMillis * 1_000_000
        return clock
    }

    private fun pose(x: Float, z: Float, yawDegrees: Float = 0f) =
        Pose3D(x, deviceHeight, z, GeometryUtils.degreesToRadians(yawDegrees))

    /** Stands still and sweeps the phone across [sweepDegrees], the way a user would on start. */
    private fun scanInPlace(
        engine: NavigationEngine,
        world: SyntheticWorld,
        at: Pose3D,
        steps: Int = 24,
        sweepDegrees: Float = 150f,
    ): NavigationSnapshot {
        var snapshot: NavigationSnapshot? = null
        for (i in 0 until steps) {
            val t = i.toFloat() / (steps - 1)
            val yaw = GeometryUtils.degreesToRadians(-sweepDegrees / 2f + t * sweepDegrees)
            val scanning = Pose3D(at.x, at.y, at.z, yaw + at.yawRadians)
            snapshot = engine.updateFrame(world.frame(nextTimestamp(), scanning))
        }
        return snapshot!!
    }

    private fun walk(
        engine: NavigationEngine,
        world: SyntheticWorld,
        from: Pose3D,
        to: Pose3D,
        steps: Int,
    ): List<NavigationSnapshot> {
        val snapshots = ArrayList<NavigationSnapshot>()
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val here = Pose3D(
                from.x + (to.x - from.x) * t,
                deviceHeight,
                from.z + (to.z - from.z) * t,
                from.yawRadians + GeometryUtils.angleDifference(from.yawRadians, to.yawRadians) * t,
            )
            snapshots.add(engine.updateFrame(world.frame(nextTimestamp(), here)))
        }
        return snapshots
    }

    // ================================================================= Test A / scenario 1

    /** Spec scenario 1 + demo test A: unfamiliar corridor, map it, pick the frontier, walk on. */
    @Test
    fun `straight corridor - the engine maps, finds the frontier ahead and says STRAIGHT`() {
        val world = SyntheticWorld.corridor(halfWidth = 1.0f, fromZ = -1f, toZ = 14f)
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)

        // The very first frame cannot possibly know anything: it must not say "walk".
        val first = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertTrue(
            first.command == NavigationCommand.SCAN || first.command == NavigationCommand.STOP,
            "the first frame produced ${first.command}",
        )

        scanInPlace(engine, world, pose(0f, 0f))
        val settled = walk(engine, world, pose(0f, 0f), pose(0f, 0.6f), steps = 12)

        val status = settled.last().status
        assertEquals(NavigationStatus.EXPLORING, status, "should be exploring by now: ${settled.last()}")
        assertTrue(engine.frontiers().isNotEmpty(), "an open corridor must expose a frontier")
        assertTrue(engine.occupancyGrid().stats().occupied > 0, "the corridor walls must be mapped")
        assertTrue(engine.occupancyGrid().stats().free > 0, "ray integration must record free space")

        // Test B: the walls are occupied and the planned path never crosses them.
        val inflated = engine.inflatedGrid()
        assertNotNull(inflated)
        for (point in engine.currentPathWorld()) {
            assertTrue(
                !inflated.isBlocked(inflated.worldToGrid(point)),
                "planned path point $point crosses an obstacle",
            )
        }

        // Test C: it commits to a frontier and steers towards it rather than standing still.
        assertNotNull(settled.last().selectedFrontierId, "a frontier should have been selected")
        val commands = settled.map { it.command }.toSet()
        assertTrue(
            commands.any { it == NavigationCommand.STRAIGHT || it == NavigationCommand.TURN_LEFT || it == NavigationCommand.TURN_RIGHT },
            "expected movement guidance, got $commands",
        )
    }

    // ================================================================= Test G

    @Test
    fun `losing tracking stops immediately`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)
        scanInPlace(engine, world, pose(0f, 0f))
        walk(engine, world, pose(0f, 0f), pose(0f, 0.5f), steps = 10)

        val lost = engine.updateFrame(
            NavigationFrame(
                timestampNanos = nextTimestamp(),
                pose = pose(0f, 0.5f),
                points = DepthPointCloud.EMPTY,
                trackingConfidence = 0f,
                tracking = false,
                depthAvailable = false,
            ),
        )
        assertEquals(NavigationStatus.LOST_TRACKING, lost.status)
        assertEquals(NavigationCommand.STOP, lost.command, "tracking loss must stop the user at once")
    }

    @Test
    fun `depth starvation stops the user and asks for a scan`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)
        scanInPlace(engine, world, pose(0f, 0f))

        // Tracking is fine but depth has dried up for longer than the configured tolerance.
        var snapshot: NavigationSnapshot? = null
        repeat(30) {
            snapshot = engine.updateFrame(
                NavigationFrame(
                    timestampNanos = nextTimestamp(),
                    pose = pose(0f, 0.5f),
                    points = DepthPointCloud.EMPTY,
                    trackingConfidence = 1f,
                    tracking = true,
                    depthAvailable = false,
                ),
            )
        }
        assertEquals(
            NavigationCommand.SCAN,
            snapshot!!.command,
            "no depth for a second and a half must not leave the user walking",
        )
    }

    // ================================================================= Test F

    /** Demo test F: a localized sighting of the destination flips EXPLORING -> NAVIGATING. */
    @Test
    fun `a localized room sighting switches from exploring to navigating`() {
        val world = SyntheticWorld.corridor(halfWidth = 1.0f, fromZ = -1f, toZ = 14f)
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Room("314"))
        scanInPlace(engine, world, pose(0f, 0f))
        walk(engine, world, pose(0f, 0f), pose(0f, 0.5f), steps = 10)
        assertEquals(NavigationStatus.EXPLORING, engine.status)

        engine.submitSemanticObservations(
            listOf(
                SemanticObservation.Room(
                    label = "314",
                    confidence = 0.92f,
                    worldPosition = Vec3(0.5f, 1.2f, 4.0f),
                ),
            ),
            nowMillis = clock / 1_000_000,
            observerPose = pose(0f, 0.5f),
        )

        val after = walk(engine, world, pose(0f, 0.5f), pose(0f, 1.0f), steps = 6)
        assertEquals(
            NavigationStatus.NAVIGATING,
            after.last().status,
            "a resolved destination must take over from exploration",
        )
        assertEquals("Room 314", after.last().targetDescription)
        assertNotNull(after.last().distanceToTargetMeters)
    }

    @Test
    fun `arriving at the destination reports ARRIVED`() {
        val world = SyntheticWorld.corridor(halfWidth = 1.0f, fromZ = -1f, toZ = 14f)
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Room("314"))
        scanInPlace(engine, world, pose(0f, 0f))

        // The room is essentially where the user already is.
        engine.submitSemanticObservations(
            listOf(SemanticObservation.Room("314", 0.95f, worldPosition = Vec3(0.2f, 1.2f, 0.3f))),
            nowMillis = clock / 1_000_000,
            observerPose = pose(0f, 0f),
        )

        var snapshot: NavigationSnapshot? = null
        repeat(12) { snapshot = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f))) }

        assertEquals(NavigationStatus.ARRIVED, snapshot!!.status)
        assertEquals(NavigationCommand.ARRIVED, snapshot!!.command)
    }

    // ================================================================= Test E / scenario 3

    /** Demo test E: a "rooms 300-349 ->" sign must bias the engine towards the right branch. */
    @Test
    fun `a room range sign biases frontier selection towards the signed side`() {
        // A T junction: corridor along +Z opening left and right at z = 5.
        val world = SyntheticWorld(
            listOf(
                com.navassist.navcore.geometry.Vec2(-1f, -1f) to com.navassist.navcore.geometry.Vec2(-1f, 5f),
                com.navassist.navcore.geometry.Vec2(1f, -1f) to com.navassist.navcore.geometry.Vec2(1f, 5f),
                com.navassist.navcore.geometry.Vec2(-1f, -1f) to com.navassist.navcore.geometry.Vec2(1f, -1f),
                com.navassist.navcore.geometry.Vec2(-6f, 7f) to com.navassist.navcore.geometry.Vec2(6f, 7f),
                com.navassist.navcore.geometry.Vec2(-6f, 5f) to com.navassist.navcore.geometry.Vec2(-1f, 5f),
                com.navassist.navcore.geometry.Vec2(1f, 5f) to com.navassist.navcore.geometry.Vec2(6f, 5f),
            ),
        )

        // Returns (best score among left-hand frontiers, best score among right-hand ones).
        fun explore(withSign: Boolean): Pair<Float, Float> {
            clock = 0
            val engine = NavigationEngine(TestSupport.fastScanConfig)
            val target = NavigationTarget.Room("314")
            engine.start(target)
            scanInPlace(engine, world, pose(0f, 4f))
            walk(engine, world, pose(0f, 4f), pose(0f, 5.8f), steps = 20)
            scanInPlace(engine, world, pose(0f, 5.8f))
            if (withSign) {
                engine.submitSemanticObservations(
                    listOf(
                        SemanticObservation.RoomRange(
                            min = 300,
                            max = 349,
                            direction = com.navassist.navcore.semantic.SemanticDirection.RIGHT,
                            confidence = 0.95f,
                        ),
                    ),
                    nowMillis = clock / 1_000_000,
                    observerPose = pose(0f, 5.8f),
                )
            }
            walk(engine, world, pose(0f, 5.8f), pose(0f, 6f), steps = 6)
            // Positive x is to the right of a user facing +Z.
            val frontiers = engine.frontiers()
            val left = frontiers.filter { it.centroid.x < -1f }.maxOfOrNull { it.score }
            val right = frontiers.filter { it.centroid.x > 1f }.maxOfOrNull { it.score }
            assertNotNull(left, "the junction should expose a left-hand frontier: $frontiers")
            assertNotNull(right, "the junction should expose a right-hand frontier: $frontiers")
            return left to right
        }

        // Compare the RIGHT-minus-LEFT preference with and without the sign. Comparing absolute
        // winners would be at the mercy of tiny asymmetries in how the synthetic sweep happened to
        // cover each side; the shift in preference is what the semantic layer is supposed to cause.
        val (leftPlain, rightPlain) = explore(withSign = false)
        val (leftSigned, rightSigned) = explore(withSign = true)

        val preferenceWithout = rightPlain - leftPlain
        val preferenceWith = rightSigned - leftSigned
        assertTrue(
            preferenceWith > preferenceWithout,
            "the sign must shift preference right: without=$preferenceWithout with=$preferenceWith",
        )
        assertTrue(
            rightSigned > leftSigned,
            "with the sign the right-hand branch should win outright ($rightSigned vs $leftSigned)",
        )
    }

    // ================================================================= scenario 4

    /** Spec scenario 4 + demo test D: a dead end must not be offered again. */
    @Test
    fun `a dead end is recognised and the engine turns back`() {
        val world = SyntheticWorld.deadEndCorridor(halfWidth = 1.0f, endZ = 3.5f)
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)

        // Walk the full length of the cul-de-sac, sweeping as we go.
        scanInPlace(engine, world, pose(0f, 0f), steps = 40, sweepDegrees = 340f)
        walk(engine, world, pose(0f, 0f), pose(0f, 2.5f), steps = 20)
        scanInPlace(engine, world, pose(0f, 2.5f), steps = 40, sweepDegrees = 340f)
        val snapshots = walk(engine, world, pose(0f, 2.5f), pose(0f, 2.6f), steps = 10)

        val last = snapshots.last()
        assertTrue(engine.frontiers().isEmpty(), "a closed room has nothing left to explore")
        assertTrue(
            last.status == NavigationStatus.BACKTRACKING || last.status == NavigationStatus.NO_ROUTE,
            "expected the engine to give up on this branch, got ${last.status}",
        )
        assertTrue(
            last.command != NavigationCommand.STRAIGHT ||
                last.status == NavigationStatus.BACKTRACKING,
            "must not keep pushing the user into the dead end",
        )
    }

    // ================================================================= lifecycle

    @Test
    fun `an unstarted engine never issues a movement command`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        val snapshot = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertEquals(NavigationStatus.IDLE, snapshot.status)
        assertEquals(NavigationCommand.STOP, snapshot.command)
    }

    @Test
    fun `pausing stops the user and resuming re-localizes`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)
        scanInPlace(engine, world, pose(0f, 0f))

        engine.pause()
        val paused = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertEquals(NavigationStatus.PAUSED, paused.status)
        assertEquals(NavigationCommand.STOP, paused.command)

        engine.resume()
        val resumed = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertTrue(resumed.status != NavigationStatus.PAUSED)
    }

    @Test
    fun `changing the destination restarts the search`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Room("314"))
        scanInPlace(engine, world, pose(0f, 0f))
        engine.submitSemanticObservations(
            listOf(SemanticObservation.Room("314", 0.9f, worldPosition = Vec3(0.5f, 1.2f, 3f))),
            nowMillis = clock / 1_000_000,
            observerPose = pose(0f, 0f),
        )
        engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertEquals(NavigationStatus.NAVIGATING, engine.status)

        engine.setTarget(NavigationTarget.Exit)
        val after = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertTrue(after.status != NavigationStatus.NAVIGATING, "old sighting must not steer a new search")
        assertEquals("Exit", after.targetDescription)
    }

    /**
     * The guidance layer prefixes `target` to every spoken instruction ("Room 314. Straight."),
     * so a placeholder destination would have it announce "Explore. Straight." forever.
     */
    @Test
    fun `exploring reports no destination for the guidance layer to announce`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)
        val exploring = scanInPlace(engine, world, pose(0f, 0f))
        assertEquals(null, exploring.targetDescription)

        engine.setTarget(NavigationTarget.Room("314"))
        val searching = engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertEquals("Room 314", searching.targetDescription)
    }

    @Test
    fun `the snapshot carries debug counters for the UI`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Explore)
        val snapshot = scanInPlace(engine, world, pose(0f, 0f))
        val debug = snapshot.debug
        assertNotNull(debug)
        assertTrue(debug.freeCells > 0)
        assertTrue(debug.occupiedCells > 0)
        assertTrue(debug.unknownCells > 0)
        assertTrue(debug.depthPointsLastFrame > 0)
        assertTrue(debug.floorConfidence > 0f)
    }

    /**
     * A thin map clears the confidence threshold within about a second, which used to be enough
     * to start issuing turns. An instruction built on that little evidence is indistinguishable
     * to the user from a considered one.
     */
    @Test
    fun `no movement is instructed until the scan window has elapsed`() {
        val world = SyntheticWorld.corridor(halfWidth = 1.0f, fromZ = -1f, toZ = 14f)
        val engine = NavigationEngine(NavigationConfig())
        engine.start(NavigationTarget.Explore)

        // Sweep the way a user would. Two seconds of it: plenty of map, nowhere near the budget.
        var sweep = 0.0
        fun sweepFrame(): NavigationSnapshot {
            sweep += 0.06
            val yaw = (kotlin.math.sin(sweep) * 70.0).toFloat()
            return engine.updateFrame(world.frame(nextTimestamp(33), pose(0f, 0f, yaw)))
        }

        val early = ArrayList<NavigationSnapshot>()
        repeat(60) { early.add(sweepFrame()) }

        assertTrue(
            early.all { it.command == NavigationCommand.SCAN || it.command == NavigationCommand.STOP },
            "must not steer this early: ${early.map { it.command }.toSet()}",
        )
        assertEquals(NavigationStatus.LOCALIZING, early.last().status)
        val progress = early.last().debug?.scanProgress ?: 0f
        assertTrue(progress > 0f && progress < 1f, "scan should be part-way, was $progress")

        // Carry on past the configured budget.
        var snapshot: NavigationSnapshot? = null
        repeat(400) { snapshot = sweepFrame() }

        assertEquals(1f, snapshot!!.debug?.scanProgress)
        assertEquals(
            NavigationStatus.EXPLORING,
            snapshot!!.status,
            "after a full scan it should be ready to move",
        )
    }

    @Test
    fun `time without depth does not count towards the scan budget`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(NavigationConfig())
        engine.start(NavigationTarget.Explore)
        scanInPlace(engine, world, pose(0f, 0f), steps = 8)
        val before = engine.snapshot.debug?.scanProgress ?: 0f

        // Tracking fine, depth dead, for well over the scan window.
        repeat(400) {
            engine.updateFrame(
                NavigationFrame(
                    timestampNanos = nextTimestamp(33),
                    pose = pose(0f, 0f),
                    points = DepthPointCloud.EMPTY,
                    trackingConfidence = 1f,
                    tracking = true,
                    depthAvailable = false,
                ),
            )
        }
        val after = engine.snapshot.debug?.scanProgress ?: 0f
        // Depth updates more slowly than the camera, so the latest depth image stays "live" for a
        // short grace period. Beyond that, a dead sensor must not be mistaken for scanning.
        val config = NavigationConfig()
        val grace = config.scanDepthFreshMillis.toFloat() / config.minScanMillis
        assertTrue(
            after - before <= grace + 1e-3f,
            "a dead depth sensor must not be mistaken for scanning: $before -> $after",
        )
    }
}
