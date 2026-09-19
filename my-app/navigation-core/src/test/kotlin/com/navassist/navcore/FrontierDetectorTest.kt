package com.navassist.navcore

import com.navassist.navcore.exploration.FrontierDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrontierDetectorTest {

    private val config = TestSupport.bareConfig()
    private val detector = FrontierDetector(config)

    /** Spec scenario 1: a corridor with unexplored space straight ahead. */
    @Test
    fun `finds the single frontier at the end of a corridor`() {
        val rows = listOf(
            "##########",
            ".....?????",
            ".....?????",
            ".....?????",
            "##########",
        ) + List(5) { "??????????" }

        val frontiers = detector.detect(TestSupport.gridFromAscii(rows))

        assertEquals(1, frontiers.size, "expected exactly one opening, got $frontiers")
        val frontier = frontiers.first()
        assertEquals(3, frontier.cellCount)
        assertEquals(4, frontier.centroidCell.gx, "the frontier sits at the free/unknown boundary")
        assertTrue(frontier.estimatedInformationGain > 0f)
    }

    /** Spec scenario 2: a T junction must surface BOTH branches. */
    @Test
    fun `finds both frontiers at a T junction`() {
        val stem = "######...######"
        val bar = "???.........???"
        val wall = "###############"
        val rows = List(7) { stem } + List(3) { bar } + List(5) { wall }

        val frontiers = detector.detect(TestSupport.gridFromAscii(rows))

        assertEquals(2, frontiers.size, "both branches must be offered, got $frontiers")
        val xs = frontiers.map { it.centroidCell.gx }.sorted()
        assertEquals(listOf(3, 11), xs, "one frontier on each side of the junction")
    }

    @Test
    fun `reports no frontier in a fully explored room`() {
        val rows = listOf("##########") +
            List(8) { "#........#" } +
            listOf("##########")
        assertTrue(detector.detect(TestSupport.gridFromAscii(rows)).isEmpty())
    }

    @Test
    fun `rejects a frontier cluster smaller than the noise threshold`() {
        val rows = listOf(
            "##########",
            ".....?????",
            "##########",
        ) + List(7) { "##########" }

        // One lonely frontier cell: speckle in the depth map, not a doorway.
        assertTrue(
            detector.detect(TestSupport.gridFromAscii(rows)).isEmpty(),
            "a single-cell frontier must be rejected as noise",
        )
    }

    @Test
    fun `information gain is higher for a frontier facing more unknown space`() {
        val wideOpening = listOf(
            "##########",
            "..????????",
            "..????????",
            "..????????",
            "..????????",
            "..????????",
            "..????????",
            "..????????",
            "..????????",
            "##########",
        )
        val narrowOpening = listOf(
            "##########",
            "..????????",
            "..????????",
            "..????????",
            "#########.",
            "#########.",
            "##########",
            "##########",
            "##########",
            "##########",
        )
        val wide = detector.detect(TestSupport.gridFromAscii(wideOpening)).maxOf { it.estimatedInformationGain }
        val narrow = detector.detect(TestSupport.gridFromAscii(narrowOpening)).maxOf { it.estimatedInformationGain }
        assertTrue(wide >= narrow, "wide=$wide narrow=$narrow")
    }

    @Test
    fun `frontier centroid always lands on an actual frontier cell`() {
        // An L-shaped cluster: its arithmetic centroid lands on an UNKNOWN cell, so handing that
        // centroid to the planner unchanged would mean aiming at a cell inside a wall.
        val rows = listOf(
            "##########",
            "...???????",
            "...???????",
            "...???????",
            ".......???",
            "##########",
        ) + List(4) { "##########" }
        val grid = TestSupport.gridFromAscii(rows)
        val frontiers = detector.detect(grid)
        assertTrue(frontiers.isNotEmpty())
        for (frontier in frontiers) {
            val cell = frontier.centroidCell
            assertEquals(
                com.navassist.navcore.mapping.CellState.FREE,
                grid.stateAt(cell),
                "centroid $cell must be reachable free space",
            )
        }
    }
}
