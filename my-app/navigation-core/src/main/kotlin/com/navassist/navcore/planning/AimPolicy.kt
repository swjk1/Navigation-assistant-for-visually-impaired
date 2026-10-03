package com.navassist.navcore.planning

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.state.NavigationStatus
import com.navassist.navcore.state.PhoneAim

/**
 * Decides how the user should hold the phone to find the destination (see [PhoneAim]).
 *
 * The aim depends on what is being looked for, not on the moment-to-moment command, so it changes
 * rarely: each change is spoken, and "tilt up" after every turn would bury the directions.
 *
 *  - A place known by name (a shop): its sign is high over the entrance. Tilted at the floor, the
 *    phone brings it into frame only from 8.5 m or more away; held higher, from much closer.
 *  - A numbered room while it has not been found: the plates are on the side walls, legible from
 *    only a metre or so of the corridor with the phone pointed ahead. Sweeping the phone side to
 *    side while walking passes each one through the frame.
 */
class AimPolicy(private val config: NavigationConfig) {

    fun aimFor(target: NavigationTarget, status: NavigationStatus): PhoneAim? {
        val moving = status == NavigationStatus.EXPLORING || status == NavigationStatus.NAVIGATING ||
            status == NavigationStatus.BACKTRACKING || status == NavigationStatus.NO_ROUTE
        if (!moving) return null
        val room = target as? NavigationTarget.Room ?: return null
        if (room.number == null && room.normalizedLabel != "WASHROOM") {
            return config.namedPlaceAimPitchDegrees?.let { PhoneAim(pitchDegrees = it) }
        }
        if (room.number != null && status != NavigationStatus.NAVIGATING && config.roomSearchSweepDegrees > 0f) {
            return PhoneAim(sweepDegrees = config.roomSearchSweepDegrees)
        }
        return null
    }
}
