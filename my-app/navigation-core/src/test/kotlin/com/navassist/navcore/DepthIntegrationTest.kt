package com.navassist.navcore

import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.geometry.Vec3
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.semantic.NavigationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How depth becomes map evidence. Each test here failed against the previous integration, which
 * flattened every ray to 2D, cleared the whole line, and applied evidence once per ray.
 */
class DepthIntegrationTest {

    private val deviceHeight = 1.3f
    private var clock = 0L

    private fun frame(
        cloud: DepthPointCloud,
        pose: Pose3D = Pose3D(0f, deviceHeight, 0f, 0f),
        sensorPosition: Vec3? = null,
    ): NavigationFrame {
        clock += 33_000_000
        return NavigationFrame(
            timestampNanos = clock,
            pose = pose,
            points = cloud,
            trackingConfidence = 1f,
            floorHint = 0f,
            floorHintConfidence = 0.8f,
            sensorPosition = sensorPosition,
        )
    }

    private fun startedEngine() = NavigationEngine(TestSupport.fastScanConfig).also { it.start(NavigationTarget.Explore) }

    @Test
    fun `a low obstacle survives rays aimed at the wall above it`() {
        // A 0.5 m box 1.5 m ahead with a wall 4 m ahead. From 1.3 m up, far more of the image is
        // wall above the box than box - and every one of those rays crosses the box's cell.
        val cloud = DepthPointCloud.allocate(4000)
        var x = -0.3f
        while (x <= 0.3f) {
            var h = 0.12f
            while (h <= 0.5f) { cloud.add(x, h, 1.52f); h += 0.04f }
            h = 0.55f
            while (h <= 2.0f) { cloud.add(x, h, 4.0f); h += 0.05f }
            var z = 0.5f
            while (z < 1.45f) { cloud.add(x, 0f, z); z += 0.1f }
            x += 0.03f
        }
        val engine = startedEngine()
        repeat(30) { engine.updateFrame(frame(cloud)) }

        val grid = engine.occupancyGrid()
        assertEquals(CellState.OCCUPIED, grid.stateAtWorld(Vec2(0f, 1.52f)), "the box must stay on the map")
        assertEquals(CellState.OCCUPIED, grid.stateAtWorld(Vec2(0f, 4.0f)))
        assertEquals(CellState.FREE, grid.stateAtWorld(Vec2(0f, 1.2f)), "the floor in front of it is seen")
        assertEquals(
            CellState.UNKNOWN,
            grid.stateAtWorld(Vec2(0f, 3.0f)),
            "floor hidden behind the box was never seen and must not be assumed free",
        )
    }

    @Test
    fun `one frame is one observation however many rays cross a cell`() {
        val grid = OccupancyGrid(cells = 40, resolution = 0.1f)
        repeat(50) { grid.observeFreeLine(Vec2(0.05f, 0.05f), Vec2(3.05f, 0.05f)) }
        grid.commitObservation()
        assertEquals(-0.45f, grid.logOddsAt(10, 0), 1e-6f)
    }

    @Test
    fun `an obstacle return beats floor seen in the same cell in the same frame`() {
        val grid = OccupancyGrid(cells = 40, resolution = 0.1f)
        grid.observeFree(10, 10)
        grid.observeOccupied(10, 10)
        grid.observeFree(10, 10)
        grid.commitObservation()
        assertEquals(0.85f, grid.logOddsAt(10, 10), 1e-6f)
    }

    @Test
    fun `an established obstacle needs several looks at the floor before it clears`() {
        val grid = OccupancyGrid(NavigationConfig(gridSizeMeters = 4f), 0f, 0f)
        repeat(6) { grid.observeOccupied(10, 10); grid.commitObservation() }
        assertEquals(CellState.OCCUPIED, grid.stateAt(10, 10))

        var looks = 0
        while (grid.stateAt(10, 10) == CellState.OCCUPIED) {
            grid.observeFree(10, 10)
            grid.commitObservation()
            looks++
        }
        // At full strength seven misses would clear it; a wall seen only at a shallow angle
        // loses that race. It must still clear - a moved chair is not forever.
        assertTrue(looks in 15..30, "took $looks looks")
    }

    @Test
    fun `free space is carved from where the sensor was, not where the phone is now`() {
        // Floor returns 2 m ahead of a sensor that stood at x = 1 when the depth was captured.
        val cloud = DepthPointCloud.allocate(64)
        for (i in 0 until 10) cloud.add(1f, 0f, 2f + i * 0.1f)
        val engine = startedEngine()
        repeat(3) {
            engine.updateFrame(
                frame(cloud, pose = Pose3D(-1f, deviceHeight, 0f, 0f), sensorPosition = Vec3(1f, deviceHeight, 0f)),
            )
        }
        val grid = engine.occupancyGrid()
        assertEquals(CellState.FREE, grid.stateAtWorld(Vec2(1f, 2.4f)))
        assertEquals(CellState.UNKNOWN, grid.stateAtWorld(Vec2(0f, 1.2f)), "no line from the phone's current position")
    }

    @Test
    fun `the user's own footprint is free but never wears away a wall beside them`() {
        val engine = startedEngine()
        val grid = engine.occupancyGrid()
        // First frame positions the window; then a wall is known 20 cm to the user's left.
        engine.updateFrame(frame(DepthPointCloud.EMPTY))
        val wall = grid.worldToGrid(Vec2(-0.2f, 0f))
        grid.setState(wall.gx, wall.gz, CellState.OCCUPIED)

        repeat(60) { engine.updateFrame(frame(DepthPointCloud.EMPTY)) }

        assertEquals(CellState.FREE, grid.stateAtWorld(Vec2(0.1f, 0.1f)), "the user is standing here")
        assertEquals(CellState.OCCUPIED, grid.stateAt(wall.gx, wall.gz), "out of view is not evidence")
    }
}
