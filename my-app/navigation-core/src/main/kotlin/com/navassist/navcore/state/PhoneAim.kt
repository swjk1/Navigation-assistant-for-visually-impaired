package com.navassist.navcore.state

/**
 * How the user should hold the phone, on top of the walking instruction - so the camera sees
 * what the destination's sign needs seen. A shop's name is on a fascia 4 m up, out of a phone
 * tilted at the floor; a room's plate is on the side wall, out of a phone pointed down the
 * corridor. Null in the snapshot means the usual hold: upright, pointing ahead, tilted a little
 * down at the floor.
 *
 * @param pitchDegrees the angle above horizontal to hold the camera at (negative = down), or
 *        null for the usual tilt.
 * @param yawDegrees where to point it relative to the way the user faces, positive = right.
 * @param sweepDegrees when above zero, sweep slowly left and right this far while walking.
 */
data class PhoneAim(
    val pitchDegrees: Float? = null,
    val yawDegrees: Float = 0f,
    val sweepDegrees: Float = 0f,
)
