package com.navassist.navcore.belief

import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticDirection
import com.navassist.navcore.semantic.TargetMatcher
import com.navassist.navcore.world.EvidenceKind
import com.navassist.navcore.world.ObservationEvent
import com.navassist.navcore.world.WorldModel
import kotlin.math.abs
import kotlin.math.ln

/**
 * Proposes hypotheses about how the building is numbered and signed, and tests them against each
 * new reading in the evidence ledger. Deterministic and free: the baseline every model-backed
 * proposer is compared against, and the verifier whose verdicts a model can never override.
 *
 * A reading is a test only the first time a plate is read: reading the same plate again says
 * nothing new. Each test cites the event that made it, so every status can be traced back to what
 * was seen.
 */
class HypothesisStore {

    private val byId = LinkedHashMap<String, Hypothesis>()
    val hypotheses: Collection<Hypothesis> get() = byId.values
    fun hypothesis(id: String): Hypothesis? = byId[id]

    /** A corridor's worth of plates: read plates near one another. */
    class Corridor(val id: String, val floor: String? = null) {
        val plates = ArrayList<PlateRead>()
        var directionPlus: String? = null
        var directionMinus: String? = null
        var oddLeft: String? = null
        var oddRight: String? = null
        /** Unit axis the direction hypotheses are stated along (numbers rising along +axis for "plus"). */
        var axis: Vec2? = null
        val centroid: Vec2 get() = plates.fold(Vec2.ZERO) { a, p -> a + p.position } * (1f / plates.size.coerceAtLeast(1))
        val latest: Vec2 get() = plates.last().position
    }

    data class PlateRead(val number: Int, val position: Vec2, val eventId: String, val landmarkId: String)

    val corridors = ArrayList<Corridor>()
    private val testedLandmarks = HashSet<String>()
    private var processed = 0
    private var nextId = 1
    private var floorDigit: String? = null
    private var wingDigit: String? = null

    /** The floor being walked as of the latest event; corridors on other floors are not "near". */
    private var currentFloor: String? = null

    /**
     * Lift label minus room digit, per reading on a floor whose label was known: the offset between
     * how a lift names floors and how rooms are numbered (0 where "3" holds the 3xx rooms).
     */
    private val offsets = LinkedHashMap<Int, Int>()

    /** The offset most readings agree on; 0 until a floor's label and plates have both been read. */
    fun liftOffset(): Int = offsets.maxByOrNull { it.value }?.key ?: 0

    /** Readings behind [liftOffset]: (offset, count). */
    fun liftOffsetEvidence(): Map<Int, Int> = offsets

    fun reset() {
        byId.clear()
        corridors.clear()
        testedLandmarks.clear()
        processed = 0
        nextId = 1
        floorDigit = null
        wingDigit = null
        currentFloor = null
        offsets.clear()
    }

    /** Takes in every ledger event recorded since the last call. */
    fun update(world: WorldModel, target: NavigationTarget, nowMillis: Long) {
        val events = world.events
        while (processed < events.size) {
            val e = events[processed++]
            currentFloor = e.floor
            when (e.kind) {
                EvidenceKind.ROOM_LABEL -> plateRead(world, e, nowMillis)
                EvidenceKind.ROOM_RANGE -> rangeSign(e, target, nowMillis)
                else -> Unit
            }
        }
    }

    /** The corridor a point belongs to, if any plate has been read near it. */
    fun corridorNear(p: Vec2): Corridor? = corridors.filter { it.floor == currentFloor }.minByOrNull { it.latest.distanceTo(p) }?.takeIf { c ->
        c.plates.any { it.position.distanceTo(p) <= CORRIDOR_RADIUS_METERS }
    }

    // ------------------------------------------------------------------ plates

    private fun plateRead(world: WorldModel, e: ObservationEvent, nowMillis: Long) {
        val landmarkId = e.landmarkId ?: return
        if (!testedLandmarks.add(landmarkId)) return
        val landmark = world.landmark(landmarkId) ?: return
        val number = (landmark.label ?: e.value)?.let { TargetMatcher.numericPart(it) } ?: return
        val read = PlateRead(number, landmark.position, e.id, landmarkId)
        val corridor = corridorNear(read.position) ?: Corridor("c${corridors.size + 1}", e.floor).also { corridors += it }
        // A number with fewer digits than its neighbours is a misread ("2O8" parses as 20), not a room.
        if (read.number < 100 && corridor.plates.any { it.number >= 100 }) return

        testDirection(corridor, read, nowMillis)
        testParity(corridor, read, nowMillis)
        testLeadingDigit(read, corridor, e.floor, nowMillis)
        testRangeSigns(read, nowMillis)
        corridor.plates += read

        if (corridor.directionPlus == null) proposeDirection(corridor, nowMillis)
        if (corridor.oddLeft == null && corridor.axis != null) proposeParity(corridor, nowMillis)
        if (floorDigit == null && corridors.sumOf { c -> c.plates.count { it.number >= 100 } } >= 2) proposeLeadingDigit(nowMillis)
    }

