package com.navassist.sim

import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.NavigationEngine
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.sensing.DepthImage
import com.navassist.navcore.sensing.SemanticLocator
import com.navassist.navcore.sensing.SensorFrameAssembler
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.navcore.state.NavigationStatus
import com.navassist.navcore.state.StopReason
import com.navassist.sim.perception.PerceptionModel
import com.navassist.sim.perception.PerceptionProfile
import com.navassist.sim.sensor.BodyPose
import com.navassist.sim.sensor.DepthCamera
import com.navassist.sim.sensor.DepthNoise
import com.navassist.sim.sensor.PhoneRig
import com.navassist.sim.user.UserProfile
import com.navassist.sim.user.VirtualUser
import com.navassist.sim.world.Scene
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Everything that defines one run. The same settings and seed always give the same run. */
data class SimSettings(
    val scene: Scene,
    val start: BodyPose,
    val target: NavigationTarget = NavigationTarget.Explore,
    val user: UserProfile = UserProfile.TYPICAL,
    val depthNoise: DepthNoise = DepthNoise(),
    val perception: PerceptionProfile = PerceptionProfile(),
    val engineConfig: NavigationConfig = NavigationConfig(),
    val maxSeconds: Double = 180.0,
    /** Depth images arrive every Nth camera frame, and one frame late - ARCore's usual pattern. */
    val depthEveryNthFrame: Int = 2,
    val depthLatencyFrames: Int = 1,
    /** Time windows, in seconds, during which the platform reports lost tracking. */
    val trackingLoss: List<ClosedFloatingPointRange<Double>> = emptyList(),
    val seed: Long = 1,
)

enum class Outcome { ARRIVED, TIMEOUT, FELL }

/** One sample per camera frame: where the user was and what they were being told. */
data class TraceSample(
    val seconds: Double,
    val body: BodyPose,
    val phoneYaw: Float,
    val command: NavigationCommand,
    val status: NavigationStatus,
    val stopReason: StopReason?,
    val acting: NavigationCommand,
    val touching: Boolean,
)

/** What came out of a run. */
data class SimResult(
    val outcome: Outcome,
    val seconds: Double,
    val distanceWalked: Float,
    val collisions: Int,
    /** Closest the body's EDGE came to any obstacle; negative would be a collision. */
    val minClearanceMeters: Float,
    /** How often the spoken instruction changed, per minute of guidance. */
    val instructionChangesPerMinute: Double,
    val secondsScanning: Double,
    val secondsStopped: Double,
    /** Seconds until the engine first said anything other than STOP/SCAN; null if never. */
    val firstMoveSeconds: Double?,
    val hazardStops: Int,
    val captures: Int,
    val detections: Int,
    val finalBody: BodyPose,
    val finalStatus: NavigationStatus,
) {
    fun summary(): String = buildString {
        append("$outcome in ${"%.1f".format(seconds)} s, walked ${"%.1f".format(distanceWalked)} m, ")
        append("collisions $collisions, min clearance ${"%.2f".format(minClearanceMeters)} m, ")
        append("${"%.1f".format(instructionChangesPerMinute)} changes/min, ")
        append("scan ${"%.0f".format(secondsScanning)} s, stop ${"%.0f".format(secondsStopped)} s, ")
        append("first move ${firstMoveSeconds?.let { "%.1f s".format(it) } ?: "never"}, ")
        append("detections $detections/$captures captures, final $finalStatus")
    }
}

/**
 * Closed loop: virtual user -> phone pose -> simulated depth -> the core's SensorFrameAssembler
 * (the same code the Android adapter runs) -> NavigationEngine -> instruction -> virtual user.
 *
 * Runs on a simulated clock at 30 frames per second, as fast as the machine allows.
 */
class Simulation(val settings: SimSettings) {

    private val random = Random(settings.seed)
    val scene: Scene = settings.scene
    val engine = NavigationEngine(settings.engineConfig)
    private val assembler = SensorFrameAssembler(settings.engineConfig)
    private val camera = DepthCamera(noise = settings.depthNoise, renderStride = settings.engineConfig.depthSampleStride, random = random)
    private val perception = PerceptionModel(settings.perception, camera.rgbIntrinsics, random)
    val user = VirtualUser(settings.start, settings.user, scene)

    val trace = ArrayList<TraceSample>()

    /** Called after every frame, for live viewers and recorders. */
    var onFrame: ((Simulation, TraceSample, NavigationSnapshot) -> Unit)? = null

    private val cameraMatrix = FloatArray(16)
    private val rendered = DepthImage()
    private val delivered = DepthImage()
    private var renderedAtFrame = -1L
    private var hasDelivered = false

    /** Plan position and yaw of the phone when the engine's canonical frame was fixed. */
    private var canonicalOrigin: Triple<Float, Float, Float>? = null

    var frameIndex = 0L
        private set
    val seconds: Double get() = frameIndex * FRAME_SECONDS
    private val timestampNanos: Long get() = START_NANOS + frameIndex * FRAME_NANOS

    lateinit var lastSnapshot: NavigationSnapshot
        private set

