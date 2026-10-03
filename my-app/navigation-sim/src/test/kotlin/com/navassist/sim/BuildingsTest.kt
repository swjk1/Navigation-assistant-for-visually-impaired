package com.navassist.sim

import com.navassist.sim.world.Material
import com.navassist.sim.world.Mall
import com.navassist.sim.world.School
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The multi-storey buildings: the storey walked is at height 0, and the rest is where it should be. */
class BuildingsTest {

    // Scene queries take WORLD x/z, where z is minus plan y.

    @Test
    fun `on a middle floor of the school the stairs up are in the way and the stairs down are a drop`() {
        val scene = School.build(2)
        assertTrue(scene.hasFloorAt(10f, -1.5f), "corridor floor")
        assertTrue(scene.hasFloorAt(-0.8f, -1.5f), "stair landing")
        // West stairwell, 2.5 m in: the flight up on the north side, the flight down on the south.
        assertNotNull(scene.obstacleTouching(-2.5f, -2.3f, 0.1f), "flight up")
        assertFalse(scene.hasFloorAt(-2.5f, -0.7f), "flight down")
        assertNull(scene.obstacleTouching(-2.5f, -0.7f, 0.1f), "flight down is a drop, not a wall")
    }

    @Test
    fun `on the top floor of the school nothing goes up`() {
        val scene = School.build(School.FLOORS)
        assertTrue(scene.hasFloorAt(-2.5f, -2.3f))
        assertNull(scene.obstacleTouching(-2.5f, -2.5f, 0.1f))
    }

    @Test
    fun `only the storey walked can be walked into`() {
        for (floor in 1..School.FLOORS) {
            val scene = School.build(floor)
            assertTrue(scene.obstacles.all { it.minY < 3f && it.maxY > 0f }, "floor $floor")
            if (floor < School.FLOORS) assertEquals(School.STOREY_HEIGHT - 0.1f, scene.ceilingHeight, 1e-4f)
        }
    }

    @Test
    fun `the mall atrium is a drop behind glass upstairs and open floor downstairs`() {
        val upper = Mall.build(2)
        assertFalse(upper.hasFloorAt(25f, -7f))
        assertEquals(Material.GLASS, upper.obstacleTouching(25f, -5f, 0.1f)?.material)
        val ground = Mall.build(1)
        assertTrue(ground.hasFloorAt(25f, -7f))
        assertNull(ground.obstacleTouching(25f, -5f, 0.1f))
    }

    @Test
    fun `shared walls are kept once`() {
        for (scene in listOf(School.build(1), Mall.build(2))) {
            val solids = scene.boxes.filter { !it.isFloor }
                .map { listOf(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ, it.material) }
            assertEquals(solids.size, solids.toSet().size, "${scene.name} has duplicate boxes")
        }
    }
}
