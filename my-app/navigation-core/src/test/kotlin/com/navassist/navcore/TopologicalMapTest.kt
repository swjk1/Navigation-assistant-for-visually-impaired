package com.navassist.navcore

import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.topology.NodeType
import com.navassist.navcore.topology.TopologicalMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopologicalMapTest {

    private fun map() = TopologicalMap()

    @Test
    fun `a node close to an existing one merges instead of duplicating`() {
        val topology = map()
        val first = topology.addOrMerge(Vec2(0f, 0f), NodeType.WAYPOINT, mergeRadiusMeters = 1.6f, nowMillis = 0)
        val second = topology.addOrMerge(Vec2(0.5f, 0.3f), NodeType.WAYPOINT, mergeRadiusMeters = 1.6f, nowMillis = 10)

        assertEquals(first.id, second.id, "revisiting the same spot must not create a second node")
        assertEquals(1, topology.nodeCount)
        assertEquals(2, second.visitedCount)
    }

    @Test
    fun `a node beyond the merge radius is a new place`() {
        val topology = map()
        topology.addOrMerge(Vec2(0f, 0f), NodeType.WAYPOINT, 1.6f, 0)
        topology.addOrMerge(Vec2(5f, 0f), NodeType.WAYPOINT, 1.6f, 10)
        assertEquals(2, topology.nodeCount)
    }

    @Test
    fun `merging never downgrades a junction to a plain waypoint`() {
        val topology = map()
        topology.addOrMerge(Vec2(0f, 0f), NodeType.JUNCTION, 1.6f, 0)
        val merged = topology.addOrMerge(Vec2(0.2f, 0f), NodeType.WAYPOINT, 1.6f, 10)
        assertEquals(NodeType.JUNCTION, merged.type)
    }

    @Test
    fun `graph routing finds the way back to an earlier junction`() {
        val topology = map()
        val entrance = topology.addOrMerge(Vec2(0f, 0f), NodeType.WAYPOINT, 1.6f, 0)
        val junction = topology.addOrMerge(Vec2(0f, 5f), NodeType.JUNCTION, 1.6f, 0)
        val deadEndA = topology.addOrMerge(Vec2(-5f, 5f), NodeType.DEAD_END, 1.6f, 0)
        val branchB = topology.addOrMerge(Vec2(5f, 5f), NodeType.WAYPOINT, 1.6f, 0)

        topology.connect(entrance.id, junction.id, 5f)
        topology.connect(junction.id, deadEndA.id, 5f)
        topology.connect(junction.id, branchB.id, 5f)

        val route = topology.planRoute(deadEndA.id, branchB.id)
        assertNotNull(route)
        assertEquals(listOf(deadEndA.id, junction.id, branchB.id), route.map { it.id })
    }

    @Test
    fun `routing prefers the shorter of two remembered ways round`() {
        val topology = map()
        val a = topology.addOrMerge(Vec2(0f, 0f), NodeType.WAYPOINT, 1.6f, 0)
        val shortHop = topology.addOrMerge(Vec2(0f, 4f), NodeType.WAYPOINT, 1.6f, 0)
        val detour1 = topology.addOrMerge(Vec2(10f, 0f), NodeType.WAYPOINT, 1.6f, 0)
        val detour2 = topology.addOrMerge(Vec2(10f, 4f), NodeType.WAYPOINT, 1.6f, 0)

        topology.connect(a.id, shortHop.id, 4f)
        topology.connect(a.id, detour1.id, 10f)
        topology.connect(detour1.id, detour2.id, 4f)
        topology.connect(detour2.id, shortHop.id, 10f)

        val route = topology.planRoute(a.id, shortHop.id)
        assertNotNull(route)
        assertEquals(2, route.size, "should take the direct hop: ${route.map { it.id }}")
    }

    @Test
    fun `a blocked edge is avoided`() {
        val topology = map()
        val a = topology.addOrMerge(Vec2(0f, 0f), NodeType.WAYPOINT, 1.6f, 0)
        val b = topology.addOrMerge(Vec2(0f, 4f), NodeType.WAYPOINT, 1.6f, 0)
        topology.connect(a.id, b.id, 4f)
        assertNotNull(topology.planRoute(a.id, b.id))

        topology.markEdgeBlocked(a.id, b.id)
        assertNull(topology.planRoute(a.id, b.id), "a corridor we could not traverse must not be routed through")
    }

    @Test
    fun `exhausted junctions drop out of the backtracking candidates`() {
        val topology = map()
        val open = topology.addOrMerge(Vec2(0f, 3f), NodeType.JUNCTION, 1.6f, 0)
        val closed = topology.addOrMerge(Vec2(0f, -3f), NodeType.JUNCTION, 1.6f, 0)
        assertEquals(2, topology.openJunctions(Vec2(0f, 0f)).size)

        topology.markExhausted(closed.id)
        val remaining = topology.openJunctions(Vec2(0f, 0f))
        assertEquals(1, remaining.size)
        assertEquals(open.id, remaining.first().id)
    }

    @Test
    fun `revisit density rises with how often an area was walked`() {
        val topology = map()
        val fresh = topology.revisitDensity(Vec2(0f, 0f), 2.5f)
        assertEquals(0f, fresh)

        repeat(3) { topology.addOrMerge(Vec2(0f, 0f), NodeType.WAYPOINT, 1.6f, 0) }
        val walked = topology.revisitDensity(Vec2(0f, 0f), 2.5f)
        assertTrue(walked > 0f, "walked=$walked")
        assertTrue(topology.revisitDensity(Vec2(20f, 20f), 2.5f) == 0f, "far away is still fresh")
    }

    @Test
    fun `a dead end counts as heavily visited so exploration looks elsewhere`() {
        val topology = map()
        topology.addOrMerge(Vec2(0f, 0f), NodeType.WAYPOINT, 1.6f, 0)
        val plain = topology.revisitDensity(Vec2(0f, 0f), 2.5f)
        topology.addOrMerge(Vec2(0f, 0f), NodeType.DEAD_END, 1.6f, 10)
        assertTrue(topology.revisitDensity(Vec2(0f, 0f), 2.5f) > plain)
    }

    @Test
    fun `nodes on different floors are kept apart and joined only by a vertical edge`() {
        val topology = map()
        val floor1 = topology.addOrMerge(Vec2(0f, 0f), NodeType.ELEVATOR, 1.6f, 0, floorId = "1")
        // Same X/Z, different floor: must NOT merge.
        val floor2 = topology.addOrMerge(Vec2(0f, 0f), NodeType.ELEVATOR, 1.6f, 10, floorId = "2")
        assertEquals(2, topology.nodeCount)

        topology.connect(floor1.id, floor2.id, distanceMeters = 4f, vertical = true)
        val route = topology.planRoute(floor1.id, floor2.id)
        assertNotNull(route)
        assertEquals(2, route.size)
        assertTrue(topology.edgesFrom(floor1.id).single().vertical)
    }
}
