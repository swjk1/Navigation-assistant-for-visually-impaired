package com.navassist.navcore

import com.navassist.navcore.exploration.ExplorationManager
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.FloorEstimator
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.mapping.ObstacleInflator
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.planning.AStarPlanner
import com.navassist.navcore.planning.NavigationController
import com.navassist.navcore.planning.PathSmoother
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticHintStore
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.NavigationDebugInfo
import com.navassist.navcore.state.NavigationEvent
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.navcore.state.NavigationStateMachine
import com.navassist.navcore.state.NavigationStatus
import com.navassist.navcore.topology.NodeType
import com.navassist.navcore.topology.TopologicalMap
import kotlin.math.abs
import kotlin.math.min

/**
 * The platform-independent navigation engine.
 *
 * Pipeline, once per [NavigationFrame]:
 *
 *   pose + depth points (canonical frame)
 *        -> floor estimate
 *        -> obstacle / free-space classification
 *        -> occupancy grid (ray integration + temporal decay)
 *        -> obstacle inflation
 *        -> [ target known ? topological/local route : frontier selection ]
 *        -> local A* -> line-of-sight smoothing
 *        -> lookahead waypoint -> heading error
 *        -> LEFT / RIGHT / STRAIGHT / STOP / SCAN / ARRIVED
 *
 * Contains NO Android, ARCore, Expo or threading code. Scheduling is entirely the platform
 * adapter's business; every method here is synchronous and deterministic, which is what makes the
 * whole stack unit-testable and reusable by a future ARKit adapter.
 */
class NavigationEngine(val config: NavigationConfig = NavigationConfig()) {

    // ---------------------------------------------------------------- collaborators
    private val floorEstimator = FloorEstimator(config)
    private val inflator = ObstacleInflator(config)
    private val planner = AStarPlanner(config)
    private val controller = NavigationController(config)
    private val exploration = ExplorationManager(config)
    private val semantics = SemanticHintStore(config)
    private val stateMachine = NavigationStateMachine()

    val topology = TopologicalMap()

    private var grid = OccupancyGrid(config)
    private var inflated: InflatedGrid? = null

    // ---------------------------------------------------------------- session state
    var target: NavigationTarget = NavigationTarget.Explore
        private set

    private var started = false
    private var originInitialized = false

    private var lastFrameTimestampNanos: Long = 0
    private var lastDepthTimestampNanos: Long = 0
    private var lastTrackingGoodMillis: Long = 0
    private var lastInflateMillis: Long = 0
    private var lastFrontierMillis: Long = 0
    private var lastPlanMillis: Long = 0

    private var currentPath: List<Vec2> = emptyList()
    private var currentGoal: Vec2? = null
    private var lastSnapshot: NavigationSnapshot = NavigationSnapshot(
        timestampMillis = 0,
        status = NavigationStatus.IDLE,
        command = NavigationCommand.STOP,
    )

    private var lastNodeId: String? = null
    private var lastNodePosition: Vec2? = null
    private var lastNodeYaw: Float = 0f
    private var lastDepthPointCount: Int = 0

    private var backtrackTargetNodeId: String? = null

    val snapshot: NavigationSnapshot get() = lastSnapshot

    val status: NavigationStatus get() = stateMachine.status

    // ================================================================= public API

    fun start(target: NavigationTarget = NavigationTarget.Explore) {
        reset()
        this.target = target
        started = true
        stateMachine.on(NavigationEvent.Start)
    }

    fun setTarget(target: NavigationTarget) {
        if (this.target == target) return
        this.target = target
        semantics.clearTargetSighting()
        exploration.clearSelection()
        currentPath = emptyList()
        currentGoal = null
        backtrackTargetNodeId = null
        stateMachine.onTargetChanged()
    }

    fun pause() {
        stateMachine.on(NavigationEvent.Pause)
        controller.forceCommand(NavigationCommand.STOP)
    }

    fun resume() {
        if (!started) return
        stateMachine.on(NavigationEvent.Resume)
    }

