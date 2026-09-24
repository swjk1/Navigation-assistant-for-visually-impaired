package com.navassist.sim

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.sensing.DepthImage
import com.navassist.navcore.sensing.SensorFrameAssembler
import com.navassist.sim.sensor.BodyPose
import com.navassist.sim.sensor.DepthCamera
import com.navassist.sim.sensor.DepthNoise
import com.navassist.sim.sensor.PhoneHold
import com.navassist.sim.sensor.PhoneRig
import com.navassist.sim.world.Facing
import com.navassist.sim.world.SceneBuilder
import com.navassist.sim.world.SignKind
import com.navassist.sim.perception.PerceptionModel
import com.navassist.sim.perception.PerceptionProfile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulator is only useful if its sensor produces what ARCore produces. These pin down the
 * conventions, with noise switched off: z-depth in millimetres, sensor orientation, portrait
 * mounting - and that the core's own assembler turns it back into the right world points.
 */
class SimulatedSensorTest {

    /** A wall across the plan at y = 3, the user at the origin facing north. */
    private val wallAhead = SceneBuilder("wall-ahead").apply {
        floor(-5f, -1f, 5f, 3f)
        wall(-5f, 3f, 5f, 3f, thickness = 0.02f)
    }.build()

    private val level = PhoneHold(heightMeters = 1.3f, reachMeters = 0f)

    private fun render(pitch: Float = 0f, yaw: Float = 0f, portrait: Boolean = true): Pair<DepthImage, FloatArray> {
        val camera = DepthCamera(noise = DepthNoise.NONE, renderStride = 1, random = Random(1))
        val matrix = PhoneRig.cameraToWorld(BodyPose(0f, 0f, 0f), yaw, pitch, level.copy(portrait = portrait), FloatArray(16))
        val image = DepthImage()
        camera.render(wallAhead, matrix, 1, image)
        return image to matrix
    }

    @Test
    fun `a wall 3 m ahead reads as 3000 mm of z-depth at the image centre`() {
        val (image, _) = render()
        assertEquals(3.0f, image.metersAt(80, 60), 0.02f)
    }

    @Test
    fun `held portrait, the sensor's top rows see the user's right`() {
        // Nothing in view but a post 2 m ahead, to the right of the view (no floor, so every
        // return is the post).
        val scene = SceneBuilder("post").apply {
            block(0.8f, 1.9f, 1.0f, 2.1f, height = 2.5f)
        }.build()
        val camera = DepthCamera(noise = DepthNoise.NONE, renderStride = 1, random = Random(1))
        val matrix = PhoneRig.cameraToWorld(BodyPose(0f, 0f, 0f), 0f, 0f, level, FloatArray(16))
        val image = DepthImage()
        camera.render(scene, matrix, 1, image)
        // Sensor row v -> the user's right as v decreases. The post (right of centre) must show
        // up in the TOP half of the sensor image and not in the bottom half.
        val top = (0 until 60).count { v -> (0 until 160).any { u -> image.metersAt(u, v) > 0f } }
        val bottom = (60 until 120).count { v -> (0 until 160).any { u -> image.metersAt(u, v) > 0f } }
        assertTrue(top > 0 && bottom == 0, "post rows: top $top, bottom $bottom")
    }

    @Test
    fun `the core assembler turns simulated depth back into the right world points`() {
        val config = NavigationConfig(depthSampleStride = 1, maxPointsPerFrame = 160 * 120, depthEdgeRejectFraction = 0f)
        val assembler = SensorFrameAssembler(config)
        val camera = DepthCamera(noise = DepthNoise.NONE, renderStride = 1, random = Random(1))
        val (image, matrix) = render()
        val frame = assembler.assemble(1, true, matrix, image, camera.depthIntrinsics)
        // Every return on the wall lands 3 m ahead of the phone (canonical +z), whatever the pixel.
        var onWall = 0
        for (i in 0 until frame.points.count) {
            if (frame.points.y(i) > -1.2f) {
                assertEquals(3f, frame.points.z(i), 0.05f)
                onWall++
            }
        }
        assertTrue(onWall > 1000, "wall points: $onWall")
    }

    @Test
    fun `perception sees a sign in front, reports it in sensor coordinates, and not from behind`() {
        val scene = SceneBuilder("sign").apply {
            floor(-5f, -1f, 5f, 3f)
            wall(-5f, 3f, 5f, 3f)
            sign(SignKind.EXIT_SIGN, "EXIT", 0f, 2.95f, Facing.SOUTH, height = 1.3f, width = 0.6f, tall = 0.3f)
        }.build()
        val model = PerceptionModel(PerceptionProfile.PERFECT, DepthCamera.DEFAULT_RGB, Random(1))
        val facing = PhoneRig.cameraToWorld(BodyPose(0f, 0f, 0f), 0f, 0f, level, FloatArray(16))
        model.capture(0.0, 42, scene, facing)
        val seen = model.deliver(0.0)
        assertEquals(1, seen.size)
        val exit = seen.single() as com.navassist.navcore.semantic.SemanticObservation.Exit
        assertEquals(0.5f, exit.normalizedX ?: -1f, 0.05f)
        assertEquals(0.5f, exit.normalizedY ?: -1f, 0.05f)
        assertEquals(42L, exit.timestampNanos)

        val away = PhoneRig.cameraToWorld(BodyPose(0f, 0f, kotlin.math.PI.toFloat()), kotlin.math.PI.toFloat(), 0f, level, FloatArray(16))
        model.capture(3.0, 43, scene, away)
        assertTrue(model.deliver(3.0).isEmpty())
    }
}
