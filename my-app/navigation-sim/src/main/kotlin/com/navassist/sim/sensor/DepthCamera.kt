package com.navassist.sim.sensor

import com.navassist.navcore.sensing.CameraIntrinsics
import com.navassist.navcore.sensing.DepthImage
import com.navassist.navcore.sensing.PlaneObservation
import com.navassist.sim.world.Box
import com.navassist.sim.world.Material
import com.navassist.sim.world.Scene
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How ARCore-style depth deviates from the truth. Tuned by eye against published behaviour, not
 * measured on your Pixel - calibrate against a recorded session before trusting absolute numbers.
 */
data class DepthNoise(
    /** Range noise sigma = base + quadratic * z^2, metres. */
    val sigmaBase: Float = 0.005f,
    val sigmaQuadratic: Float = 0.008f,
    /** Chance any pixel comes back empty, plus a share per metre of range. */
    val dropoutBase: Float = 0.02f,
    val dropoutPerMeter: Float = 0.03f,
    /** Below this cosine of incidence, returns get unreliable ... */
    val grazingCosine: Float = 0.25f,
    /** ... up to this extra dropout at a perfectly grazing angle. */
    val grazingDropout: Float = 0.6f,
    val darkDropout: Float = 0.6f,
    /** Chance a pixel returns the glass itself rather than what is behind it. */
    val glassReturn: Float = 0.1f,
    /** Chance a pixel on a depth edge is smeared between foreground and background. */
    val flyingPixel: Float = 0.3f,
    /** Depth jump that counts as an edge. */
    val edgeMeters: Float = 0.25f,
    /** Nothing further than this comes back at all. */
    val maxRangeMeters: Float = 8f,
) {
    companion object {
        /** Ground truth: every pixel exact, nothing lost. For tests of geometry, not robustness. */
        val NONE = DepthNoise(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 8f)
    }
}

/**
 * A simulated depth sensor producing the same [DepthImage] an ARCore adapter copies out of the
 * SDK: z-depth in millimetres, SENSOR orientation, 0 for no return.
 *
 * Speed trade-off: only every [renderStride]-th pixel is ray cast, and each rendered sample fills
 * its stride x stride block. The engine's unprojection samples exactly those pixels, so what it
 * sees is exact; only the 5x5 averaging used to place semantic observations sees blocks.
 */
