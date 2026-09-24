package expo.modules.navigationnative

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.NavigationEngine
import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.state.NavigationCommand
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.navcore.state.NavigationStatus
import expo.modules.navigationnative.platform.ArCoreFrameProcessor
import expo.modules.navigationnative.platform.ArCorePoseProvider
import expo.modules.navigationnative.platform.ArCoreDepthProvider
import expo.modules.navigationnative.platform.ArCoreSessionManager
import expo.modules.navigationnative.platform.DepthCloudPool
import expo.modules.navigationnative.platform.DepthReturnRenderer
import expo.modules.navigationnative.platform.NavigationSupport
import expo.modules.navigationnative.platform.OccupancyMapRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Glue between the ARCore/Android world and the platform-independent [NavigationEngine].
 *
 * Threading model (architecture rule: nothing expensive on the UI or GL thread):
 *
 *   GL / AR thread          session.update() -> pose + depth unprojection  (cheap, bounded)
 *        |  latest-frame slot (stale frames are DROPPED, never queued)
 *   engine thread           occupancy integration, inflation, frontiers, A*, control
 *        |  throttled
 *   JS                      one small NavigationSnapshot at <= ~8 Hz
 *
 * Only the LATEST frame is kept on purpose: if mapping falls behind, the right answer is to skip
 * old depth, not to accumulate latency and steer a walking person from a two-second-old map.
 * Each frame's depth buffer is owned by exactly one side at a time (see [DepthCloudPool]).
 *
 * All engine access happens on a single dedicated thread, so the engine itself stays synchronous
 * and lock-free - exactly the property that lets an iOS adapter pick its own threading later.
 * The frame processor's pose basis is GL-thread state; other threads only REQUEST a reset of it
 * (see [requestFrameReset]), and frames converted before the reset are discarded by generation.
 */
object NavigationRuntime {

    private const val TAG = "NavigationRuntime"

