package com.navassist.navcore.semantic

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.geometry.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** A localized sighting of the current target. */
data class TargetSighting(
    val worldPosition: Vec3,
    val confidence: Float,
    val observedAtMillis: Long,
    val label: String,
    /** Placed from one sightline's bearing and elevation, assuming a typical mounting height. */
    val ranged: Boolean = false,
    /** How far away it was measured from; depth error grows with range. */
    val rangeMeters: Float = Float.MAX_VALUE,
    /**
     * Only its bearing is known - a plate read in passing, where depth had nothing - and this is a
     * guess along it. Somewhere to go and look properly, never somewhere to arrive.
     */
    val glimpsed: Boolean = false,
) {
    /** Inferred rather than measured: any depth-placed sighting replaces it. */
    val estimated: Boolean get() = ranged || glimpsed

    /** How it was placed, for diagnostics. */
    val source: String get() = when {
        ranged -> "ranged"
        glimpsed -> "glimpsed"
        else -> "depth"
    }
}

/** One stored observation together with where the user was standing when it arrived. */
private data class StoredHint(
    val observation: SemanticObservation,
    val observerPose: Pose3D,
    val receivedAtMillis: Long,
    /** Horizontal direction it was seen in, when known. */
    val bearing: Vec2? = null,
)

/**
 * Memory of what the perception layer has reported, and the translation of that memory into a
 * directional bias for frontier selection.
 *
 * Everything in here is a HEURISTIC. Room numbering conventions vary between buildings, signage
 * is incomplete, and OCR misreads. The store therefore:
 *  - only ever nudges frontier scores, never selects a command;
 *  - applies weak, bounded negative evidence, so an alternative branch is never eliminated;
 *  - ages every hint out, so stale evidence from three corridors ago stops steering the user.
 */
class SemanticHintStore(private val config: NavigationConfig) {

    private val hints = ArrayList<StoredHint>()

    /** Rooms seen so far, in arrival order, with where they were seen. Drives the number gradient. */
    private val roomTrail = ArrayList<Pair<Int, Vec2>>()

    var currentFloorId: String? = null
        private set

    var targetSighting: TargetSighting? = null
        private set

    /** The latest advice from outside the engine, and when it arrived. */
    private var advice: List<AdviceDirection> = emptyList()
    private var adviceAtMillis = 0L

    /** The strongest direction the current advice favours, or null when there is none or it is stale. */
    fun strongestAdvice(nowMillis: Long): AdviceDirection? {
        if (nowMillis - adviceAtMillis > config.adviceMaxAgeMillis) return null
        return advice.filter { it.weight > 0f }.maxByOrNull { it.weight }
    }

    /** When the current advice arrived: identifies one answer. */
    val adviceReceivedAtMillis: Long get() = adviceAtMillis

    /** Replaces the current advice. Advice is never merged: each answer is a fresh judgement. */
    fun setAdvice(directions: List<AdviceDirection>, nowMillis: Long) {
        advice = directions
        adviceAtMillis = nowMillis
    }

    /**
     * How strongly the current advice favours a frontier, in -1..1: each suggested direction
     * contributes its weight, tapering to nothing [NavigationConfig.adviceHalfAngleDegrees] away
     * from it, as seen from where the advice was asked. Zero once the advice is stale.
     */
    fun adviceScoreFor(frontierCentroid: Vec2, nowMillis: Long): Float {
        if (advice.isEmpty() || nowMillis - adviceAtMillis > config.adviceMaxAgeMillis) return 0f
        val halfAngle = GeometryUtils.degreesToRadians(config.adviceHalfAngleDegrees)
        var score = 0f
        for (a in advice) {
            val toFrontier = frontierCentroid - a.origin
            if (toFrontier.lengthSquared() < 1e-4f) continue
            val diff = abs(GeometryUtils.angleDifference(toFrontier.yawRadians(), a.direction.yawRadians()))
            if (diff >= halfAngle) continue
            score += a.weight * (1f - diff / halfAngle)
        }
        return GeometryUtils.clamp(score, -1f, 1f)
    }

