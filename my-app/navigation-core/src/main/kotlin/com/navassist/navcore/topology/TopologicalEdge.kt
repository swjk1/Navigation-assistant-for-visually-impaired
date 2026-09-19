package com.navassist.navcore.topology

/**
 * A remembered connection between two places.
 *
 * [traversable] is set false when the engine repeatedly failed to route along the edge (a door
 * that is now locked, a corridor now blocked). Global planning then avoids it, but the edge is
 * kept so the engine remembers that it tried.
 */
data class TopologicalEdge(
    val fromId: String,
    val toId: String,
    val distanceMeters: Float,
    val traversable: Boolean = true,
    val visitCount: Int = 1,
    /** True when the edge crosses floors (stairs / lift). */
    val vertical: Boolean = false,
)
