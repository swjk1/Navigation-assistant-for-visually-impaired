package expo.modules.navigationnative.platform

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Vec3
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * The LAST place in the stack that knows what ARCore is.
 *
 *   ARCore Frame -> tracking check -> camera pose -> depth -> canonical NavigationFrame
 *
 * Everything after this point (occupancy, frontiers, A*, control, state machine) is
 * platform-independent. Swap this class for an ARKit equivalent and nothing downstream changes.
 */
class ArCoreFrameProcessor(
    private val config: NavigationConfig,
    private val poseProvider: ArCorePoseProvider = ArCorePoseProvider(),
    private val depthProvider: ArCoreDepthProvider = ArCoreDepthProvider(config),
) {
    var lastTrackingState: TrackingState = TrackingState.STOPPED
        private set

    var lastDepthPointCount: Int = 0
        private set

    /**
     * Which coordinate frame the frames produced here belong to. Changes only on the GL thread,
     * together with the pose basis itself, so a frame and its generation can never disagree.
     */
    var generation: Int = 0
        private set

    private val pendingResetGeneration = AtomicInteger(NO_RESET)

    /**
     * Asks for the canonical frame to be re-established, from any thread. It happens at the start
     * of the next [process] call - never in the middle of one, which is what resetting directly
     * from the JS or engine thread used to risk: a frame converted with half-old, half-new basis.
     * Frames produced from then on carry [newGeneration].
     */
    fun requestReset(newGeneration: Int) {
        pendingResetGeneration.set(newGeneration)
    }

    private fun resetNow() {
        poseProvider.reset()
        depthProvider.reset()
        lastTrackingState = TrackingState.STOPPED
        lastDepthPointCount = 0
    }

    /**
     * @return a canonical frame, or null when the pose cannot be trusted yet. The caller turns a
     *         null into LOST_TRACKING + STOP rather than reusing the previous pose.
     */
    fun process(session: Session, frame: Frame): NavigationFrame {
        val requested = pendingResetGeneration.getAndSet(NO_RESET)
        if (requested != NO_RESET) {
            resetNow()
            generation = requested
        }

        val camera = frame.camera
        lastTrackingState = camera.trackingState

        if (camera.trackingState != TrackingState.TRACKING) {
            // Rule 4: no trusted pose means STOP. We still emit a frame so the engine can run its
            // state machine, but with tracking = false and no points.
            return NavigationFrame(
                timestampNanos = frame.timestamp,
                pose = com.navassist.navcore.geometry.Pose3D.IDENTITY,
                points = DepthPointCloud.EMPTY,
                trackingConfidence = 0f,
                tracking = false,
                depthAvailable = false,
            )
        }

        if (!poseProvider.isEstablished) poseProvider.establish(camera.pose)

        val pose = poseProvider.toCanonicalPose(camera.pose)
        val points = depthProvider.acquire(frame, camera, poseProvider)
        lastDepthPointCount = points?.count ?: 0

        val floorHint = estimateFloorHint(session, pose.y)

        return NavigationFrame(
            timestampNanos = frame.timestamp,
            pose = pose,
            points = points ?: DepthPointCloud.EMPTY,
            trackingConfidence = 1f,
            tracking = true,
            depthAvailable = points != null && points.count > 0,
            floorHint = floorHint?.first,
            floorHintConfidence = floorHint?.second ?: 0f,
            sensorPosition = if (points != null) depthProvider.lastSensorPosition else null,
        )
    }

    /**
     * Floor height from ARCore's horizontal upward-facing planes, expressed in canonical Y.
     *
     * This is only ever a HINT. It is passed through NavigationFrame.floorHint so the core can
     * use it when it is good and fall back to its own depth-based estimation when it is not -
     * which is also how an ARKit plane anchor will be delivered later.
     */
    private fun estimateFloorHint(session: Session, deviceY: Float): Pair<Float, Float>? {
        var lowestY: Float? = null
        var lowestExtent = 0f
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            if (plane.subsumedBy != null) continue

            val extent = plane.extentX * plane.extentZ
            if (extent < MIN_FLOOR_PLANE_AREA) continue

            val center = plane.centerPose
            val canonical: Vec3 = poseProvider.toCanonicalPoint(center.tx(), center.ty(), center.tz())
            val drop = deviceY - canonical.y
            if (drop < config.floorMinDropMeters || drop > config.floorMaxDropMeters) continue

            // LOWEST, not largest. A desk, a table or a bed is a horizontal upward-facing plane
            // sitting in the same band as the floor, and near the start of a session it is often
            // the biggest one ARCore has tracked - it is close, well lit and textured. Picking by
            // area therefore hands back the desk height, and everything beneath it (the real
            // floor, chairs, bags) then classifies as "below floor" and is integrated as free
            // space. The floor is by definition the lowest surface you can stand on.
            if (lowestY == null || canonical.y < lowestY) {
                lowestY = canonical.y
                lowestExtent = extent
            }
        }
        val y = lowestY ?: return null
        // Capped below 1.0 on purpose: a plane is a hint. The core fuses it with depth and lets a
        // well-supported surface below it win, in case this is still a table.
        val confidence = min(MAX_FLOOR_HINT_CONFIDENCE, 0.4f + lowestExtent / 8f)
        return y to confidence
    }

    /** Resolves a semantic observation against the depth of the frame it was seen in. Any thread. */
    fun resolve(
        normalizedX: Float?,
        normalizedY: Float?,
        timestampNanos: Long?,
    ): ArCoreDepthProvider.Resolution = depthProvider.resolve(normalizedX, normalizedY, timestampNanos)

    /** Keeps the depth for a frame whose RGB image was just captured for perception. GL thread. */
    fun pinDepth(timestampNanos: Long) = depthProvider.pin(timestampNanos)

    private companion object {
        /** Ignore specks: a real floor patch ARCore is tracking is at least this many m^2. */
        const val MIN_FLOOR_PLANE_AREA = 0.6f
        /** A plane never fully overrides depth-based floor estimation. */
        const val MAX_FLOOR_HINT_CONFIDENCE = 0.8f

        const val NO_RESET = -1
    }
}
