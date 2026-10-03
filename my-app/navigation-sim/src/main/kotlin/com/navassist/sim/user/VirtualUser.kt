package com.navassist.sim.user

import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.PhoneAim
import com.navassist.navcore.state.StopReason
import com.navassist.sim.sensor.BodyPose
import com.navassist.sim.sensor.PhoneHold
import com.navassist.sim.world.Box
import com.navassist.sim.world.Scene
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How a particular person responds to guidance. */
data class UserProfile(
    val name: String = "typical",
    /** Time from an instruction being spoken to the person acting on it. */
    val reactionSeconds: Float = 0.45f,
    val walkMetersPerSecond: Float = 1.0f,
    val turnDegreesPerSecond: Float = 60f,
    /** Keeps edging forward while turning, as many people do. */
    val walkWhileTurningMetersPerSecond: Float = 0f,
    /** When asked to scan: how far the phone sweeps either side of the body. */
    val scanSweepDegrees: Float = 60f,
    val scanPeriodSeconds: Float = 4f,
    /** When asked to scan, whether the person also tilts the phone down at the floor. */
    val scanTiltsDown: Boolean = true,
    /** How far below horizontal the phone points normally. */
    val holdPitchDegrees: Float = 15f,
    /** Side-to-side phone sway per stride while walking. */
    val swayDegrees: Float = 3f,
    val hold: PhoneHold = PhoneHold(),
    val bodyRadiusMeters: Float = 0.25f,
) {
    companion object {
        val TYPICAL = UserProfile()
        val CAUTIOUS = UserProfile(name = "cautious", reactionSeconds = 0.8f, walkMetersPerSecond = 0.6f, turnDegreesPerSecond = 40f)

        /** Sweeps side to side but never tilts the phone at the floor: the near-field worst case. */
        val NEVER_TILTS = UserProfile(name = "never-tilts", scanTiltsDown = false, holdPitchDegrees = 5f)
        val HURRIED = UserProfile(name = "hurried", reactionSeconds = 0.3f, walkMetersPerSecond = 1.3f, walkWhileTurningMetersPerSecond = 0.3f)
    }
}

/**
 * A scripted person following the engine's instructions.
 *
 * They act on what they HEARD, [UserProfile.reactionSeconds] after the engine said it - so the
 * engine's own dwell and stability rules meet a realistic delay rather than an instant robot. They
 * can walk into things: collisions are recorded, and the step that would cause one is refused.
 */
