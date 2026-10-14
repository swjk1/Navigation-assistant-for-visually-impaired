package com.navassist.navcore

import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.RoomNumberModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomNumberModelTest {

    /**
     * A corridor along +z, 2 m wide: odd rooms on the west wall (x = -1), even on the east
     * (x = +1), two numbers every 3 m - 301 and 302 at z = 0, 303 and 304 at z = 3, and so on.
     */
    private fun plate(number: Int): Pair<Int, Vec2> {
        val z = (number - 301) / 2 * 3f
        val x = if (number % 2 != 0) -1f else 1f
        return number to Vec2(x, z)
    }

    private fun modelWith(vararg numbers: Int) = RoomNumberModel().apply {
        for (n in numbers) plate(n).let { (number, position) -> add(number, position) }
    }

    @Test
    fun `fewer than three plates give no estimate`() {
        assertNull(modelWith(301, 303).estimate(319))
    }

    @Test
    fun `the target is placed further along the run`() {
        val estimate = assertNotNull(modelWith(301, 302, 303, 304, 305).estimate(319))
        // 319 is nine steps of 3 m from 301: z = 27.
        assertEquals(27f, estimate.position.z, 1.5f)
        assertTrue(estimate.rising.z > 0.9f, "numbers rise along +z, got ${estimate.rising}")
        assertTrue(estimate.confidence > 0.5f, "a clean run should be trusted, got ${estimate.confidence}")
    }

    @Test
    fun `odd and even walls put the target on its side`() {
        val model = modelWith(301, 302, 303, 304, 305, 306)
        val odd = assertNotNull(model.estimate(319))
        val even = assertNotNull(model.estimate(320))
        assertTrue(odd.sideKnown && even.sideKnown)
        assertTrue(odd.position.x < 0f, "odd numbers are on the west wall, got ${odd.position}")
        assertTrue(even.position.x > 0f, "even numbers are on the east wall, got ${even.position}")
    }

    @Test
    fun `a target behind is placed behind`() {
        val estimate = assertNotNull(modelWith(311, 313, 315, 317).estimate(303))
        assertTrue(estimate.position.z < plate(311).second.z, "303 comes before 311, got ${estimate.position}")
    }

    @Test
    fun `one misread plate does not wreck the fit`() {
        val model = modelWith(301, 303, 305, 307)
        // 309 read as 389.
        model.add(389, plate(309).second)
        val estimate = assertNotNull(model.estimate(319))
        assertEquals(27f, estimate.position.z, 3f)
    }

    @Test
    fun `plates from another corridor are left out`() {
        val model = modelWith(301, 303, 305)
        // A far corridor, numbered the other way.
        model.add(150, Vec2(60f, 0f))
        model.add(152, Vec2(60f, -3f))
        model.add(154, Vec2(60f, -6f))
        val estimate = assertNotNull(model.estimate(160))
        assertTrue(estimate.position.x > 50f, "should use the corridor it is in now, got ${estimate.position}")
    }

    @Test
    fun `the same plate read twice counts once`() {
        val model = modelWith(301, 303)
        model.add(303, plate(303).second + Vec2(0.3f, 0.2f))
        assertNull(model.estimate(319))
    }

    @Test
    fun `a target in another hundreds block is likely on another floor`() {
        val model = modelWith(301, 303, 305)
        assertTrue(model.targetLikelyOnAnotherFloor(214))
        assertFalse(model.targetLikelyOnAnotherFloor(319))
    }

    @Test
    fun `numbers that do not change along the corridor give no estimate`() {
        val model = RoomNumberModel()
        model.add(310, Vec2(0f, 0f))
        model.add(310, Vec2(0f, 5f))
        model.add(310, Vec2(0f, 10f))
        assertNull(model.estimate(319))
    }
}
