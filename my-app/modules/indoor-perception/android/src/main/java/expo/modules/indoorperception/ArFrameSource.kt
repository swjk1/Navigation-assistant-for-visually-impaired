package expo.modules.indoorperception

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.util.Base64
import android.util.Log
import android.view.Surface
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import expo.modules.navigationnative.NavigationSensorBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the ONE camera session for the whole app.
 *
 * ARCore requires exclusive access to the camera, and the navigation engine cannot run without
 * ARCore - depth and 6DoF pose are its only inputs. So the module that opens the camera has to be
 * the module that runs the session; they cannot be two different modules. A second `CameraView`
 * or Camera2 session will either fail to open or silently evict the first, with no crash and no
 * error - one of the two features just stops seeing.
 *
 * Perception owns it here, and the navigation engine is fed from the same frame:
 *
 *     session.update()
 *        |-- pose + depth  -> NavigationSensorBridge.submitFrame()
 *        |-- RGB image     -> YOLO / ML Kit OCR / Gemini
 *
 * Nothing downstream of `analyzeFrame(base64)` changes; only the source of the pixels does.
 */
object ArFrameSource {

    private const val TAG = "ArFrameSource"
    private const val JPEG_QUALITY = 55

    @Volatile
    var session: Session? = null
        private set

    @Volatile
    var depthSupported: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Nanoseconds since boot, from the frame the last capture came from. */
    @Volatile
    var lastCaptureTimestampNanos: Long = 0
        private set

    private val captureRequested = AtomicBoolean(false)