    /** What has been read or detected recently, newest first, for an adviser. */
    fun seenSigns(nowMillis: Long, limit: Int = 20): List<SeenSign> = hints.asReversed().asSequence().mapNotNull { hint ->
        val o = hint.observation
        val (kind, label, pointing) = when (o) {
            is SemanticObservation.Room -> Triple("ROOM", o.label, null)
            is SemanticObservation.RoomRange -> Triple("ROOM_RANGE", "${o.min}-${o.max}", o.direction)
            is SemanticObservation.Exit -> Triple("EXIT", null, o.direction)
            is SemanticObservation.Door -> Triple("DOOR", null, o.direction)
            is SemanticObservation.Stairs -> Triple("STAIRS", null, null)
            is SemanticObservation.Elevator -> Triple("ELEVATOR", null, null)
            else -> return@mapNotNull null
        }
        SeenSign(
            kind = kind, label = label, pointing = pointing,
            position = o.worldPosition?.toVec2(),
            observerPosition = hint.observerPose.position2D,
            bearing = hint.bearing,
            ageMillis = nowMillis - hint.receivedAtMillis,
        )
    }.take(limit).toList()

    /** Glimpses that were followed up and came to nothing: where, and when. */
    private val spentGlimpses = ArrayList<Pair<Vec2, Long>>()

    fun reset() {
        hints.clear()
        roomTrail.clear()
        advice = emptyList()
        currentFloorId = null
        targetSighting = null
        spentGlimpses.clear()
    }

    /**
     * Drops the sighting: the destination changed ([forgetGlimpses], so an old search does not
     * steer a new one), or it could not be reached.
     */
    fun clearTargetSighting(forgetGlimpses: Boolean = false) {
        targetSighting = null
        if (forgetGlimpses) spentGlimpses.clear()
    }

    /**
     * The user went to look where a glimpse pointed and nothing better turned up: drop it, and do
     * not send them back there on the next reading of the same plate from the same corridor.
     */
    fun spendGlimpse(nowMillis: Long) {
        val sighting = targetSighting ?: return
        if (!sighting.glimpsed) return
        spentGlimpses += sighting.worldPosition.toVec2() to nowMillis
        targetSighting = null
    }

    val hintCount: Int get() = hints.size

    /**
     * @param observerPose where the user stood when the observation was made. Directional hints
     *        ("rooms 300-349 to the right") are meaningless without it.
     * @param elevations per observation, tangent of the angle above horizontal it was seen at.
     * @param cameraHeightMeters the camera's height above the floor then; null if the floor is not
     *        known yet.
     */
    fun submit(
        observations: List<SemanticObservation>,
        nowMillis: Long,
        observerPose: Pose3D,
        target: NavigationTarget,
        bearings: List<Vec2?> = emptyList(),
        elevations: List<Float?> = emptyList(),
        cameraHeightMeters: Float? = null,
    ) {
        for ((index, observation) in observations.withIndex()) {
            if (observation.confidence < config.semanticMinConfidence) continue

            if (observation is SemanticObservation.Floor) {
                currentFloorId = observation.floor
                continue
            }

            hints.add(StoredHint(observation, observerPose, nowMillis, bearings.getOrNull(index)))

            if (observation is SemanticObservation.Room) {
                TargetMatcher.numericPart(observation.label)?.let { number ->
                    roomTrail.add(number to observerPose.position2D)
                    if (roomTrail.size > MAX_ROOM_TRAIL) roomTrail.removeAt(0)
                }
            }

            // A localized sighting of the destination is what flips EXPLORING -> NAVIGATING.
            if (TargetMatcher.matches(observation, target)) {
                val world = observation.worldPosition
                val bearing = bearings.getOrNull(index)
                val facility = isFacility(target)
                // A facility's own sign is at door height; one hung higher is a pointer to it
                // ("washrooms this way" over a corridor mouth), which steers but is not there.
                val pointer = facility && world != null && cameraHeightMeters != null &&
                    world.y - (observerPose.position3D.y - cameraHeightMeters) > config.facilitySignMaxHeightMeters
                // Without depth, only a shop's name is placed, by ranging on its fascia's usual
                // height. Crossing sightlines was tried and dropped: from a walk past, they cross
                // at shallow angles, and put the destination metres off nine times in ten.
                if (world == null && bearing != null && !facility) {
                    if (isNamedPlace(target)) {
                        rangeByHeight(bearing, elevations.getOrNull(index), cameraHeightMeters, observerPose, observation.confidence, nowMillis, target)
                    } else if ((target as? NavigationTarget.Room)?.number != null) {
                        // A numbered plate: small, so a reading means it is near. An exit or
                        // lift sign is recognised from far off, and a guess along it is not.
                        glimpse(bearing, observerPose, observation.confidence, nowMillis, target)
                    }
                }
                if (world != null && !pointer) {
                    val existing = targetSighting
                    // A measurement from nearer is the better one: depth error grows with range,
                    // and a far reading is the one that lands on something in front.
                    val range = world.toVec2().distanceTo(observerPose.position2D)
                    if (existing == null || existing.estimated || range <= existing.rangeMeters + NEARER_SLACK_METERS ||
                        nowMillis - existing.observedAtMillis > config.semanticMaxAgeMillis / 2
                    ) {
                        targetSighting = TargetSighting(
                            worldPosition = world,
                            confidence = observation.confidence,
                            observedAtMillis = nowMillis,
                            label = target.description,
                            rangeMeters = range,
                        )
                    }
                }
            }
        }
        prune(nowMillis)
    }

