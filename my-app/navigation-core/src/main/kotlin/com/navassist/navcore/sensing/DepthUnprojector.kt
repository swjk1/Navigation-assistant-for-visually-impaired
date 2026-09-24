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
                if (depth >= minDepthMeters && depth <= maxDepthMeters) {
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
}
