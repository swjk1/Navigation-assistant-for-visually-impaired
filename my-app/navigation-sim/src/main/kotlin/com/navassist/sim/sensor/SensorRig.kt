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
    /** A 2D LiDAR sharing the camera's mount; null for none. */
    val lidar: PlanarLidarSpec? = null,
    /** Strapped to the chest: the camera turns only with the body, at a fixed pitch. */
    val chestMounted: Boolean = false,
) {
    fun applyTo(settings: SimSettings): SimSettings = settings.copy(
        cameraIntrinsics = camera,
        depthNoise = depth,
        user = settings.user.copy(
            hold = hold, holdPitchDegrees = pitchDegrees, swayDegrees = swayDegrees, chestMounted = chestMounted,
        ),
        engineConfig = engine(settings.engineConfig),
        lidar = lidar,
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

        /**
         * The phone strapped to the chest in portrait, 10 degrees down: no LiDAR. Pointing where the
         * body points, it cannot be tilted at the floor, so its nearest view of the floor is about
         * 1.4 m out; the engine bridges up to 1.6 m of unseen floor, and only to floor it has seen.
         */
        val CHEST_PHONE = SensorRig(
            name = "chest-phone",
            camera = DepthCamera.DEFAULT_RGB,
            depth = DepthNoise(),
            hold = PhoneHold(heightMeters = 1.3f, reachMeters = 0.15f, portrait = true),
            pitchDegrees = 10f,
            swayDegrees = 0f,
            engine = { it.copy(blindZoneMeters = 1.6f, blindZoneSkipsFootprint = true, forwardGateToWaypoint = true, haloEscapeOnlyFromHalo = true, inflationRadiusMeters = 0.35f) },
            chestMounted = true,
        )

        /**
         * The chosen rig: [CHEST_PHONE] plus a 2D 360-degree LiDAR (RPLIDAR C1 class) on the same
         * harness. Exploration follows what the camera has seen (a LiDAR maps a corridor's air at
         * once and would leave nothing to explore). The map window stays 12 m: a 28 m one, to use
         * the LiDAR's full range, scored 50.0% against 65.2% (p1d, robot, 3 seeds), for reasons not
         * yet run down - see rig-chest-c1-grid28.
         */
        val CHEST_C1 = CHEST_PHONE.copy(
            name = "chest-c1",
            engine = { it.copy(blindZoneMeters = 1.6f, blindZoneSkipsFootprint = true, forwardGateToWaypoint = true, haloEscapeOnlyFromHalo = true, inflationRadiusMeters = 0.35f, frontiersFromCamera = true) },
            lidar = PlanarLidarSpec(),
        )

        val ALL = listOf(PHONE, WIDE_PHONE, STEREO_CHEST, STEREO_CHEST_GLASS, LIDAR_CHEST, CHEST_PHONE, CHEST_C1)
    }
}
