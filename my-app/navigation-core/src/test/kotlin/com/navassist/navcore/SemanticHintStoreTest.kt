package com.navassist.navcore

import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.geometry.Vec3
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticDirection
import com.navassist.navcore.semantic.SemanticHintStore
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.semantic.TargetMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticHintStoreTest {

    private val config = NavigationConfig()
    private val origin = Pose3D(0f, 1.4f, 0f, 0f)

    // ---------------------------------------------------------------- TargetMatcher

    @Test
    fun `labels are normalised before matching`() {
        assertEquals("RM314", TargetMatcher.normalizeLabel("Rm. 314 "))
        assertEquals("B12", TargetMatcher.normalizeLabel("b-12"))
        assertEquals(314, TargetMatcher.numericPart("Room 314"))
        assertEquals(314, TargetMatcher.numericPart("B314a"))
        assertNull(TargetMatcher.numericPart("Lobby"))
    }

    @Test
    fun `a door plate matches the requested room by number`() {
        val target = NavigationTarget.Room("314")
        assertTrue(TargetMatcher.matches(SemanticObservation.Room("314", 0.9f), target))
        assertTrue(TargetMatcher.matches(SemanticObservation.Room("Room 314", 0.9f), target))
        assertTrue(!TargetMatcher.matches(SemanticObservation.Room("315", 0.9f), target))
        assertTrue(!TargetMatcher.matches(SemanticObservation.Exit(confidence = 0.9f), target))
    }

    @Test
    fun `an exit sign matches an exit request`() {
        assertTrue(
            TargetMatcher.matches(SemanticObservation.Exit(confidence = 0.8f), NavigationTarget.Exit),
        )
    }

    // ---------------------------------------------------------------- store behaviour

    @Test
    fun `a localized sighting of the target is remembered`() {
        val store = SemanticHintStore(config)
        store.submit(
            listOf(SemanticObservation.Room("314", 0.9f, worldPosition = Vec3(2f, 1.2f, 6f))),
            nowMillis = 1_000,
            observerPose = origin,
            target = NavigationTarget.Room("314"),
        )
        val sighting = store.targetSighting
        assertNotNull(sighting)
        assertEquals(Vec3(2f, 1.2f, 6f), sighting.worldPosition)
    }

    @Test
    fun `a sighting without a world position cannot become a destination`() {
        val store = SemanticHintStore(config)
        store.submit(
            // Perception saw the plate but the adapter could not associate depth with it.
            listOf(SemanticObservation.Room("314", 0.9f, normalizedX = 0.5f, normalizedY = 0.4f)),
            nowMillis = 1_000,
            observerPose = origin,
            target = NavigationTarget.Room("314"),
        )
        assertNull(store.targetSighting, "we must not route to a position we never resolved")
    }

    @Test
    fun `low confidence observations are discarded on arrival`() {
        val store = SemanticHintStore(config)
        store.submit(
            listOf(SemanticObservation.Room("314", confidence = 0.1f, worldPosition = Vec3(1f, 1f, 1f))),
            nowMillis = 1_000,
            observerPose = origin,
            target = NavigationTarget.Room("314"),
        )
        assertEquals(0, store.hintCount)
        assertNull(store.targetSighting)
    }

    @Test
    fun `hints age out so stale signage stops steering the user`() {
        val store = SemanticHintStore(config)
        store.submit(
            listOf(SemanticObservation.RoomRange(300, 349, SemanticDirection.RIGHT, 0.9f)),
            nowMillis = 1_000,
            observerPose = origin,
            target = NavigationTarget.Room("314"),
        )
        assertEquals(1, store.hintCount)

        val rightOfUser = Vec2(3f, 1f)
        assertTrue(
            store.semanticScoreFor(rightOfUser, origin, NavigationTarget.Room("314"), 1_000) > 0f,
        )

        store.prune(nowMillis = 1_000 + config.semanticMaxAgeMillis + 1)
        assertEquals(0, store.hintCount)
        assertEquals(
            0f,
            store.semanticScoreFor(rightOfUser, origin, NavigationTarget.Room("314"), 60_000),
        )
    }

    @Test
    fun `a directional sign biases the side it points at, not the opposite side`() {
        val store = SemanticHintStore(config)
        val target = NavigationTarget.Room("314")
        store.submit(
            listOf(SemanticObservation.RoomRange(300, 349, SemanticDirection.RIGHT, 0.9f)),
            nowMillis = 0,
            observerPose = origin,
            target = target,
        )
        val right = store.semanticScoreFor(Vec2(3f, 1f), origin, target, 0)
        val left = store.semanticScoreFor(Vec2(-3f, 1f), origin, target, 0)
        assertTrue(right > 0f, "right=$right")
        assertEquals(0f, left, "a right-pointing sign says nothing about the left branch")
    }

    @Test
    fun `an ascending room sequence encourages continuing in the same direction`() {
        val store = SemanticHintStore(config)
        val target = NavigationTarget.Room("319")

        // Walking along +Z past 301, 303, 305.
        listOf(301 to 0f, 303 to 2f, 305 to 4f).forEach { (number, z) ->
            store.submit(
                listOf(SemanticObservation.Room(number.toString(), 0.9f)),
                nowMillis = 0,
                observerPose = Pose3D(0f, 1.4f, z, 0f),
                target = target,
            )
        }

        val here = Pose3D(0f, 1.4f, 4f, 0f)
        val ahead = store.semanticScoreFor(Vec2(0f, 8f), here, target, 0)
        val behind = store.semanticScoreFor(Vec2(0f, 0f), here, target, 0)
        assertTrue(ahead > behind, "ahead=$ahead behind=$behind")
    }

    @Test
    fun `the number heuristic never fully eliminates the alternative`() {
        val store = SemanticHintStore(config)
        val target = NavigationTarget.Room("319")
        listOf(301 to 0f, 305 to 4f).forEach { (number, z) ->
            store.submit(
                listOf(SemanticObservation.Room(number.toString(), 0.9f)),
                nowMillis = 0,
                observerPose = Pose3D(0f, 1.4f, z, 0f),
                target = target,
            )
        }
        val behind = store.semanticScoreFor(Vec2(0f, 0f), Pose3D(0f, 1.4f, 4f, 0f), target, 0)
        assertTrue(
            behind > -1.01f,
            "room numbering is a heuristic; the other branch must stay selectable ($behind)",
        )
    }

    @Test
    fun `a floor observation is recorded for multi floor routing`() {
        val store = SemanticHintStore(config)
        assertNull(store.currentFloorId)
        store.submit(
            listOf(SemanticObservation.Floor("3", 0.9f)),
            nowMillis = 0,
            observerPose = origin,
            target = NavigationTarget.Exit,
        )
        assertEquals("3", store.currentFloorId)
    }

    @Test
    fun `changing destination clears the previous sighting`() {
        val store = SemanticHintStore(config)
        store.submit(
            listOf(SemanticObservation.Room("314", 0.9f, worldPosition = Vec3(2f, 1f, 6f))),
            nowMillis = 0,
            observerPose = origin,
            target = NavigationTarget.Room("314"),
        )
        assertNotNull(store.targetSighting)
        store.clearTargetSighting()
        assertNull(store.targetSighting)
    }
}