    fun run(): SimResult {
        engine.start(settings.target)
        var outcome = Outcome.TIMEOUT
        while (seconds < settings.maxSeconds) {
            val snapshot = step()
            if (user.fell) { outcome = Outcome.FELL; break }
            if (snapshot.command == NavigationCommand.ARRIVED) { outcome = Outcome.ARRIVED; break }
        }
        return result(outcome)
    }

    /** Advances one camera frame. Public so interactive front ends can drive the loop. */
    fun step(driver: ((VirtualUser) -> Unit)? = null): NavigationSnapshot {
        if (frameIndex == 0L && engine.status == NavigationStatus.IDLE) engine.start(settings.target)
        frameIndex++
        val dt = FRAME_SECONDS.toFloat()
        if (driver != null) driver(user) else user.update(seconds, dt)

        PhoneRig.cameraToWorld(user.body, user.phoneYaw, user.phonePitch, settings.user.hold, cameraMatrix)
        val tracking = settings.trackingLoss.none { seconds in it }

        // Depth: rendered every Nth frame from the pose at that moment, handed over late.
        if (frameIndex % settings.depthEveryNthFrame == 0L) {
            camera.render(scene, cameraMatrix, timestampNanos, rendered)
            renderedAtFrame = frameIndex
        }
        if (renderedAtFrame >= 0 && frameIndex - renderedAtFrame == settings.depthLatencyFrames.toLong()) {
            delivered.copyFrom(rendered)
            hasDelivered = true
        }

        val wasEstablished = assembler.canonical.isEstablished
        val frame = assembler.assemble(
            timestampNanos = timestampNanos,
            tracking = tracking,
            cameraToWorld = cameraMatrix,
            depth = if (hasDelivered) delivered else null,
            depthIntrinsics = camera.depthIntrinsics,
            planes = camera.trackedPlanes(),
        )
        if (!wasEstablished && assembler.canonical.isEstablished) {
            val reach = settings.user.hold.reachMeters
            canonicalOrigin = Triple(
                user.body.x + sin(user.body.heading) * reach,
                user.body.y + cos(user.body.heading) * reach,
                user.phoneYaw,
            )
        }
        val snapshot = engine.updateFrame(frame)
        lastSnapshot = snapshot
        user.hear(snapshot.command, snapshot.stopReason, seconds)

        // Perception, on the app's cadence: observations placed with the depth of THEIR frame.
        val arrived = perception.deliver(seconds)
        if (arrived.isNotEmpty()) SemanticLocator.submit(engine, SemanticLocator.locate(arrived, assembler))
        if (tracking && perception.wantsCapture(seconds)) {
            assembler.pinDepth(timestampNanos)
            perception.capture(seconds, timestampNanos, scene, cameraMatrix)
        }

        val sample = TraceSample(
            seconds, user.body, user.phoneYaw, snapshot.command, snapshot.status, snapshot.stopReason,
            user.acting, user.touching != null,
        )
        trace.add(sample)
        onFrame?.invoke(this, sample, snapshot)
        return snapshot
    }

    /** Engine canonical (x, z) -> floor-plan (x, y). Null until the frame is established. */
    fun canonicalToPlan(point: Vec2): Pair<Float, Float>? {
        val (ox, oy, yaw) = canonicalOrigin ?: return null
        // Canonical +z is the phone's initial forward, +x its right.
        val fx = sin(yaw)
        val fy = cos(yaw)
        val rx = cos(yaw)
        val ry = -sin(yaw)
        return (ox + rx * point.x + fx * point.z) to (oy + ry * point.x + fy * point.z)
    }

    fun result(outcome: Outcome): SimResult {
        var changes = 0
        var scanning = 0.0
        var stopped = 0.0
        var firstMove: Double? = null
        var hazards = 0
        var minClearance = Float.MAX_VALUE
        var previous: TraceSample? = null
        for (sample in trace) {
            if (previous != null && previous.command != sample.command) changes++
            if (sample.command == NavigationCommand.SCAN) scanning += FRAME_SECONDS
            if (sample.command == NavigationCommand.STOP) stopped += FRAME_SECONDS
            if (firstMove == null && sample.command != NavigationCommand.STOP && sample.command != NavigationCommand.SCAN) {
                firstMove = sample.seconds
            }
            if (sample.stopReason == StopReason.OBSTACLE_AHEAD && previous?.stopReason != StopReason.OBSTACLE_AHEAD) hazards++
            minClearance = min(minClearance, scene.clearanceAt(sample.body.x, -sample.body.y) - settings.user.bodyRadiusMeters)
            previous = sample
        }
        val minutes = (seconds / 60.0).coerceAtLeast(1.0 / 60.0)
        return SimResult(
            outcome = outcome,
            seconds = seconds,
            distanceWalked = user.distanceWalked,
            collisions = user.collisions,
            minClearanceMeters = minClearance,
            instructionChangesPerMinute = changes / minutes,
            secondsScanning = scanning,
            secondsStopped = stopped,
            firstMoveSeconds = firstMove,
            hazardStops = hazards,
            captures = perception.captures,
            detections = perception.detections,
            finalBody = user.body,
            finalStatus = engine.status,
        )
    }

    companion object {
        const val FRAME_SECONDS = 1.0 / 30.0
        const val FRAME_NANOS = 33_333_333L

        /** Platform timestamps start well above zero, like nanoseconds since boot. */
        const val START_NANOS = 5_000_000_000_000L
    }
}
