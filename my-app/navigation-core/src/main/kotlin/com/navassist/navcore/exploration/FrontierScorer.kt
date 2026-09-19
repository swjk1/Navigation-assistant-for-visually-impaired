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
        val normalizer = config.gridSizeMeters * 0.5f
        return frontiers
            .map { frontier ->
                val distance = frontier.centroid.distanceTo(userPosition)
                val semanticScore =
                    semantics.semanticScoreFor(frontier.centroid, userPose, target, nowMillis)
                val revisit = topology.revisitDensity(frontier.centroid, config.topoNodeSpacingMeters)
                val risk = riskOf(frontier, inflated)

                val normalizedDistance =
                    GeometryUtils.clamp(distance / normalizer, 0f, 2f)

                val total =
                    config.weightInformation * frontier.estimatedInformationGain +
                        config.weightSemantic * semanticScore +
                        config.weightDistance * normalizedDistance +
                        config.weightRevisit * revisit +
                        config.weightRisk * risk

                frontier.copy(
                    distanceMeters = distance,
                    semanticScore = semanticScore,
                    revisitPenalty = revisit,
                    riskPenalty = risk,
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
