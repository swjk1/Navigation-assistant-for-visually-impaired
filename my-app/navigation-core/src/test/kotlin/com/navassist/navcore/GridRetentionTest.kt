package com.navassist.navcore

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.OccupancyGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How long the map remembers somewhere the user has walked past and is no longer looking at.
 *
 * The engine decays the WHOLE grid every frame, including cells far outside the depth sensor's
 * range and cells behind the user. Nothing outside the current view can gain evidence to offset
 * that, so any time-based fade applied to decided cells is one-way: it is what users perceived
 * as the map keeping only the newest scans. Decay is therefore limited to cells that never
 * reached FREE or OCCUPIED, and confirmed map data is overturned only by re-observation or by
 * scrolling out of the rolling window.
 *
 * Each test states the elapsed wall-clock time a real walk would take, so a regression shows up
 * as "the corridor behind me vanished after N seconds" rather than as a log-odds number.
 */
class GridRetentionTest {

    private val config = NavigationConfig()

    private fun grid() = OccupancyGrid(config, originX = 0f, originZ = 0f)

    /** Walks [seconds] forward at 30 fps, the way the engine does: decay every frame. */
    private fun OccupancyGrid.elapse(seconds: Float, fps: Int = 30) {
        val step = 1f / fps
        repeat((seconds * fps).toInt()) { applyDecay(step) }
    }

    /** Observations needed before a cell first reports [state]. */
    private fun observationsToReach(state: CellState): Int {
        val g = grid()
        var n = 0
        while (g.stateAt(1, 1) != state && n < 100) {
            if (state == CellState.OCCUPIED) g.markOccupied(1, 1) else g.markFree(1, 1)
            n++
        }
        return n
    }

    @Test
    fun `free space survives a minute of walking without being re-observed`() {
        val g = grid()
        // A patch of corridor floor the user swept past: seen well enough to be called FREE,
        // but not stared at. Two passes of the depth cloud is a realistic edge-of-scan sample.
        repeat(2) { g.markFree(10, 10) }
        assertEquals(CellState.FREE, g.stateAt(10, 10), "should start out known-free")

        g.elapse(seconds = 60f)

        assertEquals(
            CellState.FREE,
            g.stateAt(10, 10),
            "corridor the user walked down a minute ago must still be on the map"
        )
    }

    @Test
    fun `a scanned wall survives a minute of walking without being re-observed`() {
        val g = grid()
        repeat(2) { g.markOccupied(10, 10) }
        assertEquals(CellState.OCCUPIED, g.stateAt(10, 10))

        g.elapse(seconds = 60f)

        assertEquals(
            CellState.OCCUPIED,
            g.stateAt(10, 10),
            "a wall does not stop existing because the user looked away"
        )
    }

    @Test
    fun `a well-observed area survives several minutes`() {
        val g = grid()
        // What the middle of a proper scan looks like: saturated evidence.
        repeat(20) { g.markFree(10, 10) }
        repeat(20) { g.markOccupied(20, 20) }

        g.elapse(seconds = 300f)

        assertEquals(CellState.FREE, g.stateAt(10, 10), "scanned floor lost after 5 minutes")
        assertEquals(CellState.OCCUPIED, g.stateAt(20, 20), "scanned wall lost after 5 minutes")
    }

    @Test
    fun `undecided speckle still clears quickly`() {
        val g = grid()
        // One stray depth return, never confirmed. This is the evidence decay exists to remove:
        // it never reached a decided state, so it is noise rather than map.
        g.markOccupied(10, 10)
        assertEquals(CellState.UNKNOWN, g.stateAt(10, 10), "one hit alone is not an obstacle")

        g.elapse(seconds = 10f)

        assertTrue(
            kotlin.math.abs(g.logOddsAt(10, 10)) < 0.1f,
            "unconfirmed speckle should fade towards zero, was ${g.logOddsAt(10, 10)}"
        )
    }

