package com.navassist.navcore.geometry

/**
 * Compact, reusable depth sample container.
 *
 * Points are already expressed in the canonical navigation world frame by the platform adapter.
 * The layout is interleaved so that no per-point object is allocated per AR frame:
 *   xyz[3*i + 0] = x, xyz[3*i + 1] = y, xyz[3*i + 2] = z
 *
 * [count] is the number of VALID points, which may be smaller than xyz.size / 3 because the
 * backing array is reused across frames.
 */
class DepthPointCloud(
    val xyz: FloatArray,
    var count: Int = 0,
) {
    val capacity: Int get() = xyz.size / 3

    fun x(index: Int): Float = xyz[index * 3]

    fun y(index: Int): Float = xyz[index * 3 + 1]

    fun z(index: Int): Float = xyz[index * 3 + 2]

    fun clear() {
        count = 0
    }

    /** Appends a point if there is room. Returns false when the cloud is full. */
    fun add(x: Float, y: Float, z: Float): Boolean {
        if (count >= capacity) return false
        val base = count * 3
        xyz[base] = x
        xyz[base + 1] = y
        xyz[base + 2] = z
        count++
        return true
    }

    companion object {
        fun allocate(capacity: Int): DepthPointCloud = DepthPointCloud(FloatArray(capacity * 3))

        val EMPTY = DepthPointCloud(FloatArray(0), 0)

        /** Test / utility helper: builds a cloud from an explicit list of points. */
        fun of(vararg points: Vec3): DepthPointCloud {
            val cloud = allocate(points.size)
            points.forEach { cloud.add(it.x, it.y, it.z) }
            return cloud
        }
    }
}
