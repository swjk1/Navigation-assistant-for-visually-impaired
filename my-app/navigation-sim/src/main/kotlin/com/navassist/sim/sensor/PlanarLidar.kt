package com.navassist.sim.sensor

import com.navassist.navcore.geometry.LidarScan
import com.navassist.sim.world.Box
import com.navassist.sim.world.Material
import com.navassist.sim.world.Scene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A 2D scanning LiDAR: one horizontal plane, a full turn per revolution.
 *
 * The defaults follow the Slamtec RPLIDAR C1 class (about $80): 10 revolutions a second, about 500
 * beams each (0.72 degrees apart), 0.05-12 m on light surfaces. Range noise, the dark-surface range
 * and the dropout rate are plausible values, not measurements: check them against the datasheet
 * and a real unit before trusting absolute numbers.
 */
data class PlanarLidarSpec(
    val name: String = "rplidar-c1",
    val revolutionsPerSecond: Float = 10f,
    val beamsPerRevolution: Int = 500,
    val minRangeMeters: Float = 0.05f,
    val maxRangeMeters: Float = 12f,
    /** Dark, low-reflectivity surfaces return only this close. */
    val darkMaxRangeMeters: Float = 6f,
    val rangeSigmaMeters: Float = 0.02f,
    /** Chance a beam returns nothing even off a good surface. */
    val dropout: Float = 0.01f,
    /** Chance a beam returns off glass instead of passing through it. */
    val glassReturn: Float = 0.1f,
    /** Height of the scan plane above the floor. */
    val heightMeters: Float = 1.2f,
    /**
     * The sector behind the wearer that their own body blocks, centred straight back. Mounted on
     * the chest, the torso hides the rear of the plane; measure it on the real harness.
     */
    val blockedRearDegrees: Float = 150f,
) {
    val revolutionFrames: Int get() = maxOf(1, (30f / revolutionsPerSecond).toInt())
}

/**
 * Renders [PlanarLidarSpec] scans against a [Scene]. The scan is centred on the camera (the two
 * share a chest mount), in the camera's frame: angles from its forward, positive clockwise.
 */
class PlanarLidar(val spec: PlanarLidarSpec, private val random: Random) {

    val scan = LidarScan(spec.beamsPerRevolution)

    /** Renders one revolution from plan position (x, y), facing [yaw] (0 = north, clockwise). */
    fun render(scene: Scene, x: Float, y: Float, yaw: Float) {
        scan.clear()
        val oy = spec.heightMeters
        val ox = x
        val oz = -y
        val candidates = scene.near(ox, oy, oz, spec.maxRangeMeters).filter { oy >= it.minY && oy <= it.maxY }
        val halfBlocked = Math.toRadians(spec.blockedRearDegrees / 2.0).toFloat()
        val step = (2.0 * PI / spec.beamsPerRevolution).toFloat()
        for (b in 0 until spec.beamsPerRevolution) {
            // -pi..pi from forward, clockwise.
            val angle = (b * step - PI).toFloat()
            if (PI.toFloat() - abs(angle) < halfBlocked) {
                scan.add(angle, 0f)
                continue
            }
            val heading = yaw + angle
            // Plan heading -> world: x east, z south.
            val dx = sin(heading)
            val dz = -cos(heading)
            scan.add(angle, rangeAlong(scene, candidates, ox, oy, oz, dx, dz))
        }
    }

    private fun rangeAlong(scene: Scene, candidates: List<Box>, ox: Float, oy: Float, oz: Float, dx: Float, dz: Float): Float {
        var fromX = ox
        var fromZ = oz
        var travelled = 0f
        // A beam can pass through glass and go on to the wall behind it.
        repeat(4) {
            val hit = scene.raycast(fromX, oy, fromZ, dx, 0f, dz, spec.maxRangeMeters - travelled, candidates = candidates)
                ?: return 0f
            val range = travelled + hit.t
            if (hit.box.material == Material.GLASS && random.nextFloat() >= spec.glassReturn) {
                travelled = range + 0.02f
                fromX = ox + dx * travelled
                fromZ = oz + dz * travelled
                return@repeat
            }
            if (range < spec.minRangeMeters) return 0f
            if (hit.box.material == Material.DARK && range > spec.darkMaxRangeMeters) return 0f
            if (random.nextFloat() < spec.dropout) return 0f
            return (range + gaussian() * spec.rangeSigmaMeters).coerceAtLeast(spec.minRangeMeters)
        }
        return 0f
    }

    private fun gaussian(): Float {
        val u = random.nextFloat().coerceAtLeast(1e-7f)
        val v = random.nextFloat()
        return (sqrt(-2.0 * kotlin.math.ln(u.toDouble())) * cos(2.0 * PI * v)).toFloat()
    }
}
