package com.navassist.navcore

import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.PlannerHypothesis
import com.navassist.navcore.semantic.SemanticHintStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerHypothesisTest {

    private val config = NavigationConfig()
    private val user = Vec2(0f, 0f)
    private val east = Vec2(10f, 0f)
    private val west = Vec2(-10f, 0f)

    private fun storeWith(vararg guesses: PlannerHypothesis) = SemanticHintStore(config).apply { setPlan(guesses.toList(), 0L) }

    @Test
    fun `no plan, no pull`() {
        assertEquals(0f, SemanticHintStore(config).planScoreFor(east, user, 0L))
    }

    @Test
    fun `frontiers towards the guess score above those away from it`() {
        val store = storeWith(PlannerHypothesis(Vec2(30f, 0f), 3f, 1f))
        val towards = store.planScoreFor(east, user, 0L)
        val away = store.planScoreFor(west, user, 0L)
        assertTrue(towards > 0.9f, "towards: $towards")
        assertEquals(0f, away)
    }

    @Test
    fun `a guess fades with age`() {
        val store = storeWith(PlannerHypothesis(Vec2(30f, 0f), 3f, 1f))
        val fresh = store.planScoreFor(east, user, 0L)
        val old = store.planScoreFor(east, user, config.plannerFadeMillis)
        assertEquals(fresh / kotlin.math.E.toFloat(), old, 0.02f)
    }

    @Test
    fun `a searched guess is spent, and stays spent when repeated`() {
        val guess = PlannerHypothesis(Vec2(30f, 0f), 3f, 1f)
        val store = storeWith(guess)
        store.noteSearched(Vec2(29f, 0f))
        assertEquals(0f, store.planScoreFor(east, user, 0L))
        store.setPlan(listOf(guess), 1000L)
        assertEquals(0f, store.planScoreFor(east, user, 1000L))
    }

    @Test
    fun `near the guess, frontiers close to it win`() {
        val store = storeWith(PlannerHypothesis(Vec2(4f, 0f), 2f, 1f))
        val near = store.planScoreFor(Vec2(5f, 1f), user, 0L)
        val far = store.planScoreFor(Vec2(-6f, 0f), user, 0L)
        assertTrue(near > far, "near $near, far $far")
    }
}