    fun stop() {
        stateMachine.on(NavigationEvent.Stop)
        started = false
        controller.forceCommand(NavigationCommand.STOP)
    }

    fun reset() {
        grid = OccupancyGrid(config)
        inflated = null
        floorEstimator.reset()
        controller.reset()
        exploration.reset()
        semantics.reset()
        topology.reset()
        stateMachine.reset()
        currentPath = emptyList()
        currentGoal = null
        lastNodeId = null
        lastNodePosition = null
        backtrackTargetNodeId = null
        originInitialized = false
        lastDepthTimestampNanos = 0
        lastTrackingGoodMillis = 0
        lastDepthPointCount = 0
        started = false
        target = NavigationTarget.Explore
    }

    /** Clears the learned map but keeps the session and destination. */
    fun resetMap() {
        grid.clear()
        inflated = null
        floorEstimator.reset()
        exploration.reset()
        topology.reset()
        currentPath = emptyList()
        currentGoal = null
        lastNodeId = null
        lastNodePosition = null
        originInitialized = false
    }

    fun submitSemanticObservations(
        observations: List<SemanticObservation>,
        nowMillis: Long,
        observerPose: Pose3D = lastPose,
    ) {
        semantics.submit(observations, nowMillis, observerPose, target)
        // Landmarks worth remembering at building scale go straight into the graph.
        for (observation in observations) {
            val world = observation.worldPosition ?: continue
            val type = when (observation) {
                is SemanticObservation.Room -> NodeType.ROOM
                is SemanticObservation.Exit -> NodeType.EXIT
                is SemanticObservation.Stairs -> NodeType.STAIRS
                is SemanticObservation.Elevator -> NodeType.ELEVATOR
                else -> continue
            }
            val label = when (observation) {
                is SemanticObservation.Room -> observation.label
                else -> type.name
            }
            val node = topology.addOrMerge(
                position = world.toVec2(),
                type = type,
                mergeRadiusMeters = config.topoMergeRadiusMeters,
                nowMillis = nowMillis,
                floorId = semantics.currentFloorId,
                semanticLabels = setOf(label),
            )
            lastNodeId?.let { topology.connect(it, node.id, node.position.distanceTo(lastPose.position2D)) }
        }
    }

    private var lastPose: Pose3D = Pose3D.IDENTITY

    // ================================================================= frame update

