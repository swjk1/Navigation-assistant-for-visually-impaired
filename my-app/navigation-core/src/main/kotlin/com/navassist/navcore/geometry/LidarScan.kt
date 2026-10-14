package com.navassist.navcore.geometry

/**
 * One revolution of a 2D scanning LiDAR whose scan plane is horizontal and which is mounted rigidly
 * with the camera (both on a chest harness), so the camera's pose places the scan.
 *
 * Beams are in the SENSOR's frame: [angleRadians] is measured from the camera's forward direction,
 * positive clockwise seen from above - the canonical yaw convention (see [Pose3D]). A range of 0
 * means no return: nothing within range, a beam through glass, the wearer's own body, a dropout.
 *
 * The engine projects beams with its own pose estimate, as it does depth, so tracking drift moves
 * the scan exactly as it would on a real device.
 */
class LidarScan(capacity: Int) {
    val angleRadians = FloatArray(capacity)
    val rangeMeters = FloatArray(capacity)

    var count: Int = 0
        private set

    val capacity: Int get() = angleRadians.size

    fun clear() {
        count = 0
    }

    fun add(angle: Float, range: Float) {
        if (count >= angleRadians.size) return
        angleRadians[count] = angle
        rangeMeters[count] = range
        count++
    }

    fun copyFrom(other: LidarScan) {
        count = minOf(other.count, angleRadians.size)
        other.angleRadians.copyInto(angleRadians, 0, 0, count)
        other.rangeMeters.copyInto(rangeMeters, 0, 0, count)
    }
}
