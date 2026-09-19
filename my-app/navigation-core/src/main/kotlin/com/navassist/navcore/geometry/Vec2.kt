package com.navassist.navcore.geometry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point / vector on the horizontal navigation plane.
 *
 * Canonical navigation frame (see [Pose3D]): x -> right, z -> initial forward.
 * The vertical axis (y) is deliberately absent: all mapping and planning happens on X/Z.
 */
data class Vec2(val x: Float, val z: Float) {

    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, z + other.z)

    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, z - other.z)

    operator fun times(scalar: Float): Vec2 = Vec2(x * scalar, z * scalar)

    fun length(): Float = sqrt(x * x + z * z)

    fun lengthSquared(): Float = x * x + z * z

    fun distanceTo(other: Vec2): Float = (this - other).length()

    fun distanceSquaredTo(other: Vec2): Float = (this - other).lengthSquared()

    fun dot(other: Vec2): Float = x * other.x + z * other.z

    fun normalized(): Vec2 {
        val len = length()
        return if (len < 1e-6f) ZERO else Vec2(x / len, z / len)
    }

    /**
     * Yaw of this vector in the canonical convention: 0 rad points along +Z,
     * positive yaw rotates clockwise (seen from above) towards +X.
     */
    fun yawRadians(): Float = atan2(x, z)

    companion object {
        val ZERO = Vec2(0f, 0f)

        /** Unit vector pointing along [yawRadians] in the canonical convention. */
        fun fromYaw(yawRadians: Float): Vec2 = Vec2(sin(yawRadians), cos(yawRadians))
    }
}
