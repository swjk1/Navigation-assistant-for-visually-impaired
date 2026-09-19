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
)

/** One stored observation together with where the user was standing when it arrived. */
private data class StoredHint(
    val observation: SemanticObservation,
    val observerPose: Pose3D,
    val receivedAtMillis: Long,
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

    fun reset() {
        hints.clear()
        roomTrail.clear()
        currentFloorId = null
        targetSighting = null
    }

    /** Called when the destination changes: an old sighting must not steer a new search. */
    fun clearTargetSighting() {
        targetSighting = null
    }

    val hintCount: Int get() = hints.size

    /**
     * @param observerPose where the user stood when the observation was made. Directional hints
     *        ("rooms 300-349 to the right") are meaningless without it.
     */
    fun submit(
        observations: List<SemanticObservation>,
        nowMillis: Long,
        observerPose: Pose3D,
        target: NavigationTarget,
    ) {
        for (observation in observations) {
            if (observation.confidence < config.semanticMinConfidence) continue

            if (observation is SemanticObservation.Floor) {
                currentFloorId = observation.floor
                continue
            }

            hints.add(StoredHint(observation, observerPose, nowMillis))

            if (observation is SemanticObservation.Room) {
                TargetMatcher.numericPart(observation.label)?.let { number ->
                    roomTrail.add(number to observerPose.position2D)
                    if (roomTrail.size > MAX_ROOM_TRAIL) roomTrail.removeAt(0)
                }
            }

            // A localized sighting of the destination is what flips EXPLORING -> NAVIGATING.
            if (TargetMatcher.matches(observation, target)) {
                val world = observation.worldPosition
                if (world != null) {
                    val existing = targetSighting
                    if (existing == null || observation.confidence >= existing.confidence ||
                        nowMillis - existing.observedAtMillis > config.semanticMaxAgeMillis / 2
                    ) {
                        targetSighting = TargetSighting(
                            worldPosition = world,
                            confidence = observation.confidence,
                            observedAtMillis = nowMillis,
                            label = target.description,
                        )
                    }
                }
            }
        }
        prune(nowMillis)
    }

    fun prune(nowMillis: Long) {
        val maxAge = config.semanticMaxAgeMillis
        hints.removeAll { nowMillis - it.receivedAtMillis > maxAge }
        targetSighting?.let {
            // A sighting survives longer than a directional hint: it is a concrete destination.
            if (nowMillis - it.observedAtMillis > maxAge * 4) targetSighting = null
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
            is SemanticObservation.Room ->
                // A near-miss room number is mild evidence that the right corridor is this way.
                nearMissRelevance(observation, target)
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
        const val MAX_GRADIENT_SCORE = 0.5f
    }
}
