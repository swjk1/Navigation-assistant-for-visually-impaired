package com.navassist.navcore

import com.navassist.navcore.belief.HypothesisFamily
import com.navassist.navcore.belief.HypothesisStatus
import com.navassist.navcore.belief.HypothesisStore
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec3
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticDirection
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.world.WorldModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HypothesisStoreTest {

    private val target = NavigationTarget.Room("219")

    /** A corridor along +z: odd on the west wall (x = -1), even on the east, two numbers every 3 m. */
    private fun read(world: WorldModel, number: Int, rising: Boolean = true, oddWest: Boolean = true, t: Long = number * 100L) {
        val step = (number - 201) / 2 * 3f
        val z = if (rising) step else 30f - step
        val odd = number % 2 != 0
        val x = if (odd == oddWest) -1f else 1f
        world.record(SemanticObservation.Room(number.toString(), 0.9f, worldPosition = Vec3(x, 1.5f, z)), t, Pose3D(0f, 1.3f, z - 2f, 0f), null)
    }

    private fun store(world: WorldModel) = HypothesisStore().also { it.update(world, target, 0) }

    @Test
    fun `numbers rising along the corridor confirm that direction and contradict the other`() {
        val world = WorldModel()
        for (n in listOf(201, 202, 203, 205, 207, 209)) read(world, n)
        val s = store(world)
        val direction = s.hypotheses.filter { it.family == HypothesisFamily.NUMBERING_DIRECTION }
        assertEquals(2, direction.size)
        val confirmed = direction.single { it.status == HypothesisStatus.CONFIRMED }
        assertTrue(confirmed.direction!!.z > 0.5f, "rising along +z")
        assertEquals(HypothesisStatus.CONTRADICTED, direction.single { it !== confirmed }.status)
    }

    @Test
    fun `reversed numbering confirms the opposite direction`() {
        val world = WorldModel()
        for (n in listOf(201, 203, 205, 207, 209)) read(world, n, rising = false)
        val confirmed = store(world).hypotheses.single { it.family == HypothesisFamily.NUMBERING_DIRECTION && it.status == HypothesisStatus.CONFIRMED }
        assertTrue(confirmed.direction!!.z < -0.5f, "rising along -z")
    }

    @Test
    fun `parity follows which wall the odd numbers are on`() {
        for (oddWest in listOf(true, false)) {
            val world = WorldModel()
            for (n in listOf(201, 203, 202, 204, 205, 206, 207)) read(world, n, oddWest = oddWest)
            val sides = store(world).hypotheses.filter { it.family == HypothesisFamily.PARITY_SIDE }
            val winner = sides.maxByOrNull { it.belief }!!
            assertTrue(winner.status == HypothesisStatus.CONFIRMED || winner.status == HypothesisStatus.SUPPORTED)
            // Rising along +z, west (x < 0) is on the left.
            assertEquals(oddWest, winner.proposition.contains("left"), winner.proposition)
        }
    }

    @Test
    fun `a range sign's arrow is supported by the plates it leads to`() {
        val world = WorldModel()
        // Seen facing +z with the arrow ahead: rooms 201-209 that way.
        world.record(SemanticObservation.RoomRange(201, 209, SemanticDirection.FORWARD, 0.9f), 0, Pose3D(0f, 1.3f, -3f, 0f), null)
        for (n in listOf(201, 203, 205)) read(world, n)
        val h = store(world).hypotheses.single { it.family == HypothesisFamily.RANGE_SIGN }
        assertNotNull(h)
        assertEquals(3, h.support.size)
        assertEquals(HypothesisStatus.CONFIRMED, h.status)
    }

    @Test
    fun `every status change cites evidence and is kept in the history`() {
        val world = WorldModel()
        for (n in listOf(201, 203, 205, 207)) read(world, n)
        val s = store(world)
        for (h in s.hypotheses.filter { it.status != HypothesisStatus.PROPOSED }) {
            assertTrue(h.support.isNotEmpty() || h.against.isNotEmpty(), h.id)
            assertTrue(h.history.size >= 2, h.id)
            for (id in h.support + h.against) assertTrue(world.events.any { it.id == id }, "$id is a ledger event")
        }
    }
}
