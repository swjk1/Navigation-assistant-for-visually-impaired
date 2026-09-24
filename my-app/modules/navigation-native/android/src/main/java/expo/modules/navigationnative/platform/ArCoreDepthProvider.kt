package expo.modules.navigationnative.platform

import android.media.Image
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2

/**
 * ARCore depth image -> canonical [DepthPointCloud].
 *
 *   depth pixel -> camera-space 3D point -> ARCore world -> canonical navigation world
 *
 * Things that are easy to get wrong and are handled explicitly here:
 *
 *  - The DEPTH IMAGE RESOLUTION IS NOT THE RGB RESOLUTION (typically ~160x120 vs 640x480+). The
 *    camera intrinsics describe the CPU image, so they are rescaled to the depth image's own
 *    dimensions before being used. Screen coordinates are never assumed to equal depth
 *    coordinates.
 *  - The depth image's y axis points DOWN while ARCore's camera y axis points UP, and the camera
 *    looks along its own -Z. Both signs are flipped when unprojecting.
 *  - Depth usually updates more slowly than the camera, and [Frame.acquireDepthImage16Bits]
 *    hands back the latest depth image it has - often the same one as last frame, captured at an
 *    earlier camera pose. Each depth image is therefore integrated ONCE, keyed by its own
 *    timestamp, and unprojected with the camera pose recorded at that timestamp. Re-integrating
 *    it would count one observation many times; unprojecting it with today's pose smears every
 *    wall sideways while the user turns.
 *  - [Frame.acquireDepthImage16Bits] throws [NotYetAvailableException] routinely, especially in
 *    the first second of a session. That is normal, not an error.
 *  - Images are ALWAYS closed, via `use {}`.
 *
 * Threading: [acquire], [pin] and [reset] run on the GL thread; [resolve] runs on whichever thread
 * delivers semantic observations. Only the snapshot buffers are shared, under [snapshotLock].
 */
class ArCoreDepthProvider(private val config: NavigationConfig) {

    /** Reused across frames: no per-frame allocation of thousands of points. */
    private val cloud = DepthPointCloud.allocate(config.maxPointsPerFrame)
    private val transform = FloatArray(12)
    private val cameraMatrix = FloatArray(16)
    private val point = FloatArray(3)

    // ---------------------------------------------------------------- camera pose history (GL)

    private val poseTimestamps = LongArray(POSE_HISTORY)
    private val poseMatrices = Array(POSE_HISTORY) { FloatArray(16) }
    private var poseNext = 0
    private var poseCount = 0

    private var lastDepthImageTimestampNanos = 0L

    /** Where the sensor was for the most recently integrated depth image, canonical frame. */
    var lastSensorPosition: Vec3? = null
        private set

    var lastPointCount: Int = 0
        private set

    // ---------------------------------------------------------------- depth snapshots (shared)

    /**
     * A copy of one depth image plus the intrinsics and transform needed to unproject it. A few
     * tens of kilobytes, unlike retaining the ARCore Image itself.
     */
    private class DepthSnapshot {
        var depth = ShortArray(0)
        var width = 0
        var height = 0
        var timestampNanos = 0L
        var fx = 0f
        var fy = 0f
        var cx = 0f
        var cy = 0f
        val transform = FloatArray(12)

        fun copyFrom(other: DepthSnapshot) {
            if (depth.size != other.depth.size) depth = ShortArray(other.depth.size)
            System.arraycopy(other.depth, 0, depth, 0, other.depth.size)
            width = other.width
            height = other.height
            timestampNanos = other.timestampNanos
            fx = other.fx
            fy = other.fy
            cx = other.cx
            cy = other.cy
            System.arraycopy(other.transform, 0, transform, 0, transform.size)
        }
    }

    private val snapshotLock = Any()

    /** The last few depth images, newest at `recentNext - 1`. */
    private val recent = Array(RECENT_SNAPSHOTS) { DepthSnapshot() }
    private var recentNext = 0
    private var recentCount = 0

