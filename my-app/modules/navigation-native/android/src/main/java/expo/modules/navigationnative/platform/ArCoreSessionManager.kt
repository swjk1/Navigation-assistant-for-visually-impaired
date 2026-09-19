package expo.modules.navigationnative.platform

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException

/**
 * Support state reported to JavaScript. The engine never pretends a capability exists: if depth is
 * unsupported the app is told so rather than silently navigating on geometry it does not have.
 */
data class NavigationSupport(
    val arCoreSupported: Boolean,
    val depthSupported: Boolean,
    val trackingAvailable: Boolean,
    val reason: String? = null,
)

/**
 * Owns the ARCore session lifecycle: initialize / resume / pause / destroy.
 *
 * ARCore OWNS THE CAMERA. No CameraX or Camera2 session may be opened alongside it for this
 * feature; a second consumer will either fail to open the camera or evict ARCore. If another
 * module needs RGB frames, expose them from the ARCore frame (see ArCoreFrameProcessor) rather
 * than starting a competing capture session.
 */
class ArCoreSessionManager(private val context: Context) {

    var session: Session? = null
        private set

    var depthSupported: Boolean = false
        private set

    var lastError: String? = null
        private set

    private var installRequested = false

    /** Cheap capability probe that does not create a session. */
    fun queryAvailability(): NavigationSupport {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        if (!availability.isSupported) {
            return NavigationSupport(
                arCoreSupported = false,
                depthSupported = false,
                trackingAvailable = false,
                reason = "ARCore availability: $availability",
            )
        }
        val existing = session
        return NavigationSupport(
            arCoreSupported = true,
            depthSupported = existing?.let { depthSupported } ?: depthSupported,
            trackingAvailable = existing != null,
            reason = if (availability.isTransient) "ARCore availability still resolving" else null,
        )
    }

    /**
     * Creates and configures the session. Safe to call repeatedly.
     *
     * @return null on success, or a human-readable reason for failure.
     */
    fun initialize(activity: Activity?): String? {
        if (session != null) return null
        try {
            if (activity != null) {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return "ARCore installation requested; retry once it completes"
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
            }

            val created = Session(context)
            val config = created.config

            // Depth is the whole point of this engine, but it is not available on every device.
            depthSupported = created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            config.depthMode =
                if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED

            // Horizontal planes give the floor estimator a strong hint. They are a HINT only:
            // the core can estimate the floor from the depth cloud alone (and must, on iOS
            // devices or scenes where no plane is found).
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            config.focusMode = Config.FocusMode.AUTO
            // BLOCKING pairs Session.update() with the GL render loop, which is what we want:
            // one AR frame per rendered frame, no busy-waiting.
            config.updateMode = Config.UpdateMode.BLOCKING
            created.configure(config)

            session = created
            lastError = null
            return null
        } catch (e: UnavailableException) {
            lastError = "ARCore unavailable: ${e.javaClass.simpleName}"
            Log.w(TAG, "ARCore session creation failed", e)
            return lastError
        } catch (e: Exception) {
            lastError = "ARCore session creation failed: ${e.message}"
            Log.w(TAG, "ARCore session creation failed", e)
            return lastError
        }
    }

    fun resume(): String? {
        val current = session ?: return "No ARCore session"
        return try {
            current.resume()
            null
        } catch (e: CameraNotAvailableException) {
            lastError = "Camera not available (is another camera session open?)"
            Log.w(TAG, "resume failed", e)
            lastError
        }
    }

    fun pause() {
        session?.pause()
    }

    fun destroy() {
        session?.close()
        session = null
    }

    fun setCameraTexture(textureId: Int) {
        session?.setCameraTextureName(textureId)
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        session?.setDisplayGeometry(rotation, width, height)
    }

    private companion object {
        const val TAG = "ArCoreSessionManager"
    }
}
