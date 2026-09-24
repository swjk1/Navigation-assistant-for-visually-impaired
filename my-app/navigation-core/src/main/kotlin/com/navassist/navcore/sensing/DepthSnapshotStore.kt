package com.navassist.navcore.sensing

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec3
import kotlin.math.abs

/**
 * Recent depth images, kept so a semantic observation can be placed in the world using the depth
 * of the camera frame it came from.
 *
 * Perception takes seconds (YOLO, OCR, sometimes a VLM round trip). By the time an observation
 * arrives the camera points somewhere else, so resolving its image coordinate against the newest
 * depth would place the landmark on whatever wall is in view now. Two buffers:
 *
 *  - a small ring of the latest depth images, for observations that are effectively immediate;
 *  - PINNED images, copied out when an RGB frame is captured for perception ([pin]), which survive
 *    until the observations derived from that capture have had time to arrive.
 *
 * Thread-safe: an adapter records on its camera thread and resolves on whichever thread delivers
 * observations.
 */
class DepthSnapshotStore(
    private val config: NavigationConfig,
    private val recentSlots: Int = 4,
    private val pinnedSlots: Int = 4,
    /** Called when a timestamp is not in the platform's clock domain at all. */
    private val onClockMismatch: (offByMillis: Long) -> Unit = {},
) {
    /** A copy of one depth image plus what is needed to unproject it. */
    private class Snapshot {
        val image = DepthImage()
        var intrinsics = CameraIntrinsics(0f, 0f, 0f, 0f, 0, 0)
        val transform = FloatArray(12)

        fun copyFrom(other: Snapshot) {
            image.copyFrom(other.image)
            intrinsics = other.intrinsics
            other.transform.copyInto(transform)
        }
    }

    /** What a semantic observation resolves to. */
    data class Resolution(
        /** Canonical world position of the image point, when it had usable depth. */
        val worldPosition: Vec3?,
        /** Where the camera was when the image was taken, when a matching frame was found. */
        val observerPose: Pose3D?,
    )

    private val lock = Any()
    private val recent = Array(recentSlots) { Snapshot() }
    private var recentNext = 0
    private var recentCount = 0
    private val pinned = Array(pinnedSlots) { Snapshot() }
    private var pinnedNext = 0
    private var pinnedCount = 0

    fun clear() = synchronized(lock) {
        recentNext = 0
        recentCount = 0
        pinnedNext = 0
        pinnedCount = 0
    }

    /** Records a depth image that has just been integrated, with the transform it used. */
    fun record(image: DepthImage, intrinsics: CameraIntrinsics, cameraToCanonical: FloatArray) = synchronized(lock) {
        val slot = recent[recentNext]
        slot.image.copyFrom(image)
        slot.intrinsics = intrinsics
        cameraToCanonical.copyInto(slot.transform)
        recentNext = (recentNext + 1) % recentSlots
        if (recentCount < recentSlots) recentCount++
    }

    /**
     * Keeps the depth image nearest [timestampNanos] until observations derived from that camera
     * frame have arrived. Call right after the frame was processed, when its RGB image is captured.
     */
    fun pin(timestampNanos: Long) = synchronized(lock) {
        val source = nearestIn(recent, recentCount, timestampNanos) ?: return@synchronized
        if (abs(source.image.timestampNanos - timestampNanos) > MATCH_TOLERANCE_NANOS) return@synchronized
        pinned[pinnedNext].copyFrom(source)
        pinnedNext = (pinnedNext + 1) % pinnedSlots
        if (pinnedCount < pinnedSlots) pinnedCount++
    }

    /**
     * Resolves an observation against the depth of the frame it came from.
     *
     * With a timestamp, a matching snapshot (pinned, or still in the ring) must exist; otherwise
     * nothing is resolved rather than something wrong. Without one, the newest depth is used.
     * Image coordinates are normalized 0..1 in the SENSOR image, like the depth image.
     */
    fun resolve(normalizedX: Float?, normalizedY: Float?, timestampNanos: Long?): Resolution = synchronized(lock) {
        val snapshot = find(timestampNanos) ?: return@synchronized NOT_RESOLVED
        val pose = CanonicalFrame.poseOf(snapshot.transform)
        if (normalizedX == null || normalizedY == null) return@synchronized Resolution(null, pose)
        Resolution(worldPositionIn(snapshot, normalizedX, normalizedY), pose)
    }

    private fun find(timestampNanos: Long?): Snapshot? {
        val latest = if (recentCount > 0) recent[(recentNext - 1 + recentSlots) % recentSlots] else null
        if (timestampNanos == null) return latest
        if (latest != null && abs(latest.image.timestampNanos - timestampNanos) > CLOCK_DOMAIN_MISMATCH_NANOS) {
            // Not a platform timestamp at all - almost always a wall clock where the frame
            // timestamp was expected. Dropping would silently discard every observation; the
            // latest frame is the least-bad answer, and the callback makes the mistake visible.
            onClockMismatch(abs(latest.image.timestampNanos - timestampNanos) / 1_000_000)
            return latest
        }
        val best = listOfNotNull(
            nearestIn(recent, recentCount, timestampNanos),
            nearestIn(pinned, pinnedCount, timestampNanos),
        ).minByOrNull { abs(it.image.timestampNanos - timestampNanos) }
        return best?.takeIf { abs(it.image.timestampNanos - timestampNanos) <= MATCH_TOLERANCE_NANOS }
    }

    private fun nearestIn(slots: Array<Snapshot>, count: Int, timestampNanos: Long): Snapshot? {
        var best: Snapshot? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until count) {
            val delta = abs(slots[i].image.timestampNanos - timestampNanos)
            if (delta < bestDelta) {
                bestDelta = delta
                best = slots[i]
            }
        }
        return best
    }

    private fun worldPositionIn(snapshot: Snapshot, normalizedX: Float, normalizedY: Float): Vec3? {
        val image = snapshot.image
        if (image.width == 0 || image.height == 0) return null
        val u = (normalizedX * image.width).toInt().coerceIn(0, image.width - 1)
        val v = (normalizedY * image.height).toInt().coerceIn(0, image.height - 1)

        // Average a small window: a single depth pixel on a door plate is easily invalid.
        var sum = 0f
        var count = 0
        for (dv in -2..2) {
            for (du in -2..2) {
                val meters = image.metersAt(
                    (u + du).coerceIn(0, image.width - 1),
                    (v + dv).coerceIn(0, image.height - 1),
                )
                if (meters >= config.minDepthMeters && meters <= config.maxDepthMeters) {
                    sum += meters
                    count++
                }
            }
        }
        if (count == 0) return null
        val depth = sum / count

        val k = snapshot.intrinsics
        val out = FloatArray(3)
        CanonicalFrame.transform(
            snapshot.transform,
            (u - k.cx) * depth / k.fx,
            -(v - k.cy) * depth / k.fy,
            -depth,
            out,
        )
        return Vec3(out[0], out[1], out[2])
    }

    companion object {
        /**
         * A snapshot counts as the observation's frame within this window: depth arrives every one
         * or two camera frames, so the nearest image is within ~70 ms.
         */
        const val MATCH_TOLERANCE_NANOS = 150_000_000L

        /** Beyond this a timestamp cannot plausibly be in the platform's clock domain. */
        const val CLOCK_DOMAIN_MISMATCH_NANOS = 60_000_000_000L

        private val NOT_RESOLVED = Resolution(null, null)
    }
}