    private fun proposeDirection(c: Corridor, nowMillis: Long) {
        // Two plates on the same wall - same parity - lie along the corridor; an odd and an even
        // one may face each other across it, and their line would be the corridor's width.
        val pair = c.plates.distinctBy { it.number }.let { plates ->
            plates.flatMap { a -> plates.filter { b -> b.number > a.number && (b.number - a.number) % 2 == 0 }.map { b -> a to b } }
                .maxByOrNull { (a, b) -> a.position.distanceTo(b.position) }
        } ?: return
        val (a, b) = pair
        val along = b.position - a.position
        if (along.length() < 2f * MIN_SEPARATION_METERS) return
        val axis = along.normalized()
        c.axis = axis
        val plus = add(HypothesisFamily.NUMBERING_DIRECTION, "numbers rise towards (%.2f, %.2f) in ${c.id}".format(axis.x, axis.z), c.id, axis, nowMillis)
        val minus = add(HypothesisFamily.NUMBERING_DIRECTION, "numbers rise towards (%.2f, %.2f) in ${c.id}".format(-axis.x, -axis.z), c.id, axis * -1f, nowMillis)
        link(plus, minus)
        c.directionPlus = plus.id
        c.directionMinus = minus.id
        // The pair it was stated from is its first support.
        plus.record(b.eventId, met = true)
        minus.record(b.eventId, met = false)
        settle(plus, nowMillis)
    }

    /** Does the new plate's number rise the way its position along the corridor says? */
    private fun testDirection(c: Corridor, read: PlateRead, nowMillis: Long) {
        val plus = c.directionPlus?.let { byId[it] } ?: return
        val axis = c.axis ?: return
        var agree = 0
        var disagree = 0
        for (p in c.plates) {
            if (p.number == read.number) continue
            val along = (read.position - p.position).dot(axis)
            if (abs(along) < MIN_SEPARATION_METERS) continue
            if ((read.number > p.number) == (along > 0f)) agree++ else disagree++
        }
        if (agree == disagree) return
        plus.record(read.eventId, met = agree > disagree)
        byId[plus.rivalId!!]!!.record(read.eventId, met = agree < disagree)
        settle(plus, nowMillis)
    }

    private fun proposeParity(c: Corridor, nowMillis: Long) {
        val left = add(HypothesisFamily.PARITY_SIDE, "odd numbers on the left of the rising direction in ${c.id}", c.id, c.axis, nowMillis, dependsOn = listOfNotNull(c.directionPlus))
        val right = add(HypothesisFamily.PARITY_SIDE, "odd numbers on the right of the rising direction in ${c.id}", c.id, c.axis, nowMillis, dependsOn = listOfNotNull(c.directionPlus))
        link(left, right)
        c.oddLeft = left.id
        c.oddRight = right.id
        // Test the plates already read.
        for (p in c.plates) parityTest(c, p, nowMillis)
    }

    private fun testParity(c: Corridor, read: PlateRead, nowMillis: Long) {
        if (c.oddLeft == null) return
        parityTest(c, read, nowMillis)
    }

    private fun parityTest(c: Corridor, read: PlateRead, nowMillis: Long) {
        val axis = c.axis ?: return
        val left = c.oddLeft?.let { byId[it] } ?: return
        val offset = read.position - c.centroid
        // Canonical frame: +x is right of +z. Left of the axis is where cross(axis, offset) > 0.
        val cross = axis.x * offset.z - axis.z * offset.x
        if (abs(cross) < SIDE_MARGIN_METERS) return
        val onLeft = cross > 0f
        val odd = read.number % 2 != 0
        val metLeft = odd == onLeft
        left.record(read.eventId, met = metLeft)
        byId[left.rivalId!!]!!.record(read.eventId, met = !metLeft)
        settle(left, nowMillis)
    }

    // ------------------------------------------------------------------ the leading digit

