package com.navassist.navcore.sensing

import com.navassist.navcore.geometry.DepthPointCloud

/**
 * Depth image -> canonical [DepthPointCloud].
 *
 *   depth pixel -> camera-space 3D point -> canonical navigation world
 *
 * Two sign flips that are easy to get wrong: the image's v axis points DOWN while the camera's
 * +y points up, and the camera looks along its own -Z.
 */
object DepthUnprojector {

    /**
     * @param intrinsics the camera AT THE DEPTH IMAGE'S RESOLUTION (see [CameraIntrinsics.scaledTo]).
     * @param cameraToCanonical 3x4 transform from [CanonicalFrame.cameraToCanonical] for the pose
     *        the depth was captured at.
     * @param stride sample every Nth pixel in both directions.
     * @param edgeRejectFraction drop samples that jump from a neighbouring pixel by more than this
     *        fraction of their range (0 disables); see NavigationConfig.depthEdgeRejectFraction.
     * @return the number of points written to [out], which is cleared first.
     */
    fun unproject(
        image: DepthImage,
        intrinsics: CameraIntrinsics,
        cameraToCanonical: FloatArray,
        stride: Int,
        minDepthMeters: Float,
        maxDepthMeters: Float,
        out: DepthPointCloud,
        edgeRejectFraction: Float = 0f,
    ): Int {
        out.clear()
        val fx = intrinsics.fx
        val fy = intrinsics.fy
        val cx = intrinsics.cx
        val cy = intrinsics.cy
        if (fx <= 0f || fy <= 0f) return 0

        val step = stride.coerceAtLeast(1)
        val point = FloatArray(3)
        var v = 0
        while (v < image.height) {
            var u = 0
            while (u < image.width) {
                val depth = image.metersAt(u, v)
                if (depth >= minDepthMeters && depth <= maxDepthMeters &&
                    (edgeRejectFraction <= 0f || !onEdge(image, u, v, depth, edgeRejectFraction))
                ) {
                    val camX = (u - cx) * depth / fx
                    val camY = -(v - cy) * depth / fy
                    val camZ = -depth
                    CanonicalFrame.transform(cameraToCanonical, camX, camY, camZ, point)
                    if (!out.add(point[0], point[1], point[2])) return out.count
                }
                u += step
            }
            v += step
        }
        return out.count
    }

    /** True when a 4-neighbour with a valid return differs by more than the edge threshold. */
    private fun onEdge(image: DepthImage, u: Int, v: Int, depth: Float, fraction: Float): Boolean {
        val threshold = maxOf(MIN_EDGE_JUMP_METERS, depth * fraction)
        fun jumps(nu: Int, nv: Int): Boolean {
            if (nu < 0 || nv < 0 || nu >= image.width || nv >= image.height) return false
            val other = image.metersAt(nu, nv)
            return other > 0f && kotlin.math.abs(other - depth) > threshold
        }
        return jumps(u - 1, v) || jumps(u + 1, v) || jumps(u, v - 1) || jumps(u, v + 1)
    }

    private const val MIN_EDGE_JUMP_METERS = 0.05f
}
