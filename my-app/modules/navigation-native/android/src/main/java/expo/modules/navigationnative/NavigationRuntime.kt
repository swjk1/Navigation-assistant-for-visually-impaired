package expo.modules.navigationnative

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.NavigationEngine
import com.navassist.navcore.geometry.NavigationFrame
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
import expo.modules.navigationnative.platform.NavigationSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Glue between the ARCore/Android world and the platform-independent [NavigationEngine].
 *
 * Threading model (architecture rule: nothing expensive on the UI or GL thread):
 *
 *   GL / AR thread          session.update() -> pose + depth unprojection  (cheap, bounded)
 *        |  conflated channel (stale frames are DROPPED, never queued)
 *   engine thread           occupancy integration, inflation, frontiers, A*, control
 *        |  throttled
 *   JS                      one small NavigationSnapshot at <= ~8 Hz
 *
 * The channel is CONFLATED on purpose: if mapping falls behind, the right answer is to skip old
 * depth, not to accumulate latency and steer a walking person from a two-second-old map.
 *
 * All engine access happens on a single dedicated thread, so the engine itself stays synchronous
 * and lock-free - exactly the property that lets an iOS adapter pick its own threading later.
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

    private val frames = Channel<NavigationFrame>(Channel.CONFLATED)

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

    /** Set by the Expo module so snapshots can be pushed to JS. */
    @Volatile
    var snapshotListener: ((NavigationSnapshot) -> Unit)? = null

    init {
        engineScope.launch {
            for (frame in frames) {
                try {
                    publish(engine.updateFrame(frame))
                } catch (e: Throwable) {
                    Log.e(TAG, "engine update failed", e)
                }
            }
        }
    }

    // ================================================================= session lifecycle

    fun attach(context: Context): ArCoreSessionManager {
        val existing = sessionManager
        if (existing != null) return existing
        val created = ArCoreSessionManager(context.applicationContext)
        sessionManager = created
        return created
    }

    fun support(context: Context): NavigationSupport {
        val manager = attach(context)
        val availability = manager.queryAvailability()
        return availability.copy(
            depthSupported = manager.depthSupported || availability.depthSupported,
            trackingAvailable = manager.session != null &&
                snapshot.status != NavigationStatus.LOST_TRACKING &&
                snapshot.status != NavigationStatus.IDLE,
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
        frameProcessor.reset()
    }

    fun session(): Session? = sessionManager?.session

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
        }
        frameProcessor.reset()
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
        val navigationFrame = frameProcessor.process(session, arFrame)
        // Copy the reusable depth buffer into a hand-off slot before publishing.
        val handOff = navigationFrame.copy(points = cloudPool.copyOf(navigationFrame.points))
        frames.trySend(handOff)
    }

    // ================================================================= engine API (from JS)

    fun start(target: NavigationTarget) {
        running = true
        engineScope.launch {
            engine.start(target)
            publish(engine.snapshot, force = true)
        }
    }

    fun stop() {
        running = false
        engineScope.launch {
            engine.stop()
            publish(engine.snapshot, force = true)
        }
    }

    fun pause() {
        engineScope.launch {
            engine.pause()
            publish(engine.snapshot, force = true)
        }
    }

    fun resume() {
        engineScope.launch {
            engine.resume()
            publish(engine.snapshot, force = true)
        }
    }

    fun setTarget(target: NavigationTarget) {
        engineScope.launch { engine.setTarget(target) }
    }

    fun resetMap() {
        engineScope.launch {
            engine.resetMap()
            frameProcessor.reset()
        }
    }

    fun reset() {
        running = false
        engineScope.launch {
            engine.reset()
            frameProcessor.reset()
        }
    }

    /**
     * Semantic observations from the perception teammate.
     *
     * Observations carrying an image coordinate are resolved against the most recent depth frame
     * HERE, on the platform side, because the core never sees depth images or intrinsics.
     */
    fun submitSemanticObservations(observations: List<SemanticObservation>, nowMillis: Long) {
        if (observations.isEmpty()) return
        val resolved = observations.map { resolveWorldPosition(it) }
        engineScope.launch { engine.submitSemanticObservations(resolved, nowMillis) }
    }

    private fun resolveWorldPosition(observation: SemanticObservation): SemanticObservation {
        if (observation.worldPosition != null) return observation
        return when (observation) {
            is SemanticObservation.Room -> {
                val x = observation.normalizedX ?: return observation
                val y = observation.normalizedY ?: return observation
                observation.copy(
                    worldPosition = frameProcessor.resolveWorldPosition(x, y, observation.timestampNanos),
                )
            }
            is SemanticObservation.Exit -> {
                val x = observation.normalizedX ?: return observation
                val y = observation.normalizedY ?: return observation
                observation.copy(
                    worldPosition = frameProcessor.resolveWorldPosition(x, y, observation.timestampNanos),
                )
            }
            is SemanticObservation.Stairs -> {
                val x = observation.normalizedX ?: return observation
                val y = observation.normalizedY ?: return observation
                observation.copy(
                    worldPosition = frameProcessor.resolveWorldPosition(x, y, observation.timestampNanos),
                )
            }
            is SemanticObservation.Elevator -> {
                val x = observation.normalizedX ?: return observation
                val y = observation.normalizedY ?: return observation
                observation.copy(
                    worldPosition = frameProcessor.resolveWorldPosition(x, y, observation.timestampNanos),
                )
            }
            else -> observation
        }
    }

    fun currentSnapshot(): NavigationSnapshot = snapshot

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
