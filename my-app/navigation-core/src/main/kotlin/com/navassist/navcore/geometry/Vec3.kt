package com.navassist.navcore.geometry

import kotlin.math.sqrt

/**
 * A point in the canonical navigation world frame.
 * y is the vertical axis (up positive); x/z form the navigation plane.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Float): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    /** Drops the vertical component, yielding the navigation-plane projection. */
    fun toVec2(): Vec2 = Vec2(x, z)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}
