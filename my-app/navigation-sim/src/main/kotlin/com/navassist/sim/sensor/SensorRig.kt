package com.navassist.sim.sensor

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.sensing.CameraIntrinsics
import com.navassist.sim.SimSettings
import kotlin.math.tan

/**
 * A sensor setup to compare on the benchmark before buying one: the camera's field of view and
 * resolution, how its depth behaves, where it is worn, and the engine settings that suit it.
 *
 * Every number here except [PHONE]'s is a plausible guess, not a datasheet or a measurement. The
 * point is to see which properties matter (range, field of view, glass), then measure the real
 * candidates.
 *
 * Not modelled yet: a mount the user cannot aim. The simulated user still points the camera as the
 * engine asks, sweeping it to scan and tilting it to read a sign, which a chest mount cannot do
 * without the whole body turning. Rigs marked chest-worn are therefore optimistic about aiming.
 */
data class SensorRig(
    val name: String,
    val camera: CameraIntrinsics,
    val depth: DepthNoise,
    val hold: PhoneHold,
    val pitchDegrees: Float,
    val swayDegrees: Float,
    /** Engine settings that suit the sensor: how far its depth is trusted, for one. */
    val engine: (NavigationConfig) -> NavigationConfig = { it },
) {
    fun applyTo(settings: SimSettings): SimSettings = settings.copy(
        cameraIntrinsics = camera,
        depthNoise = depth,
        user = settings.user.copy(hold = hold, holdPitchDegrees = pitchDegrees, swayDegrees = swayDegrees),
        engineConfig = engine(settings.engineConfig),
    )

    companion object {
        /** A 640x480 camera with the given horizontal field of view, in sensor orientation. */
        private fun camera(horizontalFovDegrees: Float, width: Int = 640, height: Int = 480): CameraIntrinsics {
            val f = (width / 2f) / tan(Math.toRadians(horizontalFovDegrees / 2.0).toFloat())
            return CameraIntrinsics(fx = f, fy = f, cx = width / 2f, cy = height / 2f, width = width, height = height)
        }

        /** Today's phone, held in portrait at chest height: the simulator's defaults. */
        val PHONE = SensorRig("phone", DepthCamera.DEFAULT_RGB, DepthNoise(), PhoneHold(), pitchDegrees = 15f, swayDegrees = 3f)

        /** The same phone using an ultra-wide camera: more in view, fewer pixels on each sign. */
        val WIDE_PHONE = PHONE.copy(name = "wide-phone", camera = camera(100f))

        /**
         * A stereo depth camera worn on the chest, landscape, steadier than a hand. Depth noise lower and
         * range longer than phone depth; glass still mostly invisible.
         */
        val STEREO_CHEST = SensorRig(
            name = "stereo-chest",
            camera = camera(87f, 1280, 800),
            depth = DepthNoise(sigmaQuadratic = 0.004f, dropoutPerMeter = 0.015f, maxRangeMeters = 12f, glassReturn = 0.1f),
            hold = PhoneHold(heightMeters = 1.3f, reachMeters = 0.15f, portrait = false),
            pitchDegrees = 15f,
            swayDegrees = 1f,
            // Depth is integrated only inside the rolling map window, so a longer reach needs a bigger one.
            engine = { it.copy(gridSizeMeters = 24f, maxDepthMeters = 10f, maxObstacleRangeMeters = 6f, nearFloorVerifyMeters = 5f) },
        )

        /** The stereo rig plus an ultrasonic sensor that sees glass: modelled as glass returning depth. */
        val STEREO_CHEST_GLASS = STEREO_CHEST.copy(name = "stereo-chest-glass", depth = STEREO_CHEST.depth.copy(glassReturn = 0.9f))

        /** A solid-state LiDAR on the chest: long range, low noise, narrow vertical view, glass invisible. */
        val LIDAR_CHEST = SensorRig(
            name = "lidar-chest",
            camera = camera(70f, 1280, 800),
            depth = DepthNoise(sigmaBase = 0.01f, sigmaQuadratic = 0.0005f, dropoutPerMeter = 0.005f, maxRangeMeters = 25f, glassReturn = 0.05f),
            hold = PhoneHold(heightMeters = 1.3f, reachMeters = 0.15f, portrait = false),
            pitchDegrees = 10f,
            swayDegrees = 1f,
            engine = { it.copy(gridSizeMeters = 36f, maxDepthMeters = 15f, maxObstacleRangeMeters = 8f, nearFloorVerifyMeters = 6f) },
        )

        val ALL = listOf(PHONE, WIDE_PHONE, STEREO_CHEST, STEREO_CHEST_GLASS, LIDAR_CHEST)
    }
}
