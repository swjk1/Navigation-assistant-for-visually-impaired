package com.navassist.navcore.sensing

import com.navassist.navcore.NavigationEngine
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.semantic.SemanticObservation

/**
 * Places semantic observations in the world before they reach the engine.
 *
 * An observation carrying an image coordinate is resolved against the depth of the camera frame
 * it was seen in ([SensorFrameAssembler.resolve]); the same lookup recovers where the user stood
 * then, which directional hints ("exit to the LEFT") are relative to.
 */
object SemanticLocator {

    /** An observation with its world position filled in where possible, and the observer's pose. */
    data class Located(val observation: SemanticObservation, val observerPose: Pose3D?)

    /** Any thread: only touches the assembler's locked depth snapshots. */
    fun locate(observations: List<SemanticObservation>, assembler: SensorFrameAssembler): List<Located> =
        observations.map { locate(it, assembler) }

    fun locate(observation: SemanticObservation, assembler: SensorFrameAssembler): Located {
        val (x, y) = imageCoordinatesOf(observation)
        val resolution = assembler.resolve(x, y, observation.timestampNanos)
        val world = resolution.worldPosition?.takeIf { plausibleSize(observation, resolution) }
        if (observation.worldPosition != null || world == null) return Located(observation, resolution.observerPose)
        val placed = when (observation) {
            is SemanticObservation.Room -> observation.copy(worldPosition = world)
            is SemanticObservation.Exit -> observation.copy(worldPosition = world)
            is SemanticObservation.Door -> observation.copy(worldPosition = world)
            is SemanticObservation.Stairs -> observation.copy(worldPosition = world)
            is SemanticObservation.Elevator -> observation.copy(worldPosition = world)
            else -> observation
        }
        return Located(placed, resolution.observerPose)
    }

    /**
     * Engine thread. Time is the engine's own clock, never the wall clock: hint ageing and
     * sighting expiry compare against frame timestamps.
     */
    fun submit(engine: NavigationEngine, located: List<Located>) {
        for ((observerPose, group) in located.groupBy { it.observerPose }) {
            engine.submitSemanticObservations(
                group.map { it.observation },
                engine.clockMillis,
                observerPose ?: engine.currentPose(),
            )
        }
    }

    /**
     * Rejects a placement that would make the detected object implausibly big or small for what it
     * is. Depth at a single image point is fragile exactly where landmarks are seen: a lift door
     * glimpsed past the end of a corridor wall sits one pixel from that wall's corner, and was
     * placed on it - 7 m short - and the user was told they had arrived. A 2 m door filling a sliver
     * of the image cannot be 2 m away.
     *
     * Without a box size (older callers), the placement is trusted as before.
     */
    private fun plausibleSize(observation: SemanticObservation, resolution: DepthSnapshotStore.Resolution): Boolean {
        val (width, height) = boxSizeOf(observation) ?: return true
        val expected = typicalSizeMeters(observation) ?: return true
        val perWidth = resolution.metersPerImageWidth ?: return true
        val perHeight = resolution.metersPerImageHeight ?: return true
        val placedSize = maxOf(width * perWidth, height * perHeight)
        return placedSize in expected * MIN_SIZE_FRACTION..expected * MAX_SIZE_FACTOR
    }

    /** The largest dimension such an object usually has, in metres. */
    private fun typicalSizeMeters(observation: SemanticObservation): Float? = when (observation) {
        is SemanticObservation.Door -> 2.0f
        is SemanticObservation.Elevator -> 2.1f
        is SemanticObservation.Exit -> 0.4f
        is SemanticObservation.Stairs -> 0.35f
        // A room plate, or the (smaller) text on it.
        is SemanticObservation.Room -> 0.12f
        else -> null
    }

    private fun boxSizeOf(observation: SemanticObservation): Pair<Float, Float>? {
        val (w, h) = when (observation) {
            is SemanticObservation.Room -> observation.normalizedWidth to observation.normalizedHeight
            is SemanticObservation.Exit -> observation.normalizedWidth to observation.normalizedHeight
            is SemanticObservation.Door -> observation.normalizedWidth to observation.normalizedHeight
            is SemanticObservation.Stairs -> observation.normalizedWidth to observation.normalizedHeight
            is SemanticObservation.Elevator -> observation.normalizedWidth to observation.normalizedHeight
            else -> null to null
        }
        return if (w != null && h != null && w > 0f && h > 0f) w to h else null
    }

    /**
     * How far off the typical size a placement may be and still be believed. Wrong placements err
     * NEAR - depth picked up something in front of the object - which makes it look too small, so
     * the lower bound is the one that matters. A third let a lift door placed on a corridor corner
     * 2.5 m away pass as 0.75 m tall; half does not. Partly hidden objects still pass unless more
     * than half is hidden.
     */
    private const val MIN_SIZE_FRACTION = 0.5f
    private const val MAX_SIZE_FACTOR = 3f

    private fun imageCoordinatesOf(observation: SemanticObservation): Pair<Float?, Float?> =
        when (observation) {
            is SemanticObservation.Room -> observation.normalizedX to observation.normalizedY
            is SemanticObservation.Exit -> observation.normalizedX to observation.normalizedY
            is SemanticObservation.Door -> observation.normalizedX to observation.normalizedY
            is SemanticObservation.Stairs -> observation.normalizedX to observation.normalizedY
            is SemanticObservation.Elevator -> observation.normalizedX to observation.normalizedY
            else -> null to null
        }
}
