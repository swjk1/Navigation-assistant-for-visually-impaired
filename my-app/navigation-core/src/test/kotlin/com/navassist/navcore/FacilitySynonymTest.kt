package com.navassist.navcore

import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.semantic.TargetMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FacilitySynonymTest {

    private fun room(label: String) = SemanticObservation.Room(label, 0.9f, null, null, 0L)

    @Test
    fun `every name for a washroom is the same place`() {
        for (name in listOf("Washroom", "Restrooms", "Men's toilets", "Bathroom", "Lavatories", "WC")) {
            assertEquals(TargetMatcher.WASHROOM, TargetMatcher.normalizeLabel(name), name)
        }
    }

    @Test
    fun `a request for a restroom matches a washroom sign`() {
        assertTrue(TargetMatcher.matches(room("washroom"), NavigationTarget.Room("restroom")))
        assertTrue(TargetMatcher.matches(room("TOILETS"), NavigationTarget.Room("washroom")))
    }

    @Test
    fun `other names are left alone`() {
        assertEquals("PHARMACY", TargetMatcher.normalizeLabel("Pharmacy"))
        assertFalse(TargetMatcher.matches(room("Pharmacy"), NavigationTarget.Room("restroom")))
    }
}
