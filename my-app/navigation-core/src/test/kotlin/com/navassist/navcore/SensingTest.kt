package com.navassist.navcore

import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.sensing.CameraIntrinsics
import com.navassist.navcore.sensing.CanonicalFrame
import com.navassist.navcore.sensing.DepthImage
import com.navassist.navcore.sensing.DepthSnapshotStore
import com.navassist.navcore.sensing.DepthUnprojector
import com.navassist.navcore.sensing.PlaneObservation
import com.navassist.navcore.sensing.SensorFrameAssembler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The platform-to-canonical conversion, which used to exist only inside the Android adapter and
 * so was only ever exercised on a phone. Platform world here is ARCore's: right-handed, +y up,
 * cameras look along their own -Z.
 */
class SensingTest {

    private val intrinsics = CameraIntrinsics(fx = 100f, fy = 100f, cx = 80f, cy = 60f, width = 160, height = 120)

    /** Column-major camera-to-world matrix from the camera's axes expressed in world coordinates. */
    private fun camera(
        position: FloatArray,
        xAxis: FloatArray,
        yAxis: FloatArray,
        zAxis: FloatArray,
    ) = floatArrayOf(
        xAxis[0], xAxis[1], xAxis[2], 0f,
        yAxis[0], yAxis[1], yAxis[2], 0f,
        zAxis[0], zAxis[1], zAxis[2], 0f,
        position[0], position[1], position[2], 1f,
    )

    /** A phone held landscape, looking along world -Z. */
    private fun landscapeLookingMinusZ(x: Float = 0f, y: Float = 1.4f, z: Float = 0f) = camera(
        floatArrayOf(x, y, z), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
    )

    private fun depthImage(millimetres: Int, timestampNanos: Long) = DepthImage(160, 120).also {
        it.depthMm.fill(millimetres.toShort())
        it.timestampNanos = timestampNanos
    }

    private fun unprojectAll(frame: CanonicalFrame, cameraMatrix: FloatArray, image: DepthImage): DepthPointCloud {
        val transform = FloatArray(12)
        frame.cameraToCanonical(cameraMatrix, transform)
        val cloud = DepthPointCloud.allocate(image.width * image.height)
        DepthUnprojector.unproject(image, intrinsics, transform, 1, 0.1f, 10f, cloud)
        return cloud
    }

    @Test
    fun `canonical frame puts forward on +z, right on +x and a right turn at positive yaw`() {
        val frame = CanonicalFrame()
        frame.establish(landscapeLookingMinusZ(x = 5f, z = 2f))

        val stepForward = frame.toCanonicalPose(landscapeLookingMinusZ(x = 5f, z = 1f))
        assertEquals(1f, stepForward.z, 1e-5f)
        assertEquals(0f, stepForward.x, 1e-5f)
        assertEquals(0f, stepForward.yawRadians, 1e-5f)

        assertEquals(1f, frame.toCanonicalPoint(6f, 1.4f, 2f).x, 1e-5f, "world +x is to the right")

        // Turned to look along world +X, i.e. 90 degrees to the right.
        val turnedRight = frame.toCanonicalPose(
            camera(floatArrayOf(5f, 1.4f, 2f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f)),
        )
        assertEquals(GeometryUtils.PI_F / 2f, turnedRight.yawRadians, 1e-5f)
    }

    @Test
    fun `depth unprojects forward, with the image top above and the image right to the right`() {
        val frame = CanonicalFrame()
        val pose = landscapeLookingMinusZ()
        frame.establish(pose)
        val cloud = unprojectAll(frame, pose, depthImage(2000, 1))

        fun at(u: Int, v: Int) = (v * 160 + u).let { floatArrayOf(cloud.x(it), cloud.y(it), cloud.z(it)) }
        val centre = at(80, 60)
        assertEquals(0f, centre[0], 1e-4f)
        assertEquals(0f, centre[1], 1e-4f)
        assertEquals(2f, centre[2], 1e-4f)
        assertTrue(at(80, 0)[1] > 1f, "row 0 is the top of the image")
        assertTrue(at(159, 60)[0] > 1f, "the last column is on the right")
    }

    @Test
    fun `a phone held portrait sees its sensor top row on the user's right`() {
        // Portrait with the usual 90-degree sensor: the sensor's +x points DOWN and its +y RIGHT.
        val portrait = camera(
            floatArrayOf(0f, 1.4f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f),
        )
        val frame = CanonicalFrame()
        frame.establish(portrait)
        val cloud = unprojectAll(frame, portrait, depthImage(2000, 1))

        assertEquals(0f, frame.toCanonicalPose(portrait).yawRadians, 1e-5f, "still facing forward")
        val topRow = 0 * 160 + 80
        assertTrue(cloud.x(topRow) > 1f, "sensor top edge is the user's right")
        val leftColumn = 60 * 160 + 0
        assertTrue(cloud.y(leftColumn) > 0.5f, "sensor left edge is the top of the upright screen")
    }