    fun updateFrame(frame: NavigationFrame): NavigationSnapshot {
        val nowMillis = frame.timestampNanos / 1_000_000
        lastPose = frame.pose

        if (!started || stateMachine.status == NavigationStatus.IDLE) {
            return finish(nowMillis, frame, NavigationCommand.STOP, null, null)
        }
        if (stateMachine.status == NavigationStatus.PAUSED) {
            return finish(nowMillis, frame, NavigationCommand.STOP, null, null)
        }

        // ---------------------------------------------------------- 1. tracking gate (Rule 4)
        val trackingGood = frame.tracking && frame.trackingConfidence >= config.minTrackingConfidence
        if (!trackingGood) {
            lastTrackingGoodMillis = 0
            stateMachine.on(NavigationEvent.TrackingLost)
            currentPath = emptyList()
            controller.reset()
            return finish(nowMillis, frame, NavigationCommand.STOP, null, null)
        }
        if (lastTrackingGoodMillis == 0L) lastTrackingGoodMillis = nowMillis
        val trackingStable = nowMillis - lastTrackingGoodMillis >= config.trackingRecoveryMillis
        if (stateMachine.status == NavigationStatus.INITIALIZING ||
            (stateMachine.status == NavigationStatus.LOST_TRACKING && trackingStable)
        ) {
            stateMachine.on(NavigationEvent.TrackingAcquired)
        }
        if (stateMachine.status == NavigationStatus.LOST_TRACKING) {
            return finish(nowMillis, frame, NavigationCommand.STOP, null, null)
        }

        // ---------------------------------------------------------- 2. mapping
        if (!originInitialized) {
            grid.recenter(frame.pose.position2D)
            originInitialized = true
        } else if (frame.pose.position2D.distanceTo(grid.centerWorld()) > config.gridRecenterThresholdMeters) {
            grid.recenter(frame.pose.position2D)
            inflated = null
        }

        val deltaSeconds = if (lastFrameTimestampNanos == 0L) {
            0f
        } else {
            ((frame.timestampNanos - lastFrameTimestampNanos) / 1e9).toFloat().coerceIn(0f, 1f)
        }
        lastFrameTimestampNanos = frame.timestampNanos
        grid.applyDecay(deltaSeconds)

        if (frame.depthAvailable && frame.points.count > 0) {
            lastDepthTimestampNanos = frame.timestampNanos
            lastDepthPointCount = frame.points.count
            val floor = floorEstimator.update(
                frame.points,
                frame.pose.y,
                frame.floorHint,
                frame.floorHintConfidence,
            )
            if (floor.confidence >= config.floorMinConfidence) {
                integrateDepth(frame, floor.floorY)
            }
        }

        val depthStale = lastDepthTimestampNanos == 0L ||
            (frame.timestampNanos - lastDepthTimestampNanos) / 1_000_000 > config.depthStarvationMillis

        val mapConfidence =
            grid.knownFractionWithin(frame.pose.position2D, config.mapConfidenceRadiusMeters)
        val floorReady = floorEstimator.estimate.confidence >= config.floorMinConfidence

        if (stateMachine.status == NavigationStatus.LOCALIZING &&
            floorReady && mapConfidence >= config.minMapConfidenceToNavigate
        ) {
            stateMachine.on(NavigationEvent.MapReady)
        }
        if (stateMachine.status == NavigationStatus.LOCALIZING) {
            return finish(nowMillis, frame, NavigationCommand.SCAN, null, null, mapConfidence)
        }

        // ---------------------------------------------------------- 3. depth starvation (Rule 4)
        if (depthStale) {
            currentPath = emptyList()
            return finish(nowMillis, frame, NavigationCommand.SCAN, null, null, mapConfidence)
        }

        // ---------------------------------------------------------- 4. topological memory
        updateTopology(frame.pose, nowMillis)

        // ---------------------------------------------------------- 5. planning view of the map
        val inflatedGrid = refreshInflation(nowMillis)

        // ---------------------------------------------------------- 6. where are we going?
        val goal = resolveGoal(frame.pose, inflatedGrid, nowMillis, mapConfidence)
        if (goal == null) {
            currentPath = emptyList()
            return finish(nowMillis, frame, NavigationCommand.SCAN, null, null, mapConfidence)
        }

        // ---------------------------------------------------------- 7. arrival
        val distanceToTarget = finalDestination()?.distanceTo(frame.pose.position2D)
        if (controller.updateArrival(distanceToTarget)) {
            stateMachine.on(NavigationEvent.Arrived)
            return finish(nowMillis, frame, NavigationCommand.ARRIVED, null, distanceToTarget, mapConfidence)
        }
        if (stateMachine.status == NavigationStatus.ARRIVED) {
            return finish(nowMillis, frame, NavigationCommand.ARRIVED, null, distanceToTarget, mapConfidence)
        }

        // ---------------------------------------------------------- 8. local path
        val path = refreshPath(frame.pose, inflatedGrid, goal, nowMillis)
        if (path.size < 2) {
            exploration.reportPlanningFailure()
            stateMachine.on(NavigationEvent.RouteUnavailable)
            return finish(nowMillis, frame, NavigationCommand.SCAN, null, distanceToTarget, mapConfidence)
        }
        exploration.reportPlanningSuccess()
        if (stateMachine.status == NavigationStatus.NO_ROUTE) {
            stateMachine.on(NavigationEvent.RouteFound)
        }

        // ---------------------------------------------------------- 9. command
        val output = controller.update(frame.pose, path, inflatedGrid)
        return finish(
            nowMillis = nowMillis,
            frame = frame,
            command = output.command,
            headingErrorDegrees = output.headingErrorDegrees,
            distanceToTargetMeters = distanceToTarget,
            mapConfidence = mapConfidence,
            distanceToWaypointMeters = output.distanceToWaypointMeters,
        )
    }

