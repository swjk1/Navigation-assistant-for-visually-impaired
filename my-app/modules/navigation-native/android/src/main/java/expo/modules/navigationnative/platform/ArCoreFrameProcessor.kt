package expo.modules.navigationnative.platform

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Vec3
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

    fun reset() {
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
        var bestY: Float? = null
        var bestExtent = 0f
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            if (plane.subsumedBy != null) continue

            val center = plane.centerPose
            val canonical: Vec3 = poseProvider.toCanonicalPoint(center.tx(), center.ty(), center.tz())
            val drop = deviceY - canonical.y
            // The floor is below the phone, but not by an implausible amount. A table top or a
            // stair landing sits in the same band, so extent is used to pick the dominant surface.
            if (drop < 0.6f || drop > 2.6f) continue

            val extent = plane.extentX * plane.extentZ
            if (extent > bestExtent) {
                bestExtent = extent
                bestY = canonical.y
            }
        }
        val y = bestY ?: return null
        // A 1 m^2 patch is weak evidence; 6 m^2 of tracked floor is strong.
        val confidence = min(1f, 0.35f + bestExtent / 6f)
        return y to confidence
    }

    /** Exposed so semantic observations can be turned into world positions. */
    fun resolveWorldPosition(
        normalizedX: Float,
        normalizedY: Float,
        timestampNanos: Long?,
    ): Vec3? = depthProvider.resolveWorldPosition(normalizedX, normalizedY, timestampNanos)
}