    /**
     * Depth images kept for an RGB capture that is still being analysed. Perception takes
     * seconds (YOLO, OCR, sometimes a VLM round trip); by the time its observation arrives, the
     * recent ring has long moved on and the camera is looking somewhere else. See [pin].
     */
    private val pinned = Array(PINNED_SNAPSHOTS) { DepthSnapshot() }
    private var pinnedNext = 0
    private var pinnedCount = 0

    /** What a semantic observation resolves to. */
    data class Resolution(
        /** Canonical world position of the image point, when it had usable depth. */
        val worldPosition: Vec3?,
        /** Where the camera was when the image was taken, when a matching frame was found. */
        val observerPose: Pose3D?,
    )

    fun reset() {
        cloud.clear()
        lastPointCount = 0
        poseNext = 0
        poseCount = 0
        lastDepthImageTimestampNanos = 0L
        lastSensorPosition = null
        // Snapshots carry transforms into the canonical frame that is being discarded.
        synchronized(snapshotLock) {
            recentCount = 0
            recentNext = 0
            pinnedCount = 0
            pinnedNext = 0
        }
    }

    /**
     * @return the populated point cloud, or null when there is no NEW depth for this frame - not
     *         available yet, or the same image already integrated. Neither is an error.
     */
    fun acquire(frame: Frame, camera: Camera, poseProvider: ArCorePoseProvider): DepthPointCloud? {
        recordPose(frame.timestamp, camera)
        return try {
            frame.acquireDepthImage16Bits().use { image ->
                val depthTimestamp = image.timestamp
                if (depthTimestamp == lastDepthImageTimestampNanos) return null
                lastDepthImageTimestampNanos = depthTimestamp

                if (!poseAt(depthTimestamp, cameraMatrix)) camera.pose.toMatrix(cameraMatrix, 0)
                poseProvider.fillCanonicalFromCamera(cameraMatrix, transform)
                // The camera origin in canonical coordinates is the transform's translation.
                lastSensorPosition = Vec3(transform[3], transform[7], transform[11])
                unproject(image, camera, depthTimestamp)
                lastPointCount = cloud.count
                cloud
            }
        } catch (e: NotYetAvailableException) {
            // Depth is simply not ready yet. The engine tolerates gaps and only escalates to
            // SCAN once depth has been missing for longer than depthStarvationMillis.
            null
        } catch (e: IllegalStateException) {
            // Session in a state where depth cannot be acquired (e.g. paused mid-frame).
            null
        }
    }

    private fun recordPose(timestampNanos: Long, camera: Camera) {
        camera.pose.toMatrix(poseMatrices[poseNext], 0)
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
        System.arraycopy(poseMatrices[best], 0, out, 0, 16)
        return true
    }

    private fun unproject(image: Image, camera: Camera, timestampNanos: Long) {
        cloud.clear()

        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer.order(ByteOrder.nativeOrder())
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // Rescale the CPU-image intrinsics onto the (much smaller) depth image.
        val intrinsics = camera.imageIntrinsics
        val dimensions = intrinsics.imageDimensions
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        val scaleX = width.toFloat() / dimensions[0].toFloat()
        val scaleY = height.toFloat() / dimensions[1].toFloat()
        val fx = focal[0] * scaleX
        val fy = focal[1] * scaleY
        val cx = principal[0] * scaleX
        val cy = principal[1] * scaleY
        if (fx <= 0f || fy <= 0f) return

        val stride = config.depthSampleStride.coerceAtLeast(1)
        val minDepth = config.minDepthMeters
        val maxDepth = config.maxDepthMeters

        captureSnapshot(width, height, buffer, rowStride, pixelStride, timestampNanos, fx, fy, cx, cy)

        var v = 0
        while (v < height) {
            val rowBase = v * rowStride
            var u = 0
            while (u < width) {
                val raw = buffer.getShort(rowBase + u * pixelStride).toInt() and 0xFFFF
                val depthMeters = raw * 0.001f
                if (depthMeters >= minDepth && depthMeters <= maxDepth) {
                    // Camera space: +x right, +y up, camera looks along -z.
                    // The depth image's v axis points down, hence the y negation.
                    val camX = (u - cx) * depthMeters / fx
                    val camY = -(v - cy) * depthMeters / fy
                    val camZ = -depthMeters
                    ArCorePoseProvider.transform(transform, camX, camY, camZ, point)
                    if (!cloud.add(point[0], point[1], point[2])) return
                }
                u += stride
            }
            v += stride
        }
    }

