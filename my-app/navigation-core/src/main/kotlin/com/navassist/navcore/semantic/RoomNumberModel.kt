package com.navassist.navcore.semantic

import com.navassist.navcore.geometry.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where a room number should be, worked out from the plates read so far.
 *
 * A corridor numbers its rooms in order along its length, usually odd on one wall and even on the
 * other. From a handful of plates and where each was seen, fit how the numbers change along the
 * corridor, then read off where the target number falls - and on which wall.
 *
 * Plain code, not a language model: instant, free, testable, and it hands any planner clean facts
 * instead of raw plates. It deliberately stays local - the last few plates near each other - since
 * numbering restarts or turns at junctions, and a fit across two corridors is a fit to neither.
 */
class RoomNumberModel(
    /** Plates further than this from the latest one are taken to be another corridor. */
    private val neighbourhoodMeters: Float = 20f,
    private val maxPlates: Int = 8,
) {
    /** One plate read: its number, and where it is (or best guess at where). */
    data class Plate(val number: Int, val position: Vec2)

    data class Estimate(
        /** Where the target's plate should be. */
        val position: Vec2,
        /** 0..1: how well the plates fit a straight run of numbers, and how many there were. */
        val confidence: Float,
        /** Unit direction along which numbers rise. */
        val rising: Vec2,
        /** How many numbers per metre along [rising]. */
        val numbersPerMeter: Float,
        /** The target's wall is known from odd/even: [position] is offset onto it. */
        val sideKnown: Boolean,
        val platesUsed: Int,
    )

    private val ledger = ArrayList<Plate>()

    val plates: List<Plate> get() = ledger

    fun add(number: Int, position: Vec2) {
        // The same plate read again from a step away is one plate, not evidence of a run.
        ledger.removeAll { it.number == number && it.position.distanceTo(position) < SAME_PLATE_METERS }
        ledger += Plate(number, position)
        if (ledger.size > MAX_LEDGER) ledger.removeAt(0)
    }

    fun clear() = ledger.clear()

    /**
     * Number blocks usually encode the floor: 1xx on the first, 3xx on the third. When every
     * plate read so far shares a hundreds digit and the target's differs, the target is most likely
     * on another floor, and walking this one will not find it.
     */
    fun targetLikelyOnAnotherFloor(target: Int): Boolean {
        if (ledger.size < MIN_PLATES || target < 100) return false
        val blocks = ledger.map { it.number / 100 }.toSet()
        return blocks.size == 1 && blocks.first() != target / 100
    }

    fun estimate(target: Int): Estimate? {
        val latest = ledger.lastOrNull() ?: return null
        val local = ledger.filter { it.position.distanceTo(latest.position) <= neighbourhoodMeters }.takeLast(maxPlates)
        if (local.size < MIN_PLATES) return null
        val fit = fitRun(local) ?: return null
        // One misread ("389" for "309") spreads its error over a whole least-squares fit, so its
        // residual does not stand out. Instead: refit without each plate in turn, and keep the
        // best only when it is clearly better.
        val refit = if (local.size > MIN_PLATES && fit.rSquared < CLEAN_FIT) {
            local.mapNotNull { fitRun(local - it) }.maxByOrNull { it.rSquared }
                ?.takeIf { it.rSquared > fit.rSquared + OUTLIER_GAIN } ?: fit
        } else {
            fit
        }
        if (abs(refit.slope) < MIN_NUMBERS_PER_METER) return null

        val along = (target - refit.interceptFor(target)) / refit.slope
        var position = refit.centre + refit.axis * along
        val side = refit.sideOffset(target)
        if (side != null) position += refit.normal * side

        // Far extrapolations are guesses about corridors not yet seen.
        val reach = abs(along) - refit.halfSpan
        val plausibility = if (reach <= 0f) 1f else max(0f, 1f - reach / MAX_EXTRAPOLATION_METERS)
        val support = min(1f, refit.count / 4f)
        val confidence = refit.rSquared * support * plausibility
        if (confidence <= 0f) return null
        return Estimate(
            position = position,
            confidence = confidence,
            rising = if (refit.slope > 0f) refit.axis else refit.axis * -1f,
            numbersPerMeter = abs(refit.slope),
            sideKnown = side != null,
            platesUsed = refit.count,
        )
    }

    /**
     * number = intercept + slope * (along the corridor axis from its centre), with its own
     * intercept for odd and even numbers: 302 usually faces 301, so the two walls are two
     * parallel runs offset by one.
     */
    private class Run(
        val centre: Vec2,
        val axis: Vec2,
        val normal: Vec2,
        val slope: Float,
        val rSquared: Float,
        val halfSpan: Float,
        val count: Int,
        private val oddIntercept: Float?,
        private val evenIntercept: Float?,
        private val oddSide: Float?,
        private val evenSide: Float?,
    ) {
        fun interceptFor(number: Int): Float {
            val own = if (number % 2 != 0) oddIntercept else evenIntercept
            return own ?: oddIntercept ?: evenIntercept ?: 0f
        }

        /** How far off the axis the target's wall is, when odd and even sit on opposite walls. */
        fun sideOffset(target: Int): Float? {
            val odd = oddSide ?: return null
            val even = evenSide ?: return null
            if (odd * even >= 0f || abs(odd - even) < MIN_SIDE_SEPARATION_METERS) return null
            return if (target % 2 != 0) odd else even
        }
    }

    private fun fitRun(plates: List<Plate>): Run? {
        if (plates.size < MIN_PLATES) return null
        val n = plates.size.toFloat()
        val centre = meanPosition(plates)

        val groups = plates.groupBy { it.number % 2 }.values

        // The corridor's axis: the main direction the plates spread along, measured within each
        // wall's plates so the width between the walls does not tilt it.
        var sxx = 0f
        var szz = 0f
        var sxz = 0f
        for (group in groups.filter { it.size >= 2 }.ifEmpty { listOf(plates) }) {
            val c = meanPosition(group)
            for (p in group) {
                val d = p.position - c
                sxx += d.x * d.x
                szz += d.z * d.z
                sxz += d.x * d.z
            }
        }
        if (sxx + szz < 1e-4f) return null
        val angle = 0.5f * kotlin.math.atan2(2f * sxz, sxx - szz)
        val axis = Vec2(kotlin.math.cos(angle), kotlin.math.sin(angle))
        val normal = Vec2(-axis.z, axis.x)

        fun along(p: Plate) = (p.position - centre).dot(axis)
        val alongs = plates.map(::along)
        val span = alongs.max() - alongs.min()
        if (span < MIN_SPAN_METERS) return null

        // One slope shared by both walls, each wall with its own intercept.
        var sss = 0f
        var ssn = 0f
        for (group in groups) {
            val meanS = group.sumOf { along(it).toDouble() }.toFloat() / group.size
            val meanN = group.sumOf { it.number.toDouble() }.toFloat() / group.size
            for (p in group) {
                sss += (along(p) - meanS) * (along(p) - meanS)
                ssn += (along(p) - meanS) * (p.number - meanN)
            }
        }
        if (sss < 1e-4f) return null
        val slope = ssn / sss

        fun intercept(parity: Int): Float? {
            val group = plates.filter { it.number % 2 == parity }
            if (group.isEmpty()) return null
            return group.sumOf { (it.number - slope * along(it)).toDouble() }.toFloat() / group.size
        }
        val odd = intercept(1)
        val even = intercept(0)
        val meanNumber = plates.sumOf { it.number.toDouble() }.toFloat() / n
        var residual = 0f
        var total = 0f
        for (p in plates) {
            val predicted = (if (p.number % 2 != 0) odd else even)!! + slope * along(p)
            residual += (p.number - predicted) * (p.number - predicted)
            total += (p.number - meanNumber) * (p.number - meanNumber)
        }
        if (total < 1e-4f) return null

        fun meanSide(parity: Int): Float? {
            val side = plates.filter { it.number % 2 == parity }.map { (it.position - centre).dot(normal) }
            return if (side.isEmpty()) null else side.sum() / side.size
        }
        return Run(
            centre = centre,
            axis = axis,
            normal = normal,
            slope = slope,
            rSquared = max(0f, 1f - residual / total),
            halfSpan = span / 2f,
            count = plates.size,
            oddIntercept = odd,
            evenIntercept = even,
            oddSide = meanSide(1),
            evenSide = meanSide(0),
        )
    }

    private fun meanPosition(plates: List<Plate>): Vec2 = Vec2(
        plates.sumOf { it.position.x.toDouble() }.toFloat() / plates.size,
        plates.sumOf { it.position.z.toDouble() }.toFloat() / plates.size,
    )

    private companion object {
        const val MIN_PLATES = 3
        const val MAX_LEDGER = 64
        const val SAME_PLATE_METERS = 1.5f
        const val MIN_SPAN_METERS = 2f
        const val MIN_NUMBERS_PER_METER = 0.05f

        /** A fit this good is not second-guessed; a refit must beat a worse one by this much. */
        const val CLEAN_FIT = 0.95f
        const val OUTLIER_GAIN = 0.1f
        const val MAX_EXTRAPOLATION_METERS = 60f
        const val MIN_SIDE_SEPARATION_METERS = 0.8f
    }
}