    /** Found by a generic pictogram, not a name or number: a washroom. */
    private fun isFacility(target: NavigationTarget): Boolean =
        target is NavigationTarget.Room && target.normalizedLabel == "WASHROOM"

    /** A place known by name, like a shop, whose sign is on a fascia over its entrance. */
    private fun isNamedPlace(target: NavigationTarget): Boolean =
        target is NavigationTarget.Room && target.number == null && !isFacility(target)

    /**
     * A plate that names the destination, read but not placed: it is small, so legible only a
     * few metres off, and passing it along a corridor the one legible moment is at a grazing
     * angle to its wall, where depth returns nothing. Guess it [NavigationConfig.glimpseRangeMeters]
     * along the bearing, so the engine turns the user towards it and walks them up - facing the
     * wall, depth places the next reading. Only while nothing better is known.
     */
    private fun glimpse(bearing: Vec2, observerPose: Pose3D, confidence: Float, nowMillis: Long, target: NavigationTarget) {
        val existing = targetSighting
        if (existing != null && !existing.glimpsed) return
        spentGlimpses.removeAll { nowMillis - it.second > config.glimpseMemoryMillis }
        val guess = observerPose.position2D + bearing * config.glimpseRangeMeters
        if (spentGlimpses.any { it.first.distanceTo(guess) < config.glimpseSpentRadiusMeters }) return
        targetSighting = TargetSighting(
            worldPosition = Vec3(guess.x, observerPose.position3D.y, guess.z),
            confidence = confidence * RANGED_CONFIDENCE,
            observedAtMillis = nowMillis,
            label = target.description,
            glimpsed = true,
        )
    }

    /**
     * Places the destination from ONE sighting that depth could not reach, using how steeply the
     * camera looked up at it. Shop names and hanging signs are mounted high: seen from the
     * concourse they are beyond depth range, walking up to them they leave the top of the frame, so
     * a pass usually yields one reading. Their
     * mounting height is fairly uniform, and with it the elevation angle gives the range:
     * `(mount height - camera height) / tan(elevation)`.
     *
     * An estimate, and the weakest kind: only used while nothing better exists, replaced by any
     * depth measurement, refreshed by each new reading. Near-horizontal readings
     * are refused, since a small angle error there is a large range error, and so are readings
     * that would place the sign closer than depth should have reached.
     */
    private fun rangeByHeight(
        bearing: Vec2,
        elevationTan: Float?,
        cameraHeightMeters: Float?,
        observerPose: Pose3D,
        confidence: Float,
        nowMillis: Long,
        target: NavigationTarget,
    ) {
        if (elevationTan == null || cameraHeightMeters == null) return
        val existing = targetSighting
        if (existing != null && !existing.ranged) return
        val minTan = kotlin.math.tan(GeometryUtils.degreesToRadians(config.heightRangingMinElevationDegrees))
        if (elevationTan < minTan) return
        val range = (config.heightRangingMountMeters - cameraHeightMeters) / elevationTan
        if (range < config.heightRangingMinRangeMeters || range > config.heightRangingMaxRangeMeters) return
        val origin = observerPose.position2D
        targetSighting = TargetSighting(
            worldPosition = Vec3(origin.x + bearing.x * range, observerPose.position3D.y, origin.z + bearing.z * range),
            confidence = confidence * RANGED_CONFIDENCE,
            observedAtMillis = nowMillis,
            label = target.description,
            ranged = true,
        )
    }