class VirtualUser(
    start: BodyPose,
    val profile: UserProfile,
    private val scene: Scene,
) {
    var body: BodyPose = start
        private set

    /** Absolute yaw the phone points along (differs from the body while scanning or swaying). */
    var phoneYaw: Float = start.heading
        private set

    var phonePitch: Float = GeometryUtils.degreesToRadians(profile.holdPitchDegrees)
        private set

    /** The instruction the person is currently carrying out. */
    var acting: NavigationCommand = NavigationCommand.STOP
        private set

    /** Why they were told to hold still, as they understood it ("look down ahead", "obstacle"). */
    var actingReason: StopReason? = null
        private set

    var distanceWalked = 0f
        private set

    var collisions = 0
        private set

    /** What each collision was with, in order. */
    val collidedWith = ArrayList<Box>()

    var fell = false
        private set

    /** What they are touching right now, if anything. */
    var touching: Box? = null
        private set

    private class Heard(val atSeconds: Double, val command: NavigationCommand, val reason: StopReason?)

    /** How they are holding the phone as asked; null for their usual hold. */
    var aim: PhoneAim? = null
        private set
    private val heardAims = ArrayDeque<Pair<Double, PhoneAim?>>()
    private var lastAimHeard: PhoneAim? = null
    private var sweepClock = 0f

    /** Called with every engine output; the person hears a change of aim, and acts on it later. */
    fun hearAim(next: PhoneAim?, nowSeconds: Double) {
        if (next == lastAimHeard) return
        lastAimHeard = next
        heardAims.addLast(nowSeconds + profile.reactionSeconds to next)
    }

    private val heard = ArrayDeque<Heard>()
    private var lastHeard: Pair<NavigationCommand, StopReason?>? = null
    private var scanClock = 0f
    private var strideClock = 0f

    /** Called with every engine output; the person hears changes. */
    fun hear(command: NavigationCommand, reason: StopReason?, nowSeconds: Double) {
        val said = command to reason
        if (said == lastHeard) return
        lastHeard = said
        heard.addLast(Heard(nowSeconds + profile.reactionSeconds, command, reason))
    }

    fun update(nowSeconds: Double, dt: Float) {
        while (heard.isNotEmpty() && heard.first().atSeconds <= nowSeconds) {
            val next = heard.removeFirst()
            if (next.command != acting || next.reason != actingReason) {
                acting = next.command
                actingReason = next.reason
                scanClock = 0f
            }
        }

        while (heardAims.isNotEmpty() && heardAims.first().first <= nowSeconds) aim = heardAims.removeFirst().second

        val usualPitch = GeometryUtils.degreesToRadians(profile.holdPitchDegrees)
        // Pitch is stored as degrees DOWN; an aim is given as degrees above horizontal.
        val holdPitch = aim?.pitchDegrees?.let { GeometryUtils.degreesToRadians(-it) } ?: usualPitch
        val aimYaw = GeometryUtils.degreesToRadians(aim?.yawDegrees ?: 0f)
        when (acting) {
            NavigationCommand.STRAIGHT -> {
                walk(profile.walkMetersPerSecond * dt)
                strideClock += dt
                val sway = GeometryUtils.degreesToRadians(profile.swayDegrees) * sin(strideClock * 2f * PI.toFloat() * 0.9f)
                val sweepDegrees = aim?.sweepDegrees ?: 0f
                val sweep = if (sweepDegrees > 0f) {
                    sweepClock += dt
                    GeometryUtils.degreesToRadians(sweepDegrees) * sin(2f * PI.toFloat() * sweepClock / profile.scanPeriodSeconds)
                } else 0f
                phoneYaw = GeometryUtils.normalizeAngle(body.heading + sway + aimYaw + sweep)
                phonePitch = holdPitch
            }
            NavigationCommand.TURN_LEFT, NavigationCommand.TURN_RIGHT -> {
                val direction = if (acting == NavigationCommand.TURN_RIGHT) 1f else -1f
                val turn = GeometryUtils.degreesToRadians(profile.turnDegreesPerSecond) * dt * direction
                body = body.copy(heading = GeometryUtils.normalizeAngle(body.heading + turn))
                if (profile.walkWhileTurningMetersPerSecond > 0f) walk(profile.walkWhileTurningMetersPerSecond * dt)
                phoneYaw = GeometryUtils.normalizeAngle(body.heading + aimYaw)
                phonePitch = holdPitch
            }
            NavigationCommand.SCAN -> if (actingReason == StopReason.UNKNOWN_AHEAD && profile.scanTiltsDown) {
                // "Look down ahead": tilt the phone at the floor in front, facing the way they go.
                phoneYaw = body.heading
                phonePitch = GeometryUtils.degreesToRadians(45f)
            } else {
                scanClock += dt
                val phase = 2f * PI.toFloat() * scanClock / profile.scanPeriodSeconds
                phoneYaw = GeometryUtils.normalizeAngle(
                    body.heading + GeometryUtils.degreesToRadians(profile.scanSweepDegrees) * sin(phase),
                )
                // Tilting down on alternate sweeps, the way people look at the floor then ahead.
                phonePitch = if (profile.scanTiltsDown) {
                    holdPitch + GeometryUtils.degreesToRadians(25f) * (0.5f - 0.5f * cos(phase * 0.5f))
                } else {
                    holdPitch
                }
            }
            NavigationCommand.STOP, NavigationCommand.ARRIVED -> {
                phoneYaw = GeometryUtils.normalizeAngle(body.heading + aimYaw)
                phonePitch = holdPitch
            }
        }
    }

    /**
     * Driven by a person instead of the script (live mode): walk at [forwardMetersPerSecond] and
     * turn at [turnRadiansPerSecond] (positive = to the right). Collisions still apply.
     */
    fun drive(forwardMetersPerSecond: Float, turnRadiansPerSecond: Float, dt: Float) {
        body = body.copy(heading = GeometryUtils.normalizeAngle(body.heading + turnRadiansPerSecond * dt))
        walk(forwardMetersPerSecond * dt)
        phoneYaw = body.heading
        phonePitch = GeometryUtils.degreesToRadians(profile.holdPitchDegrees)
    }

    private fun walk(meters: Float) {
        if (meters <= 0f || fell) return
        val nx = body.x + sin(body.heading) * meters
        val ny = body.y + cos(body.heading) * meters
        // World z is -north.
        val blocker = scene.obstacleTouching(nx, -ny, profile.bodyRadiusMeters)
        if (blocker != null) {
            if (touching != blocker) {
                collisions++
                collidedWith += blocker
            }
            touching = blocker
            return
        }
        touching = null
        body = body.copy(x = nx, y = ny)
        distanceWalked += meters
        if (!scene.hasFloorAt(nx, -ny)) fell = true
    }
}
