package com.navassist.navcore

import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.geometry.Vec3
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.navcore.state.NavigationStatus
import com.navassist.navcore.topology.NodeType
import com.navassist.navcore.topology.TopologicalMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Routing to a destination whose position is known.
 *
 * The behaviour under test is the three-tier fallback in NavigationEngine.goalForLocatedTarget:
 * aim straight at it, then route over the remembered graph when that stops working, then abandon
 * the sighting rather than standing still forever.
 */
class TargetRoutingTest {

    private val deviceHeight = 1.4f
    private var clock = 0L

    private fun nextTimestamp(stepMillis: Long = 33): Long {
        clock += stepMillis * 1_000_000
        return clock
    }

    private fun nowMillis() = clock / 1_000_000

    private fun pose(x: Float, z: Float, yawDegrees: Float = 0f) =
        Pose3D(x, deviceHeight, z, GeometryUtils.degreesToRadians(yawDegrees))

    private fun scanInPlace(
        engine: NavigationEngine,
        world: SyntheticWorld,
        at: Pose3D,
        steps: Int = 24,
        sweepDegrees: Float = 200f,
    ) {
        for (i in 0 until steps) {
            val t = i.toFloat() / (steps - 1)
            val yaw = GeometryUtils.degreesToRadians(-sweepDegrees / 2f + t * sweepDegrees)
            engine.updateFrame(world.frame(nextTimestamp(), Pose3D(at.x, at.y, at.z, yaw + at.yawRadians)))
        }
    }

    private fun walk(
        engine: NavigationEngine,
        world: SyntheticWorld,
        from: Pose3D,
        to: Pose3D,
        steps: Int,
    ): List<NavigationSnapshot> = (1..steps).map { i ->
        val t = i.toFloat() / steps
        val here = Pose3D(
            from.x + (to.x - from.x) * t,
            deviceHeight,
            from.z + (to.z - from.z) * t,
            from.yawRadians + GeometryUtils.angleDifference(from.yawRadians, to.yawRadians) * t,
        )
        engine.updateFrame(world.frame(nextTimestamp(), here))
    }

    /** Holds position while frames keep arriving, e.g. a user who has stopped because of STOP. */
    private fun hold(
        engine: NavigationEngine,
        world: SyntheticWorld,
        at: Pose3D,
        millis: Long,
    ): List<NavigationSnapshot> {
        val snapshots = ArrayList<NavigationSnapshot>()
        val until = nowMillis() + millis
        while (nowMillis() < until) {
            snapshots.add(engine.updateFrame(world.frame(nextTimestamp(), at)))
        }
        return snapshots
    }

    // ================================================================= tier 1

    @Test
    fun `a reachable sighting is approached directly`() {
        val world = SyntheticWorld.corridor(halfWidth = 1.0f, fromZ = -1f, toZ = 14f)
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Room("314"))
        scanInPlace(engine, world, pose(0f, 0f))

        engine.submitSemanticObservations(
            listOf(SemanticObservation.Room("314", 0.9f, worldPosition = Vec3(0f, 1.2f, 4f))),
            nowMillis(),
            pose(0f, 0f),
        )
        val snapshots = walk(engine, world, pose(0f, 0f), pose(0f, 0.5f), steps = 10)