    @Test
    fun `a dynamic obstacle is cleared by looking through it, not by waiting`() {
        val g = grid()
        // Someone stands in the corridor and is seen properly.
        repeat(10) { g.markOccupied(10, 10) }
        assertEquals(CellState.OCCUPIED, g.stateAt(10, 10))

        // They walk away. The user keeps looking at that spot, so rays now pass THROUGH it.
        // This - not the passage of time - is what makes the map let go of a moved obstacle,
        // and it has to work promptly enough not to route a blind user around a ghost.
        repeat(15) { g.markFree(10, 10) }

        assertEquals(
            CellState.FREE,
            g.stateAt(10, 10),
            "re-observing empty space must clear a departed obstacle"
        )
    }

    @Test
    fun `the line between noise and map is the decided state itself`() {
        // Decay exists to clear unconfirmed returns. If a cell could be called FREE or OCCUPIED
        // while still being decayed as if it were noise, ordinary map data would spend its life
        // on the fast-decay path and the grid could not hold a room in memory - which is exactly
        // how it came to show only the newest scans.
        val toFree = observationsToReach(CellState.FREE)
        val toOccupied = observationsToReach(CellState.OCCUPIED)
        assertTrue(toOccupied in 1..4, "occupied should be reachable from a few hits, was $toOccupied")

        val g = grid()
        repeat(toFree) { g.markFree(2, 2) }
        repeat(toOccupied) { g.markOccupied(3, 3) }
        val freeEvidence = g.logOddsAt(2, 2)
        val occupiedEvidence = g.logOddsAt(3, 3)

        g.elapse(seconds = 60f)

        assertEquals(
            freeEvidence,
            g.logOddsAt(2, 2),
            "a cell is decayed despite having just been decided FREE from $toFree observations"
        )
        assertEquals(
            occupiedEvidence,
            g.logOddsAt(3, 3),
            "a cell is decayed despite having just been decided OCCUPIED from $toOccupied hits"
        )
    }

    // ------------------------------------------------------------------ whole-engine behaviour

    /** Decided cells inside a world-space rectangle - "how much of this area is on the map". */
    private fun OccupancyGrid.decidedCellsIn(
        minX: Float,
        maxX: Float,
        minZ: Float,
        maxZ: Float,
    ): Int {
        var decided = 0
        for (gz in 0 until cells) {
            for (gx in 0 until cells) {
                if (stateAt(gx, gz) == CellState.UNKNOWN) continue
                val world = gridToWorld(gx, gz)
                if (world.x in minX..maxX && world.z in minZ..maxZ) decided++
            }
        }
        return decided
    }

    /**
     * The symptom as the user actually reported it, driven through the real engine rather than
     * the grid alone: scan a stretch of corridor, then turn away from it and keep walking the
     * phone for half a minute. What was scanned has to still be on the map the phone draws.
     */
    @Test
    fun `the engine keeps a scanned corridor after the user turns away from it`() {
        val world = SyntheticWorld.corridor()
        val engine = NavigationEngine(TestSupport.fastScanConfig)
        engine.start()

        var clock = 0L
        fun step(yawDegrees: Float) {
            clock += 100L * 1_000_000L // 10 Hz
            val pose = Pose3D(0f, 1.4f, 0f, GeometryUtils.degreesToRadians(yawDegrees))
            engine.updateFrame(world.frame(clock, pose))
        }

        // Scan the corridor ahead of the user (+Z).
        repeat(20) { step(0f) }
        val scanned = engine.occupancyGrid().decidedCellsIn(-1f, 1f, 1.5f, 4f)
        assertTrue(scanned > 50, "the corridor ahead should be mapped first, only $scanned cells were")

        // Turn around and keep the session running for 30 s. Nothing re-observes the corridor.
        repeat(300) { step(180f) }

        val remaining = engine.occupancyGrid().decidedCellsIn(-1f, 1f, 1.5f, 4f)
        assertTrue(
            remaining >= scanned * 9 / 10,
            "the scanned corridor faded once the user looked away: $scanned cells -> $remaining"
        )
    }
}
