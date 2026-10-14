package com.navassist.navcore

import com.navassist.navcore.exploration.ExplorationManager
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.FloorEstimator
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.mapping.ObstacleInflator
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.planning.AStarPlanner
import com.navassist.navcore.planning.NavigationController
import com.navassist.navcore.planning.PathSmoother
import com.navassist.navcore.semantic.AdviceDirection
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.PlannerHypothesis
import com.navassist.navcore.semantic.SemanticHintStore
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.semantic.Situation
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.NavigationDebugInfo
import com.navassist.navcore.state.NavigationEvent
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.navcore.state.NavigationStateMachine
import com.navassist.navcore.state.NavigationStatus
import com.navassist.navcore.state.StopReason
import com.navassist.navcore.topology.NodeType
import com.navassist.navcore.topology.TopologicalMap
import kotlin.math.abs
import kotlin.math.max
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
    private val aimPolicy = com.navassist.navcore.planning.AimPolicy(config)
    private val stateMachine = NavigationStateMachine()

    val topology = TopologicalMap()

    /** The trip's evidence ledger and landmarks: facts, never edited (see WorldModel). */
    val world = com.navassist.navcore.world.WorldModel()

    /** Hypotheses about the building, tested against every new reading (see HypothesisStore). */
    val beliefs = com.navassist.navcore.belief.HypothesisStore()

    /** Runs the long-term planner's subgoals on the tactical machinery below. */
    val executor = com.navassist.navcore.strategy.SubgoalExecutor(world, topology)

    /**
     * The long-term planner, consulted on events (see [runStrategy]); null for none, which is the
     * engine as it always was. Planners behind a network call submit through [submitSubgoal].
     */
    var longTermPlanner: com.navassist.navcore.strategy.LongTermPlanner? = null

    /** Subgoals refused by validation, oldest first. */
    val rejections = ArrayList<com.navassist.navcore.strategy.Rejection>()

    private var nextSubgoal = 1
    private var outcomesSeen = 0
    private var plannerNextMillis = 0L
    private var plannerLandmarks = -1
    private var plannerHadSighting = false
    /** The current goal came from a subgoal, not the engine's own choice. */
    private var goalOverride = false

    /** Where the destination's plate was read at its door (VERIFY_DESTINATION); null until then. */
    private var verifiedDestination: Vec2? = null

    private var grid = OccupancyGrid(config)
    private var inflated: InflatedGrid? = null

    /**
     * How far from the sensor depth is integrated: [NavigationConfig.maxDepthMeters], but never
     * so far that it reaches the edge of the rolling window.
     *
     * The user can stand up to [NavigationConfig.gridRecenterThresholdMeters] from the window's
     * centre. Depth that reaches the window's border fills it with FREE cells whose unknown
     * neighbours lie outside the window - and frontiers are never detected on the border - so the
     * one frontier that says "the corridor carries on" vanishes, and the engine decides there is
     * nothing left to explore while standing in an open corridor.
     */
    private val integrationRangeMeters: Float = run {
        val insideWindow = config.gridSizeMeters / 2f - config.gridRecenterThresholdMeters -
            2f * config.gridResolutionMeters
        // Tiny test grids cannot satisfy this; they are not about range.
        if (insideWindow > config.minDepthMeters) min(config.maxDepthMeters, insideWindow) else config.maxDepthMeters
    }

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
    /** Where the path to a located destination actually ends, when that is not the destination. */
    private var targetApproachPoint: Vec2? = null
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

    /** Where the session's first node was dropped, and the furthest the user has been from it. */
    private var sessionOrigin: Vec2? = null
    private var farthestFromOrigin = 0f

    /** Distance walked and when walking began, for advisers. */
    private var walkedMeters = 0f
    private var lastWalkPosition: Vec2? = null
    private var sessionStartMillis: Long? = null

    private var lastNodeYaw: Float = 0f
    private var lastDepthPointCount: Int = 0

    private var backtrackTargetNodeId: String? = null

    /**
     * Two separate failure clocks for a located target, both 0 when healthy.
     *
     * They must not be one field. Falling back to the graph is a response to the DIRECT route
     * failing; abandoning the sighting is a response to EVERY route failing. Collapsing them
     * makes a successful graph hop reset the direct-route clock, which sends the next frame
     * straight back to the route that just failed - the engine then oscillates between
     * NAVIGATING and NO_ROUTE once per frame.
     */
    private var targetDirectFailingSinceMillis: Long = 0
    private var targetStuckSinceMillis: Long = 0

    /** True when the current goal came from the graph rather than the destination itself. */
    private var targetGoalViaGraph: Boolean = false

    /** Milliseconds of useful scanning (tracking + depth) accumulated while LOCALIZING. */
    private var scanElapsedMillis: Long = 0

    /** How far the user has turned during the opening look-around, and their yaw last frame. */
    private var lookAroundRadians = 0f
    private var lookAroundLastYaw: Float? = null

    val snapshot: NavigationSnapshot get() = lastSnapshot

    val status: NavigationStatus get() = stateMachine.status

    /**
     * The engine's own clock: the platform timestamp of the latest frame, in milliseconds.
     *
     * Everything time-based in here (hint ageing, sighting expiry, route clocks) runs on this
     * clock. Callers that timestamp input for the engine - semantic observations above all - must
     * use it rather than a wall clock, or ageing compares two unrelated clocks and never fires.
     */
    var clockMillis: Long = 0
        private set

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
        semantics.clearTargetSighting(forgetGlimpses = true)
        exploration.clearSelection()
        currentPath = emptyList()
        currentGoal = null
        backtrackTargetNodeId = null
        clearTargetRouteFailures()
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
        targetApproachPoint = null
        floorEstimator.reset()
        controller.reset()
        exploration.reset()
        semantics.reset()
        topology.reset()
        world.reset()
        beliefs.reset()
        executor.reset()
        longTermPlanner?.reset()
        rejections.clear()
        nextSubgoal = 1
        outcomesSeen = 0
        plannerNextMillis = 0L
        plannerLandmarks = -1
        plannerHadSighting = false
        goalOverride = false
        verifiedDestination = null
        stateMachine.reset()
        currentPath = emptyList()
        currentGoal = null
        lastNodeId = null
        lastNodePosition = null
        sessionOrigin = null
        farthestFromOrigin = 0f
        walkedMeters = 0f
        lastWalkPosition = null
        sessionStartMillis = null
        backtrackTargetNodeId = null
        clearTargetRouteFailures()
        originInitialized = false
        lastFrameTimestampNanos = 0
        lastDepthTimestampNanos = 0
        lastTrackingGoodMillis = 0
        lastDepthPointCount = 0
        scanElapsedMillis = 0
        lookAroundRadians = 0f
        lookAroundLastYaw = null
        started = false
        target = NavigationTarget.Explore
    }

    /**
     * Clears the learned map but keeps the session, the destination and the coordinate frame.
     *
     * Semantic memory survives on purpose: sightings are resolved from depth, not from the map,
     * and stay valid as long as the canonical frame does - which is why the platform adapter
     * must NOT re-anchor its pose conversion on a map reset.
     */
    fun resetMap() {
        grid.clear()
        inflated = null
        targetApproachPoint = null
        floorEstimator.reset()
        exploration.reset()
        topology.reset()
        controller.reset()
        currentPath = emptyList()
        currentGoal = null
        lastNodeId = null
        lastNodePosition = null
        sessionOrigin = null
        farthestFromOrigin = 0f
        walkedMeters = 0f
        lastWalkPosition = null
        sessionStartMillis = null
        backtrackTargetNodeId = null
        clearTargetRouteFailures()
        lastFrontierMillis = 0
        // Clearing the map means the engine knows nothing again, so it must re-earn the right to
        // give instructions rather than coasting on the previous scan. The scan gate only runs in
        // LOCALIZING, so zeroing the budget alone is not enough: the state machine goes back too.
        scanElapsedMillis = 0
        lookAroundRadians = 0f
        lookAroundLastYaw = null
        stateMachine.on(NavigationEvent.MapReset)
        originInitialized = false
    }

    /**
     * @param nowMillis on the engine's clock - pass [clockMillis], never a wall clock.
     * @param observerPose where the user stood when the image was captured, which may be seconds
     *        before the observation arrives. Directional hints are resolved against it.
     */
    fun submitSemanticObservations(
        observations: List<SemanticObservation>,
        nowMillis: Long,
        observerPose: Pose3D = lastPose,
        bearings: List<Vec2?> = emptyList(),
        elevations: List<Float?> = emptyList(),
    ) {
        val floor = floorEstimator.estimate
        val cameraHeight = if (floor.confidence >= config.floorMinConfidence) observerPose.position3D.y - floor.floorY else null
        // A floor read (a lift display, a floor sign) first: it says where everything else was seen.
        observations.filterIsInstance<SemanticObservation.Floor>().lastOrNull()?.let { changeFloor(it.floor, nowMillis) }
        // Every report is evidence, whatever its score: the ledger records what perception said; what
        // to believe is decided downstream.
        for ((i, observation) in observations.withIndex()) world.record(observation, nowMillis, observerPose, bearings.getOrNull(i))
        beliefs.update(world, target, nowMillis)
        semantics.submit(observations, nowMillis, observerPose, target, bearings, elevations, cameraHeight)
        if (config.arrivalRequiresVerification) verifyByCloseReading(observations, observerPose, bearings)
        // Landmarks worth remembering at building scale go straight into the graph.
        for (observation in observations) {
            val world = observation.worldPosition ?: continue
            val type = when (observation) {
                is SemanticObservation.Room -> NodeType.ROOM
                is SemanticObservation.Exit -> NodeType.EXIT
                is SemanticObservation.Door -> NodeType.DOOR
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
            // Deliberately NOT connected to the node we are standing on.
            //
            // Seeing a door plate says where it is, not that there is a walkable corridor from
            // here to there - it may be across a lobby, behind glass, or on the far side of a
            // stairwell. Adding that edge would let global routing "walk" a straight line the
            // user cannot actually take, which is exactly the failure the graph exists to avoid.
            // Graph edges mean "I have travelled this"; landmark nodes are position memory only.
        }
    }

    private var lastPose: Pose3D = Pose3D.IDENTITY

    /** The advice (by arrival time) already acted on by [lookTowardsAdvice]. */
    private var adviceLookedAtMillis = -1L

    /**
     * Advice can only favour frontiers the map already has. Pointing where it has none - behind
     * the user at the start, since the opening scan only sweeps the view ahead - it would favour
     * nothing and be outvoted. Then turn the user to face that way, once per answer: the map
     * fills in, a frontier appears there, and the advice has something to choose. Turning on the
     * spot is always safe.
     */
    private fun lookTowardsAdvice(pose: Pose3D, nowMillis: Long): NavigationCommand? {
        if (!config.adviceTurnsToLook || stateMachine.status != NavigationStatus.EXPLORING) return null
        val advice = semantics.strongestAdvice(nowMillis) ?: return null
        val stamp = semantics.adviceReceivedAtMillis
        if (stamp == adviceLookedAtMillis) return null
        // Only frontiers worth walking to: the edge of the user's own footprint is a frontier too.
        val matched = exploration.frontiers.filter { it.centroid.distanceTo(pose.position2D) > config.frontierReachedMeters }
            .maxOfOrNull { semantics.adviceScoreFor(it.centroid, nowMillis) } ?: 0f
        val error = GeometryUtils.angleDifference(pose.yawRadians, advice.direction.yawRadians())
        if (matched >= ADVICE_MATCHED_SCORE || abs(error) <= GeometryUtils.degreesToRadians(ADVICE_FACING_DEGREES)) {
            adviceLookedAtMillis = stamp
            lastFrontierMillis = 0
            return null
        }
        return if (error > 0f) NavigationCommand.TURN_RIGHT else NavigationCommand.TURN_LEFT
    }

    /** Until the turn is done - or has had its time: someone who cannot turn is not held forever. */
    private fun lookingAround(): Boolean =
        lookAroundRadians < GeometryUtils.degreesToRadians(config.initialLookAroundDegrees) &&
            scanElapsedMillis < config.minScanMillis + config.initialLookAroundMaxExtraMillis

    // ================================================================= frame update

    fun updateFrame(frame: NavigationFrame): NavigationSnapshot {
        val nowMillis = frame.timestampNanos / 1_000_000
        clockMillis = nowMillis
        lastPose = frame.pose
        semantics.noteSearched(frame.pose.position2D)

        if (!started || stateMachine.status == NavigationStatus.IDLE) {
            return finish(nowMillis, frame, NavigationCommand.STOP, null, null, stopReason = StopReason.NOT_STARTED)
        }
        if (stateMachine.status == NavigationStatus.PAUSED) {
            return finish(nowMillis, frame, NavigationCommand.STOP, null, null, stopReason = StopReason.PAUSED)
        }

        // ---------------------------------------------------------- 1. tracking gate (Rule 4)
        val trackingGood = frame.tracking && frame.trackingConfidence >= config.minTrackingConfidence
        if (!trackingGood) {
            lastTrackingGoodMillis = 0
            stateMachine.on(NavigationEvent.TrackingLost)
            currentPath = emptyList()
            controller.reset()
            return finish(nowMillis, frame, NavigationCommand.STOP, null, null, stopReason = StopReason.TRACKING_LOST)
        }
        if (lastTrackingGoodMillis == 0L) lastTrackingGoodMillis = nowMillis
        val trackingStable = nowMillis - lastTrackingGoodMillis >= config.trackingRecoveryMillis
        if (stateMachine.status == NavigationStatus.INITIALIZING ||
            (stateMachine.status == NavigationStatus.LOST_TRACKING && trackingStable)
        ) {
            stateMachine.on(NavigationEvent.TrackingAcquired)
        }
        if (stateMachine.status == NavigationStatus.LOST_TRACKING) {
            return finish(nowMillis, frame, NavigationCommand.STOP, null, null, stopReason = StopReason.TRACKING_LOST)
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

        // One frame is one observation: everything below is collected, then committed once.
        markFootprint(frame.pose)
        if (frame.depthAvailable && frame.points.count > 0) {
            lastDepthTimestampNanos = frame.timestampNanos
            lastDepthPointCount = frame.points.count
            val floor = floorEstimator.update(
                frame.points,
                frame.pose.y,
                frame.floorHint,
                frame.floorHintConfidence,
                nowMillis,
            )
            if (floor.confidence >= config.floorMinConfidence) {
                integrateDepth(frame, floor.floorY)
            }
        }
        frame.lidar?.let { integrateLidar(frame.pose, it) }
        grid.commitObservation()
        bridgeBlindZone(frame.pose)

        val depthStale = lastDepthTimestampNanos == 0L ||
            (frame.timestampNanos - lastDepthTimestampNanos) / 1_000_000 > config.depthStarvationMillis

        val mapConfidence =
            grid.knownFractionWithin(frame.pose.position2D, config.mapConfidenceRadiusMeters)
        val floorReady = floorEstimator.estimate.confidence >= config.floorMinConfidence

        if (stateMachine.status == NavigationStatus.LOCALIZING) {
            // Only time with live depth counts towards the scan budget. "Live" rather than "this
            // frame carried depth": depth updates more slowly than the camera, and the adapter
            // does not resend a depth image it already delivered.
            val depthFresh = lastDepthTimestampNanos != 0L &&
                (frame.timestampNanos - lastDepthTimestampNanos) / 1_000_000 <= config.scanDepthFreshMillis
            if (depthFresh) {
                scanElapsedMillis += (deltaSeconds * 1000f).toLong()
            }
            lookAroundLastYaw?.let { lookAroundRadians += abs(GeometryUtils.angleDifference(it, frame.pose.yawRadians)) }
            lookAroundLastYaw = frame.pose.yawRadians
            if (floorReady &&
                mapConfidence >= config.minMapConfidenceToNavigate &&
                scanElapsedMillis >= config.minScanMillis &&
                !lookingAround()
            ) {
                stateMachine.on(NavigationEvent.MapReady)
            }
        }
        if (stateMachine.status == NavigationStatus.LOCALIZING) {
            // First a full turn on the spot, so the map starts with every way out - a sweep of the
            // view ahead leaves the way behind unknown, and a destination there with it.
            return finish(
                nowMillis, frame, if (lookingAround()) NavigationCommand.TURN_RIGHT else NavigationCommand.SCAN,
                null, null, mapConfidence, stopReason = StopReason.MAP_INCOMPLETE,
            )
        }

        // ---------------------------------------------------------- 3. depth starvation (Rule 4)
        if (depthStale) {
            currentPath = emptyList()
            return finish(
                nowMillis, frame, NavigationCommand.SCAN, null, null, mapConfidence,
                stopReason = StopReason.NO_DEPTH,
            )
        }

        // ---------------------------------------------------------- 4. memory upkeep
        updateTopology(frame.pose, nowMillis)
        // Ageing semantic evidence must not depend on new observations arriving: the moment the
        // engine is stuck facing a wall, perception has nothing to report, so a submit-time-only
        // prune would pin a stale sighting forever.
        semantics.prune(nowMillis)

        // ---------------------------------------------------------- 5. planning view of the map
        val inflatedGrid = refreshInflation(nowMillis)

        // ---------------------------------------------------------- 6. where are we going?
        lookTowardsAdvice(frame.pose, nowMillis)?.let { turn ->
            return finish(nowMillis, frame, turn, null, null, mapConfidence)
        }
        runStrategy(nowMillis)
        val directive = executor.directive(
            frame.pose, nowMillis, walkedMeters,
            frontierNear = { at -> exploration.frontiers.firstOrNull { it.centroid.distanceTo(at) <= FRONTIER_TRACK_METERS }?.centroid },
            targetMatches = ::landmarkNamesTarget,
        )
        executor.verifiedLandmarkId?.let { id ->
            // Read at the door: arrival there is now allowed, by walking up to it as usual.
            executor.verifiedLandmarkId = null
            verifiedDestination = world.landmark(id)?.position
        }
        if (directive is com.navassist.navcore.strategy.SubgoalExecutor.Directive.Turn) {
            currentPath = emptyList()
            return finish(nowMillis, frame, directive.command, null, null, mapConfidence)
        }
        goalOverride = directive is com.navassist.navcore.strategy.SubgoalExecutor.Directive.HeadFor
        val goal = if (directive is com.navassist.navcore.strategy.SubgoalExecutor.Directive.HeadFor) {
            clampToGrid(frame.pose.position2D, directive.goal).also { currentGoal = it }
        } else {
            resolveGoal(frame.pose, inflatedGrid, nowMillis, mapConfidence)
        }
        if (goal == null) {
            currentPath = emptyList()
            return finish(
                nowMillis, frame, NavigationCommand.SCAN, null, null, mapConfidence,
                stopReason = StopReason.NO_ROUTE,
            )
        }

        // ---------------------------------------------------------- 7. arrival
        // A glimpse is where to look, not where to arrive: once there, it has done its job - a
        // placed reading has replaced it, or there is nothing to find and exploring resumes.
        semantics.targetSighting?.takeIf { it.glimpsed }?.let {
            if (it.worldPosition.toVec2().distanceTo(frame.pose.position2D) <= config.glimpseReachedMeters) semantics.spendGlimpse(nowMillis)
        }
        val glimpsed = semantics.targetSighting?.glimpsed == true
        val distanceToTarget = (finalDestination() ?: verifiedDestination.takeIf { config.arrivalRequiresVerification })?.distanceTo(frame.pose.position2D)
        // Arrival also counts at the reachable spot nearest the destination (see refreshPath).
        // Only near the destination itself, though: at the approach spot's edge the user could
        // otherwise be told they had arrived 2.5 m from it.
        val arrivalDistance = targetApproachPoint?.let { approach ->
            val target = finalDestination()
            if (target != null && approach.distanceTo(target) <= config.targetApproachRadiusMeters &&
                (distanceToTarget ?: Float.MAX_VALUE) <= config.targetApproachRadiusMeters
            ) {
                minOf(distanceToTarget ?: Float.MAX_VALUE, approach.distanceTo(frame.pose.position2D))
            } else null
        } ?: distanceToTarget
        // Arrival must be read at the door when so configured: only near a verified destination.
        val verifiedHere = verifiedDestination?.let { v -> finalDestination()?.let { it.distanceTo(v) <= VERIFIED_MATCH_METERS } ?: true } ?: false
        val proximityAllowed = !glimpsed && (!config.arrivalRequiresVerification || verifiedHere)
        if (controller.updateArrival(arrivalDistance.takeIf { proximityAllowed }, nowMillis)) {
            stateMachine.on(NavigationEvent.Arrived)
            return finish(nowMillis, frame, NavigationCommand.ARRIVED, null, distanceToTarget, mapConfidence)
        }
        if (stateMachine.status == NavigationStatus.ARRIVED) {
            return finish(nowMillis, frame, NavigationCommand.ARRIVED, null, distanceToTarget, mapConfidence)
        }

        // ---------------------------------------------------------- 8. local path
        val path = refreshPath(frame.pose, inflatedGrid, goal, nowMillis)
        if (goalOverride) executor.reportRoute(path.size >= 2, nowMillis, walkedMeters)
        if (path.size < 2) {
            exploration.reportPlanningFailure(nowMillis)
            recordTargetRouteOutcome(succeeded = false, nowMillis = nowMillis)
            stateMachine.on(NavigationEvent.RouteUnavailable)
            return finish(
                nowMillis, frame, NavigationCommand.SCAN, null, distanceToTarget, mapConfidence,
                stopReason = StopReason.NO_ROUTE,
            )
        }
        exploration.reportPlanningSuccess()
        recordTargetRouteOutcome(succeeded = true, nowMillis = nowMillis)
        if (stateMachine.status == NavigationStatus.NO_ROUTE) {
            stateMachine.on(NavigationEvent.RouteFound)
        }

        // ---------------------------------------------------------- 9. command
        val output = controller.update(frame.pose, path, inflatedGrid, nowMillis, grid)
        return finish(
            nowMillis = nowMillis,
            frame = frame,
            command = output.command,
            headingErrorDegrees = output.headingErrorDegrees,
            distanceToTargetMeters = distanceToTarget,
            mapConfidence = mapConfidence,
            distanceToWaypointMeters = output.distanceToWaypointMeters,
            stopReason = output.stopReason,
        )
    }

    // ================================================================= mapping helpers

    /**
     * Classifies every depth sample against the floor estimate and records it in the pending
     * grid observation (committed once per frame by the caller).
     *
     * FREE space comes only from floor returns, and only from the stretch of each floor ray that
     * is provably below obstacle height. A ray that reaches the floor 3 m away proves that nothing
     * along it was taller than the ray itself at each point - so where the ray passes 60 cm above
     * the floor, a 40 cm box could be sitting underneath it. Flattening every ray to 2D and
     * clearing the whole line (what this used to do) erased exactly those low obstacles: a box in
     * front of a wall lost every frame to the rays aimed at the wall above it.
     *
     * Obstacle returns mark only their own cell. They clear nothing, for the same reason.
     *
     * The carved stretch grows with range (it is range * minHeight / sensorHeight), which matches
     * how the spacing between floor samples grows with range, so coverage stays continuous.
     */
    private fun integrateDepth(frame: NavigationFrame, floorY: Float) {
        val points = frame.points
        val sensor = frame.sensorPosition ?: frame.pose.position3D
        val originX = sensor.x
        val originZ = sensor.z
        val sensorHeight = sensor.y - floorY
        val minHeight = config.minObstacleHeightMeters
        val maxHeight = config.maxObstacleHeightMeters
        val maxRangeSq = integrationRangeMeters * integrationRangeMeters
        val minRangeSq = config.minDepthMeters * config.minDepthMeters
        val obstacleRange = min(config.maxObstacleRangeMeters, integrationRangeMeters)
        val obstacleRangeSq = obstacleRange * obstacleRange
        val nearFloorSq = config.nearFloorVerifyMeters * config.nearFloorVerifyMeters
        val limit = min(points.count, config.maxPointsPerFrame)

        for (i in 0 until limit) {
            val px = points.x(i)
            val py = points.y(i)
            val pz = points.z(i)
            if (px.isNaN() || py.isNaN() || pz.isNaN()) continue

            val dx = px - originX
            val dz = pz - originZ
            val rangeSq = dx * dx + dz * dz
            if (rangeSq > maxRangeSq || rangeSq < minRangeSq) continue

            val height = py - floorY
            when {
                // Overhead structure: tells us nothing about the floor plan under it.
                height > maxHeight -> continue
                // Far below the floor: the floor is missing where this ray crossed floor level.
                height < -config.dropOffDepthMeters && sensorHeight > 0f -> {
                    val t = sensorHeight / (sensorHeight - height)
                    val cell = grid.worldToGrid(Vec2(originX + dx * t, originZ + dz * t))
                    grid.observeOccupied(cell.gx, cell.gz)
                    // An edge needs more room than a wall: brushing a wall is a bump, brushing a
                    // drop is a fall. And a drop's edge can sit past floor-level ground that is not
                    // floor - the flush top plate of a descending escalator, 0.5 m before its
                    // first step down. Extend it back towards the observer, the side one walks up.
                    val range = kotlin.math.sqrt(rangeSq) * t
                    if (range > 1e-3f && config.dropMarginMeters > 0f) {
                        var back = grid.resolution
                        while (back <= config.dropMarginMeters && back < range) {
                            val f = (range - back) / range * t
                            val c = grid.worldToGrid(Vec2(originX + dx * f, originZ + dz * f))
                            grid.observeOccupied(c.gx, c.gz)
                            back += grid.resolution
                        }
                    }
                }
                // Slightly below the floor we think we have: noise, or an estimate a little off.
                // It says nothing trustworthy about what is walkable; dropping it is the safe failure.
                height < -config.floorBandBelowMeters -> continue
                // Floor / low ground: carve the low end of the ray.
                height < minHeight -> {
                    // Fraction along the ray from which it stays below obstacle height.
                    val t = if (sensorHeight <= minHeight) {
                        0f
                    } else {
                        ((sensorHeight - minHeight) / (sensorHeight - height)).coerceIn(0f, 1f)
                    }
                    grid.observeFreeLine(Vec2(originX + dx * t, originZ + dz * t), Vec2(px, pz))
                    if (rangeSq <= nearFloorSq) grid.worldToGrid(Vec2(px, pz)).let { grid.markNearSeen(it.gx, it.gz) }
                }
                // Body-height obstacle - but only where depth is accurate enough to tell one from
                // a noisy floor return. See NavigationConfig.maxObstacleRangeMeters.
                rangeSq > obstacleRangeSq -> continue
                else -> {
                    val cell = grid.worldToGrid(Vec2(px, pz))
                    grid.observeOccupied(cell.gx, cell.gz)
                }
            }
        }
    }

    /**
     * One horizontal LiDAR revolution, placed by the camera pose (the two are mounted together).
     *
     * A return is a solid surface at scan height: an occupied cell, recorded in the same per-frame
     * observation as depth. The cells before it are plane-clear - air at scan height - which
     * planning may route through but the controller will not step onto until depth has seen the
     * floor there (see OccupancyGrid.isPlaneClear). A beam without a return clears nothing: it
     * may have passed through glass, or off a dark surface, as easily as down an empty corridor.
     */
    private fun integrateLidar(pose: Pose3D, scan: com.navassist.navcore.geometry.LidarScan) {
        val origin = pose.position2D
        val minRange = config.lidarMinRangeMeters
        val maxRange = lidarRangeMeters
        for (i in 0 until scan.count) {
            val range = scan.rangeMeters[i]
            if (range < minRange || range > maxRange) continue
            val direction = Vec2.fromYaw(pose.yawRadians + scan.angleRadians[i])
            val end = origin + direction * range
            val endCell = grid.worldToGrid(end)
            if (config.lidarClearsPlane) {
                val start = grid.worldToGrid(origin)
                OccupancyGrid.traceLine(start.gx, start.gz, endCell.gx, endCell.gz) { gx, gz, isEndpoint ->
                    if (!isEndpoint) grid.markPlaneClear(gx, gz)
                }
            }
            if (config.lidarMarksObstacles) grid.observeOccupied(endCell.gx, endCell.gz)
        }
    }

    /** LiDAR range, never reaching the rolling window's edge (see [integrationRangeMeters]). */
    private val lidarRangeMeters: Float = run {
        val insideWindow = config.gridSizeMeters / 2f - config.gridRecenterThresholdMeters -
            2f * config.gridResolutionMeters
        if (insideWindow > config.lidarMinRangeMeters) min(config.lidarMaxRangeMeters, insideWindow) else config.lidarMaxRangeMeters
    }

    /**
     * Bridges the floor the phone cannot see: the strip just in front of it, below the camera's
     * view even when tilted down. Without this nothing can be planned from a standing start - the
     * body's footprint and the first visible floor are separated by half a metre of unknown.
     *
     * Only along the heading, only UNKNOWN cells, and only when seen FREE floor continues
     * directly beyond the gap. An edge the user has never seen is never bridged: beyond it there
     * is no seen floor, only unknown or a drop-off (which is an obstacle, see dropOffDepthMeters).
     */
    private fun bridgeBlindZone(phone: Pose3D) {
        val zone = config.blindZoneMeters
        if (zone <= 0f) return
        val step = grid.resolution
        val forward = phone.forward2D
        val right = Vec2(forward.z, -forward.x)
        val lanes = floatArrayOf(-step, 0f, step)
        for (lane in lanes) {
            val origin = phone.position2D + right * lane
            // Find where seen floor resumes, within the zone plus a little.
            var resume = -1f
            var d = step
            // A fixed camera never sees the floor just past the body: skip the footprint first, so
            // "seen floor resumes" means beyond the gap rather than the footprint's own edge.
            if (config.blindZoneSkipsFootprint) {
                while (d <= zone && grid.stateAtWorld(origin + forward * d) == CellState.FREE) d += step
            }
            while (d <= zone + 3f * step) {
                val state = grid.stateAtWorld(origin + forward * d)
                if (state == CellState.OCCUPIED) break
                if (state == CellState.FREE && d > step) { resume = d; break }
                d += step
            }
            if (resume < 0f) continue
            // Bridged floor is as trustworthy as the floor it bridges to.
            val nearBeyond = grid.isNearSeen(origin + forward * resume)
            var fill = step
            while (fill < resume) {
                val cell = grid.worldToGrid(origin + forward * fill)
                if (grid.stateAt(cell.gx, cell.gz) == CellState.UNKNOWN) grid.observeFree(cell.gx, cell.gz)
                if (nearBeyond) grid.markNearSeen(cell.gx, cell.gz)
                fill += step
            }
        }
        grid.commitObservation()
    }

    /**
     * The user is standing here, so it is walkable - the one piece of free space no depth sensor
     * held at chest height can see. Centred on the BODY, behind the phone, never on the phone
     * itself: nothing in front of the phone is known to be floor until it has been seen.
     * Occupied cells are skipped: a wall beside the user is out of view, and nothing would ever
     * restore it if the footprint wore it away.
     */
    private fun markFootprint(phone: Pose3D) {
        val radius = config.footprintFreeRadiusMeters
        if (radius <= 0f) return
        val body = phone.position2D - phone.forward2D * config.footprintBehindPhoneMeters
        val center = grid.worldToGrid(body)
        val radiusCells = radius / grid.resolution
        val reach = kotlin.math.ceil(radiusCells).toInt()
        val radiusSq = radiusCells * radiusCells
        for (dz in -reach..reach) {
            for (dx in -reach..reach) {
                if (dx * dx + dz * dz > radiusSq) continue
                val gx = center.gx + dx
                val gz = center.gz + dz
                if (grid.stateAt(gx, gz) == CellState.OCCUPIED) continue
                grid.observeFree(gx, gz)
                grid.markNearSeen(gx, gz)
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
        val origin = sessionOrigin ?: position.also { sessionOrigin = it }
        farthestFromOrigin = max(farthestFromOrigin, origin.distanceTo(position))
        if (sessionStartMillis == null) sessionStartMillis = nowMillis
        lastWalkPosition?.let { walkedMeters += it.distanceTo(position) }
        lastWalkPosition = position
        val previous = lastNodePosition
        val travelled = previous?.distanceTo(position) ?: Float.MAX_VALUE
        val turned = abs(GeometryUtils.angleDifference(lastNodeYaw, pose.yawRadians))

        val shouldDrop = previous == null ||
            travelled >= config.topoNodeSpacingMeters ||
            (turned >= config.topoTurnThresholdRadians && travelled >= config.topoNodeSpacingMeters * 0.4f)
        if (!shouldDrop) return

        // The first node is dropped before any frontier pass, so it is always typed DEAD_END. That
        // is kept on purpose: the space behind where the user starts is usually where they came
        // from, and its revisit penalty stops the engine turning them round to explore it (typed a
        // plain waypoint, the navigation-sim corridor scenario walked back and stalled there).
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
            val goal = goalForLocatedTarget(pose, sighting.worldPosition.toVec2(), nowMillis)
            if (goal != null) {
                if (stateMachine.status != NavigationStatus.NAVIGATING) {
                    stateMachine.on(NavigationEvent.TargetLocated)
                }
                exploration.clearSelection()
                currentGoal = goal
                return currentGoal
            }
            // The sighting was just abandoned; fall through and explore instead of standing still.
        }

        if (stateMachine.status == NavigationStatus.NAVIGATING) {
            stateMachine.on(NavigationEvent.TargetLost)
        }

        // Frontier detection is expensive; run it at ~1.5 Hz rather than per frame.
        if (nowMillis - lastFrontierMillis >= FRONTIER_INTERVAL_MILLIS || exploration.selected == null) {
            lastFrontierMillis = nowMillis
            exploration.update(grid, inflatedGrid, pose, explorationTarget(), semantics, topology, nowMillis)
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
     * Where to head when the destination's position IS known.
     *
     * Three tiers, in order:
     *
     *  1. Straight at it. While the local planner can find a way, nothing else is needed - and
     *     this is the common case, because a sighting is resolved from depth and therefore starts
     *     within a few metres of the user.
     *
     *  2. Through the remembered graph. Once the direct route starts failing, the destination is
     *     behind something the 12 m window cannot see around: a wall, a corner, a closed branch.
     *     The straight-line bearing is then actively misleading - it points INTO the obstacle - so
     *     we route over the breadcrumbs the engine has been dropping all along and aim at the next
     *     hop instead. This is the hierarchical planning path from the architecture: topological
     *     A* picks the waypoint, local A* walks to it.
     *
     *  3. Give up on the sighting. If even the graph cannot produce a route for
     *     [NavigationConfig.targetRouteAbandonMillis], the sighting is stale or unreachable.
     *     Returning null abandons it so exploration can resume. Without this the engine latches:
     *     a pinned sighting suppresses frontier selection, and nothing else would ever clear it.
     *
     * @return the goal to plan towards, or null when the sighting has just been abandoned.
     */
    private fun goalForLocatedTarget(pose: Pose3D, destination: Vec2, nowMillis: Long): Vec2? {
        // Back inside the local window: the direct approach is worth another try. Without this
        // the engine would keep routing over the graph all the way to the door.
        if (grid.contains(destination)) targetDirectFailingSinceMillis = 0

        val stuckSince = targetStuckSinceMillis
        if (stuckSince != 0L && nowMillis - stuckSince >= config.targetRouteAbandonMillis) {
            semantics.clearTargetSighting()
            targetStuckSinceMillis = 0
            targetDirectFailingSinceMillis = 0
            targetGoalViaGraph = false
            if (stateMachine.status == NavigationStatus.NAVIGATING) {
                stateMachine.on(NavigationEvent.TargetLost)
            }
            return null
        }

        if (targetDirectFailingSinceMillis == 0L) {
            targetGoalViaGraph = false
            return clampToGrid(pose.position2D, destination)
        }

        val hop = nextHopTowards(pose, destination)
        targetGoalViaGraph = hop != null
        return clampToGrid(pose.position2D, hop ?: destination)
    }

    /**
     * Topological A* from where we are to the place nearest the destination, returning the first
     * hop that is far enough away to be worth steering at.
     *
     * This is the "go back the way you came" behaviour: the hop is somewhere the user has already
     * walked, so it is both inside the local window and known to be traversable, which is exactly
     * what the local planner needs to make progress.
     */
    private fun nextHopTowards(pose: Pose3D, destination: Vec2): Vec2? {
        val floorId = semantics.currentFloorId
        val from = lastNodeId?.let { topology.node(it) }
            ?: topology.findNearest(pose.position2D, config.topoNodeSpacingMeters * 2f, floorId)
            ?: return null
        // The destination itself may only exist as an edgeless landmark node, so aim at the
        // nearest place we have actually walked instead.
        val to = topology.findNearestConnected(destination, config.topoNodeSpacingMeters * 2f, floorId)
            ?: return null
        if (from.id == to.id) return null

        val route = topology.planRoute(from.id, to.id) ?: return null
        // Skip hops we are effectively standing on, or we would steer at our own feet.
        return route.drop(1).firstOrNull {
            it.position.distanceTo(pose.position2D) > config.topoNodeSpacingMeters * 0.5f
        }?.position
    }

    /** Bookkeeping for tier 2/3 above. Called once per frame from [updateFrame]. */
    private fun recordTargetRouteOutcome(succeeded: Boolean, nowMillis: Long) {
        if (semantics.targetSighting == null) {
            clearTargetRouteFailures()
            return
        }
        if (succeeded) {
            // Any success means we are moving again, so the abandon clock stops. The direct-route
            // clock only stops if it was the direct route that worked.
            targetStuckSinceMillis = 0
            if (!targetGoalViaGraph) targetDirectFailingSinceMillis = 0
            return
        }
        if (targetStuckSinceMillis == 0L) targetStuckSinceMillis = nowMillis
        if (!targetGoalViaGraph && targetDirectFailingSinceMillis == 0L) {
            targetDirectFailingSinceMillis = nowMillis
        }
    }

    private fun clearTargetRouteFailures() {
        targetDirectFailingSinceMillis = 0
        targetStuckSinceMillis = 0
        targetGoalViaGraph = false
    }

    /**
     * Dead-end handling. Marks the current place exhausted so we never select it again, then
     * routes back through the remembered graph to the nearest junction with unexplored ways out.
     */
    private fun backtrackGoal(pose: Pose3D): Vec2? {
        // Only a place the user has walked to can be a dead end. Straight after the scan, "no
        // frontier to choose" means the map is not ready; writing the start off then left no node
        // to retreat to, and the engine sat in NO_ROUTE for good.
        if (farthestFromOrigin >= config.minTravelBeforeDeadEndMeters) {
            lastNodeId?.let {
                topology.markType(it, NodeType.DEAD_END)
                topology.markExhausted(it)
            }
            exploration.blacklistArea(pose.position2D)
        }

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
        // Only exploration goals may touch unknown space, and only right at the goal. A subgoal's
        // goal (a viewpoint, a frontier it names) may be just as unseen.
        val exploring = semantics.targetSighting == null || goalOverride
        // A destination is often not itself somewhere to stand: a sign over a glass shop front, a
        // plate on a wall, a spot the map has not scanned. Aim at the nearest reachable cell
        // within the approach radius instead, and let arrival count there.
        val destination = finalDestination()
        val aimingAtDestination = !exploring && destination != null && destination.distanceTo(goal) < grid.resolution
        val searchCells = if (aimingAtDestination) (config.targetApproachRadiusMeters / grid.resolution).toInt() else null
        val direct = planner.plan(inflatedGrid, start, goalCell, allowUnknownNearGoal = exploring, goalSearchCells = searchCells)
        // No route to a known destination: it lies in space the map has not seen, or has scrolled
        // past. Head for the unexplored edge nearest it rather than give up on it; mapping on the
        // way either opens a route or shows there is none.
        val cells: List<GridCoordinate> = direct
            ?: destination?.takeIf { !exploring }?.let { towards ->
                exploration.frontiersToward(grid, inflatedGrid, pose.position2D, towards).asSequence().take(3)
                    .firstNotNullOfOrNull { planner.plan(inflatedGrid, start, it.centroidCell, allowUnknownNearGoal = true) }
            }
            ?: run {
                currentPath = emptyList()
                return emptyList()
            }
        targetApproachPoint = if (aimingAtDestination && direct != null) inflatedGrid.gridToWorld(cells.last()) else null

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
        stopReason: StopReason? = null,
    ): NavigationSnapshot {
        if (command != controller.currentCommand) controller.forceCommand(command)
        val stats = grid.stats()
        val floor = floorEstimator.estimate
        val halting = command == NavigationCommand.STOP || command == NavigationCommand.SCAN
        lastSnapshot = NavigationSnapshot(
            timestampMillis = nowMillis,
            status = stateMachine.status,
            command = command,
            stopReason = if (halting) stopReason else null,
            headingErrorDegrees = headingErrorDegrees,
            distanceToWaypointMeters = distanceToWaypointMeters,
            distanceToTargetMeters = distanceToTargetMeters,
            trackingConfidence = frame.trackingConfidence,
            mapConfidence = mapConfidence,
            selectedFrontierId = exploration.selected?.id,
            // Null while merely exploring. The guidance layer prefixes this to every spoken
            // instruction, so advertising a placeholder destination would have it announce
            // "Explore. Straight." on every single command.
            targetDescription = target.takeIf { it != NavigationTarget.Explore }?.description,
            aim = aimPolicy.aimFor(target, stateMachine.status),
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
                scanProgress = if (config.minScanMillis <= 0) {
                    1f
                } else {
                    min(1f, scanElapsedMillis.toFloat() / config.minScanMillis)
                },
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

    /** Most recent device pose, for debug rendering. */
    fun currentPose(): Pose3D = lastPose

    /**
     * Advice from outside the engine - an LLM, a decision model, a floor plan - on which way to
     * explore. It biases frontier choice like a sign and expires after
     * [NavigationConfig.adviceMaxAgeMillis]; it cannot plan, steer, or lift a stop.
     */
    fun submitAdvice(directions: List<AdviceDirection>, nowMillis: Long = clockMillis) {
        semantics.setAdvice(directions, nowMillis)
        // Re-rank at once rather than at the next frontier pass, and let go of a committed
        // waypoint the adviser warns against - or simply does not point at, when it points at
        // another: commitment otherwise outlasts any new evidence, and the first choice, made
        // before any advice arrived, held against it all the way down the wrong corridor.
        lastFrontierMillis = 0
        val selected = exploration.selected ?: return
        val advised = semantics.adviceScoreFor(selected.centroid, nowMillis)
        val bestAdvised = exploration.frontiers.maxOfOrNull { semantics.adviceScoreFor(it.centroid, nowMillis) } ?: 0f
        if (advised < 0f || (config.adviceReleasesCommitment && advised <= 0f && bestAdvised >= ADVICE_SWITCH_SCORE)) {
            exploration.clearSelection()
        }
    }

    /**
     * A long-term planner's guesses at where the destination is. Like advice, they only bias
     * frontier choice; they fade over [NavigationConfig.plannerFadeMillis], and each is spent once
     * the user has searched its place. Each plan replaces the last.
     */
    fun submitPlan(hypotheses: List<PlannerHypothesis>, nowMillis: Long = clockMillis) {
        semantics.setPlan(hypotheses, nowMillis)
        lastFrontierMillis = 0
    }

    /** What the engine knows right now, for an adviser. Perceived state only, never ground truth. */
    fun situation(): Situation = Situation(
        target = target,
        status = stateMachine.status,
        pose = lastPose,
        targetLocated = semantics.targetSighting != null,
        walkedMeters = walkedMeters,
        elapsedMillis = sessionStartMillis?.let { clockMillis - it } ?: 0L,
        frontiers = exploration.frontiers,
        signs = semantics.seenSigns(clockMillis, limit = 60),
        numbering = semantics.numberingEstimate(target),
        targetLikelyOnAnotherFloor = semantics.targetLikelyOnAnotherFloor(target),
        plan = semantics.planWeights(clockMillis),
    )

    // ================================================================= floors

    /** The floor being walked, as last read; null until one is. */
    val currentFloor: String? get() = world.floor

    /** The floor a lift skill is asking for at the lift's door; null otherwise. The platform presses it. */
    fun liftRequest(): String? = executor.liftRequest

    /**
     * The floor changed: a lift arrived somewhere, or a floor sign was read. A new floor is a new
     * map - its walls, frontiers, walked graph and sightings are not the last floor's - but the
     * evidence ledger and the hypotheses carry on: they are what was learned about the building.
     */
    private fun changeFloor(label: String, nowMillis: Long) {
        val previous = world.floor
        if (previous == label) {
            executor.onFloorRead(label, nowMillis, walkedMeters)
            return
        }
        world.floor = label
        val riding = executor.active?.skill == com.navassist.navcore.strategy.Skill.TAKE_LIFT
        if (previous != null || riding) {
            grid.clear()
            inflated = null
            exploration.reset()
            controller.reset()
            currentPath = emptyList()
            currentGoal = null
            lastNodeId = null
            lastNodePosition = null
            backtrackTargetNodeId = null
            // The walked graph and the distance from the start are the last floor's: kept, the engine
            // took the lift lobby for a dead end of a walk it had not made here, ruled out the ways
            // on, and tried to backtrack to places on another floor.
            topology.reset()
            sessionOrigin = null
            farthestFromOrigin = 0f
            clearTargetRouteFailures()
            semantics.newFloor()
            verifiedDestination = null
            targetApproachPoint = null
            lastFrontierMillis = 0
            scanElapsedMillis = 0
            lookAroundRadians = 0f
            lookAroundLastYaw = null
            stateMachine.on(NavigationEvent.MapReset)
            originInitialized = false
        }
        executor.onFloorRead(label, nowMillis, walkedMeters)
    }

    /** What exploration steers towards: a lift while one is being searched for, else the destination. */
    private fun explorationTarget(): NavigationTarget = executor.active?.searchFor ?: target

    // ================================================================= long-term planning

    /**
     * Verification without the skill: the destination read from close to where it is believed to be,
     * on the way there. Placed by depth or seen only along a bearing that passes it - a shop's name
     * high on its fascia is placed by neither - either reading confirms it, as long as it was taken
     * from within [NavigationConfig.verifyRangeMeters] of the sighting.
     */
    private fun verifyByCloseReading(observations: List<SemanticObservation>, observer: Pose3D, bearings: List<Vec2?>) {
        if (verifiedDestination != null) return
        val sighting = semantics.targetSighting?.takeIf { !it.glimpsed }?.worldPosition?.toVec2() ?: return
        val from = observer.position2D
        // A named place is read off a fascia high above its entrance: a camera that cannot tilt up
        // loses it from view closer than several metres, so it is verified from further off.
        val namedPlace = (target as? NavigationTarget.Room)?.let { it.number == null && it.normalizedLabel != "WASHROOM" } ?: false
        val range = if (namedPlace) config.verifyRangeNamedPlaceMeters else config.verifyRangeMeters
        if (from.distanceTo(sighting) > range) return
        for ((i, o) in observations.withIndex()) {
            if (!com.navassist.navcore.semantic.TargetMatcher.matches(o, target)) continue
            val at = o.worldPosition?.toVec2()
            val consistent = if (at != null) {
                at.distanceTo(sighting) <= VERIFIED_MATCH_METERS
            } else {
                // Seen along a bearing: it must pass close by the sighting.
                val b = bearings.getOrNull(i) ?: continue
                val to = sighting - from
                val along = to.dot(b)
                along > 0f && (to - b * along).length() <= VERIFIED_MATCH_METERS
            }
            if (consistent) {
                verifiedDestination = sighting
                return
            }
        }
    }

    /** Whether a landmark's label (or kind, for a facility) names the destination. */
    fun landmarkNamesTarget(landmark: com.navassist.navcore.world.Landmark): Boolean = when (val t = target) {
        is NavigationTarget.Room -> landmark.label?.let { com.navassist.navcore.semantic.TargetMatcher.labelMatches(it, t) } ?: false
        NavigationTarget.Exit -> landmark.kind == com.navassist.navcore.world.LandmarkKind.EXIT
        NavigationTarget.Door -> landmark.kind == com.navassist.navcore.world.LandmarkKind.DOOR
        NavigationTarget.Stairs -> landmark.kind == com.navassist.navcore.world.LandmarkKind.STAIRS
        NavigationTarget.Elevator -> landmark.kind == com.navassist.navcore.world.LandmarkKind.ELEVATOR
        NavigationTarget.Explore -> false
    }

    /** Landmarks naming the destination and not refuted, nearest first. */
    fun targetLandmarks(): List<com.navassist.navcore.world.Landmark> {
        val here = lastPose.position2D
        return world.landmarks.filter { it.floor == world.floor && it.id !in executor.refuted && landmarkNamesTarget(it) }.sortedBy { it.position.distanceTo(here) }
    }

    /**
     * Validates [subgoal] against the world as it is now and, if it holds, starts it. Returns the
     * rejection, or null when accepted. A subgoal may only name things that exist: a landmark in
     * the world model, a frontier the engine currently has, a node of the walked graph.
     */
    fun submitSubgoal(subgoal: com.navassist.navcore.strategy.StrategicSubgoal, nowMillis: Long = clockMillis): com.navassist.navcore.strategy.Rejection? {
        val (resolved, reason) = resolveSubgoal(subgoal)
        if (resolved == null) return com.navassist.navcore.strategy.Rejection(subgoal, reason ?: "invalid").also { rejections += it }
        executor.start(resolved, nowMillis, walkedMeters)
        return null
    }

    private fun resolveSubgoal(s: com.navassist.navcore.strategy.StrategicSubgoal): Pair<com.navassist.navcore.strategy.StrategicSubgoal?, String?> {
        val sub = s.copy(id = if (s.id.isBlank()) "sg-%03d".format(nextSubgoal++) else s.id)
        return when (sub.skill) {
            com.navassist.navcore.strategy.Skill.EXPLORE_FRONTIER -> {
                val fid = sub.targetId ?: return sub to null
                val f = exploration.frontiers.firstOrNull { it.id == fid } ?: return null to "no frontier $fid"
                sub.copy(targetPosition = f.centroid) to null
            }
            com.navassist.navcore.strategy.Skill.GO_TO_NODE -> {
                val node = sub.targetId?.let { topology.node(it) } ?: return null to "no node ${sub.targetId}"
                sub.copy(targetPosition = node.position) to null
            }
            com.navassist.navcore.strategy.Skill.GO_TO_LANDMARK, com.navassist.navcore.strategy.Skill.APPROACH_VIEWPOINT, com.navassist.navcore.strategy.Skill.INSPECT_SIGN,
            com.navassist.navcore.strategy.Skill.INSPECT_ROOM_LABEL, com.navassist.navcore.strategy.Skill.VERIFY_DESTINATION -> {
                val l = sub.targetId?.let { world.landmark(it) } ?: return null to "no landmark ${sub.targetId}"
                if (sub.skill == com.navassist.navcore.strategy.Skill.VERIFY_DESTINATION && l.id in executor.refuted) return null to "${l.id} already refuted"
                sub.copy(targetPosition = com.navassist.navcore.strategy.SubgoalExecutor.viewpointFor(l, world, wallNormalAt(l.position))) to null
            }
            com.navassist.navcore.strategy.Skill.TAKE_LIFT -> {
                val l = sub.targetId?.let { world.landmark(it) } ?: return null to "no lift ${sub.targetId}"
                if (l.kind != com.navassist.navcore.world.LandmarkKind.ELEVATOR) return null to "${l.id} is not a lift"
                if (sub.floorLabel == null) return null to "take_lift needs a floor"
                sub.copy(targetPosition = com.navassist.navcore.strategy.SubgoalExecutor.viewpointFor(l, world, wallNormalAt(l.position))) to null
            }
            com.navassist.navcore.strategy.Skill.ROTATE_IN_PLACE -> if (sub.angleRadians == null) null to "rotate needs an angle" else sub to null
            com.navassist.navcore.strategy.Skill.FOLLOW_NUMBERING -> if (sub.angleRadians == null) null to "follow_numbering needs a heading" else sub to null
            com.navassist.navcore.strategy.Skill.SCAN_INTERSECTION, com.navassist.navcore.strategy.Skill.STOP -> sub to null
        }
    }

    /**
     * Consults the long-term planner on events: a subgoal ended, the destination located or lost,
     * a new landmark, or the replanning interval. And, when arrival must be read at the door,
     * starts that verification itself if nothing else is.
     */
    private fun runStrategy(nowMillis: Long) {
        if (config.arrivalRequiresVerification && semantics.targetSighting != null && verifiedDestination == null &&
            executor.active?.skill != com.navassist.navcore.strategy.Skill.VERIFY_DESTINATION
        ) {
            targetLandmarks().firstOrNull()?.let { l ->
                submitSubgoal(com.navassist.navcore.strategy.StrategicSubgoal(id = "", skill = com.navassist.navcore.strategy.Skill.VERIFY_DESTINATION, targetId = l.id, purpose = "read the destination's plate"), nowMillis)
            }
        }
        val planner = longTermPlanner
        val outcomes = if (planner != null) executor.takeOutcomes() else emptyList()
        for (o in executor.history.drop(outcomesSeen)) {
            if (o.skill == com.navassist.navcore.strategy.Skill.VERIFY_DESTINATION && o.targetId != null &&
                (o.status == com.navassist.navcore.strategy.OutcomeStatus.SUCCEEDED || o.reason.startsWith("refuted"))
            ) {
                beliefs.destination(o.targetId, world.landmark(o.targetId)?.label ?: "?", o.status == com.navassist.navcore.strategy.OutcomeStatus.SUCCEEDED, nowMillis)
            }
        }
        outcomesSeen = executor.history.size
        if (planner == null) return
        val landmarks = world.landmarks.size
        val sighting = semantics.targetSighting != null
        val trigger = when {
            outcomes.isNotEmpty() -> "outcome ${outcomes.last().skill} ${outcomes.last().status}"
            sighting != plannerHadSighting -> if (sighting) "destination located" else "destination lost"
            landmarks != plannerLandmarks -> "new landmark"
            nowMillis >= plannerNextMillis -> if (executor.active == null) "idle" else "replan interval"
            else -> null
        } ?: return
        plannerLandmarks = landmarks
        plannerHadSighting = sighting
        plannerNextMillis = nowMillis + config.plannerReplanMillis
        val context = com.navassist.navcore.strategy.PlannerContext(
            snapshot = worldState(),
            outcomes = executor.history.toList(),
            refuted = executor.refuted.toSet(),
            targetLandmarks = targetLandmarks().map { it.id },
            trigger = trigger,
            active = executor.active,
            beliefs = beliefs,
            numberingTarget = semantics.numberingEstimate(target)?.position,
            destinationVerified = verifiedDestination != null,
        )
        val subgoal = planner.decide(context) ?: return
        if (subgoal.id.isNotBlank() && subgoal.id == executor.active?.id) return
        submitSubgoal(subgoal, nowMillis)
    }

    /**
     * The normal of the wall at [position], from the occupied cells around it: the direction across
     * their principal axis. Unoriented; null when too few walls are mapped there to tell.
     */
    fun wallNormalAt(position: Vec2, radiusMeters: Float = 0.6f): Vec2? {
        val center = grid.worldToGrid(position)
        val r = kotlin.math.ceil(radiusMeters / grid.resolution).toInt()
        var n = 0
        var mx = 0f; var mz = 0f
        val cells = ArrayList<Vec2>()
        for (dz in -r..r) for (dx in -r..r) {
            if (grid.stateAt(center.gx + dx, center.gz + dz) != CellState.OCCUPIED) continue
            val p = grid.gridToWorld(center.gx + dx, center.gz + dz)
            if (p.distanceTo(position) > radiusMeters) continue
            cells += p; mx += p.x; mz += p.z; n++
        }
        if (n < 4) return null
        mx /= n; mz /= n
        var sxx = 0f; var szz = 0f; var sxz = 0f
        for (p in cells) { val dx = p.x - mx; val dz = p.z - mz; sxx += dx * dx; szz += dz * dz; sxz += dx * dz }
        // Principal axis angle of the 2x2 covariance; the wall runs along it.
        val angle = 0.5f * kotlin.math.atan2(2f * sxz, sxx - szz)
        val along = Vec2(kotlin.math.cos(angle), kotlin.math.sin(angle))
        // Spread along and across the axis: a blob as wide as it is long (a column, a corner) has
        // no one normal.
        val major = 0.5f * (sxx + szz) + kotlin.math.sqrt(0.25f * (sxx - szz) * (sxx - szz) + sxz * sxz)
        val minor = (sxx + szz) - major
        if (major < 1e-4f || minor > 0.25f * major) return null
        return Vec2(-along.z, along.x).normalized()
    }

    /** The world model as the long-term planner sees it: bounded, nearest first. */
    fun worldState(maxLandmarks: Int = 40, maxFrontiers: Int = 12): com.navassist.navcore.world.WorldStateSnapshot {
        val here = lastPose.position2D
        val views = world.landmarks.filter { it.floor == world.floor }.map {
            com.navassist.navcore.world.LandmarkView(
                id = it.id, kind = it.kind, label = it.label, position = it.position,
                distanceMeters = it.position.distanceTo(here), timesSeen = it.timesSeen, readCount = it.readCount,
                conflictingReads = it.conflictingReads, lastSeenAgeMillis = clockMillis - it.lastSeenMillis,
            )
        }.sortedBy { it.distanceMeters }
        val sighting = semantics.targetSighting
        return com.navassist.navcore.world.WorldStateSnapshot(
            revision = world.revision,
            nowMillis = clockMillis,
            goal = target,
            status = stateMachine.status,
            pose = lastPose,
            walkedMeters = walkedMeters,
            elapsedMillis = sessionStartMillis?.let { clockMillis - it } ?: 0L,
            landmarks = views.filter { it.label != null || it.kind != com.navassist.navcore.world.LandmarkKind.PLATE }.take(maxLandmarks),
            frontiers = exploration.frontiers.map {
                com.navassist.navcore.world.FrontierView(it.id, it.centroid, it.centroid.distanceTo(here), it.score, it.cellCount)
            }.sortedBy { it.distanceMeters }.take(maxFrontiers),
            unreadSigns = views.filter { it.readCount == 0 }.take(maxLandmarks),
            targetPosition = sighting?.worldPosition?.toVec2(),
            targetSource = sighting?.source,
            eventCount = world.events.size,
            floor = world.floor,
        )
    }

    /** The goal currently being steered at, for debug rendering. */
    fun currentGoalWorld(): Vec2? = currentGoal

    /** Everything the engine is remembering, for viewers and diagnostics. Never for the bridge. */
    fun memory(): com.navassist.navcore.state.NavigationMemory {
        val (advice, adviceAge) = semantics.currentAdvice(clockMillis)
        val nodes = topology.nodes.toList()
        return com.navassist.navcore.state.NavigationMemory(
            nowMillis = clockMillis,
            signs = semantics.seenSigns(clockMillis, limit = 200),
            plates = semantics.numberingPlates(),
            numbering = semantics.numberingEstimate(target),
            targetLikelyOnAnotherFloor = semantics.targetLikelyOnAnotherFloor(target),
            sighting = semantics.targetSighting,
            frontiers = exploration.frontiers,
            selectedFrontier = exploration.selected,
            goal = currentGoal,
            blacklisted = exploration.blacklistedAreas(),
            nodes = nodes,
            // Each edge is stored from both ends; keep one copy.
            edges = nodes.flatMap { topology.edgesFrom(it.id) }.filter { it.fromId < it.toId },
            advice = advice,
            adviceAgeMillis = adviceAge,
            plan = semantics.planWeights(clockMillis),
        )
    }

    /** Where the engine believes the destination is, and how it placed it; null if unknown. */
    fun targetSighting(): com.navassist.navcore.semantic.TargetSighting? = semantics.targetSighting

    fun floorEstimate() = floorEstimator.estimate

    private companion object {
        /** ~5 Hz local planning. */
        const val PLAN_INTERVAL_MILLIS = 200L

        /** New advice this strongly for another frontier releases one it does not point at. */
        const val ADVICE_SWITCH_SCORE = 0.5f

        /** Advice favouring some frontier at least this much needs no turn to look for one. */
        const val ADVICE_MATCHED_SCORE = 0.3f

        /** Turned to within this of the advised direction counts as having looked. */
        const val ADVICE_FACING_DEGREES = 30f
        /** A verified plate and the sighting being approached are the same destination within this. */
        const val VERIFIED_MATCH_METERS = 2f

        /** A frontier a subgoal names is followed while one remains this close to it. */
        const val FRONTIER_TRACK_METERS = 1.5f

        /** ~1.5 Hz frontier detection + scoring. */
        const val FRONTIER_INTERVAL_MILLIS = 650L
        /** ~6 Hz inflation refresh (shared by planning and frontier risk scoring). */
        const val INFLATE_INTERVAL_MILLIS = 160L
    }
}
