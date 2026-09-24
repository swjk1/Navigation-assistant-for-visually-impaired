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
        val world = resolution.worldPosition
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