        assertEquals(NavigationStatus.NAVIGATING, snapshots.last().status)
        assertTrue(engine.currentPathWorld().size >= 2, "a direct route should exist")
    }

    // ================================================================= tier 3 (the latch)

    /**
     * Regression test for the latch.
     *
     * A sighting that cannot be routed to used to pin the engine forever: it suppressed frontier
     * selection, the state machine could not fire TargetLost because the sighting never cleared,
     * and the sighting never cleared because it was only aged out when NEW observations arrived -
     * which they never do while the user is stuck facing a wall. The result was NO_ROUTE / SCAN
     * until the app restarted the search.
     */
    @Test
    fun `an unreachable sighting is abandoned so exploration can resume`() {
        val world = SyntheticWorld.corridor(halfWidth = 1.0f, fromZ = -1f, toZ = 14f)
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Room("314"))
        scanInPlace(engine, world, pose(0f, 0f))
        walk(engine, world, pose(0f, 0f), pose(0f, 1f), steps = 12)

        // Perception resolves the plate to a point outside the corridor entirely - through the
        // side wall. Depth association errors and reflections produce exactly this.
        engine.submitSemanticObservations(
            listOf(SemanticObservation.Room("314", 0.95f, worldPosition = Vec3(6f, 1.2f, 3f))),
            nowMillis(),
            pose(0f, 1f),
        )

        val stuck = hold(engine, world, pose(0f, 1f), millis = 1_000)
        assertTrue(
            stuck.any { it.status == NavigationStatus.NAVIGATING || it.status == NavigationStatus.NO_ROUTE },
            "the engine should at least try to reach it first",
        )

        // Keep the frames coming, but no new observations - the stuck case.
        val after = hold(engine, world, pose(0f, 1f), millis = 6_000)

        assertEquals(
            NavigationStatus.EXPLORING,
            after.last().status,
            "an unreachable sighting must be given up so exploration resumes",
        )
        assertTrue(
            after.takeLast(20).none { it.status == NavigationStatus.NO_ROUTE },
            "the engine must not settle back into NO_ROUTE",
        )
        // The destination the user asked for is unchanged - only the bad sighting was dropped.
        assertEquals("Room 314", after.last().targetDescription)
    }

    @Test
    fun `a stale sighting ages out without any new observations arriving`() {
        val world = SyntheticWorld.corridor(halfWidth = 1.0f, fromZ = -1f, toZ = 14f)
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Room("314"))
        scanInPlace(engine, world, pose(0f, 0f))
        engine.submitSemanticObservations(
            listOf(SemanticObservation.Room("314", 0.9f, worldPosition = Vec3(0f, 1.2f, 3f))),
            nowMillis(),
            pose(0f, 0f),
        )
        engine.updateFrame(world.frame(nextTimestamp(), pose(0f, 0f)))
        assertEquals(NavigationStatus.NAVIGATING, engine.status)

        // Pruning used to happen only inside submitSemanticObservations, so a sighting could
        // outlive its validity indefinitely on a silent perception channel.
        hold(engine, world, pose(0f, 0f), millis = NavigationConfig().semanticMaxAgeMillis * 4 + 2_000)

        assertTrue(
            engine.status != NavigationStatus.NAVIGATING,
            "a sighting older than its lifetime must stop driving navigation (was ${engine.status})",
        )
    }

    // ================================================================= tier 2 (graph routing)

    /**
     * An L-shaped floor: a long leg running along +Z, opening at the top into a cross corridor
     * running along +X.
     *
     *            z=15 ┌──────────────────────┐
     *            z=13 └───┐   cross corridor │
     *                     │                  │
     *                 leg │                  │
     *                 z=0 │ user starts here │
     */
    private fun lShapedFloor() = SyntheticWorld(
        listOf(
            Vec2(-1f, -1f) to Vec2(-1f, 15f),   // outer wall of the leg, continues along the cross
            Vec2(1f, -1f) to Vec2(1f, 13f),     // inner wall of the leg
            Vec2(-1f, -1f) to Vec2(1f, -1f),    // bottom of the leg
            Vec2(-1f, 15f) to Vec2(13f, 15f),   // far side of the cross corridor
            Vec2(1f, 13f) to Vec2(13f, 13f),    // near side of the cross corridor
            Vec2(13f, 13f) to Vec2(13f, 15f),   // end of the cross corridor
        ),
    )

    @Test
    fun `a destination around a corner and out of range is routed over the walked graph`() {
        val world = lShapedFloor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Room("314"))

        // Walk the whole leg, then well along the cross corridor, dropping breadcrumbs.
        scanInPlace(engine, world, pose(0f, 0f))
        walk(engine, world, pose(0f, 0f), pose(0f, 12f), steps = 60)
        scanInPlace(engine, world, pose(0f, 12f, 45f), steps = 16, sweepDegrees = 160f)
        walk(engine, world, pose(0f, 12f), pose(9f, 14f, 90f), steps = 50)
        scanInPlace(engine, world, pose(9f, 14f, 90f), steps = 16, sweepDegrees = 160f)

        val breadcrumbs = engine.topology.nodes.count { it.type != NodeType.ROOM }
        assertTrue(breadcrumbs >= 4, "the walk should have left a usable trail, got $breadcrumbs")

        // The room is back at the bottom of the leg: around the corner AND outside the 12 m
        // window. The straight-line bearing from here points through the corridor wall.
        engine.submitSemanticObservations(
            listOf(SemanticObservation.Room("314", 0.95f, worldPosition = Vec3(0f, 1.2f, 1f))),
            nowMillis(),
            pose(9f, 14f, 90f),
        )

        val snapshots = hold(engine, world, pose(9f, 14f, 90f), millis = 2_500)

        // It must recover: a route exists again, and it is not parked in NO_ROUTE.
        assertTrue(
            snapshots.takeLast(10).none { it.status == NavigationStatus.NO_ROUTE },
            "graph routing should have produced a reachable hop: ${snapshots.last()}",
        )
        val path = engine.currentPathWorld()
        assertTrue(path.size >= 2, "expected a plan back towards the corridor, got $path")

        // And the plan heads back the way we came (towards smaller x), not at the wall.
        val heading = path.last() - path.first()
        assertTrue(
            heading.x < 0.5f,
            "the route should lead back along the cross corridor, not further from it: $heading",
        )

        // The decisive check: the goal is a place we have WALKED, not the straight-line bearing
        // to the room. The bearing from (9,14) to (0,1) leaves the corridor through its wall, so
        // a direct-only engine cannot produce a waypoint sitting on the breadcrumb trail.
        val nearestBreadcrumb = engine.topology.nodes
            .filter { it.type != NodeType.ROOM }
            .minOf { it.position.distanceTo(path.last()) }
        assertTrue(
            nearestBreadcrumb < 1.5f,
            "the waypoint should be a remembered place, but the nearest is ${nearestBreadcrumb}m away",
        )
    }

    // ================================================================= graph hygiene

    @Test
    fun `a landmark seen from afar does not invent a corridor to itself`() {
        val world = SyntheticWorld.corridor(halfWidth = 1.0f, fromZ = -1f, toZ = 14f)
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start(NavigationTarget.Room("314"))
        scanInPlace(engine, world, pose(0f, 0f))
        walk(engine, world, pose(0f, 0f), pose(0f, 1f), steps = 10)

        engine.submitSemanticObservations(
            listOf(SemanticObservation.Room("314", 0.9f, worldPosition = Vec3(8f, 1.2f, 9f))),
            nowMillis(),
            pose(0f, 1f),
        )

        val landmark = engine.topology.nodes.firstOrNull { it.semanticLabels.contains("314") }
        assertNotNull(landmark, "the sighting should still be remembered as a place")
        assertTrue(
            engine.topology.edgesFrom(landmark.id).isEmpty(),
            "seeing a door plate is not evidence of a walkable corridor to it",
        )
    }

    @Test
    fun `nearest connected node skips edgeless landmarks`() {
        val topology = TopologicalMap()
        val walked = topology.addOrMerge(Vec2(0f, 0f), NodeType.WAYPOINT, 1.6f, 0)
        val alsoWalked = topology.addOrMerge(Vec2(0f, 4f), NodeType.WAYPOINT, 1.6f, 0)
        topology.connect(walked.id, alsoWalked.id, 4f)
        val landmark = topology.addOrMerge(Vec2(0f, 9f), NodeType.ROOM, 1.6f, 0)

        // The landmark is nearest in raw distance...
        assertEquals(landmark.id, topology.findNearest(Vec2(0f, 9.5f), 10f)?.id)
        // ...but routing must start from somewhere we have actually been.
        assertEquals(alsoWalked.id, topology.findNearestConnected(Vec2(0f, 9.5f), 10f)?.id)
        assertNull(topology.findNearestConnected(Vec2(0f, 9.5f), 1f), "nothing connected in range")
    }
}
