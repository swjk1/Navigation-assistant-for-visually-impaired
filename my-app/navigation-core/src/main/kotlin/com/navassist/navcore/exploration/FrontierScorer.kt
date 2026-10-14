package com.navassist.navcore.exploration

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticHintStore
import com.navassist.navcore.topology.TopologicalMap

/**
 * Turns raw frontiers into a ranked list.
 *
 *   score = weightInformation * informationGain
 *         + weightSemantic    * semanticScore
 *         + weightDistance    * normalisedDistance
 *         + weightRevisit     * revisitPenalty
 *         + weightRisk        * riskPenalty
 *         + weightAdvice      * adviceScore   (only when an adviser has spoken)
 *         + weightPlanner     * planScore     (only when a long-term planner has spoken)
 *         + weightAxis        * along the space's long axis   (off by default)
 *         + weightClutter     * obstacles around the frontier (off by default)
 *
 * (the distance / revisit / risk weights are negative by default, so those terms subtract.)
 *
 * Distance is NORMALISED by half the grid size before weighting. Raw metres would otherwise
 * dominate every other term the moment a frontier is more than a few metres away, and no semantic
 * clue could ever outweigh it.
 *
 * The weights are guesses that need tuning on real hardware - which is exactly why they live in
 * [NavigationConfig] rather than in this file.
 */
class FrontierScorer(private val config: NavigationConfig) {

    fun score(
        frontiers: List<Frontier>,
        userPose: Pose3D,
        target: NavigationTarget,
        semantics: SemanticHintStore,
        topology: TopologicalMap,
        inflated: InflatedGrid,
        nowMillis: Long,
    ): List<Frontier> {
        val userPosition = userPose.position2D
        // A fixed scale, not the window's: a bigger window (for a longer-range sensor) must not make
        // distance matter less. 6 m is half the original 12 m window, so that case is unchanged.
        val normalizer = config.frontierDistanceScaleMeters
        val axis = if (config.weightAxis != 0f) {
            SpaceShape.axis(inflated, userPosition, config.axisRadiusMeters, config.gridResolutionMeters)
        } else {
            null
        }
        return frontiers
            .map { frontier ->
                val distance = frontier.centroid.distanceTo(userPosition)
                val semanticScore =
                    semantics.semanticScoreFor(frontier.centroid, userPose, target, nowMillis)
                val adviceScore = semantics.adviceScoreFor(frontier.centroid, nowMillis)
                val planScore = semantics.planScoreFor(frontier.centroid, userPosition, nowMillis)
                val revisit = topology.revisitDensity(frontier.centroid, config.topoNodeSpacingMeters)
                val risk = riskOf(frontier, inflated)

                val normalizedDistance =
                    GeometryUtils.clamp(distance / normalizer, 0f, 2f)

                val along = axis?.let { it.elongation * SpaceShape.alignment(it, frontier.centroid - userPosition) } ?: 0f
                val clutter = if (config.weightClutter != 0f) {
                    SpaceShape.clutter(inflated, frontier.centroid, config.clutterRadiusMeters, config.gridResolutionMeters)
                } else {
                    0f
                }

                val total =
                    config.weightAxis * along +
                        config.weightClutter * clutter +
                        config.weightInformation * frontier.estimatedInformationGain +
                        config.weightSemantic * semanticScore +
                        config.weightDistance * normalizedDistance +
                        config.weightRevisit * revisit +
                        config.weightRisk * risk +
                        config.weightAdvice * adviceScore +
                        config.weightPlanner * planScore

                frontier.copy(
                    distanceMeters = distance,
                    semanticScore = semanticScore,
                    revisitPenalty = revisit,
                    riskPenalty = risk,
                    adviceScore = adviceScore,
                    planScore = planScore,
                    score = total,
                )
            }
            .sortedByDescending { it.score }
    }

    /**
     * How awkward this frontier is to stand at: 0 in open space, approaching 1 when the frontier
     * is wedged against an obstacle. Keeps the engine from marching a blind user into a gap that
     * is technically unexplored but practically a wall corner.
     */
    private fun riskOf(frontier: Frontier, inflated: InflatedGrid): Float {
        val cell = frontier.centroidCell
        if (inflated.isBlocked(cell)) return 1f
        val clearance = inflated.clearanceCells(cell.gx, cell.gz)
        val safeCells = config.clearanceCostRadiusCells
        if (safeCells <= 0) return 0f
        return GeometryUtils.clamp(1f - clearance.toFloat() / safeCells.toFloat(), 0f, 1f)
    }
}
