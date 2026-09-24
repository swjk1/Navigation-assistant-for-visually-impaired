package com.navassist.navcore.sensing

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec3
import kotlin.math.abs
import kotlin.math.min

/** A horizontal upward-facing plane the platform is tracking, in platform WORLD coordinates. */
data class PlaneObservation(
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val areaSquareMeters: Float,
)

/**
 * Raw AR platform output -> canonical [NavigationFrame].
 *
 *   camera pose + latest depth image + tracked planes  ->  pose, point cloud, floor hint
 *
 * Everything an AR adapter used to do after pulling buffers out of the SDK, so that the phone
 * and the simulator run the same code: the canonical frame, depth deduplication, matching depth to
 * the pose it was captured at, unprojection, the floor hint, and the depth snapshots semantic
 * observations are resolved against. An adapter is left with "copy the SDK's buffers in".
 *
 * Not thread-safe except for [resolve], which may be called from any thread. Everything else
 * belongs to the thread that calls [assemble]. The returned frame's points live in a reused
 * buffer, valid until the next [assemble]; an adapter handing frames to another thread copies them.
 */
class SensorFrameAssembler(
    private val config: NavigationConfig,
    onClockMismatch: (offByMillis: Long) -> Unit = {},
) {
    val canonical = CanonicalFrame()
    private val snapshots = DepthSnapshotStore(config, onClockMismatch = onClockMismatch)

    private val cloud = DepthPointCloud.allocate(config.maxPointsPerFrame)
    private val transform = FloatArray(12)
    private val depthPose = FloatArray(16)

    private val poseTimestamps = LongArray(POSE_HISTORY)
    private val poseMatrices = Array(POSE_HISTORY) { FloatArray(16) }
    private var poseNext = 0
    private var poseCount = 0

    private var lastDepthTimestampNanos = 0L

    var lastPointCount: Int = 0
        private set

    fun reset() {
        canonical.reset()
        snapshots.clear()
        cloud.clear()
        poseNext = 0
        poseCount = 0
        lastDepthTimestampNanos = 0L
        lastPointCount = 0
    }

    /**
     * True when a depth image with this timestamp has not been integrated yet. Lets an adapter
     * skip copying a depth image the platform merely handed back again.
     */
    fun isNewDepth(depthTimestampNanos: Long): Boolean = depthTimestampNanos != lastDepthTimestampNanos

    /**
     * @param cameraToWorld column-major 4x4 of the PHYSICAL camera (sensor axes, -Z forward).
     * @param depth the latest depth image the platform has, which may be one already integrated
     *        (it is then ignored), or null when there is none.
     * @param depthIntrinsics the camera at the depth image's resolution.
     */
    fun assemble(
        timestampNanos: Long,
        tracking: Boolean,
        cameraToWorld: FloatArray,
        depth: DepthImage?,
        depthIntrinsics: CameraIntrinsics?,
        planes: List<PlaneObservation> = emptyList(),
    ): NavigationFrame {
        if (!tracking) {
            // No trusted pose means STOP. The frame still goes out so the engine can run its
            // state machine, but with tracking = false and no points.
            return NavigationFrame(
                timestampNanos = timestampNanos,
                pose = Pose3D.IDENTITY,
                points = DepthPointCloud.EMPTY,
                trackingConfidence = 0f,
                tracking = false,
                depthAvailable = false,
            )
        }

        if (!canonical.isEstablished) canonical.establish(cameraToWorld)
        recordPose(timestampNanos, cameraToWorld)
        val pose = canonical.toCanonicalPose(cameraToWorld)

        var points: DepthPointCloud? = null
        var sensorPosition: Vec3? = null
        if (depth != null && depthIntrinsics != null && isNewDepth(depth.timestampNanos)) {
            lastDepthTimestampNanos = depth.timestampNanos
            // Depth usually lags the camera: unproject with the pose it was captured at, or the
            // walls smear sideways while the user turns.
            if (!poseAt(depth.timestampNanos, depthPose)) cameraToWorld.copyInto(depthPose)
            canonical.cameraToCanonical(depthPose, transform)
            sensorPosition = Vec3(transform[3], transform[7], transform[11])
            DepthUnprojector.unproject(
                depth,
                depthIntrinsics,
                transform,
                config.depthSampleStride,
                config.minDepthMeters,
                config.maxDepthMeters,
                cloud,
                config.depthEdgeRejectFraction,
            )
            snapshots.record(depth, depthIntrinsics, transform)
            lastPointCount = cloud.count
            points = cloud
        }

        val floorHint = floorHint(planes, pose.y)
        return NavigationFrame(
            timestampNanos = timestampNanos,
            pose = pose,
            points = points ?: DepthPointCloud.EMPTY,
            trackingConfidence = 1f,
            tracking = true,
            depthAvailable = points != null && points.count > 0,
            floorHint = floorHint?.first,
            floorHintConfidence = floorHint?.second ?: 0f,
            sensorPosition = sensorPosition,
        )
    }

    /** See [DepthSnapshotStore.pin]. Call on the thread that calls [assemble]. */
    fun pinDepth(timestampNanos: Long) = snapshots.pin(timestampNanos)

    /** See [DepthSnapshotStore.resolve]. Any thread. */
    fun resolve(normalizedX: Float?, normalizedY: Float?, timestampNanos: Long?): DepthSnapshotStore.Resolution =
        snapshots.resolve(normalizedX, normalizedY, timestampNanos)

    /**
     * Floor height from horizontal upward-facing planes, in canonical Y, with a confidence.
     *
     * LOWEST, not largest. A desk, a table or a bed is a horizontal upward-facing plane sitting in
     * the same band as the floor, and near the start of a session it is often the biggest one
     * tracked - it is close, well lit and textured. Picking by area hands back the desk height.
     * The floor is by definition the lowest surface you can stand on.
     *
     * Capped below 1.0 on purpose: a plane is a hint. The core fuses it with depth and lets a
     * well-supported surface below it win, in case this is still a table.
     */
    private fun floorHint(planes: List<PlaneObservation>, deviceY: Float): Pair<Float, Float>? {
        var lowestY: Float? = null
        var lowestArea = 0f
        for (plane in planes) {
            if (plane.areaSquareMeters < MIN_FLOOR_PLANE_AREA) continue
            val y = canonical.toCanonicalPoint(plane.centerX, plane.centerY, plane.centerZ).y
            val drop = deviceY - y
            if (drop < config.floorMinDropMeters || drop > config.floorMaxDropMeters) continue
            if (lowestY == null || y < lowestY) {
                lowestY = y
                lowestArea = plane.areaSquareMeters
            }
        }
        val y = lowestY ?: return null
        return y to min(MAX_FLOOR_HINT_CONFIDENCE, 0.4f + lowestArea / 8f)
    }

    private fun recordPose(timestampNanos: Long, cameraToWorld: FloatArray) {
        cameraToWorld.copyInto(poseMatrices[poseNext])
        poseTimestamps[poseNext] = timestampNanos
        poseNext = (poseNext + 1) % POSE_HISTORY
        if (poseCount < POSE_HISTORY) poseCount++
    }

    /** Copies the camera matrix recorded closest to [timestampNanos] into [out]. */
    private fun poseAt(timestampNanos: Long, out: FloatArray): Boolean {
        var best = -1
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until poseCount) {
            val delta = abs(poseTimestamps[i] - timestampNanos)
            if (delta < bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        if (best < 0 || bestDelta > POSE_MATCH_TOLERANCE_NANOS) return false
        poseMatrices[best].copyInto(out)
        return true
    }

    private companion object {
        /** ~0.5 s of camera poses at 30 fps: comfortably longer than depth lags behind. */
        const val POSE_HISTORY = 16

        /** A recorded pose further than this from the depth timestamp is not "its" pose. */
        const val POSE_MATCH_TOLERANCE_NANOS = 20_000_000L

        /** Ignore specks: a real floor patch being tracked is at least this many m^2. */
        const val MIN_FLOOR_PLANE_AREA = 0.6f

        /** A plane never fully overrides depth-based floor estimation. */
        const val MAX_FLOOR_HINT_CONFIDENCE = 0.8f
    }
}
