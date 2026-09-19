package com.navassist.navcore.topology

import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.planning.IntBinaryHeap

/**
 * Building-scale memory: a sparse graph of places and the connections walked between them.
 *
 * Responsibilities
 *  - revisit detection: a new node close to an existing one MERGES into it, so walking a loop does
 *    not create a parallel universe of duplicate corridors;
 *  - backtracking: graph A* answers "how do I get back to the last junction with unexplored ways
 *    out"; without this the engine would walk a user into the same dead end forever;
 *  - multi-floor: nodes carry floorId and vertical edges connect them, so the same routing code
 *    will work once stairs/lifts are handled.
 *
 * This is deliberately NOT visual SLAM loop closure. ARCore/ARKit already provide locally
 * consistent tracking; this layer only has to be good enough to remember topology.
 */
class TopologicalMap {

    private val nodesById = LinkedHashMap<String, TopologicalNode>()
    private val edgesByNode = LinkedHashMap<String, MutableList<TopologicalEdge>>()
    private var idCounter = 0

    val nodeCount: Int get() = nodesById.size

    val nodes: Collection<TopologicalNode> get() = nodesById.values

    fun reset() {
        nodesById.clear()
        edgesByNode.clear()
        idCounter = 0
    }

    fun node(id: String): TopologicalNode? = nodesById[id]

    fun edgesFrom(id: String): List<TopologicalEdge> = edgesByNode[id] ?: emptyList()

    // ------------------------------------------------------------------ node creation / merging

    /**
     * Adds a place, or merges into an existing one within [mergeRadiusMeters] on the same floor.
     *
     * Merging is the MVP form of revisitation: position proximity plus a compatible floor and a
     * non-conflicting semantic context. That is enough to stop the exploration manager treating a
     * corridor it already walked as new territory.
     */
    fun addOrMerge(
        position: Vec2,
        type: NodeType,
        mergeRadiusMeters: Float,
        nowMillis: Long,
        floorId: String? = null,
        semanticLabels: Set<String> = emptySet(),
    ): TopologicalNode {
        val existing = findNearest(position, mergeRadiusMeters, floorId)
        if (existing != null) {
            val merged = existing.copy(
                // A junction or dead end outranks a plain waypoint: never downgrade.
                type = mergeType(existing.type, type),
                visitedCount = existing.visitedCount + 1,
                semanticLabels = existing.semanticLabels + semanticLabels,
                timestampMillis = nowMillis,
                floorId = existing.floorId ?: floorId,
            )
            nodesById[merged.id] = merged
            return merged
        }
        val node = TopologicalNode(
            id = "N${idCounter++}",
            type = type,
            position = position,
            floorId = floorId,
            visitedCount = 1,
            semanticLabels = semanticLabels,
            timestampMillis = nowMillis,
        )
        nodesById[node.id] = node
        edgesByNode[node.id] = ArrayList()
        return node
    }

    private fun mergeType(existing: NodeType, incoming: NodeType): NodeType {
        if (existing == incoming) return existing
        val rank = { t: NodeType ->
            when (t) {
                NodeType.WAYPOINT -> 0
                NodeType.FRONTIER -> 1
                NodeType.DEAD_END -> 2
                NodeType.JUNCTION -> 3
                NodeType.SEMANTIC_LANDMARK -> 4
                NodeType.STAIRS, NodeType.ELEVATOR -> 5
                NodeType.ROOM, NodeType.EXIT -> 6
            }
        }
        return if (rank(incoming) > rank(existing)) incoming else existing
    }

    fun update(node: TopologicalNode) {
        nodesById[node.id] = node
    }

    fun markType(id: String, type: NodeType) {
        nodesById[id]?.let { nodesById[id] = it.copy(type = mergeType(it.type, type)) }
    }

    fun markExhausted(id: String, exhausted: Boolean = true) {
        nodesById[id]?.let { nodesById[id] = it.copy(exhausted = exhausted) }
    }

    // ------------------------------------------------------------------ edges

    fun connect(fromId: String, toId: String, distanceMeters: Float, vertical: Boolean = false) {
        if (fromId == toId) return
        if (!nodesById.containsKey(fromId) || !nodesById.containsKey(toId)) return
        addDirected(fromId, toId, distanceMeters, vertical)
        addDirected(toId, fromId, distanceMeters, vertical)
    }

    private fun addDirected(fromId: String, toId: String, distance: Float, vertical: Boolean) {
        val list = edgesByNode.getOrPut(fromId) { ArrayList() }
        val index = list.indexOfFirst { it.toId == toId }
        if (index >= 0) {
            val edge = list[index]
            list[index] = edge.copy(visitCount = edge.visitCount + 1, traversable = true)
        } else {
            list.add(TopologicalEdge(fromId, toId, distance, vertical = vertical))
        }
    }

    fun markEdgeBlocked(fromId: String, toId: String) {
        setEdgeTraversable(fromId, toId, false)
        setEdgeTraversable(toId, fromId, false)
    }

    private fun setEdgeTraversable(fromId: String, toId: String, traversable: Boolean) {
        val list = edgesByNode[fromId] ?: return
        val index = list.indexOfFirst { it.toId == toId }
        if (index >= 0) list[index] = list[index].copy(traversable = traversable)
    }

    // ------------------------------------------------------------------ queries