    private fun captureSnapshot(
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        timestampNanos: Long,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
    ) = synchronized(snapshotLock) {
        val slot = recent[recentNext]
        if (slot.depth.size != width * height) slot.depth = ShortArray(width * height)
        var index = 0
        for (v in 0 until height) {
            val rowBase = v * rowStride
            for (u in 0 until width) {
                slot.depth[index++] = buffer.getShort(rowBase + u * pixelStride)
            }
        }
        slot.width = width
        slot.height = height
        slot.timestampNanos = timestampNanos
        slot.fx = fx
        slot.fy = fy
        slot.cx = cx
        slot.cy = cy
        System.arraycopy(transform, 0, slot.transform, 0, transform.size)
        recentNext = (recentNext + 1) % RECENT_SNAPSHOTS
        if (recentCount < RECENT_SNAPSHOTS) recentCount++
    }

    /**
     * Keeps the depth image nearest [timestampNanos] until observations derived from that
     * camera frame have had time to arrive. Call on the GL thread right after the frame was
     * submitted, when capturing its RGB image for perception.
     */
    fun pin(timestampNanos: Long) = synchronized(snapshotLock) {
        val source = nearestIn(recent, recentCount, timestampNanos) ?: return@synchronized
        if (abs(source.timestampNanos - timestampNanos) > SNAPSHOT_MATCH_TOLERANCE_NANOS) return@synchronized
        pinned[pinnedNext].copyFrom(source)
        pinnedNext = (pinnedNext + 1) % PINNED_SNAPSHOTS
        if (pinnedCount < PINNED_SNAPSHOTS) pinnedCount++
    }

    /**
     * Resolves a semantic observation against the depth image of the camera frame it came from.
     *
     * With [observationTimestampNanos] the matching snapshot must be found (pinned at capture, or
     * still in the recent ring); resolving against any other frame would place the landmark
     * wherever the camera happens to point NOW, which after a second of turning is a different
     * wall. Without a timestamp the newest depth image is used, which is only right when the
     * observation is effectively instantaneous.
     *
     * MVP simplification: the normalized coordinate is assumed to address the same field of view
     * as the depth image, i.e. the camera SENSOR image, not the rotated display image.
     */
    fun resolve(
        normalizedX: Float?,
        normalizedY: Float?,
        observationTimestampNanos: Long?,
    ): Resolution = synchronized(snapshotLock) {
        val snapshot = findSnapshot(observationTimestampNanos) ?: return@synchronized NOT_RESOLVED
        val pose = observerPoseOf(snapshot)
        if (normalizedX == null || normalizedY == null) return@synchronized Resolution(null, pose)
        Resolution(worldPositionIn(snapshot, normalizedX, normalizedY), pose)
    }

    private fun findSnapshot(timestampNanos: Long?): DepthSnapshot? {
        if (recentCount == 0 && pinnedCount == 0) return null
        val latest = if (recentCount > 0) recent[(recentNext - 1 + RECENT_SNAPSHOTS) % RECENT_SNAPSHOTS] else null
        if (timestampNanos == null) return latest
        if (latest != null && abs(latest.timestampNanos - timestampNanos) > CLOCK_DOMAIN_MISMATCH_NANOS) {
            // Not in ARCore's clock domain at all - almost always a wall clock (Date.now()) where
            // Frame.getTimestamp() was expected. Dropping would silently discard every observation
            // the perception layer ever sends; the latest frame is the least-bad answer and the
            // log makes the mistake visible.
            Log.w(
                TAG,
                "Semantic observation timestamp is not an ARCore frame timestamp " +
                    "(off by ${abs(latest.timestampNanos - timestampNanos) / 1_000_000} ms); using the " +
                    "most recent depth frame instead. Pass the capture's timestampNs, or omit it.",
            )
            return latest
        }
        val fromRecent = nearestIn(recent, recentCount, timestampNanos)
        val fromPinned = nearestIn(pinned, pinnedCount, timestampNanos)
        val best = listOfNotNull(fromRecent, fromPinned).minByOrNull { abs(it.timestampNanos - timestampNanos) }
        return best?.takeIf { abs(it.timestampNanos - timestampNanos) <= SNAPSHOT_MATCH_TOLERANCE_NANOS }
    }