    private fun proposeLeadingDigit(nowMillis: Long) {
        val floor = add(HypothesisFamily.LEADING_DIGIT, "a room number's leading digit is its floor", "building", null, nowMillis)
        val wing = add(HypothesisFamily.LEADING_DIGIT, "a room number's leading digit is its wing", "building", null, nowMillis)
        link(floor, wing)
        floorDigit = floor.id
        wingDigit = wing.id
    }

    /**
     * Every plate on one floor sharing its leading digit is what "the digit is the floor" predicts;
     * corridors on the same floor with different digits is what "the digit is the wing" predicts.
     * Only plates in a different corridor from the ones read before test it.
     */
    private fun testLeadingDigit(read: PlateRead, corridor: Corridor, floorLabel: String?, nowMillis: Long) {
        val floor = floorDigit?.let { byId[it] } ?: return
        // Only room numbers with a leading digit to speak of: an OCR misread ("2O8" parses as 20)
        // must not count as a plate from floor 0.
        if (read.number < 100) return
        val digit = read.number / 100
        // The direct test: the floor's own label is known. "The digit is the floor" predicts the
        // digit matches it - up to one constant offset for the whole building, which the first such
        // reading fixes and every later one tests.
        labelNumber(floorLabel)?.let { label ->
            val offset = label - digit
            val expected = if (offsets.isEmpty()) offset else liftOffset()
            offsets[offset] = (offsets[offset] ?: 0) + 1
            val met = offset == expected
            floor.record(read.eventId, met)
            byId[floor.rivalId!!]!!.record(read.eventId, !met)
            settle(floor, nowMillis)
            return
        }
        // Otherwise: other corridors on this floor should share the digit.
        val elsewhere = corridors.filter { it !== corridor && it.floor == corridor.floor }.flatMap { it.plates }.filter { it.number >= 100 }
        if (elsewhere.isEmpty()) return
        val majority = elsewhere.groupingBy { it.number / 100 }.eachCount().maxByOrNull { it.value }!!.key
        val met = digit == majority
        floor.record(read.eventId, met)
        byId[floor.rivalId!!]!!.record(read.eventId, !met)
        settle(floor, nowMillis)
    }

    /** A floor label as a number: "3" is 3, "G" (ground) is 0; anything else unknown. */
    fun labelNumber(label: String?): Int? = when {
        label == null -> null
        label.equals("G", ignoreCase = true) -> 0
        else -> label.toIntOrNull()
    }

    /** The "leading digit is the floor" hypothesis, once proposed. */
    fun floorHypothesis(): Hypothesis? = floorDigit?.let { byId[it] }

    /** The leading digit most plates read on this floor share, and how many read it; null before two plates. */
    fun digitHere(): Pair<Int, Int>? {
        val digits = corridors.filter { it.floor == currentFloor }.flatMap { it.plates }.filter { it.number >= 100 }.map { it.number / 100 }
        if (digits.size < 2) return null
        return digits.groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair()
    }

    /** Whether the leading digit is believed to be the floor, and if so the floor's digit here. */
    fun floorDigitHere(): Int? {
        val floor = floorDigit?.let { byId[it] } ?: return null
        if (floor.status != HypothesisStatus.SUPPORTED && floor.status != HypothesisStatus.CONFIRMED) return null
        return corridors.filter { it.floor == currentFloor }.flatMap { it.plates }.filter { it.number >= 100 }.groupingBy { it.number / 100 }.eachCount().maxByOrNull { it.value }?.key
    }

    // ------------------------------------------------------------------ range signs

    private val rangeSigns = ArrayList<Triple<String, IntRange, Pair<Vec2, Vec2>>>()

    /** "Rooms 201-206 ->": following the arrow should lead to rooms in that range. */
    private fun rangeSign(e: ObservationEvent, target: NavigationTarget, nowMillis: Long) {
        val (lo, hi) = e.value?.split('-')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return
        val arrow = when (e.direction) {
            SemanticDirection.LEFT -> -GeometryUtils.PI_F / 2f
            SemanticDirection.RIGHT -> GeometryUtils.PI_F / 2f
            else -> 0f
        }
        val heading = Vec2.fromYaw(e.observerYawRadians + arrow)
        // One hypothesis per sign: the same sign read again from about the same place is not new.
        if (rangeSigns.any { (_, r, at) -> r == lo..hi && at.first.distanceTo(e.observerPosition) < 4f }) return
        val h = add(HypothesisFamily.RANGE_SIGN, "rooms $lo-$hi lie ${e.direction?.name?.lowercase() ?: "ahead"} of the sign seen at (%.1f, %.1f)".format(e.observerPosition.x, e.observerPosition.z), e.id, heading, nowMillis)
        rangeSigns += Triple(h.id, lo..hi, e.observerPosition to heading)
    }

