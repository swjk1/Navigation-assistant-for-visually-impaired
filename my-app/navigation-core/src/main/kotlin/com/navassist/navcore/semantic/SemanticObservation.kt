package com.navassist.navcore.semantic

import com.navassist.navcore.geometry.Vec3

/** Coarse direction relative to the observer's heading at the time of the observation. */
enum class SemanticDirection {
    LEFT,
    RIGHT,
    FORWARD,
}

/**
 * Evidence about what is in the environment, produced by the perception layer (OCR of room
 * numbers, exit signs, ...). The navigation engine does NOT produce these and does not implement
 * any recognition itself - that is a separate task.
 *
 * Semantics only ever bias exploration. They never issue movement commands: the deterministic
 * planner does (architecture Rule 3).
 *
 * [worldPosition] is optional and is filled in by the PLATFORM adapter when it could associate
 * the observation's image coordinate with a recent depth frame. The core cannot do that
 * association itself because it never sees depth images or camera intrinsics.
 */
sealed interface SemanticObservation {

    /** 0..1. Low-confidence observations are dropped on arrival. */
    val confidence: Float

    /** Platform timestamp, in nanoseconds, of the camera frame this came from. */
    val timestampNanos: Long?

    /** Canonical world position, when the adapter could resolve one. */
    val worldPosition: Vec3?

    /** A specific room label was read, e.g. "314" or "B12". */
    data class Room(
        val label: String,
        override val confidence: Float,
        val normalizedX: Float? = null,
        val normalizedY: Float? = null,
        override val timestampNanos: Long? = null,
        override val worldPosition: Vec3? = null,
    ) : SemanticObservation

    /** A wayfinding sign, e.g. "Rooms 300-349 ->". Purely directional evidence. */
    data class RoomRange(
        val min: Int,
        val max: Int,
        val direction: SemanticDirection,
        override val confidence: Float,
        override val timestampNanos: Long? = null,
    ) : SemanticObservation {
        override val worldPosition: Vec3? get() = null

        fun contains(number: Int): Boolean = number in min..max
    }

    data class Exit(
        val direction: SemanticDirection? = null,
        override val confidence: Float,
        val normalizedX: Float? = null,
        val normalizedY: Float? = null,
        override val timestampNanos: Long? = null,
        override val worldPosition: Vec3? = null,
    ) : SemanticObservation

    data class Stairs(
        override val confidence: Float,
        val normalizedX: Float? = null,
        val normalizedY: Float? = null,
        override val timestampNanos: Long? = null,
        override val worldPosition: Vec3? = null,
    ) : SemanticObservation

    data class Elevator(
        override val confidence: Float,
        val normalizedX: Float? = null,
        val normalizedY: Float? = null,
        override val timestampNanos: Long? = null,
        override val worldPosition: Vec3? = null,
    ) : SemanticObservation

    /** Which building floor the user is currently on, e.g. read from a lift indicator. */
    data class Floor(
        val floor: String,
        override val confidence: Float,
        override val timestampNanos: Long? = null,
    ) : SemanticObservation {
        override val worldPosition: Vec3? get() = null
    }
}