    @Test
    fun `a depth image is integrated once, with the pose it was captured at`() {
        val assembler = SensorFrameAssembler(NavigationConfig(depthSampleStride = 1, maxPointsPerFrame = 160 * 120))
        val first = depthImage(2000, 1_000_000_000)

        val a = assembler.assemble(1_000_000_000, true, landscapeLookingMinusZ(z = 0f), first, intrinsics)
        assertTrue(a.depthAvailable)

        // The platform hands the same image back on the next camera frame.
        val b = assembler.assemble(1_033_000_000, true, landscapeLookingMinusZ(z = -1f), first, intrinsics)
        assertFalse(b.depthAvailable, "the same observation must not be counted twice")

        // A new depth image arrives, but it was captured one frame ago, at z = -1.
        val lagging = depthImage(2000, 1_033_000_000)
        val c = assembler.assemble(1_066_000_000, true, landscapeLookingMinusZ(z = -2f), lagging, intrinsics)
        assertTrue(c.depthAvailable)
        assertEquals(2f, c.pose.z, 1e-4f, "the phone is at z = 2 now")
        assertEquals(1f, assertNotNull(c.sensorPosition).z, 1e-4f, "but the sensor was at z = 1")
        val centre = 60 * 160 + 80
        assertEquals(3f, c.points.z(centre), 1e-3f, "2 m in front of where the sensor WAS")
    }

    @Test
    fun `a pinned capture resolves long after the recent depth has moved on`() {
        var mismatches = 0
        val store = DepthSnapshotStore(NavigationConfig(), onClockMismatch = { mismatches++ })
        val frame = CanonicalFrame()
        val transform = FloatArray(12)
        fun record(timestamp: Long, z: Float) {
            val pose = landscapeLookingMinusZ(z = z)
            if (!frame.isEstablished) frame.establish(pose)
            frame.cameraToCanonical(pose, transform)
            store.record(depthImage(3000, timestamp), intrinsics, transform)
        }

        record(1_000_000_000, 0f)
        record(2_000_000_000, -1f)
        store.pin(2_000_000_000)
        for (i in 3..10) record(i * 1_000_000_000L, -i.toFloat())

        val pinned = store.resolve(0.5f, 0.5f, 2_000_000_000)
        assertEquals(1f, assertNotNull(pinned.observerPose).z, 1e-4f, "where the user stood at capture")
        assertEquals(4f, assertNotNull(pinned.worldPosition).z, 1e-2f, "3 m ahead of that")

        assertNull(store.resolve(0.5f, 0.5f, 4_500_000_000).observerPose, "no frame near that time")
        assertEquals(10f, assertNotNull(store.resolve(0.5f, 0.5f, null).observerPose).z, 1e-4f)

        // A wall-clock timestamp: logged, and the latest frame used instead.
        assertNotNull(store.resolve(0.5f, 0.5f, 1_758_000_000_000_000_000).observerPose)
        assertEquals(1, mismatches)
    }

    @Test
    fun `a landmark beyond depth range is not placed on whatever nearer pixels have depth`() {
        val store = DepthSnapshotStore(NavigationConfig())
        val frame = CanonicalFrame()
        val pose = landscapeLookingMinusZ()
        frame.establish(pose)
        val transform = FloatArray(12)
        frame.cameraToCanonical(pose, transform)

        // The sign itself is out of range (no return); only the bottom row of the window, where
        // the floor is, has depth - 2 m, a long way short of the sign.
        val image = depthImage(0, 1)
        for (u in 78..82) image.depthMm[62 * 160 + u] = 2000
        store.record(image, intrinsics, transform)
        assertNull(store.resolve(80f / 160f, 60f / 120f, 1).worldPosition, "a few floor pixels are not the sign")

        // The same sign within range: the window agrees, and it is placed.
        store.record(depthImage(4000, 2), intrinsics, transform)
        assertEquals(4f, assertNotNull(store.resolve(0.5f, 0.5f, 2).worldPosition).z, 1e-2f)
    }

    @Test
    fun `a door is not placed where its box would make it a fraction of a door's size`() {
        val assembler = SensorFrameAssembler(NavigationConfig())
        assembler.assemble(1_000_000_000, true, landscapeLookingMinusZ(), depthImage(2000, 1_000_000_000), intrinsics)

        fun door(width: Float, height: Float) = com.navassist.navcore.semantic.SemanticObservation.Door(
            confidence = 0.8f, normalizedX = 0.5f, normalizedY = 0.5f, timestampNanos = 1_000_000_000,
            normalizedWidth = width, normalizedHeight = height,
        )
        // At 2 m the whole image spans 3.2 m x 2.4 m, so a 5% box is ~16 cm - no door is that small:
        // the depth under it belongs to something nearer (the edge of the opening it was seen through).
        val sliver = com.navassist.navcore.sensing.SemanticLocator.locate(door(0.05f, 0.05f), assembler)
        assertNull(sliver.observation.worldPosition)
        assertNotNull(sliver.observerPose, "still usable as a directional hint")

        val doorSized = com.navassist.navcore.sensing.SemanticLocator.locate(door(0.3f, 0.8f), assembler)
        assertEquals(2f, assertNotNull(doorSized.observation.worldPosition).z, 1e-2f)
    }

    @Test
    fun `the floor hint is the lowest sizeable plane a held phone could be above`() {
        val assembler = SensorFrameAssembler(NavigationConfig())
        val frame = assembler.assemble(
            1, true, landscapeLookingMinusZ(y = 1.3f), null, null,
            planes = listOf(
                PlaneObservation(0f, 0.75f, -1f, 1.5f), // desk
                PlaneObservation(0f, 0f, -2f, 4f), // floor
                PlaneObservation(0f, -0.4f, -1f, 0.1f), // a speck, too small to trust
            ),
        )
        assertEquals(-1.3f, assertNotNull(frame.floorHint), 1e-4f, "canonical y is relative to the phone")
        assertTrue(frame.floorHintConfidence in 0.45f..0.8f)
    }
}