    private fun nearestIn(slots: Array<DepthSnapshot>, count: Int, timestampNanos: Long): DepthSnapshot? {
        var best: DepthSnapshot? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until count) {
            val delta = abs(slots[i].timestampNanos - timestampNanos)
            if (delta < bestDelta) {
                bestDelta = delta
                best = slots[i]
            }
        }
        return best
    }

    private fun worldPositionIn(snapshot: DepthSnapshot, normalizedX: Float, normalizedY: Float): Vec3? {
        val width = snapshot.width
        val height = snapshot.height
        if (width == 0 || height == 0) return null
        val u = (normalizedX * width).toInt().coerceIn(0, width - 1)
        val v = (normalizedY * height).toInt().coerceIn(0, height - 1)

        // Average a small window: a single depth pixel on a door plate is easily invalid.
        var sum = 0f
        var count = 0
        for (dv in -2..2) {
            for (du in -2..2) {
                val su = (u + du).coerceIn(0, width - 1)
                val sv = (v + dv).coerceIn(0, height - 1)
                val raw = snapshot.depth[sv * width + su].toInt() and 0xFFFF
                val meters = raw * 0.001f
                if (meters >= config.minDepthMeters && meters <= config.maxDepthMeters) {
                    sum += meters
                    count++
                }
            }
        }
        if (count == 0) return null
        val depth = sum / count

        val camX = (u - snapshot.cx) * depth / snapshot.fx
        val camY = -(v - snapshot.cy) * depth / snapshot.fy
        val camZ = -depth
        val out = FloatArray(3)
        ArCorePoseProvider.transform(snapshot.transform, camX, camY, camZ, out)
        return Vec3(out[0], out[1], out[2])
    }

    /** The camera pose the snapshot was taken from, read straight off its canonical transform. */
    private fun observerPoseOf(snapshot: DepthSnapshot): Pose3D {
        val t = snapshot.transform
        // The camera looks along its own -Z; the transform's third column is +Z in canonical.
        var hx = -t[2]
        var hz = -t[10]
        if (hx * hx + hz * hz < 1e-4f) {
            // Steeply pitched phone: use the screen-up direction, as ArCorePoseProvider does.
            hx = t[1]
            hz = t[9]
        }
        return Pose3D(t[3], t[7], t[11], atan2(hx, hz))
    }

    private companion object {
        const val TAG = "ArCoreDepthProvider"

        /** ~0.5 s of camera poses at 30 fps: comfortably longer than depth lags behind. */
        const val POSE_HISTORY = 16

        /** A recorded pose further than this from the depth timestamp is not "its" pose. */
        const val POSE_MATCH_TOLERANCE_NANOS = 20_000_000L

        const val RECENT_SNAPSHOTS = 4
        const val PINNED_SNAPSHOTS = 4

        /**
         * A depth snapshot counts as the observation's frame within this window: depth images
         * arrive every one or two camera frames, so the nearest one is within ~70 ms.
         */
        const val SNAPSHOT_MATCH_TOLERANCE_NANOS = 150_000_000L

        /**
         * Beyond this the supplied timestamp cannot plausibly be in ARCore's clock domain, so it
         * is treated as absent rather than used to reject the observation.
         */
        const val CLOCK_DOMAIN_MISMATCH_NANOS = 60_000_000_000L

        val NOT_RESOLVED = Resolution(null, null)
    }
}
