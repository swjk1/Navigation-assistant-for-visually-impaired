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
 * Two sources, in priority order:
 *  A) A platform-supplied [com.navassist.navcore.geometry.NavigationFrame.floorHint] - on Android
 *     this comes from an ARCore horizontal upward-facing plane. The core never sees the plane
 *     object itself, only the height, so the same path works for ARKit later.
 *  B) A robust estimate from the depth cloud itself: a coarse height histogram picks the lowest
 *     well-supported horizontal band, then the estimate is refined to the mean of the points in
 *     that band. This is a deliberately simplified stand-in for RANSAC plane fitting - the floor
 *     is always the dominant low horizontal surface in an indoor scene, and we only need its
 *     height, not its normal.
 *
 * Until confidence reaches the configured minimum, NO point may be classified as an obstacle:
 * misjudging the floor by 20 cm would turn the floor itself into a wall.
 */
class FloorEstimator(private val config: NavigationConfig) {

    private var current: FloorEstimate = FloorEstimate.UNKNOWN
    private var histogram = IntArray(0)

    val estimate: FloorEstimate get() = current

    fun reset() {
        current = FloorEstimate.UNKNOWN
    }

    /**
     * @param deviceY current device height in canonical world Y, used to bound the search: the
     *        floor is always below the phone and never more than ~3 m below it.
     */
    fun update(
        points: DepthPointCloud,
        deviceY: Float,
        floorHint: Float?,
        floorHintConfidence: Float,
    ): FloorEstimate {
        if (floorHint != null && floorHintConfidence >= config.floorMinConfidence) {
            current = blend(FloorEstimate(floorHint, floorHintConfidence, points.count))
            return current
        }

        val fromDepth = estimateFromDepth(points, deviceY)
        if (fromDepth != null) {
            current = blend(fromDepth)
        } else if (current.confidence > 0f) {
            // No usable support this frame: decay confidence instead of dropping the estimate.
            current = current.copy(confidence = current.confidence * 0.97f)
        }
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
        // Search from 3 m below the device up to 0.3 m below it: the floor cannot be above that.
        val minY = deviceY - 3.0f
        val maxY = deviceY - 0.3f
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
