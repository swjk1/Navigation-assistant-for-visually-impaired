package com.navassist.navcore.mapping

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.DepthPointCloud
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

data class FloorEstimate(
    val floorY: Float,
    val confidence: Float,
    val supportPoints: Int,
) {
    companion object {
        val UNKNOWN = FloorEstimate(0f, 0f, 0)
    }
}

/**
 * Estimates floor height in canonical world Y.
 *
 * Two sources, FUSED rather than ranked:
 *  A) A platform-supplied [com.navassist.navcore.geometry.NavigationFrame.floorHint] - on Android
 *     this comes from an ARCore horizontal upward-facing plane. The core never sees the plane
 *     object itself, only the height, so the same path works for ARKit later.
 *  B) A robust estimate from the depth cloud itself: a coarse height histogram picks the lowest
 *     well-supported horizontal band, then the estimate is refined to the mean of the points in
 *     that band. This is a deliberately simplified stand-in for RANSAC plane fitting - the floor
 *     is always the dominant low horizontal surface in an indoor scene, and we only need its
 *     height, not its normal.
 *
 * The hint is preferred, but it never silences depth: when depth shows a well-supported surface
 * clearly BELOW the plane, the plane is a table and depth wins. Once established, the estimate
 * also refuses to jump UP by more than [NavigationConfig.floorMaxRiseMeters] unless the rise
 * persists - the classic failure is the camera seeing only a desk for a few seconds.
 *
 * Until confidence reaches the configured minimum, NO point may be classified as an obstacle:
 * misjudging the floor by 20 cm would turn the floor itself into a wall.
 */
class FloorEstimator(private val config: NavigationConfig) {

    private var current: FloorEstimate = FloorEstimate.UNKNOWN
    private var histogram = IntArray(0)

    /** When a candidate first rose too far above the estimate; null while none is pending. */
    private var riseSinceMillis: Long? = null

    val estimate: FloorEstimate get() = current

    fun reset() {
        current = FloorEstimate.UNKNOWN
        riseSinceMillis = null
    }

    /**
     * @param deviceY current device height in canonical world Y, used to bound the search: a held
     *        phone is between [NavigationConfig.floorMinDropMeters] and
     *        [NavigationConfig.floorMaxDropMeters] above the floor.
     * @param nowMillis platform time; only used to confirm a sustained rise of the floor.
     */
    fun update(
        points: DepthPointCloud,
        deviceY: Float,
        floorHint: Float?,
        floorHintConfidence: Float,
        nowMillis: Long = 0L,
    ): FloorEstimate {
        val fromDepth = estimateFromDepth(points, deviceY)
        val fromHint = if (floorHint != null && floorHintConfidence >= config.floorMinConfidence) {
            FloorEstimate(floorHint, floorHintConfidence, points.count)
        } else {
            null
        }

        val candidate = when {
            // Depth sees a supported surface clearly below the plane: the plane is furniture.
            fromHint != null && fromDepth != null &&
                fromDepth.floorY < fromHint.floorY - config.floorBandBelowMeters -> fromDepth
            fromHint != null -> fromHint
            else -> fromDepth
        }

        if (candidate == null) {
            // No usable support this frame: decay confidence instead of dropping the estimate.
            if (current.confidence > 0f) current = current.copy(confidence = current.confidence * 0.97f)
            return current
        }

        val established = current.confidence >= config.floorMinConfidence
        if (established && candidate.floorY > current.floorY + config.floorMaxRiseMeters) {
            val since = riseSinceMillis ?: nowMillis.also { riseSinceMillis = it }
            if (nowMillis - since < config.floorRiseConfirmMillis) return current
        } else {
            riseSinceMillis = null
        }

        current = blend(candidate)
        return current
    }

    private fun blend(candidate: FloorEstimate): FloorEstimate {
        if (current.confidence <= 0f) return candidate
        val alpha = config.floorSmoothingAlpha
        val y = current.floorY + (candidate.floorY - current.floorY) * alpha
        val confidence = min(1f, current.confidence + (candidate.confidence - current.confidence) * alpha + 0.02f)
        return FloorEstimate(y, confidence, candidate.supportPoints)
    }

    private fun estimateFromDepth(points: DepthPointCloud, deviceY: Float): FloorEstimate? {
        if (points.count < config.floorMinSupportPoints) return null

        val bin = config.floorHistogramBinMeters
        // Only where a held phone's floor can be. The upper bound matters most: it is what keeps a
        // desk top from out-competing a floor that is simply out of view.
        val minY = deviceY - config.floorMaxDropMeters
        val maxY = deviceY - config.floorMinDropMeters
        val binCount = ((maxY - minY) / bin).toInt() + 1
        if (binCount <= 1) return null
        if (histogram.size < binCount) histogram = IntArray(binCount)
        for (i in 0 until binCount) histogram[i] = 0

        var considered = 0
        for (i in 0 until points.count) {
            val y = points.y(i)
            if (y < minY || y > maxY) continue
            val b = floor((y - minY) / bin).toInt()
            if (b in 0 until binCount) {
                histogram[b]++
                considered++
            }
        }
        if (considered < config.floorMinSupportPoints) return null

        // The floor is the LOWEST strongly-supported band, not simply the global mode: a large
        // table top or a crowd of people can easily out-vote the floor in a single frame.
        var best = -1
        var bestCount = 0
        val threshold = maxOf(config.floorMinSupportPoints / 2, (considered * 0.08f).toInt())
        for (b in 0 until binCount) {
            if (histogram[b] >= threshold) {
                best = b
                bestCount = histogram[b]
                break
            }
        }
        if (best < 0) return null

        // Refine: mean of the points within one bin of the winning band.
        val bandCenter = minY + (best + 0.5f) * bin
        var sum = 0f
        var n = 0
        for (i in 0 until points.count) {
            val y = points.y(i)
            if (abs(y - bandCenter) <= bin) {
                sum += y
                n++
            }
        }
        if (n == 0) return null
        val refined = sum / n

        val support = maxOf(bestCount, n)
        val confidence = min(1f, support.toFloat() / (config.floorMinSupportPoints * 3f))
        return FloorEstimate(refined, confidence, support)
    }
}
