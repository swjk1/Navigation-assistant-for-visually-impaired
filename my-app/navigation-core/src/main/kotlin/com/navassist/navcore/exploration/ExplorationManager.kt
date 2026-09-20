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
 *  1. COMMITMENT. Keep a fixed world waypoint despite frontier splits, merges and score changes.
 *     Release it on arrival, blockage, repeated route failure or a progress timeout.
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

    private var selectedCentroid: Vec2? = null
    private var lastProgressMillis: Long = 0
    private var bestDistanceMeters = Float.POSITIVE_INFINITY
    private var lastFailureMillis: Long? = null

    var frontiers: List<Frontier> = emptyList()
        private set

    var selected: Frontier? = null
        private set

    fun reset() {
        blacklist.clear()
        clearSelection()
        frontiers = emptyList()
        selected = null
        detector.resetIds()
    }

    /** Drops the current choice without blacklisting it (e.g. the destination was just found). */
    fun clearSelection() {
        selectedCentroid = null
        lastProgressMillis = 0
        bestDistanceMeters = Float.POSITIVE_INFINITY
        lastFailureMillis = null
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

        // Commit to a WORLD waypoint, not a regenerated cluster or its moving centroid.
        // A sweep can split, merge or erase frontier clusters without invalidating the route.
        val previous = selected
        if (previous != null) {
            val distance = previous.centroid.distanceTo(userPose.position2D)
            if (distance < bestDistanceMeters - config.frontierProgressMeters) {
                bestDistanceMeters = distance
                lastProgressMillis = nowMillis
            }
            val cell = inflated.worldToGrid(previous.centroid)
            val reached = distance <= config.frontierReachedMeters
            val blocked = inflated.isBlocked(cell)
            val stalled = nowMillis - lastProgressMillis >= config.frontierNoProgressMillis
            if (!reached && !blocked && !stalled) {
                selected = previous.copy(centroidCell = cell, distanceMeters = distance)
                return ExplorationResult(scored, selected, exhausted = false)
            }
            if (blocked || stalled) blacklistArea(previous.centroid)
            clearSelection()
        }

        val choice = scored.firstOrNull {
            it.distanceMeters > config.frontierReachedMeters &&
                !isBlacklisted(it.centroid) && !inflated.isBlocked(inflated.worldToGrid(it.centroid))
        }
        selectedCentroid = choice?.centroid
        selected = choice
        lastProgressMillis = nowMillis
        bestDistanceMeters = choice?.distanceMeters ?: Float.POSITIVE_INFINITY
        return ExplorationResult(scored, choice, exhausted = choice == null)
    }

    // ------------------------------------------------------------------ failure handling

    /**
     * Called when the local planner could not produce a route to the selected frontier. After
     * [NavigationConfig.frontierFailuresBeforeBlacklist] consecutive failures the frontier is
     * abandoned so exploration can move on.
     */
    fun reportPlanningFailure(nowMillis: Long) {
        val centroid = selectedCentroid ?: return
        // Count spaced failures, not render frames. Give mapping time to settle after a turn.
        val lastFailure = lastFailureMillis
        if (lastFailure != null && nowMillis - lastFailure < config.frontierFailureIntervalMillis) return
        lastFailureMillis = nowMillis
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
        lastFailureMillis = null
        val centroid = selectedCentroid ?: return
        blacklist.firstOrNull {
            it.position.distanceTo(centroid) <= config.frontierBlacklistRadiusMeters
        }?.let { if (it.failures < config.frontierFailuresBeforeBlacklist) it.failures = 0 }
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