    /**
     * Rotation and JPEG encoding run here, not on the GL thread: the GL thread is the one feeding
     * navigation its frames, and a 30-50 ms encode there is a 30-50 ms hole in mapping.
     */
    private val encoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ar-capture-encode").apply { isDaemon = true }
    }

    /** Degrees the camera sensor is mounted at, relative to the device's natural orientation. */
    @Volatile
    private var sensorOrientationDegrees = 90

    /** The display's current rotation, as a Surface.ROTATION_* constant. */
    @Volatile
    private var displayRotation = Surface.ROTATION_0

    @Volatile
    private var pendingCapture: ((Result<CapturedFrame>) -> Unit)? = null

    data class CapturedFrame(
        val base64: String,
        val width: Int,
        val height: Int,
        /**
         * ARCore's frame timestamp in nanoseconds since boot. This - not a wall clock - is what
         * `SemanticObservation.timestampNs` expects, so observations derived from this frame can
         * be associated with the right depth data.
         */
        val timestampNanos: Long,
        /**
         * How far the image was rotated clockwise from the camera SENSOR's orientation to make it
         * upright on screen. [base64], [width] and [height] describe the upright image, which is
         * what the models and a VLM need; depth is still indexed in sensor orientation, so image
         * coordinates must be rotated back by this much before being sent to navigation.
         */
        val rotationDegrees: Int,
    )

    // ---------------------------------------------------------------- lifecycle

    /** @return null on success, or a human-readable reason. */
    fun initialize(context: Context): String? {
        if (session != null) return null
        return try {
            // Tell the navigation module to stand down before we claim the camera.
            NavigationSensorBridge.takeOverFrameSource()

            val created = Session(context)
            val config = created.config
            depthSupported = created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            sensorOrientationDegrees = readSensorOrientation(context, created)
            // Depth is what the navigation engine maps with. Without it, perception still works
            // but navigation must not be started - NavigationNative.isSupported() reports this.
            config.depthMode =
                if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.BLOCKING
            created.configure(config)

            // We own the session, so we are the only one who can answer this. Without the report,
            // the navigation module would see depthSupported = false and refuse to start.
            NavigationSensorBridge.reportCapabilities(depthSupported)

            session = created
            lastError = null
            null
        } catch (e: UnavailableException) {
            lastError = "ARCore unavailable: ${e.javaClass.simpleName}"
            Log.w(TAG, "session creation failed", e)
            lastError
        } catch (e: Exception) {
            lastError = "ARCore session failed: ${e.message}"
            Log.w(TAG, "session creation failed", e)
            lastError
        }
    }

    fun resume(): String? = try {
        session?.resume()
        null
    } catch (e: CameraNotAvailableException) {
        lastError = "Camera not available - is another camera session open?"
        Log.w(TAG, "resume failed", e)
        lastError
    }

    fun pause() {
        session?.pause()
    }

    fun destroy() {
        failPendingCapture(IllegalStateException("AR session destroyed"))
        session?.close()
        session = null
        NavigationSensorBridge.releaseFrameSource()
    }

    fun setCameraTexture(textureId: Int) = session?.setCameraTextureName(textureId)

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        displayRotation = rotation
        session?.setDisplayGeometry(rotation, width, height)
    }

    private fun readSensorOrientation(context: Context, session: Session): Int = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.getCameraCharacteristics(session.cameraConfig.cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    } catch (e: Exception) {
        // 90 is what virtually every phone's back camera reports.
        Log.w(TAG, "could not read sensor orientation; assuming 90", e)
        90
    }

    /** Clockwise rotation that turns a back-camera sensor image upright for the current display. */
    private fun uprightRotationDegrees(): Int {
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientationDegrees - displayDegrees + 360) % 360
    }

    // ---------------------------------------------------------------- per frame (GL thread)

    /**
     * Called once per rendered frame. Cheap by default: the navigation hand-off is a pose
     * conversion plus a subsampled depth pass, and the RGB image is only encoded when a capture
     * has actually been requested - converting every frame to JPEG would burn battery for nothing.
     */
    fun onDrawFrame() {
        val current = session ?: return
        try {
            val frame = current.update()
            NavigationSensorBridge.submitFrame(current, frame)
            if (captureRequested.getAndSet(false)) capture(frame)
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "camera not available during update", e)
        } catch (e: Throwable) {
            Log.w(TAG, "frame update failed", e)
        }
    }

    /** Asks the render loop for one RGB frame. The callback runs on the GL thread. */
    fun requestCapture(onResult: (Result<CapturedFrame>) -> Unit) {
        if (session == null) {
            onResult(Result.failure(IllegalStateException("AR session not started")))
            return
        }
        failPendingCapture(IllegalStateException("Superseded by a newer capture"))
        pendingCapture = onResult
        captureRequested.set(true)
    }

    /**
     * GL thread: copies the pixels out (the Image must be closed before this frame ends) and asks
     * navigation to keep this frame's depth. Everything slow happens on [encoder].
     */
    private fun capture(frame: Frame) {
        val callback = pendingCapture ?: return
        pendingCapture = null
        val timestamp = frame.timestamp
        val nv21: ByteArray
        val width: Int
        val height: Int
        try {
            frame.acquireCameraImage().use { image ->
                nv21 = toNv21(image)
                width = image.width
                height = image.height
            }
        } catch (e: NotYetAvailableException) {
            // The CPU image is not ready on this frame; try again on the next one.
            pendingCapture = callback
            captureRequested.set(true)
            return
        } catch (e: Throwable) {
            callback(Result.failure(e))
            return
        }
        // Must be now, on this thread, right after this frame went to navigation: by the time
        // perception reports what it saw, the camera will be pointing somewhere else.
        NavigationSensorBridge.onFrameCaptured(timestamp)
        lastCaptureTimestampNanos = timestamp

        val rotation = uprightRotationDegrees()
        encoder.execute {
            try {
                val upright = Nv21.rotate(nv21, width, height, rotation)
                val quarterTurn = rotation == 90 || rotation == 270
                val outWidth = if (quarterTurn) height else width
                val outHeight = if (quarterTurn) width else height
                val jpeg = nv21ToJpeg(upright, outWidth, outHeight)
                callback(
                    Result.success(
                        CapturedFrame(
                            base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
                            width = outWidth,
                            height = outHeight,
                            timestampNanos = timestamp,
                            rotationDegrees = rotation,
                        ),
                    ),
                )
            } catch (e: Throwable) {
                callback(Result.failure(e))
            }
        }
    }

    private fun failPendingCapture(cause: Throwable) {
        pendingCapture?.invoke(Result.failure(cause))
        pendingCapture = null
    }

    /**
     * YUV_420_888 -> NV21, the layout [YuvImage] (the only JPEG encoder available without a
     * third-party dependency) accepts. Row and pixel strides must be respected: on many devices
     * the rows are padded, and ignoring that produces a sheared image.
     */
    private fun toNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)

        // Y plane, row by row (rowStride may exceed width).
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        var offset = 0
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            yBuffer.get(nv21, offset, width)
            offset += width
        }

        // Interleave V and U as NV21 expects (VUVU...), honouring the pixel stride.
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            var uIndex = row * uPlane.rowStride
            var vIndex = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                nv21[offset++] = vBuffer.get(vIndex)
                nv21[offset++] = uBuffer.get(uIndex)
                uIndex += uPlane.pixelStride
                vIndex += vPlane.pixelStride
            }
        }

        return nv21
    }

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        return out.toByteArray()
    }
}