    // ================================================================= mapping helpers

    /**
     * Classifies every depth sample against the floor estimate and folds it into the grid.
     *
     * Ray integration is essential: marking only the endpoint would give a map full of obstacle
     * dots surrounded by UNKNOWN, which is indistinguishable from a wall everywhere, and frontier
     * detection (a FREE cell next to an UNKNOWN cell) could never fire.
     */
    private fun integrateDepth(frame: NavigationFrame, floorY: Float) {
        val points = frame.points
        val origin = frame.pose.position2D
        val minHeight = config.minObstacleHeightMeters
        val maxHeight = config.maxObstacleHeightMeters
        val maxRangeSq = config.maxDepthMeters * config.maxDepthMeters
        val minRangeSq = config.minDepthMeters * config.minDepthMeters
        val limit = min(points.count, config.maxPointsPerFrame)

        for (i in 0 until limit) {
            val px = points.x(i)
            val py = points.y(i)
            val pz = points.z(i)
            if (px.isNaN() || py.isNaN() || pz.isNaN()) continue

            val dx = px - origin.x
            val dz = pz - origin.z
            val rangeSq = dx * dx + dz * dz
            if (rangeSq > maxRangeSq || rangeSq < minRangeSq) continue

            val height = py - floorY
            when {
                // Overhead structure: tells us nothing about the floor plan under it.
                height > maxHeight -> continue
                // Floor / low ground: the ray crossed free space and ends on walkable ground.
                height < minHeight -> grid.integrateRay(origin, Vec2(px, pz), endpointOccupied = false)
                // Body-height obstacle.
                else -> grid.integrateRay(origin, Vec2(px, pz), endpointOccupied = true)
            }
        }
    }

    private fun refreshInflation(nowMillis: Long): InflatedGrid {
        val existing = inflated
        if (existing != null && nowMillis - lastInflateMillis < INFLATE_INTERVAL_MILLIS) return existing
        val fresh = inflator.inflate(grid)
        inflated = fresh
        lastInflateMillis = nowMillis
        return fresh
    }

    /**
     * Drops breadcrumbs into the topological graph. Conservative on purpose: a node per AR frame
     * would produce a graph too dense to reason about and would destroy revisit detection.
     */
    private fun updateTopology(pose: Pose3D, nowMillis: Long) {
        val position = pose.position2D
        val previous = lastNodePosition
        val travelled = previous?.distanceTo(position) ?: Float.MAX_VALUE
        val turned = abs(GeometryUtils.angleDifference(lastNodeYaw, pose.yawRadians))

        val shouldDrop = previous == null ||
            travelled >= config.topoNodeSpacingMeters ||
            (turned >= config.topoTurnThresholdRadians && travelled >= config.topoNodeSpacingMeters * 0.4f)
        if (!shouldDrop) return

        val type = when {
            exploration.frontiers.size >= 2 -> NodeType.JUNCTION
            exploration.frontiers.isEmpty() -> NodeType.DEAD_END
            else -> NodeType.WAYPOINT
        }
        val node = topology.addOrMerge(
            position = position,
            type = type,
            mergeRadiusMeters = config.topoMergeRadiusMeters,
            nowMillis = nowMillis,
            floorId = semantics.currentFloorId,
        )
        lastNodeId?.let { previousId ->
            if (previousId != node.id) topology.connect(previousId, node.id, travelled)
        }
        lastNodeId = node.id
        lastNodePosition = position
        lastNodeYaw = pose.yawRadians
    }

    // ================================================================= goal selection

