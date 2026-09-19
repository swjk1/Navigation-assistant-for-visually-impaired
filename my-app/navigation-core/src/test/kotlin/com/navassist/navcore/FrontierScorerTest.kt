package com.navassist.navcore

import com.navassist.navcore.exploration.Frontier
import com.navassist.navcore.exploration.FrontierScorer
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.OccupancyGrid
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticDirection
import com.navassist.navcore.semantic.SemanticHintStore
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.topology.NodeType
import com.navassist.navcore.topology.TopologicalMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrontierScorerTest {

    private val config = NavigationConfig()
    private val scorer = FrontierScorer(config)
    private val userPose = Pose3D(0f, 1.4f, 0f, 0f) // standing at the origin, facing +Z

    /** An open 12 m x 12 m room centred on the user, so risk and clearance never interfere. */
    private fun openGrid(): OccupancyGrid {
        val grid = OccupancyGrid(config, originX = -6f, originZ = -6f)
        for (gz in 0 until grid.cells) for (gx in 0 until grid.cells) {
            grid.setState(gx, gz, CellState.FREE)
        }
        return grid
    }

    private fun frontierAt(grid: OccupancyGrid, x: Float, z: Float, id: String): Frontier {
        val centroid = Vec2(x, z)
        return Frontier(
            id = id,
            centroid = centroid,
            centroidCell = grid.worldToGrid(centroid),
            cellCount = 10,
            estimatedInformationGain = 0.5f,
        )
    }

    /** Spec scenario 3: a "rooms 300-349 ->" sign must beat a modestly closer alternative. */
    @Test
    fun `a semantic clue outweighs a modest distance advantage`() {
        val grid = openGrid()
        val inflated = TestSupport.inflate(grid, config)
        val topology = TopologicalMap()
        val target = NavigationTarget.Room("314")

        // Left frontier is CLOSER; right frontier is further away.
        val left = frontierAt(grid, -2f, 2f, "left")
        val right = frontierAt(grid, 3f, 2f, "right")

        val withoutSemantics = scorer.score(
            listOf(left, right), userPose, target, SemanticHintStore(config), topology, inflated, 1_000,
        )
        assertEquals("left", withoutSemantics.first().id, "without clues the nearer frontier wins")

        val semantics = SemanticHintStore(config)
        semantics.submit(
            listOf(
                SemanticObservation.RoomRange(
                    min = 300,
                    max = 349,
                    direction = SemanticDirection.RIGHT,
                    confidence = 0.9f,
                ),
            ),
            nowMillis = 1_000,
            observerPose = userPose,
            target = target,
        )

        val withSemantics = scorer.score(
            listOf(left, right), userPose, target, semantics, topology, inflated, 1_000,
        )
        assertEquals("right", withSemantics.first().id, "the sign should redirect exploration: $withSemantics")
        assertTrue(withSemantics.first { it.id == "right" }.semanticScore > 0f)
    }

    @Test
    fun `a sign that does not cover the target only weakly discourages that branch`() {
        val grid = openGrid()
        val inflated = TestSupport.inflate(grid, config)
        val target = NavigationTarget.Room("314")
        val semantics = SemanticHintStore(config)
        semantics.submit(
            listOf(
                SemanticObservation.RoomRange(100, 149, SemanticDirection.RIGHT, confidence = 0.9f),
            ),
            nowMillis = 1_000,
            observerPose = userPose,
            target = target,
        )

        val right = frontierAt(grid, 3f, 2f, "right")
        val scored = scorer.score(listOf(right), userPose, target, semantics, TopologicalMap(), inflated, 1_000)
        val semanticScore = scored.first().semanticScore
        assertTrue(semanticScore < 0f, "a mismatched sign is evidence against, score=$semanticScore")
        assertTrue(
            semanticScore > -0.5f,
            "but only weakly: signage is incomplete and the branch must stay selectable ($semanticScore)",
        )
    }

    /** A branch we already walked should lose to an equally attractive fresh one. */
    @Test
    fun `an already explored branch receives a revisit penalty`() {
        val grid = openGrid()
        val inflated = TestSupport.inflate(grid, config)
        val topology = TopologicalMap()
        val target = NavigationTarget.Explore

        val visited = frontierAt(grid, 2f, 2f, "visited")
        val fresh = frontierAt(grid, -2f, 2f, "fresh")

        repeat(4) {
            topology.addOrMerge(
                position = Vec2(2f, 2f),
                type = NodeType.WAYPOINT,
                mergeRadiusMeters = 1.6f,
                nowMillis = 1_000,
            )
        }

        val scored = scorer.score(
            listOf(visited, fresh), userPose, target, SemanticHintStore(config), topology, inflated, 1_000,
        )
        assertEquals("fresh", scored.first().id, "exploration should prefer untrodden ground: $scored")
        assertTrue(scored.first { it.id == "visited" }.revisitPenalty > 0f)
    }

    @Test
    fun `a frontier wedged against an obstacle carries a risk penalty`() {
        val grid = openGrid()
        // Wall the area around one of the frontiers.
        val wallCell = grid.worldToGrid(Vec2(2f, 2f))
        for (dz in -1..1) for (dx in -1..1) {
            grid.setState(wallCell.gx + dx, wallCell.gz + dz, CellState.OCCUPIED)
        }
        val inflated = TestSupport.inflate(grid, config)

        val risky = frontierAt(grid, 2.2f, 2f, "risky")
        val safe = frontierAt(grid, -2.2f, 2f, "safe")
        val scored = scorer.score(
            listOf(risky, safe), userPose, NavigationTarget.Explore,
            SemanticHintStore(config), TopologicalMap(), inflated, 1_000,
        )
        assertTrue(scored.first { it.id == "risky" }.riskPenalty > scored.first { it.id == "safe" }.riskPenalty)
        assertEquals("safe", scored.first().id)
    }
}
