package com.navassist.navcore.exploration

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticHintStore
import com.navassist.navcore.topology.TopologicalMap

data class ExplorationResult(
    val frontiers: List<Frontier>,
    val selected: Frontier?,
    /** True when nothing worth exploring remains locally: the trigger for backtracking. */
    val exhausted: Boolean,
)

/**
 * Chooses where to go next when the destination has not been found yet.
 *
 * Three behaviours matter more than the scoring maths:
 *
 *  1. HYSTERESIS. Frontier ids are regenerated on every detection pass, so the manager tracks its
 *     choice by POSITION and only switches when a rival beats the current choice by a margin.
 *     Without this the user gets "left, right, left, right" halfway down a corridor.
 *
 *  2. BLACKLISTING. A frontier the planner repeatedly fails to reach is abandoned, permanently
 *     enough that the engine stops re-selecting it every second.
 *
 *  3. EXHAUSTION. When no frontier survives, the caller is told, and it is the caller's job to
 *     backtrack to an earlier junction. This is what stops the engine walking a blind user into
 *     the same dead end over and over.
 */
class ExplorationManager(
    private val config: NavigationConfig,
    private val detector: FrontierDetector = FrontierDetector(config),
    private val scorer: FrontierScorer = FrontierScorer(config),
) {
    private class Blacklisted(val position: Vec2, var failures: Int)

    private val blacklist = ArrayList<Blacklisted>()

    private var selectedId: String? = null
    private var selectedCentroid: Vec2? = null
    private var selectedAtMillis: Long = 0

    var frontiers: List<Frontier> = emptyList()
        private set

    var selected: Frontier? = null
        private set

    fun reset() {
        blacklist.clear()
        selectedId = null
        selectedCentroid = null
        frontiers = emptyList()
        selected = null
        detector.resetIds()
    }

    /** Drops the current choice without blacklisting it (e.g. the destination was just found). */
    fun clearSelection() {
        selectedId = null
        selectedCentroid = null
        selectedAtMillis = 0
        selected = null
    }

    fun update(
        grid: OccupancyGrid,
        inflated: InflatedGrid,
        userPose: Pose3D,
        target: NavigationTarget,
        semantics: SemanticHintStore,
        topology: TopologicalMap,
        nowMillis: Long,
    ): ExplorationResult {
        val detected = detector.detect(grid).filterNot { isBlacklisted(it.centroid) }
        val scored = scorer.score(detected, userPose, target, semantics, topology, inflated, nowMillis)
        frontiers = scored

        if (scored.isEmpty()) {
            selected = null
            selectedId = null
            selectedCentroid = null
            return ExplorationResult(scored, null, exhausted = true)
        }

        val best = scored.first()
        val incumbent = matchIncumbent(scored)
        val committed = incumbent != null &&
            nowMillis - selectedAtMillis < config.frontierCommitMillis

        val choice = when {
            incumbent == null -> best
            // Reached the current frontier: it is no longer worth holding on to.
            incumbent.distanceMeters <= config.frontierReachedMeters -> best
            // Inside the commitment window the current choice stands, whatever the scores say.
            // Without this the user is sent left, then right, then left as the frontier set
            // churns underneath them, and never covers any ground.
            committed -> incumbent
            best.score > incumbent.score + config.frontierSwitchHysteresis -> best
            else -> incumbent
        }

        if (choice.id != selectedId) selectedAtMillis = nowMillis
        selectedId = choice.id
        selectedCentroid = choice.centroid
        selected = choice
        return ExplorationResult(scored, choice, exhausted = false)
    }

    /**
     * Re-identifies the previously selected frontier in the freshly detected set. Detection is
     * stateless, so identity has to come from geometry.
     */
    private fun matchIncumbent(scored: List<Frontier>): Frontier? {
        val previousCentroid = selectedCentroid ?: return null
        val previousId = selectedId ?: return null
        // Generous on purpose: centroids shift as the map fills in, and losing track of the
        // incumbent means a fresh winner is picked - which is the churn this is here to prevent.
        val tolerance = config.frontierReachedMeters * 4f
        var best: Frontier? = null
        var bestDistance = tolerance * tolerance
        for (frontier in scored) {
            val d = frontier.centroid.distanceSquaredTo(previousCentroid)
            if (d <= bestDistance) {
                bestDistance = d
                best = frontier
            }
        }
        // Keep the original id so the JS layer sees a stable selectedFrontierId while walking.
        return best?.copy(id = previousId)
    }

    // ------------------------------------------------------------------ failure handling

    /**
     * Called when the local planner could not produce a route to the selected frontier. After
     * [NavigationConfig.frontierFailuresBeforeBlacklist] consecutive failures the frontier is
     * abandoned so exploration can move on.
     */
    fun reportPlanningFailure() {
        val centroid = selectedCentroid ?: return
        val existing = blacklist.firstOrNull {
            it.position.distanceTo(centroid) <= config.frontierBlacklistRadiusMeters
        }
        if (existing != null) {
            existing.failures++
        } else {
            blacklist.add(Blacklisted(centroid, 1))
        }
        if ((existing?.failures ?: 1) >= config.frontierFailuresBeforeBlacklist) {
            clearSelection()
        }
    }

    /** Called when a planning attempt succeeded, so transient failures do not accumulate. */
    fun reportPlanningSuccess() {
        val centroid = selectedCentroid ?: return
        blacklist.firstOrNull {
            it.position.distanceTo(centroid) <= config.frontierBlacklistRadiusMeters
        }?.let { if (it.failures > 0) it.failures-- }
    }

    /** Permanently abandons the area around [position] (e.g. a confirmed dead end). */
    fun blacklistArea(position: Vec2) {
        blacklist.add(Blacklisted(position, config.frontierFailuresBeforeBlacklist))
        val current = selectedCentroid
        if (current != null && current.distanceTo(position) <= config.frontierBlacklistRadiusMeters) {
            clearSelection()
        }
    }

    private fun isBlacklisted(position: Vec2): Boolean = blacklist.any {
        it.failures >= config.frontierFailuresBeforeBlacklist &&
            it.position.distanceTo(position) <= config.frontierBlacklistRadiusMeters
    }
}
