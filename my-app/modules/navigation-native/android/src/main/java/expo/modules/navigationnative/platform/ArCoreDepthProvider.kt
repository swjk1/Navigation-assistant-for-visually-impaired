package expo.modules.navigationnative.platform

import android.media.Image
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

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
 *  - [Frame.acquireDepthImage16Bits] throws [NotYetAvailableException] routinely, especially in
 *    the first second of a session. That is normal, not an error.
 *  - Images are ALWAYS closed, via `use {}`.
 */
class ArCoreDepthProvider(private val config: NavigationConfig) {

    /** Reused across frames: no per-frame allocation of thousands of points. */
    private val cloud = DepthPointCloud.allocate(config.maxPointsPerFrame)
    private val transform = FloatArray(12)
    private val cameraMatrix = FloatArray(16)
    private val point = FloatArray(3)

    /**
     * Copy of the most recent depth image, kept for semantic observation association.
     * Written on the GL thread, read from whichever thread delivers semantic observations, hence
     * the explicit lock.
     */
    private val snapshotLock = Any()
    private var snapshotDepth: ShortArray = ShortArray(0)
    private var snapshotWidth = 0
    private var snapshotHeight = 0
    private var snapshotTimestampNanos = 0L
    private val snapshotTransform = FloatArray(12)
    private var snapshotFx = 0f
    private var snapshotFy = 0f
    private var snapshotCx = 0f
    private var snapshotCy = 0f

    var lastPointCount: Int = 0
        private set

    fun reset() {
        cloud.clear()
        lastPointCount = 0
        snapshotTimestampNanos = 0L
    }

    /**
     * @return the populated point cloud, or null when depth was not available for this frame
     *         (which is normal and must not be treated as an error).
     */
    fun acquire(frame: Frame, camera: Camera, poseProvider: ArCorePoseProvider): DepthPointCloud? {
        return try {
            frame.acquireDepthImage16Bits().use { image ->
                camera.pose.toMatrix(cameraMatrix, 0)
                poseProvider.fillCanonicalFromCamera(cameraMatrix, transform)
                unproject(image, camera, frame.timestamp)
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

    /**
     * Keeps a small copy of the depth image so that a semantic observation arriving a few frames
     * later (OCR is not instantaneous) can still be turned into a world position. This is the
     * "recent frame buffer" the perception hand-off needs; it is a few tens of kilobytes, unlike
     * retaining the ARCore Image itself.
     */
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
        if (snapshotDepth.size != width * height) snapshotDepth = ShortArray(width * height)
        var index = 0
        for (v in 0 until height) {
            val rowBase = v * rowStride
            for (u in 0 until width) {
                snapshotDepth[index++] = buffer.getShort(rowBase + u * pixelStride)
            }
        }
        snapshotWidth = width
        snapshotHeight = height
        snapshotTimestampNanos = timestampNanos
        snapshotFx = fx
        snapshotFy = fy
        snapshotCx = cx
        snapshotCy = cy
        System.arraycopy(transform, 0, snapshotTransform, 0, transform.size)
    }

    /**
     * Turns a normalized image coordinate reported by the perception layer into a canonical world
     * position, using the most recent depth snapshot.
     *
     * MVP simplification: the normalized coordinate is assumed to address the same field of view
     * as the depth image. Observations older than [maxAgeNanos] are rejected outright rather than
     * silently associated with the wrong part of the corridor.
     */
    fun resolveWorldPosition(
        normalizedX: Float,
        normalizedY: Float,
        observationTimestampNanos: Long?,
        maxAgeNanos: Long = 500_000_000L,
    ): Vec3? = synchronized(snapshotLock) {
        if (snapshotWidth == 0 || snapshotHeight == 0) return null
        if (observationTimestampNanos != null) {
            val age = abs(snapshotTimestampNanos - observationTimestampNanos)
            if (age > CLOCK_DOMAIN_MISMATCH_NANOS) {
                // The timestamp is not in ARCore's clock domain at all - almost always a wall
                // clock value (Date.now(), milliseconds since epoch) where the ARCore frame
                // timestamp (nanoseconds since boot) was expected.
                //
                // Rejecting it would silently drop EVERY observation the perception layer ever
                // sends, and the engine would simply never find its destination with no error
                // anywhere. Falling back to the latest depth frame - exactly what omitting the
                // timestamp does - is both safer and easier to notice in the log.
                Log.w(
                    TAG,
                    "Semantic observation timestamp is not an ARCore frame timestamp " +
                        "(off by ${age / 1_000_000} ms); using the most recent depth frame instead. " +
                        "Pass the value from ARCore Frame.getTimestamp(), or omit it.",
                )
            } else if (age > maxAgeNanos) {
                return null
            }
        }
        val u = (normalizedX * snapshotWidth).toInt().coerceIn(0, snapshotWidth - 1)
        val v = (normalizedY * snapshotHeight).toInt().coerceIn(0, snapshotHeight - 1)

        // Average a small window: a single depth pixel on a door plate is easily invalid.
        var sum = 0f
        var count = 0
        for (dv in -2..2) {
            for (du in -2..2) {
                val su = (u + du).coerceIn(0, snapshotWidth - 1)
                val sv = (v + dv).coerceIn(0, snapshotHeight - 1)
                val raw = snapshotDepth[sv * snapshotWidth + su].toInt() and 0xFFFF
                val meters = raw * 0.001f
                if (meters >= config.minDepthMeters && meters <= config.maxDepthMeters) {
                    sum += meters
                    count++
                }
            }
        }
        if (count == 0) return null
        val depth = sum / count

        val camX = (u - snapshotCx) * depth / snapshotFx
        val camY = -(v - snapshotCy) * depth / snapshotFy
        val camZ = -depth
        val out = FloatArray(3)
        ArCorePoseProvider.transform(snapshotTransform, camX, camY, camZ, out)
        return Vec3(out[0], out[1], out[2])
    }

    private companion object {
        const val TAG = "ArCoreDepthProvider"

        /**
         * Beyond this the supplied timestamp cannot plausibly be in ARCore's clock domain, so it
         * is treated as absent rather than used to reject the observation.
         */
        const val CLOCK_DOMAIN_MISMATCH_NANOS = 60_000_000_000L
    }
}