    fun findNearest(position: Vec2, radiusMeters: Float, floorId: String? = null): TopologicalNode? {
        var best: TopologicalNode? = null
        var bestDistance = radiusMeters * radiusMeters
        for (node in nodesById.values) {
            if (floorId != null && node.floorId != null && node.floorId != floorId) continue
            val d = node.position.distanceSquaredTo(position)
            if (d <= bestDistance) {
                bestDistance = d
                best = node
            }
        }
        return best
    }

    /**
     * Nearest node that is actually part of the walked graph, i.e. has at least one edge.
     *
     * Global routing must start and end on connected nodes. Landmark nodes created from semantic
     * observations are position memory with no edges - routing "to" one would either fail or, if
     * it were wired up with a synthetic edge, invent a corridor that does not exist. This finds
     * the nearest place we have actually stood instead.
     */
    fun findNearestConnected(
        position: Vec2,
        radiusMeters: Float,
        floorId: String? = null,
    ): TopologicalNode? {
        var best: TopologicalNode? = null
        var bestDistance = radiusMeters * radiusMeters
        for (node in nodesById.values) {
            if (floorId != null && node.floorId != null && node.floorId != floorId) continue
            if (edgesByNode[node.id].isNullOrEmpty()) continue
            val d = node.position.distanceSquaredTo(position)
            if (d <= bestDistance) {
                bestDistance = d
                best = node
            }
        }
        return best
    }

    /**
     * How thoroughly the area around [position] has already been walked, 0..1.
     * Used as the frontier revisit penalty: heading back into well-trodden space is rarely the
     * best way to find something new.
     */
    fun revisitDensity(position: Vec2, radiusMeters: Float): Float {
        if (nodesById.isEmpty()) return 0f
        var visits = 0
        val radiusSq = radiusMeters * radiusMeters
        for (node in nodesById.values) {
            if (node.position.distanceSquaredTo(position) <= radiusSq) {
                visits += node.visitedCount
                if (node.type == NodeType.DEAD_END || node.exhausted) visits += 3
            }
        }
        if (visits == 0) return 0f
        return kotlin.math.min(1f, visits / 6f)
    }

    /** Junctions that still have unexplored ways out, nearest first. Backtracking targets. */
    fun openJunctions(from: Vec2): List<TopologicalNode> =
        nodesById.values
            .filter { (it.type == NodeType.JUNCTION || it.type == NodeType.FRONTIER) && !it.exhausted }
            .sortedBy { it.position.distanceSquaredTo(from) }

    /**
     * Where to retreat to when no junction is left: a plain dead-end corridor has no branch to go
     * back to, but walking back the way we came is still strictly better than standing still in a
     * cul-de-sac. Least-visited places first, then nearest.
     */
    fun retreatCandidates(from: Vec2, excludeId: String? = null): List<TopologicalNode> =
        nodesById.values
            .filter { !it.exhausted && it.id != excludeId && it.type != NodeType.DEAD_END }
            .sortedWith(
                compareBy({ it.visitedCount }, { it.position.distanceSquaredTo(from) }),
            )

    // ------------------------------------------------------------------ graph A*

    /**
     * Shortest remembered route between two nodes. Used for global planning to an already-known
     * destination and for backtracking to an earlier junction.
     *
     * The heuristic is straight-line distance on the same floor and zero across floors (still
     * admissible), so the same routine serves both cases.
     */
    fun planRoute(startId: String, goalId: String): List<TopologicalNode>? {
        if (startId == goalId) return nodesById[startId]?.let { listOf(it) }
        val start = nodesById[startId] ?: return null
        val goal = nodesById[goalId] ?: return null

        val ids = nodesById.keys.toList()
        val indexOf = HashMap<String, Int>(ids.size)
        ids.forEachIndexed { index, id -> indexOf[id] = index }

        val gScore = FloatArray(ids.size) { Float.MAX_VALUE }
        val cameFrom = IntArray(ids.size) { -1 }
        val closed = BooleanArray(ids.size)
        val open = IntBinaryHeap(ids.size.coerceAtLeast(8))

        val startIndex = indexOf[startId] ?: return null
        val goalIndex = indexOf[goalId] ?: return null
        gScore[startIndex] = 0f
        open.push(startIndex, heuristic(start, goal))

        while (!open.isEmpty) {
            val current = open.pop()
            if (closed[current]) continue
            closed[current] = true
            if (current == goalIndex) break

            val currentNode = nodesById[ids[current]] ?: continue
            for (edge in edgesFrom(currentNode.id)) {
                if (!edge.traversable) continue
                val neighborIndex = indexOf[edge.toId] ?: continue
                if (closed[neighborIndex]) continue
                val neighbor = nodesById[edge.toId] ?: continue
                val tentative = gScore[current] + edge.distanceMeters
                if (tentative < gScore[neighborIndex]) {
                    gScore[neighborIndex] = tentative
                    cameFrom[neighborIndex] = current
                    open.push(neighborIndex, tentative + heuristic(neighbor, goal))
                }
            }
        }

        if (gScore[goalIndex] == Float.MAX_VALUE) return null
        val route = ArrayList<TopologicalNode>()
        var current = goalIndex
        while (current != -1) {
            nodesById[ids[current]]?.let { route.add(it) }
            if (current == startIndex) break
            current = cameFrom[current]
        }
        route.reverse()
        return route
    }

    private fun heuristic(from: TopologicalNode, to: TopologicalNode): Float =
        if (from.floorId != null && to.floorId != null && from.floorId != to.floorId) {
            0f
        } else {
            from.position.distanceTo(to.position)
        }
}
