package com.navassist.sim.sensor

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the user is, in floor-plan coordinates (x east, y north), and which way their BODY faces:
 * 0 = north, positive = clockwise (a turn to the right) - the same sign convention the engine
 * uses for yaw.
 */
data class BodyPose(val x: Float, val y: Float, val heading: Float)

/** How the phone is held relative to the body. */
data class PhoneHold(
    /** Height of the camera above the floor. */
    val heightMeters: Float = 1.3f,
    /** How far in front of the body's centre the phone is held. */
    val reachMeters: Float = 0.3f,
    /** Portrait is how the app is used; landscape exists to test the rotation handling. */
    val portrait: Boolean = true,
)

/**
 * Builds the camera-to-world matrix an AR platform would report for the PHYSICAL camera - sensor
 * axes, looking along its own -Z - for a phone held by a person.
 *
 * The phone's direction can differ from the body's ([phoneYaw] is absolute, like [BodyPose.heading]):
 * people sweep a phone while standing still, which is exactly when the engine asks them to.
 */
object PhoneRig {

    /** @param pitchDown radians below horizontal; positive tilts the camera towards the floor. */
    fun cameraToWorld(
        body: BodyPose,
        phoneYaw: Float,
        pitchDown: Float,
        hold: PhoneHold,
        out: FloatArray,
    ): FloatArray {
        // Plan -> world: x stays x, north (+y) becomes -z.
        val bodyForwardX = sin(body.heading)
        val bodyForwardZ = -cos(body.heading)
        val px = body.x + bodyForwardX * hold.reachMeters
        val py = hold.heightMeters
        val pz = -body.y + bodyForwardZ * hold.reachMeters

        // Viewing direction: the phone's yaw, pitched down.
        val cp = cos(pitchDown)
        val sp = sin(pitchDown)
        val fx = sin(phoneYaw) * cp
        val fy = -sp
        val fz = -cos(phoneYaw) * cp
        // Right stays horizontal (no roll).
        val rx = cos(phoneYaw)
        val ry = 0f
        val rz = sin(phoneYaw)
        // Up of the view = right x forward.
        val ux = ry * fz - rz * fy
        val uy = rz * fx - rx * fz
        val uz = rx * fy - ry * fx

        // Camera axes. Landscape: +x right, +y up. Portrait with the usual 90-degree sensor: the
        // sensor's +x points DOWN the screen and its +y to the RIGHT. -Z always looks forward.
        val xAxis = if (hold.portrait) floatArrayOf(-ux, -uy, -uz) else floatArrayOf(rx, ry, rz)
        val yAxis = if (hold.portrait) floatArrayOf(rx, ry, rz) else floatArrayOf(ux, uy, uz)
        val zAxis = floatArrayOf(-fx, -fy, -fz)

        out[0] = xAxis[0]; out[1] = xAxis[1]; out[2] = xAxis[2]; out[3] = 0f
        out[4] = yAxis[0]; out[5] = yAxis[1]; out[6] = yAxis[2]; out[7] = 0f
        out[8] = zAxis[0]; out[9] = zAxis[1]; out[10] = zAxis[2]; out[11] = 0f
        out[12] = px; out[13] = py; out[14] = pz; out[15] = 1f
        return out
    }
}
