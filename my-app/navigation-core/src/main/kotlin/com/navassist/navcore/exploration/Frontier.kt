package com.navassist.navcore.exploration

import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.Vec2

/**
 * A boundary between known-free space and unexplored space: the only place worth walking towards
 * when the destination has not been found yet.
 */
data class Frontier(
    val id: String,
    /** World position of the cluster centroid, on the navigation plane. */
    val centroid: Vec2,
    /** Grid cell nearest the centroid, used as the planning goal. */
    val centroidCell: GridCoordinate,
    /** Number of frontier cells in the cluster. Bigger clusters are usually real openings. */
    val cellCount: Int,
    /** How much unknown space this frontier is likely to reveal, normalised 0..1. */
    val estimatedInformationGain: Float = 0f,
    /** Straight-line distance from the user, in metres. */
    val distanceMeters: Float = 0f,
    /** Positive when semantic evidence points this way (e.g. "rooms 300-349 to the right"). */
    val semanticScore: Float = 0f,
    /** Positive penalty for areas the user has already been through. */
    val revisitPenalty: Float = 0f,
    /** Positive penalty for frontiers hugging obstacles / hard to reach safely. */
    val riskPenalty: Float = 0f,
    /** Weighted total produced by [FrontierScorer]. */
    val score: Float = 0f,
)
