package expo.modules.navigationnative

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import com.navassist.navcore.geometry.Vec3
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticDirection
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.state.NavigationSnapshot

/**
 * The JS <-> core translation layer.
 *
 * The shared semantic models live in the platform-independent core, so a future Swift adapter
 * translates the SAME JavaScript objects into the SAME Kotlin types. React Native code does not
 * change when the platform does.
 */

class TargetRecord : Record {
    /** "ROOM" | "EXIT" | "DOOR" | "STAIRS" | "ELEVATOR" | "EXPLORE" */
    @Field var type: String = "EXPLORE"

    /** Room label, e.g. "314". Only meaningful for type = "ROOM". */
    @Field var value: String? = null

    fun toNavigationTarget(): NavigationTarget = when (type.uppercase()) {
        "ROOM" -> value?.takeIf { it.isNotBlank() }
            ?.let { NavigationTarget.Room(it) }
            ?: NavigationTarget.Explore
        "EXIT" -> NavigationTarget.Exit
        "DOOR" -> NavigationTarget.Door
        "STAIRS" -> NavigationTarget.Stairs
        "ELEVATOR" -> NavigationTarget.Elevator
        else -> NavigationTarget.Explore
    }
}

class SemanticObservationRecord : Record {
    /** "ROOM" | "ROOM_RANGE" | "EXIT" | "DOOR" | "STAIRS" | "ELEVATOR" | "FLOOR" */
    @Field var type: String = ""

    @Field var confidence: Float = 0f

    // ROOM
    @Field var label: String? = null

    // ROOM_RANGE
    @Field var min: Int? = null
    @Field var max: Int? = null

    // ROOM_RANGE / EXIT
    @Field var direction: String? = null

    // FLOOR
    @Field var floor: String? = null

    // Image-space location, 0..1. The adapter resolves this against a recent depth frame.
    @Field var normalizedX: Float? = null
    @Field var normalizedY: Float? = null

    /**
     * Timestamp of the camera frame the observation came from, in nanoseconds (the value from
     * ARCore's Frame.getTimestamp()). Used to reject observations that are too old to associate
     * with current depth. JS numbers hold this exactly for the lifetime of a boot.
     */
    @Field var timestampNs: Double? = null

    // Already-resolved world position, if the caller has one.
    @Field var worldX: Float? = null
    @Field var worldY: Float? = null
    @Field var worldZ: Float? = null

    fun toObservation(): SemanticObservation? {
        val world = if (worldX != null && worldY != null && worldZ != null) {
            Vec3(worldX!!, worldY!!, worldZ!!)
        } else {
            null
        }
        val timestamp = timestampNs?.toLong()
        return when (type.uppercase()) {
            "ROOM" -> SemanticObservation.Room(
                label = label ?: return null,
                confidence = confidence,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                timestampNanos = timestamp,
                worldPosition = world,
            )

            "ROOM_RANGE" -> SemanticObservation.RoomRange(
                min = min ?: return null,
                max = max ?: return null,
                direction = parseDirection(direction) ?: SemanticDirection.FORWARD,
                confidence = confidence,
                timestampNanos = timestamp,
            )

            "EXIT" -> SemanticObservation.Exit(
                direction = parseDirection(direction),
                confidence = confidence,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                timestampNanos = timestamp,
                worldPosition = world,
            )

            "DOOR" -> SemanticObservation.Door(
                direction = parseDirection(direction),
                confidence = confidence,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                timestampNanos = timestamp,
                worldPosition = world,
            )

            "STAIRS" -> SemanticObservation.Stairs(
                confidence = confidence,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                timestampNanos = timestamp,
                worldPosition = world,
            )

            "ELEVATOR" -> SemanticObservation.Elevator(
                confidence = confidence,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                timestampNanos = timestamp,
                worldPosition = world,
            )

            "FLOOR" -> SemanticObservation.Floor(
                floor = floor ?: return null,
                confidence = confidence,
                timestampNanos = timestamp,
            )

            else -> null
        }
    }

    private fun parseDirection(value: String?): SemanticDirection? = when (value?.uppercase()) {
        "LEFT" -> SemanticDirection.LEFT
        "RIGHT" -> SemanticDirection.RIGHT
        "FORWARD" -> SemanticDirection.FORWARD
        else -> null
    }
}

/** Flattens a snapshot into the plain map the bridge can carry. */
fun NavigationSnapshot.toEventMap(): Map<String, Any?> = buildMap {
    put("timestamp", timestampMillis.toDouble())
    put("status", status.name)
    put("command", command.name)
    put("stopReason", stopReason?.name)
    put("headingErrorDegrees", headingErrorDegrees)
    put("distanceToWaypointMeters", distanceToWaypointMeters)
    put("distanceMeters", distanceToTargetMeters ?: distanceToWaypointMeters)
    put("trackingConfidence", trackingConfidence)
    put("mapConfidence", mapConfidence)
    put("selectedFrontierId", selectedFrontierId)
    put("target", targetDescription)
    debug?.let { d ->
        put(
            "debug",
            mapOf(
                "freeCells" to d.freeCells,
                "occupiedCells" to d.occupiedCells,
                "unknownCells" to d.unknownCells,
                "frontierCount" to d.frontierCount,
                "topologicalNodeCount" to d.topologicalNodeCount,
                "pathLength" to d.pathLength,
                "floorY" to d.floorY,
                "floorConfidence" to d.floorConfidence,
                "depthPointsLastFrame" to d.depthPointsLastFrame,
                "depthAvailable" to d.depthAvailable,
                "poseX" to d.poseX,
                "poseY" to d.poseY,
                "poseZ" to d.poseZ,
                "yawDegrees" to d.yawDegrees,
                "lastError" to d.lastError,
            ),
        )
    }
}
