package com.navassist.navcore

import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.geometry.Vec3
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.world.EvidenceKind
import com.navassist.navcore.world.LandmarkKind
import com.navassist.navcore.world.Placement
import com.navassist.navcore.world.WorldModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldModelTest {

    private val observer = Pose3D(0f, 1.3f, 0f, 0f)

    private fun plate(label: String, x: Float, z: Float) =
        SemanticObservation.Room(label, 0.9f, worldPosition = Vec3(x, 1.5f, z))

    @Test
    fun `repeated readings of one plate are one landmark`() {
        val world = WorldModel()
        world.record(plate("218", 1f, 5f), 0, observer, null)
        world.record(plate("218", 1.2f, 5.1f), 1000, observer, null)
        world.record(plate("218", 0.9f, 4.9f), 2000, observer, null)
        assertEquals(1, world.landmarks.size)
        val landmark = world.landmarks.single()
        assertEquals(LandmarkKind.PLATE, landmark.kind)
        assertEquals("218", landmark.label)
        assertEquals(3, landmark.timesSeen)
        assertEquals(3, world.events.size)
    }

    @Test
    fun `an OCR misread is outvoted, not a new landmark`() {
        val world = WorldModel()
        world.record(plate("218", 1f, 5f), 0, observer, null)
        world.record(plate("21B", 1f, 5f), 1000, observer, null)
        world.record(plate("218", 1f, 5f), 2000, observer, null)
        val landmark = world.landmarks.single()
        assertEquals("218", landmark.label)
        assertEquals(1, landmark.conflictingReads)
    }

    @Test
    fun `different plates and different kinds stay apart`() {
        val world = WorldModel()
        world.record(plate("217", -1f, 5f), 0, observer, null)
        world.record(plate("218", 1f, 5f), 0, observer, null)
        world.record(SemanticObservation.Door(confidence = 0.8f, worldPosition = Vec3(1f, 1f, 5f)), 0, observer, null)
        assertEquals(3, world.landmarks.size)
    }

    @Test
    fun `an unread plate joins the plate later read there, and keeps no label of its own`() {
        val world = WorldModel()
        world.record(SemanticObservation.UnreadSign(plate = true, confidence = 0.6f, worldPosition = Vec3(1f, 1.5f, 5f)), 0, observer, null)
        assertNull(world.landmarks.single().label)
        world.record(plate("218", 1.1f, 5f), 1000, observer, null)
        assertEquals(1, world.landmarks.size)
        assertEquals("218", world.landmarks.single().label)
        assertEquals(EvidenceKind.UNREAD_PLATE, world.events.first().kind)
    }

    @Test
    fun `bearing-only readings are evidence without a landmark`() {
        val world = WorldModel()
        val event = world.record(SemanticObservation.Room("218", 0.9f), 0, observer, Vec2(1f, 0f))
        assertEquals(Placement.BEARING_ONLY, event.placement)
        assertNull(event.landmarkId)
        assertEquals(0, world.landmarks.size)
    }

    @Test
    fun `replaying the ledger rebuilds the same landmarks`() {
        val world = WorldModel()
        val readings = listOf(
            plate("201", -1f, 0f), plate("202", 1f, 0f), plate("201", -1.1f, 0.1f),
            plate("203", -1f, 3f), plate("2O3", -1f, 3.05f), plate("204", 1f, 3f),
        )
        readings.forEachIndexed { i, o -> world.record(o, i * 500L, observer, null) }
        val again = WorldModel.replay(world.events)
        assertEquals(
            world.landmarks.map { Triple(it.id, it.label, it.timesSeen) },
            again.landmarks.map { Triple(it.id, it.label, it.timesSeen) },
        )
        assertEquals(world.events.map { it.landmarkId }, again.events.map { it.landmarkId })
    }
}