    var config: NavigationConfig = NavigationConfig()
        private set

    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "navigation-engine").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val engineScope = CoroutineScope(SupervisorJob() + engineExecutor.asCoroutineDispatcher())

    private val engine = NavigationEngine(config)
    private val poseProvider = ArCorePoseProvider()
    private val depthProvider = ArCoreDepthProvider(config)
    private val frameProcessor = ArCoreFrameProcessor(config, poseProvider, depthProvider)
    private val cloudPool = DepthCloudPool(config.maxPointsPerFrame)

    /** A converted frame and the coordinate-frame generation it was converted in. */
    private class PendingFrame(val generation: Int, val frame: NavigationFrame)

    /** The newest frame not yet taken by the engine. Swapped atomically; never shared. */
    private val latestFrame = AtomicReference<PendingFrame?>(null)
    private val frameReady = Channel<Unit>(Channel.CONFLATED)

    /** The coordinate-frame generation the engine currently accepts. */
    @Volatile
    private var acceptedGeneration = 0
    private val generationLock = Any()

    /** PNG encoding for the debug views, kept off the engine thread. */
    private val encodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "navigation-debug-encode").apply { isDaemon = true }
    }

    private var sessionManager: ArCoreSessionManager? = null

    @Volatile
    private var snapshot: NavigationSnapshot = NavigationSnapshot(
        timestampMillis = 0,
        status = NavigationStatus.IDLE,
        command = NavigationCommand.STOP,
    )

    @Volatile
    var debugEnabled: Boolean = false

    @Volatile
    private var lastEmittedAtMillis: Long = 0

    @Volatile
    private var running = false

    /** Paused from JS: only JS may resume. */
    @Volatile
    private var pausedByUser = false

    /** Paused because the app left the foreground: coming back resumes by itself. */
    @Volatile
    private var pausedByLifecycle = false

    /** Set by the Expo module so snapshots can be pushed to JS. */
    @Volatile
    var snapshotListener: ((NavigationSnapshot) -> Unit)? = null

    /**
     * The most recent depth frame, kept for the debug depth view only.
     *
     * Touched exclusively on the engine thread (the frame loop writes it, renderDepth reads it),
     * which is single-threaded, so it needs no synchronisation. The cloud is COPIED rather than
     * referenced: the depth provider recycles its buffers through [DepthCloudPool] and would
     * overwrite ours within three frames.
     */
    private var debugCloud: DepthPointCloud? = null
    private var debugPose: Pose3D = Pose3D.IDENTITY
    private var debugFrameMillis: Long = 0L

    init {
        engineScope.launch {
            for (signal in frameReady) {
                val pending = latestFrame.getAndSet(null) ?: continue
                try {
                    // Converted before a reset of the canonical frame: its coordinates belong to
                    // a basis that no longer exists.
                    if (pending.generation != acceptedGeneration) continue
                    if (debugEnabled) captureDepthFrame(pending.frame)
                    publish(engine.updateFrame(pending.frame))
                } catch (e: Throwable) {
                    Log.e(TAG, "engine update failed", e)
                } finally {
                    cloudPool.release(pending.frame.points)
                }
            }
        }
    }

    /**
     * Re-establishes the canonical frame at the next GL frame, and makes the engine ignore every
     * frame converted before that. Safe from any thread.
     */
    private fun requestFrameReset() = synchronized(generationLock) {
        acceptedGeneration += 1
        frameProcessor.requestReset(acceptedGeneration)
        latestFrame.getAndSet(null)?.let { cloudPool.release(it.frame.points) }
    }

    /** Copies one frame's returns aside so the debug view can draw the sensor's own input. */
    private fun captureDepthFrame(frame: NavigationFrame) {
        val source = frame.points
        val target = debugCloud?.takeIf { it.capacity >= source.count }
            ?: DepthPointCloud.allocate(maxOf(source.count, config.maxPointsPerFrame))
        target.clear()
        System.arraycopy(source.xyz, 0, target.xyz, 0, source.count * 3)
        target.count = source.count
        debugCloud = target
        debugPose = frame.pose
        debugFrameMillis = System.currentTimeMillis()
    }

    // ================================================================= session lifecycle

    fun attach(context: Context): ArCoreSessionManager {
        val existing = sessionManager
        if (existing != null) return existing
        val created = ArCoreSessionManager(context.applicationContext)
        sessionManager = created
        return created
    }

    /**
     * Depth support as reported by whoever owns the ARCore session.
     *
     * `isDepthModeSupported` can only be answered by an open Session, and in external mode we
     * deliberately never open one - so this module cannot discover it for itself and must be told.
     * Null means nobody has reported yet.
     */
    @Volatile
    private var externalDepthSupported: Boolean? = null

    /** Called by the session owner (see NavigationSensorBridge.reportCapabilities). */
    fun reportExternalCapabilities(depthSupported: Boolean) {
        externalDepthSupported = depthSupported
    }

    fun support(context: Context): NavigationSupport {
        val manager = attach(context)
        val availability = manager.queryAvailability()
        val sessionOwned = if (externalFrameSource) session() != null else manager.session != null
        return availability.copy(
            depthSupported = when {
                // In external mode our own manager never opened a session, so its flag is
                // meaningless; the owner's report is the only real answer.
                externalFrameSource -> externalDepthSupported ?: availability.depthSupported
                else -> manager.depthSupported || availability.depthSupported
            },
            trackingAvailable = sessionOwned &&
                snapshot.status != NavigationStatus.LOST_TRACKING &&
                snapshot.status != NavigationStatus.IDLE,
            reason = if (externalFrameSource && externalDepthSupported == null) {
                "Waiting for the session owner to report capabilities"
            } else {
                availability.reason
            },
        )
    }

    fun initializeSession(context: Context, activity: Activity?): String? {
        // In external mode another module owns the camera; claiming it here would evict them.
        if (externalFrameSource) return null
        return attach(context).initialize(activity)
    }

    fun resumeSession(): String? {
        if (externalFrameSource) return null
        return sessionManager?.resume() ?: "No ARCore session"
    }

    fun pauseSession() {
        sessionManager?.pause()
    }

    fun destroySession() {
        sessionManager?.destroy()
        requestFrameReset()
    }

    /**
     * The session this module owns, or the externally owned one once a frame has arrived.
     *
     * In external mode the reference is only as fresh as the last [onExternalArFrame] call, and
     * it is cleared the moment ownership returns - see [useExternalFrameSource].
     */
    fun session(): Session? = if (externalFrameSource) externalSession else sessionManager?.session

    @Volatile
    private var externalSession: Session? = null

    fun setCameraTexture(textureId: Int) = sessionManager?.setCameraTexture(textureId)

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) =
        sessionManager?.setDisplayGeometry(rotation, width, height)

    // ================================================================= frame sources

    /**
     * True when another module owns the ARCore session and feeds us frames.
     *
     * ARCore requires EXCLUSIVE access to the camera, and this engine cannot work without ARCore
     * (depth and 6DoF pose are its only inputs). So the module that opens the camera must also be
     * the module that runs the session - they cannot be two different modules. External mode is
     * how that ownership moves elsewhere without this engine changing.
     */
    @Volatile
    var externalFrameSource: Boolean = false
        private set

    /**
     * Hands session ownership to another module. Call BEFORE `start()`. In this mode
     * [NavigationArView] is not needed and no session is created here; the owner is expected to
     * call [onExternalArFrame] once per ARCore frame.
     */
    fun useExternalFrameSource(external: Boolean) {
        if (externalFrameSource == external) return
        externalFrameSource = external
        if (external) {
            // Release our own camera claim so the new owner can take it.
            sessionManager?.pause()
        } else {
            // Ownership is coming back to us, which means the previous owner is tearing its
            // session down (ArFrameSource.destroy calls releaseFrameSource right after
            // Session.close). Holding the reference would leave session() handing out a CLOSED
            // session, and support() reporting trackingAvailable = true against it.
            externalSession = null
        }
        requestFrameReset()
    }

    /**
     * Called once per rendered frame on the GL thread when THIS module owns the session.
     * Keeps only cheap, bounded work here: one `Session.update()`, one pose conversion and one
     * depth unprojection pass.
     */
    fun onGlFrame() {
        if (externalFrameSource) return
        val session = sessionManager?.session ?: return
        if (!running) return
        try {
            ingest(session, session.update())
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "camera not available during update", e)
        } catch (e: Throwable) {
            Log.w(TAG, "frame update failed", e)
        }
    }

    /**
     * Entry point for a module that owns the ARCore session itself.
     *
     * Call once per frame, on whichever thread already called `Session.update()`, passing the
     * frame it returned. The RGB image is none of our business - read it from the same frame with
     * `acquireCameraImage()` for perception work.
     *
     * Only the pose and the depth image are consumed, and the depth image is acquired, copied and
     * closed inside this call, so the caller may continue using the frame afterwards.
     */
    fun onExternalArFrame(session: Session, frame: Frame) {
        if (!externalFrameSource) {
            Log.w(TAG, "onExternalArFrame ignored: call useExternalFrameSource(true) first")
            return
        }
        if (!running) return
        try {
            ingest(session, frame)
        } catch (e: Throwable) {
            Log.w(TAG, "external frame update failed", e)
        }
    }

    private fun ingest(session: Session, arFrame: Frame) {
        externalSession = session
        val navigationFrame = frameProcessor.process(session, arFrame)
        // Copy the reusable depth buffer into a buffer the engine will own until it is done.
        val source = navigationFrame.points
        val points = if (source.count == 0) {
            DepthPointCloud.EMPTY
        } else {
            // Cannot happen with the pool sized for GL + latest + engine, but never block the GL
            // thread for it: dropping one frame is exactly what the latest-frame slot does anyway.
            cloudPool.copyOf(source) ?: return
        }
        val handOff = PendingFrame(frameProcessor.generation, navigationFrame.copy(points = points))
        latestFrame.getAndSet(handOff)?.let { cloudPool.release(it.frame.points) }
        frameReady.trySend(Unit)
    }

    /**
     * Called by the session owner on the GL thread right after [onExternalArFrame], when it
     * captures that frame's RGB image for perception. Keeps the matching depth image so the
     * observations - which arrive seconds later - resolve against what the camera saw then.
     */
    fun onFrameCaptured(timestampNanos: Long) {
        if (!running) return
        frameProcessor.pinDepth(timestampNanos)
    }

    // ================================================================= engine API (from JS)

    fun start(target: NavigationTarget) {
        running = true
        pausedByUser = false
        pausedByLifecycle = false
        engineScope.launch {
            engine.start(target)
            publish(engine.snapshot, force = true)
        }
    }

    fun stop() {
        running = false
        pausedByUser = false
        pausedByLifecycle = false
        engineScope.launch {
            engine.stop()
            publish(engine.snapshot, force = true)
        }
    }

    fun pause() {
        pausedByUser = true
        engineScope.launch {
            engine.pause()
            publish(engine.snapshot, force = true)
        }
    }

    fun resume() {
        pausedByUser = false
        pausedByLifecycle = false
        engineScope.launch {
            engine.resume()
            publish(engine.snapshot, force = true)
        }
    }

    /**
     * The app left the foreground: the camera is being taken away, so guidance must stop. Marked
     * as a lifecycle pause, so [onHostForeground] can undo it - nothing else would, and a phone
     * locked mid-route used to stay PAUSED until the user found a button to press.
     */
    fun onHostBackground() {
        if (!running || pausedByUser) return
        pausedByLifecycle = true
        engineScope.launch {
            engine.pause()
            publish(engine.snapshot, force = true)
        }
    }

    /** Resumes only what [onHostBackground] paused. The engine re-localizes before guiding. */
    fun onHostForeground() {
        if (!pausedByLifecycle) return
        pausedByLifecycle = false
        if (!running || pausedByUser) return
        engineScope.launch {
            engine.resume()
            publish(engine.snapshot, force = true)
        }
    }

    fun setTarget(target: NavigationTarget) {
        engineScope.launch { engine.setTarget(target) }
    }

    /**
     * Keeps the canonical frame on purpose: the engine keeps its semantic memory across a map
     * reset, and that memory is in canonical coordinates.
     */
    fun resetMap() {
        engineScope.launch {
            engine.resetMap()
            publish(engine.snapshot, force = true)
        }
    }

    fun reset() {
        running = false
        pausedByUser = false
        pausedByLifecycle = false
        requestFrameReset()
        engineScope.launch { engine.reset() }
    }

    /**
     * Semantic observations from the perception teammate.
     *
     * Observations carrying an image coordinate are resolved against the depth image of the
     * camera frame they were seen in - HERE, on the platform side, because the core never sees
     * depth images or intrinsics. The same lookup recovers where the user stood at that moment,
     * which directional hints ("exit to the LEFT") are relative to.
     *
     * Time is the engine's own clock, not the wall clock: hint ageing and sighting expiry compare
     * against frame timestamps, and a wall-clock stamp made every hint look permanently fresh.
     */
    fun submitSemanticObservations(observations: List<SemanticObservation>) {
        if (observations.isEmpty()) return
        // Resolution only touches the depth snapshots, which are locked; do it on this thread.
        val resolved = observations.map { resolve(it) }
        engineScope.launch {
            for ((observerPose, group) in resolved.groupBy { it.second }) {
                engine.submitSemanticObservations(
                    group.map { it.first },
                    engine.clockMillis,
                    observerPose ?: engine.currentPose(),
                )
            }
        }
    }

    private fun resolve(observation: SemanticObservation): Pair<SemanticObservation, Pose3D?> {
        val (x, y) = imageCoordinatesOf(observation)
        val resolution = frameProcessor.resolve(x, y, observation.timestampNanos)
        val world = resolution.worldPosition
        if (observation.worldPosition != null || world == null) return observation to resolution.observerPose
        val located = when (observation) {
            is SemanticObservation.Room -> observation.copy(worldPosition = world)
            is SemanticObservation.Exit -> observation.copy(worldPosition = world)
            // Doors were missing here, so a door was never localized and "find a door" could
            // only ever explore.
            is SemanticObservation.Door -> observation.copy(worldPosition = world)
            is SemanticObservation.Stairs -> observation.copy(worldPosition = world)
            is SemanticObservation.Elevator -> observation.copy(worldPosition = world)
            else -> observation
        }
        return located to resolution.observerPose
    }

    private fun imageCoordinatesOf(observation: SemanticObservation): Pair<Float?, Float?> =
        when (observation) {
            is SemanticObservation.Room -> observation.normalizedX to observation.normalizedY
            is SemanticObservation.Exit -> observation.normalizedX to observation.normalizedY
            is SemanticObservation.Door -> observation.normalizedX to observation.normalizedY
            is SemanticObservation.Stairs -> observation.normalizedX to observation.normalizedY
            is SemanticObservation.Elevator -> observation.normalizedX to observation.normalizedY
            else -> null to null
        }

    fun currentSnapshot(): NavigationSnapshot = snapshot

    /**
     * Renders the occupancy grid to a PNG, on the engine thread.
     *
     * Drawing must run there: it reads the live grid, path and frontier state, which the engine
     * may be rewriting at 30 Hz. PNG encoding then runs on its own thread so it does not hold up
     * mapping. Pulled on demand rather than pushed with every snapshot, because a map image is
     * orders of magnitude larger than the state the guidance layer actually needs.
     */
    fun renderMap(onResult: (Result<OccupancyMapRenderer.RenderedMap>) -> Unit) {
        if (!debugEnabled) {
            onResult(Result.failure(IllegalStateException("Map rendering is available only in debug mode")))
            return
        }
        engineScope.launch {
            val pending = try {
                OccupancyMapRenderer.render(engine)
            } catch (e: Throwable) {
                onResult(Result.failure(e))
                return@launch
            }
            encodeExecutor.execute { onResult(runCatching { pending.encode() }) }
        }
    }

    /**
     * Renders the latest raw depth returns, on the engine thread, for the same reason renderMap
     * does: it reads the grid window and the captured frame while the engine is still running.
     *
     * Returns a frame-less picture rather than failing when no depth has arrived yet - "the
     * sensor has sent nothing" is the single most useful thing this view can report, so it must
     * not come back as an error the UI quietly swallows.
     */
    fun renderDepth(onResult: (Result<DepthReturnRenderer.RenderedDepth>) -> Unit) {
        if (!debugEnabled) {
            onResult(Result.failure(IllegalStateException("Depth rendering is available only in debug mode")))
            return
        }
        engineScope.launch {
            val pending = try {
                val age = if (debugFrameMillis == 0L) 0L else System.currentTimeMillis() - debugFrameMillis
                DepthReturnRenderer.render(engine, config, debugCloud, debugPose, age)
            } catch (e: Throwable) {
                onResult(Result.failure(e))
                return@launch
            }
            encodeExecutor.execute { onResult(runCatching { pending.encode() }) }
        }
    }

    // ================================================================= snapshot throttling

    /**
     * Emits at most one snapshot per [NavigationConfig.snapshotIntervalMillis], EXCEPT when the
     * status or command changed - a STOP must reach the user immediately, not up to 120 ms later.
     */
    private fun publish(next: NavigationSnapshot, force: Boolean = false) {
        val previous = snapshot
        snapshot = next
        val changed = previous.status != next.status || previous.command != next.command
        val due = next.timestampMillis - lastEmittedAtMillis >= config.snapshotIntervalMillis
        if (!force && !changed && !due) return
        lastEmittedAtMillis = next.timestampMillis
        snapshotListener?.invoke(if (debugEnabled) next else next.copy(debug = null))
    }
}
