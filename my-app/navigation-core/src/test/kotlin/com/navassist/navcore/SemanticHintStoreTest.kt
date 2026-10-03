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

    // ---------------------------------------------------------------- doors

    @Test
    fun `a door matches a door request but not an exit request`() {
        val door = SemanticObservation.Door(confidence = 0.9f)
        assertTrue(TargetMatcher.matches(door, NavigationTarget.Door))
        assertTrue(
            !TargetMatcher.matches(door, NavigationTarget.Exit),
            "a door may lead into a cupboard; it is not the exit",
        )
        assertTrue(!TargetMatcher.matches(door, NavigationTarget.Room("314")))
    }

    @Test
    fun `a door biases an exit search without deciding it`() {
        val store = SemanticHintStore(config)
        store.submit(
            listOf(SemanticObservation.Door(direction = SemanticDirection.RIGHT, confidence = 0.9f)),
            nowMillis = 0,
            observerPose = origin,
            target = NavigationTarget.Exit,
        )
        val towardsDoor = store.semanticScoreFor(Vec2(3f, 1f), origin, NavigationTarget.Exit, 0)
        val awayFromDoor = store.semanticScoreFor(Vec2(-3f, 1f), origin, NavigationTarget.Exit, 0)
        assertTrue(towardsDoor > awayFromDoor, "towards=$towardsDoor away=$awayFromDoor")
        assertTrue(towardsDoor < 1f, "a door is partial evidence only ($towardsDoor)")
    }

    @Test
    fun `a door counts for more when a door is what was asked for`() {
        fun scoreFor(target: NavigationTarget): Float {
            val store = SemanticHintStore(config)
            store.submit(
                listOf(SemanticObservation.Door(direction = SemanticDirection.RIGHT, confidence = 0.9f)),
                nowMillis = 0,
                observerPose = origin,
                target = target,
            )
            return store.semanticScoreFor(Vec2(3f, 1f), origin, target, 0)
        }
        val asDoor = scoreFor(NavigationTarget.Door)
        val asExit = scoreFor(NavigationTarget.Exit)
        val asRoom = scoreFor(NavigationTarget.Room("314"))
        assertTrue(asDoor > asExit && asExit > asRoom, "door=$asDoor exit=$asExit room=$asRoom")
        assertTrue(asRoom > 0f, "rooms sit behind doors, so it is still a nudge")
    }

    @Test
    fun `a located door becomes a destination when a door was requested`() {
        val store = SemanticHintStore(config)
        store.submit(
            listOf(SemanticObservation.Door(confidence = 0.9f, worldPosition = Vec3(1f, 1.2f, 3f))),
            nowMillis = 0,
            observerPose = origin,
            target = NavigationTarget.Door,
        )
        assertNotNull(store.targetSighting)
    }

    // ---------------------------------------------------------------- placement rules

    @Test
    fun `a washroom sign hung high is a pointer, not the destination`() {
        val store = SemanticHintStore(config)
        // Camera 1.4 m up, so the floor is at y = 0: one sign at 2.8 m, one at door height.
        store.submit(
            listOf(SemanticObservation.Room("washroom", 0.9f, worldPosition = Vec3(0f, 2.8f, 6f))),
            nowMillis = 1_000, observerPose = origin, target = NavigationTarget.Room("washroom"), cameraHeightMeters = 1.4f,
        )
        assertNull(store.targetSighting)
        store.submit(
            listOf(SemanticObservation.Room("washroom", 0.9f, worldPosition = Vec3(1f, 1.6f, 9f))),
            nowMillis = 2_000, observerPose = origin, target = NavigationTarget.Room("washroom"), cameraHeightMeters = 1.4f,
        )
        assertEquals(9f, store.targetSighting?.worldPosition?.z)
    }

    @Test
    fun `a washroom sign hung high steers exploration towards itself`() {
        val store = SemanticHintStore(config)
        val target = NavigationTarget.Room("washroom")
        store.submit(
            listOf(SemanticObservation.Room("washroom", 0.9f, worldPosition = Vec3(0f, 2.8f, 6f))),
            nowMillis = 1_000, observerPose = origin, target = target, cameraHeightMeters = 1.4f,
        )
        assertNull(store.targetSighting)
        val towardsSign = store.semanticScoreFor(Vec2(0f, 4f), origin, target, 1_000)
        val sideways = store.semanticScoreFor(Vec2(4f, 0f), origin, target, 1_000)
        assertTrue(towardsSign > 0.5f, "a frontier under the sign should be pulled: $towardsSign")
        assertEquals(0f, sideways)
    }

    @Test
    fun `a sighting measured from nearer replaces one from farther, whatever its confidence`() {
        val store = SemanticHintStore(config)
        val target = NavigationTarget.Room("314")
        store.submit(listOf(SemanticObservation.Room("314", 0.95f, worldPosition = Vec3(0f, 1.5f, 6f))), 1_000, origin, target)
        val nearer = Pose3D(0f, 1.4f, 5f, 0f)
        store.submit(listOf(SemanticObservation.Room("314", 0.6f, worldPosition = Vec3(0.5f, 1.5f, 7f))), 2_000, nearer, target)
        assertEquals(7f, store.targetSighting?.worldPosition?.z)
    }

    @Test
    fun `a plate read without depth is a glimpse - somewhere to look, once`() {
        val store = SemanticHintStore(config)
        val target = NavigationTarget.Room("314")
        val read = SemanticObservation.Room("314", 0.9f)
        store.submit(listOf(read), 1_000, origin, target, bearings = listOf(Vec2(0f, 1f)))
        val glimpse = assertNotNull(store.targetSighting)
        assertTrue(glimpse.glimpsed && glimpse.estimated)
        assertEquals(config.glimpseRangeMeters, glimpse.worldPosition.z, 1e-3f)

        // Followed up and nothing better turned up: the same reading does not send them back.
        store.spendGlimpse(3_000)
        assertNull(store.targetSighting)
        store.submit(listOf(read), 4_000, origin, target, bearings = listOf(Vec2(0f, 1f)))
        assertNull(store.targetSighting)

        // A depth-placed reading is taken as ever.
        store.submit(listOf(read.copy(worldPosition = Vec3(1f, 1.5f, 2f))), 5_000, origin, target)
        assertTrue(store.targetSighting?.glimpsed == false)
    }

    @Test
    fun `a shop name read without depth is ranged, a facility sign is not placed at all`() {
        val store = SemanticHintStore(config)
        // 20 degrees up from 1.4 m: a fascia at 4 m is about 7 m away.
        val up = kotlin.math.tan(Math.toRadians(20.0)).toFloat()
        store.submit(
            listOf(SemanticObservation.Room("Pharmacy", 0.9f)), 1_000, origin, NavigationTarget.Room("Pharmacy"),
            bearings = listOf(Vec2(0f, 1f)), elevations = listOf(up), cameraHeightMeters = 1.4f,
        )
        assertTrue(store.targetSighting?.ranged == true)
        val other = SemanticHintStore(config)
        other.submit(
            listOf(SemanticObservation.Room("washroom", 0.9f)), 1_000, origin, NavigationTarget.Room("washroom"),
            bearings = listOf(Vec2(0f, 1f)), elevations = listOf(up), cameraHeightMeters = 1.4f,
        )
        assertNull(other.targetSighting)
    }
}