    fun prune(nowMillis: Long) {
        val maxAge = config.semanticMaxAgeMillis
        hints.removeAll { nowMillis - it.receivedAtMillis > maxAge }
        targetSighting?.let {
            // A sighting survives longer than a directional hint: it is a concrete destination.
            if (nowMillis - it.observedAtMillis > maxAge * 4) targetSighting = null
            // A glimpse is worth one look, not a long walk.
            if (it.glimpsed && nowMillis - it.observedAtMillis > config.glimpseMaxAgeMillis) spendGlimpse(nowMillis)
        }
    }

    /**
     * Directional bias for one candidate frontier, roughly in -1..1.5.
     *
     * For every live hint we reconstruct the world bearing it implied (from the pose it was
     * observed at) and reward frontiers that lie along that bearing, with a linear angular
     * falloff and an age falloff.
     */
    fun semanticScoreFor(
        frontierCentroid: Vec2,
        userPose: Pose3D,
        target: NavigationTarget,
        nowMillis: Long,
    ): Float {
        if (target == NavigationTarget.Explore) return 0f
        var score = 0f
        val halfAngle = GeometryUtils.degreesToRadians(config.semanticDirectionHalfAngleDegrees)

        for (hint in hints) {
            val relevance = relevanceOf(hint.observation, target)
            if (relevance == 0f) continue

            val hintYaw = hintBearing(hint) ?: continue
            val toFrontier = frontierCentroid - hint.observerPose.position2D
            if (toFrontier.lengthSquared() < 1e-4f) continue
            val diff = abs(GeometryUtils.angleDifference(hintYaw, toFrontier.yawRadians()))
            if (diff > halfAngle) continue

            val angular = 1f - diff / halfAngle
            val age = (nowMillis - hint.receivedAtMillis).toFloat() / config.semanticMaxAgeMillis
            val recency = GeometryUtils.clamp(1f - age, 0f, 1f)
            score += angular * recency * hint.observation.confidence * relevance
        }

        score += roomNumberGradientScore(frontierCentroid, userPose, target)
        return GeometryUtils.clamp(score, -1f, 1.5f)
    }

    private fun relevanceOf(observation: SemanticObservation, target: NavigationTarget): Float =
        when (observation) {
            is SemanticObservation.RoomRange -> TargetMatcher.rangeRelevance(observation, target)
            is SemanticObservation.Exit -> if (target == NavigationTarget.Exit) 1f else 0f
            is SemanticObservation.Stairs ->
                if (target == NavigationTarget.Stairs || target == NavigationTarget.Exit) 0.5f else 0f
            is SemanticObservation.Elevator -> if (target == NavigationTarget.Elevator) 1f else 0f
            // A door is weak evidence for anything except a door: it may lead outside, into a
            // room, or into a cupboard. Rooms sit behind doors, so it is worth a small nudge
            // when hunting a room number, but never enough to override a read plate.
            is SemanticObservation.Door -> when {
                target == NavigationTarget.Door -> 1f
                target == NavigationTarget.Exit -> 0.5f
                target is NavigationTarget.Room -> 0.3f
                else -> 0f
            }
            is SemanticObservation.Room -> when {
                // A facility's sign is the way to it. Hung high over a corridor mouth it is not
                // the destination itself (see submit), but it is the strongest hint there is:
                // without this, a washroom pointer had no pull at all, since it has no number.
                isFacility(target) && TargetMatcher.matches(observation, target) -> 1f
                // A near-miss room number is mild evidence that the right corridor is this way.
                else -> nearMissRelevance(observation, target)
            }
            is SemanticObservation.Floor -> 0f
        }

