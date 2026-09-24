package com.navassist.navcore.geometry

/**
 * The ONLY input type the navigation core accepts from a platform adapter.
 *
 * If ARCore were swapped for ARKit tomorrow, the iOS adapter would produce exactly this type and
 * everything downstream (mapping, exploration, planning, control) would keep working unchanged.
 */
data class NavigationFrame(
    /** Monotonic timestamp supplied by the platform. The core never reads a system clock. */
    val timestampNanos: Long,
    /** Device pose in the canonical navigation frame. */
    val pose: Pose3D,
    /** Depth samples in canonical world coordinates. */
    val points: DepthPointCloud,
    /** 0 = no tracking, 1 = fully confident tracking. */
    val trackingConfidence: Float,
    /** True when the platform reported valid 6DoF tracking for this frame. */
    val tracking: Boolean = trackingConfidence > 0f,
    /** True when depth was actually available for this frame. */
    val depthAvailable: Boolean = points.count > 0,
    /**
     * Optional floor height hint in canonical world y, e.g. from an ARCore/ARKit horizontal
     * upward-facing plane. The core is fully capable of operating without it.
     */
    val floorHint: Float? = null,
    /** Confidence of [floorHint] in 0..1. */
    val floorHintConfidence: Float = 0f,
    /**
     * Where the depth sensor was when [points] were captured, in canonical world coordinates.
     *
     * Depth usually lags the camera pose by a frame or more; the rays that carve free space must
     * start where the sensor actually was, not where the phone is now. Null means "at [pose]".
     */
    val sensorPosition: Vec3? = null,
)