    private fun testRangeSigns(read: PlateRead, nowMillis: Long) {
        for ((id, range, at) in rangeSigns) {
            val (origin, heading) = at
            val to = read.position - origin
            val distance = to.length()
            if (distance < 1f || distance > RANGE_SIGN_REACH_METERS) continue
            val inArrow = to.normalized().dot(heading) > RANGE_SIGN_CONE_COS
            if (!inArrow) continue
            val h = byId[id] ?: continue
            // In range, or on the way to it (within half a range's width), meets the prediction.
            val slack = maxOf(4, (range.last - range.first) / 2)
            val met = read.number in (range.first - slack)..(range.last + slack)
            h.record(read.eventId, met)
            settle(h, nowMillis)
        }
    }

    // ------------------------------------------------------------------ status

    /** Sets the status of a hypothesis and its rival from their evidence. */
    private fun settle(h: Hypothesis, nowMillis: Long) {
        apply(h, nowMillis)
        h.rivalId?.let { byId[it] }?.let { apply(it, nowMillis) }
    }

    private fun apply(h: Hypothesis, nowMillis: Long) {
        val b = h.belief
        val next = when {
            h.tests >= CONFIRM_TESTS && b >= CONFIRM_BELIEF -> HypothesisStatus.CONFIRMED
            h.tests >= 1 && b >= SUPPORT_BELIEF -> HypothesisStatus.SUPPORTED
            h.tests >= 1 && b <= 1f - SUPPORT_BELIEF -> HypothesisStatus.CONTRADICTED
            else -> HypothesisStatus.PROPOSED
        }
        h.setStatus(next, nowMillis, "%d met, %d failed".format(h.support.size, h.against.size))
        // A hypothesis that falls is a reason to reconsider what was built on it.
        if (next == HypothesisStatus.CONTRADICTED) {
            for (d in byId.values) if (h.id in d.dependsOn && d.status != HypothesisStatus.RETRACTED) {
                d.setStatus(HypothesisStatus.PROPOSED, nowMillis, "depends on ${h.id}, now contradicted")
            }
        }
    }

    /** The destination door a reading at it confirmed or refuted. */
    fun destination(landmarkId: String, label: String, verified: Boolean, nowMillis: Long) {
        val h = byId.values.firstOrNull { it.family == HypothesisFamily.DESTINATION && it.scope == landmarkId }
            ?: add(HypothesisFamily.DESTINATION, "$landmarkId ('$label') is the destination", landmarkId, null, nowMillis)
        h.setStatus(if (verified) HypothesisStatus.CONFIRMED else HypothesisStatus.RETRACTED, nowMillis, if (verified) "read at the door" else "read otherwise at the door")
    }

    /** Uncertainty of a two-way family, in bits: 1 when undecided, 0 when certain. */
    fun entropyBits(h: Hypothesis): Float {
        val p = h.belief.coerceIn(1e-4f, 1f - 1e-4f)
        return (-(p * ln(p) + (1f - p) * ln(1f - p)) / ln(2f))
    }

    private fun add(family: HypothesisFamily, proposition: String, scope: String, direction: Vec2?, nowMillis: Long, dependsOn: List<String> = emptyList()): Hypothesis {
        val h = Hypothesis("H-%02d".format(nextId++), family, proposition, scope, null, dependsOn, direction, nowMillis)
        byId[h.id] = h
        h.history += nowMillis to "proposed"
        return h
    }

    private fun link(a: Hypothesis, b: Hypothesis) {
        a.rivalId = b.id
        b.rivalId = a.id
    }

    companion object {
        const val CORRIDOR_RADIUS_METERS = 15f
        const val MIN_SEPARATION_METERS = 1.0f
        const val SIDE_MARGIN_METERS = 0.4f
        const val RANGE_SIGN_REACH_METERS = 30f
        val RANGE_SIGN_CONE_COS = kotlin.math.cos(GeometryUtils.degreesToRadians(50f))
        const val CONFIRM_TESTS = 3
        const val CONFIRM_BELIEF = 0.8f
        const val SUPPORT_BELIEF = 0.6f
    }
}
