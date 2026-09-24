package com.navassist.navcore

import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.mapping.FloorEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloorEstimatorTest {

    private val config = NavigationConfig()
    private val deviceY = 1.3f

    /** A patch of horizontal returns at height [y], in front of the phone. */
    private fun surface(y: Float, count: Int = 400): DepthPointCloud {
        val cloud = DepthPointCloud.allocate(count)
        for (i in 0 until count) cloud.add((i % 20) * 0.05f, y, 1f + (i / 20) * 0.05f)
        return cloud
    }

    @Test
    fun `depth showing the real floor overrides a table-height plane hint`() {
        val estimator = FloorEstimator(config)
        var estimate = estimator.update(surface(0f), deviceY, 0.75f, 0.475f, 0)
        repeat(60) { estimate = estimator.update(surface(0f), deviceY, 0.75f, 0.475f, it * 33L) }
        assertEquals(0f, estimate.floorY, 0.02f)
    }

    @Test
    fun `a plane hint is used when depth has nothing better`() {
        val estimator = FloorEstimator(config)
        val estimate = estimator.update(DepthPointCloud.EMPTY, deviceY, 0.02f, 0.8f, 0)
        assertEquals(0.02f, estimate.floorY, 1e-4f)
    }

    @Test
    fun `a desk is not taken for the floor when the floor is out of view`() {
        // Phone at 1.3 m, desk at 0.75 m: only 0.55 m below the phone, which no held phone is.
        val estimator = FloorEstimator(config)
        val estimate = estimator.update(surface(0.75f), deviceY, null, 0f, 0)
        assertEquals(0f, estimate.confidence)
    }

    @Test
    fun `an established floor ignores a brief rise but accepts a sustained one`() {
        val estimator = FloorEstimator(config)
        repeat(30) { estimator.update(surface(0f), deviceY, null, 0f, it * 33L) }
        assertEquals(0f, estimator.estimate.floorY, 0.02f)

        // Standing on a floor at 0 while the camera sees only a surface 0.4 m up (phone raised
        // to 1.9 m so the surface is inside the plausible band) for one second: ignored.
        var now = 1000L
        repeat(30) { now += 33; estimator.update(surface(0.4f), 1.9f, null, 0f, now) }
        assertEquals(0f, estimator.estimate.floorY, 0.02f)

        // The same rise held for longer than the confirmation window: a ramp, accepted.
        repeat(90) { now += 33; estimator.update(surface(0.4f), 1.9f, null, 0f, now) }
        assertTrue(estimator.estimate.floorY > 0.35f, "was ${estimator.estimate.floorY}")
    }
}
