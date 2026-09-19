package com.navassist.navcore.topology

import com.navassist.navcore.geometry.Vec2

enum class NodeType {
    /** Ordinary corridor breadcrumb. */
    WAYPOINT,
    /** More than two ways out: a decision point worth backtracking to. */
    JUNCTION,
    /** Explored and closed off. */
    DEAD_END,
    SEMANTIC_LANDMARK,
    ROOM,
    EXIT,
    /** Vertical transition. Carries an edge to a node on another floorId. */
    STAIRS,
    /** Vertical transition. */
    ELEVATOR,
    /** A frontier the engine committed to. */
    FRONTIER,
}

/**
 * A place, remembered at building scale.
 *
 * The 10 cm occupancy grid is local and rolling - it forgets a corridor once the user is two
 * rooms away. This graph is the long-term memory that makes "go back to the junction you passed
 * three minutes ago" possible without holding an entire building at grid resolution.
 *
 * [floorId] is present from day one so multi-floor routing is a graph question later, not a
 * rewrite. It is null until the perception layer reports a FLOOR observation.
 */
data class TopologicalNode(
    val id: String,
    val type: NodeType,
    val position: Vec2,
    val floorId: String? = null,
    val visitedCount: Int = 1,
    val semanticLabels: Set<String> = emptySet(),
    /** Platform timestamp (ms) of the most recent visit. */
    val timestampMillis: Long = 0L,
    /** True once every frontier reachable from here has been explored or abandoned. */
    val exhausted: Boolean = false,
)
