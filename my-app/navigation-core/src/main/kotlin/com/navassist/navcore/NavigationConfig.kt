package com.navassist.navcore

/**
 * Every tunable parameter of the navigation engine lives here.
 *
 * Rationale: the algorithms below must not contain scattered magic numbers, and the Android /
 * future iOS adapters must be able to tune the engine without touching core logic.
 */
data class NavigationConfig(
    // ---------------------------------------------------------------- occupancy grid
    /** Side length of one grid cell, in metres. */
    val gridResolutionMeters: Float = 0.10f,
    /** Side length of the rolling local grid, in metres (so 12 m / 0.10 m = 120x120 cells). */
    val gridSizeMeters: Float = 12f,
    /**
     * The grid re-centres on the device once the device drifts further than this from the current
     * grid centre. Keeps the local map small while the user walks across a building.
     */
    val gridRecenterThresholdMeters: Float = 2.0f,

    // ---------------------------------------------------------------- occupancy evidence (log odds)
    val logOddsHit: Float = 0.85f,
    val logOddsMiss: Float = -0.45f,
    val logOddsMin: Float = -4.0f,
    val logOddsMax: Float = 4.0f,
    /** Above this the cell is reported OCCUPIED. */
    val logOddsOccupiedThreshold: Float = 0.9f,
    /** Below this the cell is reported FREE. */
    val logOddsFreeThreshold: Float = -0.5f,
    /**
     * Floor evidence against a cell that is already OCCUPIED counts at this fraction.
     *
     * A 10 cm cell at the foot of a wall holds both the wall and a strip of floor in front of it,
     * and at shallow viewing angles most frames see only that floor strip while wall returns are
     * sparse. At full strength the floor wins and walls erode from their far ends into holes the
     * planner will happily aim at. An obstacle return is the more specific evidence; the floor
     * still clears a moved obstacle, just after a few more looks.
     */
    val logOddsMissOnOccupiedScale: Float = 0.33f,

    // ---------------------------------------------------------------- temporal decay
    /**
     * Per-second multiplicative decay applied to evidence that has NOT yet decided a cell either
     * way, clearing stray single returns before they can be mistaken for structure.
     *
     * Cells that did reach FREE or OCCUPIED are not decayed at all: a wall does not stop existing
     * because the user looked away, and only the narrow sensor cone could ever re-confirm it.
     * A moved obstacle is cleared by looking through it instead (see OccupancyGrid.applyDecay).
     */
    val decayPerSecondUnconfirmed: Float = 0.80f,

    // ---------------------------------------------------------------- depth handling
    /** Sample every Nth depth pixel (handled by the platform adapter, surfaced here for tuning). */
    val depthSampleStride: Int = 3,
    /** Depth samples further than this are discarded: accuracy degrades badly with range. */
    val maxDepthMeters: Float = 6.0f,
    /** Depth samples closer than this are discarded (lens/holder artefacts). */
    val minDepthMeters: Float = 0.3f,
    /** Upper bound on points fed into the engine per frame. */
    val maxPointsPerFrame: Int = 6000,
    /**
     * Radius around the user's own position that is marked FREE on every tracked frame.
     *
     * Free space otherwise only comes from floor the sensor actually sees, and the floor under
     * and immediately around a person holding a phone upright is never in view. They are
     * standing on it, though, which is better evidence than any depth return. Cells already
     * OCCUPIED are left alone, so a wall beside the user is never walked out of the map.
     */
    val footprintFreeRadiusMeters: Float = 0.3f,

    // ---------------------------------------------------------------- floor + obstacle extraction
    /** Points below this height above the floor count as floor, not obstacle. */
    val minObstacleHeightMeters: Float = 0.10f,
    /**
     * How far below the floor estimate a point may sit and still be believed.
     *
     * Anything lower is not evidence of free space - it is evidence that the floor estimate is
     * too high, usually because a desk or table was mistaken for the floor. Treating those points
     * as "floor, therefore walkable" is how a map fills with green that bleeds through walls and
     * over obstacles. Discarding them fails safe: less claimed free space, not more.
     */
    val floorBandBelowMeters: Float = 0.12f,
    /** Points above this height above the floor count as ceiling / overhead, not obstacle. */
    val maxObstacleHeightMeters: Float = 2.10f,
    /** Floor estimation histogram bin size. */
    val floorHistogramBinMeters: Float = 0.05f,
    /** Minimum points in the winning bin before a floor estimate is trusted at all. */
    val floorMinSupportPoints: Int = 60,
    /** Floor estimate confidence must reach this before ANY point is classified as an obstacle. */
    val floorMinConfidence: Float = 0.45f,
    /** Temporal smoothing factor for the floor height estimate. */
    val floorSmoothingAlpha: Float = 0.25f,
    /**
     * A held phone sits between these heights above the floor. Depth-based floor candidates
     * outside the band are rejected, which is what keeps a desk at 0.75 m from being taken for
     * the floor when the phone is at 1.3 m.
     */
    val floorMinDropMeters: Float = 0.7f,
    val floorMaxDropMeters: Float = 2.6f,
    /**
     * Once the floor is established, a candidate this much HIGHER than the current estimate is
     * ignored unless it persists for [floorRiseConfirmMillis]. Floors do not jump up by a table
     * height; the usual cause is the camera seeing only a table. A sustained rise (a ramp, a
     * raised landing) is still accepted.
     */
    val floorMaxRiseMeters: Float = 0.25f,
    val floorRiseConfirmMillis: Long = 2000,

    // ---------------------------------------------------------------- obstacle inflation
    /** The user is not a point. Obstacles are inflated by this radius before planning. */
    val inflationRadiusMeters: Float = 0.45f,
    /** Cells within this distance of an obstacle get an extra planning cost (but stay passable). */
    val clearanceCostRadiusMeters: Float = 0.85f,
    /** Weight of the obstacle-proximity penalty in A* cost. */
    val clearanceCostWeight: Float = 2.2f,

    // ---------------------------------------------------------------- planning
    /** Extra A* cost applied when the path changes direction: discourages zig-zag. */
    val turnPenalty: Float = 0.35f,
    /** Hard cap on A* expansions so planning can never stall a frame budget. */
    val maxAStarExpansions: Int = 40_000,
    /**
     * Unknown space is NOT free. Local planning refuses unknown cells entirely, except within
     * this many cells of the goal, so an exploration goal sitting on a frontier stays reachable.
     */
    val allowUnknownNearGoalCells: Int = 3,

    // ---------------------------------------------------------------- frontier exploration
    /** Frontier clusters smaller than this are noise and are rejected. */
    val minFrontierCells: Int = 6,
    /** Radius (in cells) used to estimate how much unknown space a frontier would reveal. */
    val informationGainRadiusCells: Int = 12,
    /** Frontier score weights. Not scientifically derived: tune on real hardware. */
    val weightSemantic: Float = 3.0f,
    val weightInformation: Float = 1.5f,
    val weightDistance: Float = -1.0f,
    val weightRevisit: Float = -2.0f,
    val weightRisk: Float = -2.0f,
    /** A frontier counts as reached within this distance. */
    val frontierReachedMeters: Float = 0.9f,
    /** Frontiers we failed to route to are blacklisted within this radius. */
    val frontierBlacklistRadiusMeters: Float = 0.8f,
    /** Consecutive planning failures before a frontier is abandoned. */
    val frontierFailuresBeforeBlacklist: Int = 3,
    /** Minimum spacing between failed attempts counted against an exploration waypoint. */
    val frontierFailureIntervalMillis: Long = 1000,
    /** Abandon a waypoint after this long without measurable approach, allowing time to turn. */
    val frontierNoProgressMillis: Long = 20_000,
    /** Approach needed to renew the exploration progress timeout. */
    val frontierProgressMeters: Float = 0.3f,
    /**
     * A committed exploration waypoint is released once no frontier cell (FREE next to UNKNOWN)
     * remains within this distance of it: whatever it was chosen to reveal has been seen, even
     * from afar.
     */
    val frontierCommitUnknownRadiusMeters: Float = 1.0f,

    // ---------------------------------------------------------------- topological memory
    /** A new graph node is created once the user has travelled this far from the last one. */
    val topoNodeSpacingMeters: Float = 2.5f,
    /** A new node closer than this to an existing one is merged into it (revisit detection). */
    val topoMergeRadiusMeters: Float = 1.6f,
    /** Heading change (radians) that justifies dropping a node even before the spacing is met. */
    val topoTurnThresholdRadians: Float = 0.9f,
    /**
     * How long the route to a located target may keep failing before the sighting is abandoned
     * and exploration resumes.
     *
     * Time-based rather than a frame count on purpose: a failed plan clears the current path, so
     * the engine replans on EVERY subsequent frame. A "give up after N failures" rule would
     * therefore fire in a few hundred milliseconds at 30 fps - long before a user has finished
     * turning around.
     */
    val targetRouteAbandonMillis: Long = 4000,

    // ---------------------------------------------------------------- navigation control
    /** Waypoint lookahead distance: we steer towards a point this far along the path. */
    val lookaheadMeters: Float = 1.5f,
    val minLookaheadMeters: Float = 0.8f,
    /** |heading error| below this is STRAIGHT. */
    val straightThresholdDegrees: Float = 15f,
    /** Once turning, we keep turning until the error drops below this (hysteresis). */
    val turnReleaseDegrees: Float = 8f,
    /** |heading error| above this means the user is badly misaligned: stop and turn in place. */
    val sharpTurnDegrees: Float = 65f,
    /** Exponential smoothing on the heading error: single-frame estimates are noisy. */
    val headingSmoothingAlpha: Float = 0.35f,
    /**
     * A command must hold for this long before it is emitted.
     *
     * Time, not frames. The engine is driven by the AR render loop, so a frame count means
     * whatever the device's frame rate happens to be - three frames is 300 ms at 10 fps but
     * 100 ms at 30 fps, and at 100 ms the guidance can legally change ten times a second, which
     * is exactly what "left, no right, no left" sounds like.
     */
    val commandStabilityMillis: Long = 350,
    /**
     * Once a turn is announced, hold it for at least this long.
     *
     * A person needs a beat to hear an instruction and start turning. Re-deciding before they
     * have moved means the engine is reacting to a world the user has not acted on yet, and the
     * two of you oscillate. Safety stops ignore this and interrupt immediately.
     */
    val commandMinDwellMillis: Long = 1400,
    /** Distance at which the final target counts as reached. */
    val arrivalRadiusMeters: Float = 1.0f,
    /** Time spent continuously within [arrivalRadiusMeters] before ARRIVED is declared. */
    val arrivalStableMillis: Long = 600,
    /**
     * Immediate-path safety check: if any inflated-blocked or unknown cell lies within this
     * distance straight ahead, STRAIGHT is suppressed in favour of STOP.
     */
    val forwardSafetyDistanceMeters: Float = 0.9f,

    // ---------------------------------------------------------------- state machine / safety
    /** Radius around the user over which map confidence is measured. */
    val mapConfidenceRadiusMeters: Float = 3.0f,
    /** Fraction of the cells within that radius that must be known before we start moving. */
    val minMapConfidenceToNavigate: Float = 0.18f,
    /**
     * Minimum time spent actively scanning before the first movement instruction.
     *
     * Map confidence alone clears its threshold within a second of pointing down a corridor, and
     * a map that thin produces a confident-sounding "left" based on almost nothing. The user has
     * no way to know the difference between a considered instruction and a guess, so the engine
     * owes them a deliberate look around first.
     *
     * This counts time with tracking AND depth actually available, not wall-clock since start, so
     * a slow ARCore warm-up or a few seconds of lost tracking does not eat the budget.
     */
    val minScanMillis: Long = 10_000,
    /**
     * Scan time is credited while the latest depth image is at most this old.
     *
     * Depth usually updates more slowly than the camera, and the adapter drops frames that would
     * only repeat the previous depth image, so crediting only frames that carry depth would make
     * the scan last several times longer than [minScanMillis].
     */
    val scanDepthFreshMillis: Long = 300,
    /** No depth for longer than this and the engine stops and asks the user to SCAN. */
    val depthStarvationMillis: Long = 1500,
    /** Tracking must be good for this long before leaving LOST_TRACKING. */
    val trackingRecoveryMillis: Long = 600,
    /** Tracking confidence below this counts as lost. */
    val minTrackingConfidence: Float = 0.3f,

    // ---------------------------------------------------------------- semantics
    /** Semantic observations older than this are ignored. */
    val semanticMaxAgeMillis: Long = 30_000,
    /** Directional hints (e.g. "rooms 300-349 to the right") apply within this half-angle. */
    val semanticDirectionHalfAngleDegrees: Float = 55f,
    /** Observations below this confidence are discarded on arrival. */
    val semanticMinConfidence: Float = 0.3f,

    // ---------------------------------------------------------------- output throttling
    /** Minimum interval between snapshots pushed to JS, unless status/command changed. */
    val snapshotIntervalMillis: Long = 120,
) {
    /** Number of cells along one edge of the local grid. */
    val gridCells: Int get() = (gridSizeMeters / gridResolutionMeters).toInt()

    val inflationRadiusCells: Int
        get() = kotlin.math.ceil(inflationRadiusMeters / gridResolutionMeters).toInt()

    val clearanceCostRadiusCells: Int
        get() = kotlin.math.ceil(clearanceCostRadiusMeters / gridResolutionMeters).toInt()
}
