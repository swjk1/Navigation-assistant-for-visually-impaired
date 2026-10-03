package com.navassist.sim.sensor

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * How a visual-inertial tracker's reported pose wanders from the truth.
 *
 * Every tracker drifts: heading slowly, position by a share of the distance walked. Some correct
 * themselves now and then (a relocalisation, a loop closure), and the pose jumps back towards the
 * truth. The engine sees only the reported pose, so its map is built in the drifted frame; what it
 * remembers from a minute ago no longer lines up with where things are now.
 *
 * @param yawDegreesPerMinute typical heading drift; each run draws its own rate around it, with a
 *        random sign.
 * @param distanceErrorFraction position error per metre walked, in a direction fixed per run (a
 *        scale and heading bias rather than noise).
 * @param relocalizeEverySeconds spells between relocalisations; null for a tracker that never
 *        corrects itself.
 * @param relocalizeKeep share of the accumulated drift left after a relocalisation.
 */
data class DriftModel(
    val yawDegreesPerMinute: Float,
    val distanceErrorFraction: Float,
    val relocalizeEverySeconds: ClosedFloatingPointRange<Double>? = null,
    val relocalizeKeep: Float = 0.3f,
) {
    companion object {
        /** A good tracker on a steady walk. Plausible, not measured. */
        val MILD = DriftModel(yawDegreesPerMinute = 1f, distanceErrorFraction = 0.01f)

        /** A poor one, which also jumps when it relocalises. Plausible, not measured. */
        val STRONG = DriftModel(yawDegreesPerMinute = 3f, distanceErrorFraction = 0.03f, relocalizeEverySeconds = 60.0..150.0)
    }
}

/**
 * Applies a [DriftModel] to the true camera-to-world matrix, giving the pose a tracker would report.
 * Depth is still rendered from the true pose; only what the engine is told drifts.
 */
class PoseDrift(private val model: DriftModel, seed: Long) {
    private val random = Random(seed * 2654435761L + 5)
    private val yawRatePerSecond = Math.toRadians(model.yawDegreesPerMinute.toDouble()).toFloat() / 60f *
        (0.5f + random.nextFloat()) * (if (random.nextBoolean()) 1f else -1f)
    private val errorAngle = random.nextFloat() * 2f * Math.PI.toFloat()

    private var yaw = 0f
    private var offsetX = 0f
    private var offsetZ = 0f
    private var pivotX = Float.NaN
    private var pivotZ = Float.NaN
    private var lastX = Float.NaN
    private var lastZ = Float.NaN
    private var nextRelocalize = model.relocalizeEverySeconds?.let { draw(it) } ?: Double.POSITIVE_INFINITY

    /** Heading error now, in degrees, and position error now, in metres (for reports). */
    val yawErrorDegrees: Float get() = Math.toDegrees(yaw.toDouble()).toFloat()
    val positionErrorMeters: Float get() = hypot(offsetX, offsetZ)

    /** Writes into [out] the pose a tracker would report for the true pose [truth] at [seconds]. */
    fun apply(seconds: Double, dtSeconds: Float, truth: FloatArray, out: FloatArray): FloatArray {
        val x = truth[12]
        val z = truth[14]
        if (pivotX.isNaN()) {
            pivotX = x; pivotZ = z; lastX = x; lastZ = z
        }
        yaw += yawRatePerSecond * dtSeconds
        val moved = hypot(x - lastX, z - lastZ)
        offsetX += moved * model.distanceErrorFraction * cos(errorAngle)
        offsetZ += moved * model.distanceErrorFraction * sin(errorAngle)
        lastX = x; lastZ = z
        if (seconds >= nextRelocalize) {
            yaw *= model.relocalizeKeep
            offsetX *= model.relocalizeKeep
            offsetZ *= model.relocalizeKeep
            nextRelocalize = seconds + draw(model.relocalizeEverySeconds!!)
        }

        // Rotate about the start, where tracking began, then offset. Column-major 4x4.
        val c = cos(yaw)
        val s = sin(yaw)
        for (col in 0..2) {
            val ax = truth[col * 4]
            val ay = truth[col * 4 + 1]
            val az = truth[col * 4 + 2]
            out[col * 4] = c * ax + s * az
            out[col * 4 + 1] = ay
            out[col * 4 + 2] = -s * ax + c * az
            out[col * 4 + 3] = truth[col * 4 + 3]
        }
        val dx = x - pivotX
        val dz = z - pivotZ
        out[12] = pivotX + c * dx + s * dz + offsetX
        out[13] = truth[13]
        out[14] = pivotZ - s * dx + c * dz + offsetZ
        out[15] = 1f
        return out
    }

    private fun draw(range: ClosedFloatingPointRange<Double>) =
        range.start + random.nextDouble() * (range.endInclusive - range.start)
}
