package com.navassist.navcore.exploration

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
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
            // Commitment survives frontier churn - clusters splitting, merging or fragmenting below
            // the size filter - but not the frontier being fully seen: a sweep routinely fills in
            // a waypoint picked a second earlier, and holding it would send the user back to look
            // at space the map already knows.
            val explored = !hasFrontierCellNear(grid, grid.worldToGrid(previous.centroid))
            if (!reached && !blocked && !stalled && !explored) {
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

    /**
     * True while any FREE cell bordering UNKNOWN space lies near [cell], regardless of whether
     * the detector still reports it as part of a cluster. Unknown space behind a wall borders an
     * obstacle, not free space, so it does not count - it is not reachable from here.
     */
    private fun hasFrontierCellNear(grid: OccupancyGrid, cell: GridCoordinate): Boolean {
        val radiusCells = config.frontierCommitUnknownRadiusMeters / grid.resolution
        val reach = kotlin.math.ceil(radiusCells).toInt()
        val radiusSq = radiusCells * radiusCells
        for (dz in -reach..reach) {
            for (dx in -reach..reach) {
                if (dx * dx + dz * dz > radiusSq) continue
                val gx = cell.gx + dx
                val gz = cell.gz + dz
                if (grid.stateAt(gx, gz) != CellState.FREE) continue
                if (isUnknownInWindow(grid, gx + 1, gz) || isUnknownInWindow(grid, gx - 1, gz) ||
                    isUnknownInWindow(grid, gx, gz + 1) || isUnknownInWindow(grid, gx, gz - 1)
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** Outside the window reads UNKNOWN but is not unexplored space; see FrontierDetector. */
    private fun isUnknownInWindow(grid: OccupancyGrid, gx: Int, gz: Int): Boolean =
        grid.inBounds(gx, gz) && grid.stateAt(gx, gz) == CellState.UNKNOWN

    private fun isBlacklisted(position: Vec2): Boolean = blacklist.any {
        it.failures >= config.frontierFailuresBeforeBlacklist &&
            it.position.distanceTo(position) <= config.frontierBlacklistRadiusMeters
    }
}
