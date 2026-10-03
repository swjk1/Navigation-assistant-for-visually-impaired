package com.navassist.navcore

import com.navassist.navcore.planning.AimPolicy
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.state.NavigationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AimPolicyTest {

    @Test
    fun `looking for a shop, the phone is tilted up to its fascia`() {
        val config = NavigationConfig()
        val aim = AimPolicy(config).aimFor(NavigationTarget.Room("Pharmacy"), NavigationStatus.EXPLORING)
        assertEquals(config.namedPlaceAimPitchDegrees, aim?.pitchDegrees)
    }

    @Test
    fun `the usual hold for numbered rooms, facilities, exits, and before setting off`() {
        val policy = AimPolicy(NavigationConfig())
        assertNull(policy.aimFor(NavigationTarget.Room("314"), NavigationStatus.EXPLORING))
        assertNull(policy.aimFor(NavigationTarget.Room("washroom"), NavigationStatus.EXPLORING))
        assertNull(policy.aimFor(NavigationTarget.Exit, NavigationStatus.EXPLORING))
        assertNull(policy.aimFor(NavigationTarget.Room("Pharmacy"), NavigationStatus.LOCALIZING))
    }

    @Test
    fun `a room search sweeps when configured to, and stops once the room is found`() {
        val policy = AimPolicy(NavigationConfig(roomSearchSweepDegrees = 30f))
        assertEquals(30f, policy.aimFor(NavigationTarget.Room("314"), NavigationStatus.EXPLORING)?.sweepDegrees)
        assertNull(policy.aimFor(NavigationTarget.Room("314"), NavigationStatus.NAVIGATING))
    }
}