    /**
     * Hierarchical planning entry point.
     *
     *   target located?  -> route to it (topological route when it is outside the local window)
     *   otherwise        -> best-scoring frontier
     *   nothing left     -> backtrack to the nearest junction that still has ways out
     */
    private fun resolveGoal(
        pose: Pose3D,
        inflatedGrid: InflatedGrid,
        nowMillis: Long,
        mapConfidence: Float,
    ): Vec2? {
        val sighting = semantics.targetSighting
        if (sighting != null) {
            if (stateMachine.status != NavigationStatus.NAVIGATING) {
                stateMachine.on(NavigationEvent.TargetLocated)
            }
            exploration.clearSelection()
            currentGoal = clampToGrid(pose.position2D, sighting.worldPosition.toVec2())
            return currentGoal
        }

        if (stateMachine.status == NavigationStatus.NAVIGATING) {
            stateMachine.on(NavigationEvent.TargetLost)
        }

        // Frontier detection is expensive; run it at ~1.5 Hz rather than per frame.
        if (nowMillis - lastFrontierMillis >= FRONTIER_INTERVAL_MILLIS || exploration.selected == null) {
            lastFrontierMillis = nowMillis
            exploration.update(grid, inflatedGrid, pose, target, semantics, topology, nowMillis)
        }

        val selected = exploration.selected
        if (selected != null) {
            if (stateMachine.status == NavigationStatus.BACKTRACKING) {
                stateMachine.on(NavigationEvent.RouteFound)
                backtrackTargetNodeId = null
            }
            currentGoal = clampToGrid(pose.position2D, selected.centroid)
            return currentGoal
        }

        // Nothing unexplored nearby: this branch is finished.
        return backtrackGoal(pose)
    }

    /**
     * Dead-end handling. Marks the current place exhausted so we never select it again, then
     * routes back through the remembered graph to the nearest junction with unexplored ways out.
     */
    private fun backtrackGoal(pose: Pose3D): Vec2? {
        lastNodeId?.let {
            topology.markType(it, NodeType.DEAD_END)
            topology.markExhausted(it)
        }
        exploration.blacklistArea(pose.position2D)

        val currentNode = lastNodeId?.let { topology.node(it) }
            ?: topology.findNearest(pose.position2D, config.topoNodeSpacingMeters * 2f)

        // Prefer a junction with unexplored branches; failing that, retreat the way we came.
        val candidates = topology.openJunctions(pose.position2D)
            .filter { it.id != currentNode?.id }
            .ifEmpty { topology.retreatCandidates(pose.position2D, excludeId = currentNode?.id) }

        if (currentNode == null || candidates.isEmpty()) {
            stateMachine.on(NavigationEvent.RouteUnavailable)
            return null
        }

        if (stateMachine.status != NavigationStatus.BACKTRACKING) {
            stateMachine.on(NavigationEvent.BacktrackRequested)
        }

        for (candidate in candidates) {
            val route = topology.planRoute(currentNode.id, candidate.id) ?: continue
            backtrackTargetNodeId = candidate.id
            // Aim at the next hop, not the far end: only the next hop is inside the local grid.
            val nextHop = route.getOrNull(1)?.position ?: candidate.position
            currentGoal = clampToGrid(pose.position2D, nextHop)
            return currentGoal
        }

        stateMachine.on(NavigationEvent.RouteUnavailable)
        return null
    }

    /** The destination the user actually asked for, when its position is known. */
    private fun finalDestination(): Vec2? = semantics.targetSighting?.worldPosition?.toVec2()

    /**
     * The local grid is a 12 m window. A goal outside it is pulled onto the window's edge along
     * the line from the user, which keeps long-range routing working through a local planner.
     */
    private fun clampToGrid(from: Vec2, goal: Vec2): Vec2 {
        if (grid.contains(goal)) return goal
        val margin = grid.resolution * (config.inflationRadiusCells + 2)
        val half = grid.sizeMeters * 0.5f - margin
        val center = grid.centerWorld()
        val delta = goal - from
        val length = delta.length()
        if (length < 1e-4f) return from
        val direction = delta * (1f / length)

        var best = from
        var step = grid.resolution
        while (step <= length) {
            val probe = from + direction * step
            if (abs(probe.x - center.x) > half || abs(probe.z - center.z) > half) break
            best = probe
            step += grid.resolution
        }
        return best
    }

    // ================================================================= path