    private fun nearMissRelevance(
        observation: SemanticObservation.Room,
        target: NavigationTarget,
    ): Float {
        val targetNumber = (target as? NavigationTarget.Room)?.number ?: return 0f
        val seen = TargetMatcher.numericPart(observation.label) ?: return 0f
        if (seen == targetNumber) return 1f
        val delta = abs(seen - targetNumber)
        // Same hundred block is a useful hint; anything further away tells us very little.
        return if (delta <= 50) 0.35f * (1f - delta / 50f) else 0f
    }

    /**
     * World bearing implied by a hint. Directional signs are resolved against the heading the user
     * had when the sign was read; localized observations use their own world position.
     */
    private fun hintBearing(hint: StoredHint): Float? {
        hint.observation.worldPosition?.let { world ->
            val delta = world.toVec2() - hint.observerPose.position2D
            if (delta.lengthSquared() > 1e-4f) return delta.yawRadians()
        }
        val direction = when (val o = hint.observation) {
            is SemanticObservation.RoomRange -> o.direction
            is SemanticObservation.Exit -> o.direction
            is SemanticObservation.Door -> o.direction
            else -> null
        } ?: return null
        val offset = when (direction) {
            SemanticDirection.FORWARD -> 0f
            SemanticDirection.RIGHT -> GeometryUtils.PI_F / 2f
            SemanticDirection.LEFT -> -GeometryUtils.PI_F / 2f
        }
        return GeometryUtils.normalizeAngle(hint.observerPose.yawRadians + offset)
    }

    /**
     * "301, 303, 305 while walking this way, and I want 319" - keep going this way.
     *
     * Deliberately capped low: numbering can restart, skip, or run in the opposite direction on
     * the far side of a junction. It nudges, it does not decide.
     */
    private fun roomNumberGradientScore(
        frontierCentroid: Vec2,
        userPose: Pose3D,
        target: NavigationTarget,
    ): Float {
        val targetNumber = (target as? NavigationTarget.Room)?.number ?: return 0f
        if (roomTrail.size < 2) return 0f

        val (firstNumber, firstPos) = roomTrail.first()
        val (lastNumber, lastPos) = roomTrail.last()
        val travel = lastPos - firstPos
        val travelled = travel.length()
        if (travelled < 1.0f) return 0f

        val numberDelta = lastNumber - firstNumber
        if (numberDelta == 0) return 0f

        val remaining = targetNumber - lastNumber
        if (remaining == 0) return 0f

        // Walk on in the same direction when the numbers are still heading towards the target.
        val sameDirection = sign(remaining.toFloat()) == sign(numberDelta.toFloat())
        val preferred = if (sameDirection) travel.normalized() else travel.normalized() * -1f

        val toFrontier = (frontierCentroid - userPose.position2D)
        if (toFrontier.lengthSquared() < 1e-4f) return 0f
        val alignment = toFrontier.normalized().dot(preferred)
        if (alignment <= 0f) return 0f

        // Confidence shrinks as the remaining gap grows: 319 from 305 is plausible, 900 is not.
        val plausibility = GeometryUtils.clamp(1f - abs(remaining) / 100f, 0f, 1f)
        return min(MAX_GRADIENT_SCORE, alignment * plausibility * MAX_GRADIENT_SCORE)
    }

    /** Debug/UI helper: the most recent room labels observed. */
    fun recentRoomNumbers(limit: Int = 5): List<Int> =
        roomTrail.takeLast(max(0, limit)).map { it.first }

    private companion object {
        const val MAX_ROOM_TRAIL = 12

        /** One reading and an assumed height: ranks below any depth-placed sighting. */
        const val RANGED_CONFIDENCE = 0.5f
        const val MAX_GRADIENT_SCORE = 0.5f

        /** A new depth sighting replaces one measured from at most this much nearer. */
        const val NEARER_SLACK_METERS = 1f
    }
}
