package com.navassist.sim

import com.navassist.sim.sensor.DriftModel
import com.navassist.sim.sensor.PoseDrift
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoseDriftTest {
    /** A camera at (x, 1.4, z) looking along -z, column-major. */
    private fun pose(x: Float, z: Float) = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, 1.4f, z, 1f)

    @Test
    fun `heading and position drift grow with time and distance walked`() {
        val drift = PoseDrift(DriftModel(yawDegreesPerMinute = 2f, distanceErrorFraction = 0.02f), seed = 1)
        val out = FloatArray(16)
        var z = 0f
        for (frame in 0 until 30 * 120) { // two minutes at 30 fps, walking 1 m/s
            drift.apply(frame / 30.0, 1f / 30f, pose(0f, z), out)
            z -= 1f / 30f
        }
        assertTrue(abs(drift.yawErrorDegrees) in 2f..6f, "yaw error ${drift.yawErrorDegrees}")
        assertEquals(120f * 0.02f, drift.positionErrorMeters, 0.1f)
        // The reported position is off by more than the offset alone: the heading error swings it.
        assertTrue(hypot(out[12] - 0f, out[14] - z) > drift.positionErrorMeters)
        assertEquals(1.4f, out[13])
    }

    @Test
    fun `a relocalisation snaps most of the drift away`() {
        val model = DriftModel(yawDegreesPerMinute = 3f, distanceErrorFraction = 0.03f, relocalizeEverySeconds = 50.0..50.0, relocalizeKeep = 0.3f)
        val drift = PoseDrift(model, seed = 2)
        val out = FloatArray(16)
        var z = 0f
        var before = 0f
        for (frame in 0 until 30 * 60) {
            if (frame == 30 * 50 - 1) before = drift.positionErrorMeters
            drift.apply(frame / 30.0, 1f / 30f, pose(0f, z), out)
            z -= 1f / 30f
        }
        assertTrue(before > 1f, "drift before the jump: $before")
        assertTrue(drift.positionErrorMeters < before * 0.6f, "after: ${drift.positionErrorMeters}, before: $before")
    }
}