    private fun refreshPath(
        pose: Pose3D,
        inflatedGrid: InflatedGrid,
        goal: Vec2,
        nowMillis: Long,
    ): List<Vec2> {
        val due = nowMillis - lastPlanMillis >= PLAN_INTERVAL_MILLIS
        val invalid = currentPath.size < 2 || !pathStillValid(inflatedGrid)
        if (!due && !invalid) return currentPath

        lastPlanMillis = nowMillis
        val start = inflatedGrid.worldToGrid(pose.position2D)
        val goalCell = inflatedGrid.worldToGrid(goal)
        // Only exploration goals may touch unknown space, and only right at the goal.
        val exploring = semantics.targetSighting == null
        val cells: List<GridCoordinate> =
            planner.plan(inflatedGrid, start, goalCell, allowUnknownNearGoal = exploring)
                ?: run {
                    currentPath = emptyList()
                    return emptyList()
                }

        val smoothed = PathSmoother.smooth(inflatedGrid, cells)
        currentPath = smoothed.map { inflatedGrid.gridToWorld(it) }
        return currentPath
    }

    /** A path planned two seconds ago may now cross a person. Cheap re-validation every frame. */
    private fun pathStillValid(inflatedGrid: InflatedGrid): Boolean {
        for (point in currentPath) {
            val cell = inflatedGrid.worldToGrid(point)
            if (!inflatedGrid.inBounds(cell)) return false
            if (inflatedGrid.isBlocked(cell)) return false
        }
        return true
    }

    // ================================================================= snapshot

    private fun finish(
        nowMillis: Long,
        frame: NavigationFrame,
        command: NavigationCommand,
        headingErrorDegrees: Float?,
        distanceToTargetMeters: Float?,
        mapConfidence: Float = lastSnapshot.mapConfidence,
        distanceToWaypointMeters: Float? = null,
    ): NavigationSnapshot {
        if (command != controller.currentCommand) controller.forceCommand(command)
        val stats = grid.stats()
        val floor = floorEstimator.estimate
        lastSnapshot = NavigationSnapshot(
            timestampMillis = nowMillis,
            status = stateMachine.status,
            command = command,
            headingErrorDegrees = headingErrorDegrees,
            distanceToWaypointMeters = distanceToWaypointMeters,
            distanceToTargetMeters = distanceToTargetMeters,
            trackingConfidence = frame.trackingConfidence,
            mapConfidence = mapConfidence,
            selectedFrontierId = exploration.selected?.id,
            targetDescription = target.description,
            debug = NavigationDebugInfo(
                freeCells = stats.free,
                occupiedCells = stats.occupied,
                unknownCells = stats.unknown,
                frontierCount = exploration.frontiers.size,
                topologicalNodeCount = topology.nodeCount,
                pathLength = currentPath.size,
                floorY = floor.floorY,
                floorConfidence = floor.confidence,
                depthPointsLastFrame = lastDepthPointCount,
                depthAvailable = frame.depthAvailable,
                poseX = frame.pose.x,
                poseY = frame.pose.y,
                poseZ = frame.pose.z,
                yawDegrees = GeometryUtils.radiansToDegrees(frame.pose.yawRadians),
                lastError = stateMachine.lastError,
            ),
        )
        return lastSnapshot
    }

    // ================================================================= introspection (debug/tests)

    fun occupancyGrid(): OccupancyGrid = grid

    fun inflatedGrid(): InflatedGrid? = inflated

    fun frontiers() = exploration.frontiers

    fun currentPathWorld(): List<Vec2> = currentPath

    fun floorEstimate() = floorEstimator.estimate

    private companion object {
        /** ~5 Hz local planning. */
        const val PLAN_INTERVAL_MILLIS = 200L
        /** ~1.5 Hz frontier detection + scoring. */
        const val FRONTIER_INTERVAL_MILLIS = 650L
        /** ~6 Hz inflation refresh (shared by planning and frontier risk scoring). */
        const val INFLATE_INTERVAL_MILLIS = 160L
    }
}