class DepthCamera(
    val rgbIntrinsics: CameraIntrinsics = DEFAULT_RGB,
    depthWidth: Int = 160,
    depthHeight: Int = 120,
    private val noise: DepthNoise = DepthNoise(),
    private val renderStride: Int = 3,
    private val random: Random,
) {
    val depthIntrinsics: CameraIntrinsics = rgbIntrinsics.scaledTo(depthWidth, depthHeight)

    private val samplesX = (depthWidth + renderStride - 1) / renderStride
    private val samplesY = (depthHeight + renderStride - 1) / renderStride
    private val clean = FloatArray(samplesX * samplesY)
    private val hitBoxes = arrayOfNulls<Box>(samplesX * samplesY)
    private val cosines = FloatArray(samplesX * samplesY)

    /** Per trackable surface: how much of it has been seen. Drives [trackedPlanes]. */
    private val planeSupport = HashMap<Box, Int>()

    fun render(scene: Scene, cameraToWorld: FloatArray, timestampNanos: Long, out: DepthImage) {
        val k = depthIntrinsics
        out.resize(k.width, k.height)
        out.timestampNanos = timestampNanos
        val m = cameraToWorld
        val ox = m[12]
        val oy = m[13]
        val oz = m[14]
        // Rays from the camera: a sample is z-depth, and its ray is longer than that, but never
        // longer than maxRange divided by the cosine of the corner of the view.
        val reach = noise.maxRangeMeters * sqrt(1f + (k.width / (2f * k.fx)).let { it * it } + (k.height / (2f * k.fy)).let { it * it })
        val candidates = scene.near(ox, oy, oz, reach)

        // Pass 1: exact depth at each sample.
        for (sy in 0 until samplesY) {
            for (sx in 0 until samplesX) {
                val u = sx * renderStride
                val v = sy * renderStride
                // Camera-space ray through pixel (u, v) with z = -1, so the hit parameter IS the
                // z-depth - what ARCore reports.
                val a = (u - k.cx) / k.fx
                val b = -(v - k.cy) / k.fy
                val dx = m[0] * a + m[4] * b - m[8]
                val dy = m[1] * a + m[5] * b - m[9]
                val dz = m[2] * a + m[6] * b - m[10]
                val length = sqrt(dx * dx + dy * dy + dz * dz)
                val tMax = noise.maxRangeMeters
                var hit = scene.raycast(ox, oy, oz, dx, dy, dz, tMax, candidates = candidates)
                if (hit != null && hit.box.material == Material.GLASS && random.nextFloat() >= noise.glassReturn) {
                    hit = scene.raycast(ox, oy, oz, dx, dy, dz, tMax, skipGlass = true, candidates = candidates)
                }
                val i = sy * samplesX + sx
                if (hit == null) {
                    clean[i] = 0f
                    hitBoxes[i] = null
                } else {
                    clean[i] = hit.t
                    hitBoxes[i] = hit.box
                    cosines[i] = abs(dx * hit.normalX + dy * hit.normalY + dz * hit.normalZ) / length
                    if (hit.box.trackablePlane && hit.normalY > 0.5f) {
                        planeSupport[hit.box] = (planeSupport[hit.box] ?: 0) + 1
                    }
                }
            }
        }

        // Pass 2: sensor artefacts, then write blocks.
        for (sy in 0 until samplesY) {
            for (sx in 0 until samplesX) {
                val i = sy * samplesX + sx
                val depth = corrupt(i, sx, sy)
                val mm = if (depth <= 0f) 0 else (depth * 1000f).toInt().coerceIn(0, 65535)
                val u0 = sx * renderStride
                val v0 = sy * renderStride
                for (v in v0 until minOf(v0 + renderStride, k.height)) {
                    for (u in u0 until minOf(u0 + renderStride, k.width)) {
                        out.depthMm[v * k.width + u] = mm.toShort()
                    }
                }
            }
        }
    }

    private fun corrupt(i: Int, sx: Int, sy: Int): Float {
        var depth = clean[i]
        if (depth <= 0f) return 0f
        val box = hitBoxes[i] ?: return 0f

        // Flying pixels: at a depth edge, a return lands somewhere between the two surfaces.
        if (noise.flyingPixel > 0f) {
            val neighbour = when {
                sx + 1 < samplesX && edge(depth, clean[i + 1]) -> clean[i + 1]
                sy + 1 < samplesY && edge(depth, clean[i + samplesX]) -> clean[i + samplesX]
                else -> 0f
            }
            if (neighbour > 0f && random.nextFloat() < noise.flyingPixel) {
                depth += (neighbour - depth) * random.nextFloat()
            }
        }

        var dropout = noise.dropoutBase + noise.dropoutPerMeter * depth
        val cosine = cosines[i]
        if (cosine < noise.grazingCosine && noise.grazingCosine > 0f) {
            dropout += noise.grazingDropout * (1f - cosine / noise.grazingCosine)
        }
        if (box.material == Material.DARK) dropout += noise.darkDropout
        if (random.nextFloat() < dropout) return 0f

        val sigma = noise.sigmaBase + noise.sigmaQuadratic * depth * depth
        if (sigma > 0f) depth += gaussian() * sigma
        return if (depth > 0.05f) depth else 0f
    }

    private fun edge(a: Float, b: Float) = b > 0f && abs(a - b) > noise.edgeMeters

    private fun gaussian(): Float {
        // Box-Muller; the second value is discarded, which is fine at these rates.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
    }

    /**
     * Horizontal planes an AR platform would be tracking by now: a surface appears once enough of
     * it has been in view, and reports the extent seen so far - so early in a session a table
     * can easily be the biggest plane there is.
     */
    fun trackedPlanes(): List<PlaneObservation> = planeSupport.mapNotNull { (box, support) ->
        if (support < PLANE_MIN_SUPPORT) return@mapNotNull null
        val area = minOf(box.topArea, support * PLANE_AREA_PER_SAMPLE)
        PlaneObservation((box.minX + box.maxX) / 2f, box.maxY, (box.minZ + box.maxZ) / 2f, area)
    }

    fun resetPlanes() = planeSupport.clear()

    companion object {
        /** A 640x480 CPU image with a ~65 degree field of view across its long side. */
        val DEFAULT_RGB = CameraIntrinsics(fx = 500f, fy = 500f, cx = 320f, cy = 240f, width = 640, height = 480)

        private const val PLANE_MIN_SUPPORT = 150
        private const val PLANE_AREA_PER_SAMPLE = 0.01f
    }
}
