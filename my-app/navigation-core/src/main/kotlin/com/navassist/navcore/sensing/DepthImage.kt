package com.navassist.navcore.sensing

/** Pinhole intrinsics of one image, in that image's own pixels. */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    /**
     * The same camera described at another resolution. The depth image is much smaller than the
     * RGB image the platform's intrinsics describe (typically 160x120 vs 640x480), and screen
     * coordinates must never be mistaken for depth coordinates.
     */
    fun scaledTo(width: Int, height: Int): CameraIntrinsics {
        val sx = width.toFloat() / this.width.toFloat()
        val sy = height.toFloat() / this.height.toFloat()
        return CameraIntrinsics(fx * sx, fy * sy, cx * sx, cy * sy, width, height)
    }
}

/**
 * One depth image: millimetres along the camera's optical axis, row-major, in the camera SENSOR
 * orientation (row 0 at the top of the sensor, not of the screen). 0 means no return.
 *
 * Mutable and reusable on purpose - an adapter copies into the same buffer every frame instead
 * of allocating 20 000 shorts at 30 Hz.
 */
class DepthImage(width: Int = 0, height: Int = 0) {
    var width: Int = width
        private set
    var height: Int = height
        private set
    var depthMm: ShortArray = ShortArray(width * height)
        private set

    /** Platform timestamp of the camera frame this depth belongs to, in nanoseconds. */
    var timestampNanos: Long = 0L

    fun resize(width: Int, height: Int) {
        if (width * height != depthMm.size) depthMm = ShortArray(width * height)
        this.width = width
        this.height = height
    }

    /** Depth at pixel (u, v) in metres; 0 when there is no return. */
    fun metersAt(u: Int, v: Int): Float = (depthMm[v * width + u].toInt() and 0xFFFF) * 0.001f

    fun copyFrom(other: DepthImage) {
        resize(other.width, other.height)
        other.depthMm.copyInto(depthMm)
        timestampNanos = other.timestampNanos
    }
}
