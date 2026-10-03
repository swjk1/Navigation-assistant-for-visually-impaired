package com.navassist.navcore.sensing

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
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
        /**
         * Metres spanned by the whole image width / height at the resolved depth: multiply by a
         * detection's normalized box size to get its physical size, as placed.
         */
        val metersPerImageWidth: Float? = null,
        val metersPerImageHeight: Float? = null,
        /**
         * Horizontal unit direction (canonical x, z) from the camera through the image point.
         * Known whenever the frame is, with or without depth - a sign read from beyond depth range
         * still says which way it is.
         */
        val bearing: Vec2? = null,
        /**
         * Tangent of the angle above horizontal of that same ray (negative below). With a known
         * mounting height it ranges a sign that depth cannot reach.
         */
        val elevationTan: Float? = null,
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
    fun resolve(
        normalizedX: Float?,
        normalizedY: Float?,
        timestampNanos: Long?,
        boxWidth: Float? = null,
        boxHeight: Float? = null,
    ): Resolution = synchronized(lock) {
        val snapshot = find(timestampNanos) ?: return@synchronized NOT_RESOLVED
        val pose = CanonicalFrame.poseOf(snapshot.transform)
        if (normalizedX == null || normalizedY == null) return@synchronized Resolution(null, pose)
        val bearing = bearingOf(snapshot, normalizedX, normalizedY, pose)
        val elevationTan = elevationTanOf(snapshot, normalizedX, normalizedY, pose)
        val depth = depthAt(snapshot, normalizedX, normalizedY)
            ?.takeIf { boxWidth == null || boxHeight == null || oneSurface(snapshot, normalizedX, normalizedY, boxWidth, boxHeight, it) }
            ?: return@synchronized Resolution(null, pose, bearing = bearing, elevationTan = elevationTan)
        val k = snapshot.intrinsics
        Resolution(
            worldPosition(snapshot, normalizedX, normalizedY, depth),
            pose,
            metersPerImageWidth = depth * snapshot.image.width / k.fx,
            metersPerImageHeight = depth * snapshot.image.height / k.fy,
            bearing = bearing,
            elevationTan = elevationTan,
        )
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

    /** Depth at an image point, or null when the window around it is not one clear surface. */
    private fun depthAt(snapshot: Snapshot, normalizedX: Float, normalizedY: Float): Float? {
        val image = snapshot.image
        if (image.width == 0 || image.height == 0) return null
        val u = (normalizedX * image.width).toInt().coerceIn(0, image.width - 1)
        val v = (normalizedY * image.height).toInt().coerceIn(0, image.height - 1)

        // A 3x3 window on the point itself, because a single depth pixel on a door plate is easily
        // invalid - but it must be mostly valid and mostly ONE surface. Averaging whatever few
        // pixels had depth in a wider window placed a lift 14 m away (beyond depth range) on the
        // floor, or on the edges of the opening it was seen through, a few metres ahead - and the
        // user was told they had arrived there. An observation that cannot be placed still steers
        // exploration as a directional hint; it just cannot become a destination.
        val samples = FloatArray(WINDOW * WINDOW)
        var count = 0
        for (dv in -WINDOW / 2..WINDOW / 2) {
            for (du in -WINDOW / 2..WINDOW / 2) {
                val meters = image.metersAt(
                    (u + du).coerceIn(0, image.width - 1),
                    (v + dv).coerceIn(0, image.height - 1),
                )
                if (meters >= config.minDepthMeters && meters <= config.maxDepthMeters) samples[count++] = meters
            }
        }
        if (count < MIN_VALID_SAMPLES) return null
        samples.sort(0, count)
        val depth = samples[count / 2]
        val tolerance = maxOf(SURFACE_TOLERANCE_METERS, depth * SURFACE_TOLERANCE_FRACTION)
        val onSurface = (0 until count).count { kotlin.math.abs(samples[it] - depth) <= tolerance }
        if (onSurface < MIN_VALID_SAMPLES) return null
        return depth
    }

    /**
     * Whether the object's whole box, not only its centre, lies on the surface found at its
     * centre. A sign glimpsed just past a column or a corridor corner has a centre window that can
     * land on the edge of the nearer thing - a hanging sign 12 m off was placed on a column 6 m
     * away, and the user told they had arrived there - while most of its box has no depth at all
     * or the far wall's. A sign on a wall has the wall all round it.
     */
    private fun oneSurface(snapshot: Snapshot, x: Float, y: Float, boxWidth: Float, boxHeight: Float, depth: Float): Boolean {
        // A big box is a near object with plenty of depth under its centre, which the centre
        // window already checks; its edges run into door frames and off the image.
        if (boxWidth > BOX_CHECK_MAX_FRACTION || boxHeight > BOX_CHECK_MAX_FRACTION) return true
        val image = snapshot.image
        val tolerance = maxOf(BOX_TOLERANCE_METERS, depth * BOX_TOLERANCE_FRACTION)
        var onSurface = 0
        var total = 0
        // At least a pixel apart: a far sign spans a pixel or two, and the question is then what
        // surrounds it - its own wall, or nothing (beyond range) with a nearer edge beside it.
        val stepX = maxOf(0.18f * boxWidth, 1f / image.width)
        val stepY = maxOf(0.18f * boxHeight, 1f / image.height)
        for (i in -2..2) for (j in -2..2) {
            val px = x + i * stepX
            val py = y + j * stepY
            if (px < 0f || py < 0f || px >= 1f || py >= 1f) continue
            val meters = image.metersAt((px * image.width).toInt(), (py * image.height).toInt())
            total++
            if (meters >= config.minDepthMeters && meters <= config.maxDepthMeters && abs(meters - depth) <= tolerance) onSurface++
        }
        return total == 0 || onSurface >= total * BOX_MIN_ON_SURFACE
    }

    /** The image point 1 m out, relative to the camera, flattened to the floor plane. */
    private fun bearingOf(snapshot: Snapshot, normalizedX: Float, normalizedY: Float, pose: Pose3D): Vec2? {
        if (snapshot.image.width == 0 || snapshot.image.height == 0) return null
        val point = worldPosition(snapshot, normalizedX, normalizedY, 1f)
        val dx = point.x - pose.position3D.x
        val dz = point.z - pose.position3D.z
        val length = kotlin.math.sqrt(dx * dx + dz * dz)
        return if (length < 1e-3f) null else Vec2(dx / length, dz / length)
    }

    private fun elevationTanOf(snapshot: Snapshot, normalizedX: Float, normalizedY: Float, pose: Pose3D): Float? {
        if (snapshot.image.width == 0 || snapshot.image.height == 0) return null
        val point = worldPosition(snapshot, normalizedX, normalizedY, 1f)
        val dx = point.x - pose.position3D.x
        val dz = point.z - pose.position3D.z
        val horizontal = kotlin.math.sqrt(dx * dx + dz * dz)
        return if (horizontal < 1e-3f) null else (point.y - pose.position3D.y) / horizontal
    }

    private fun worldPosition(snapshot: Snapshot, normalizedX: Float, normalizedY: Float, depth: Float): Vec3 {
        val image = snapshot.image
        val u = (normalizedX * image.width).toInt().coerceIn(0, image.width - 1)
        val v = (normalizedY * image.height).toInt().coerceIn(0, image.height - 1)

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

        /** The depth window around an observation's image point, in depth pixels. */
        private const val WINDOW = 3

        /** A majority of the window must have depth, and agree on it. */
        private const val MIN_VALID_SAMPLES = WINDOW * WINDOW / 2 + 1

        /** How close to the median a sample must be to count as the same surface. */
        private const val SURFACE_TOLERANCE_METERS = 0.1f
        private const val SURFACE_TOLERANCE_FRACTION = 0.05f

        /** Across a whole box: looser, since a plate seen at an angle spans a range of depths. */
        private const val BOX_TOLERANCE_METERS = 0.2f
        private const val BOX_TOLERANCE_FRACTION = 0.08f
        private const val BOX_MIN_ON_SURFACE = 0.6f
        private const val BOX_CHECK_MAX_FRACTION = 0.25f

        private val NOT_RESOLVED = Resolution(null, null)
    }
}
