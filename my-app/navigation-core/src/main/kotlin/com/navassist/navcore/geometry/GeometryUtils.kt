package com.navassist.navcore.geometry

import kotlin.math.PI
import kotlin.math.abs

object GeometryUtils {

    const val PI_F: Float = PI.toFloat()
    const val TWO_PI: Float = (2.0 * PI).toFloat()

    /** Wraps an angle into (-pi, pi]. */
    fun normalizeAngle(radians: Float): Float {
        var a = radians
        while (a > PI_F) a -= TWO_PI
        while (a <= -PI_F) a += TWO_PI
        return a
    }

    /** Signed smallest difference (target - current), wrapped into (-pi, pi]. */
    fun angleDifference(current: Float, target: Float): Float = normalizeAngle(target - current)

    fun radiansToDegrees(radians: Float): Float = radians * (180f / PI_F)

    fun degreesToRadians(degrees: Float): Float = degrees * (PI_F / 180f)

    /**
     * Circular exponential moving average. Averaging angles naively breaks across the +-pi seam,
     * so we blend along the shortest arc instead.
     */
    fun blendAngle(current: Float, target: Float, alpha: Float): Float =
        normalizeAngle(current + angleDifference(current, target) * alpha)

    fun clamp(value: Float, min: Float, max: Float): Float =
        if (value < min) min else if (value > max) max else value

    fun clampInt(value: Int, min: Int, max: Int): Int =
        if (value < min) min else if (value > max) max else value

    fun approximately(a: Float, b: Float, epsilon: Float = 1e-4f): Boolean = abs(a - b) <= epsilon
}
