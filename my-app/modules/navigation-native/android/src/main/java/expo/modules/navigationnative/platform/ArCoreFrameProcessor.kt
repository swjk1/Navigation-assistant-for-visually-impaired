package expo.modules.navigationnative.platform

import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.sensing.CameraIntrinsics
import com.navassist.navcore.sensing.DepthImage
import com.navassist.navcore.sensing.DepthSnapshotStore
import com.navassist.navcore.sensing.PlaneObservation
import com.navassist.navcore.sensing.SensorFrameAssembler
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The LAST place in the stack that knows what ARCore is.
 *
 *   ARCore Frame -> camera matrix, depth image, planes -> SensorFrameAssembler -> NavigationFrame
 *
 * All it does is copy ARCore's buffers into platform-neutral types. The canonical frame, depth
 * deduplication, pose matching, unprojection, the floor hint and the depth snapshots all live in
 * navigation-core ([SensorFrameAssembler]), so the simulator exercises the same code a phone runs.
 *
 * Things handled here because they are ARCore's: [Frame.acquireDepthImage16Bits] throws
 * [NotYetAvailableException] routinely (normal, not an error), images are ALWAYS closed via
 * `use {}`, and rows may be padded (row and pixel strides are respected).
 */
class ArCoreFrameProcessor(private val config: NavigationConfig) {

    private val assembler = SensorFrameAssembler(config) { offByMillis ->
        Log.w(
            TAG,
            "Semantic observation timestamp is not an ARCore frame timestamp (off by $offByMillis ms); " +
                "using the most recent depth frame instead. Pass the capture's timestampNs, or omit it.",
        )
    }

    private val cameraMatrix = FloatArray(16)
    private val depthImage = DepthImage()
    private val planes = ArrayList<PlaneObservation>()

    var lastTrackingState: TrackingState = TrackingState.STOPPED
        private set

    val lastDepthPointCount: Int get() = assembler.lastPointCount

    /**
     * Which coordinate frame the frames produced here belong to. Changes only on the GL thread,
     * together with the canonical frame itself, so a frame and its generation can never disagree.
     */
    var generation: Int = 0
        private set

    private val pendingResetGeneration = AtomicInteger(NO_RESET)

    /**
     * Asks for the canonical frame to be re-established, from any thread. It happens at the start
     * of the next [process] call - never in the middle of one, which is what resetting directly
     * from the JS or engine thread used to risk: a frame converted with half-old, half-new basis.
     * Frames produced from then on carry [newGeneration].
     */
    fun requestReset(newGeneration: Int) {
        pendingResetGeneration.set(newGeneration)
    }

    fun process(session: Session, frame: Frame): NavigationFrame {
        val requested = pendingResetGeneration.getAndSet(NO_RESET)
        if (requested != NO_RESET) {
            assembler.reset()
            lastTrackingState = TrackingState.STOPPED
            generation = requested
        }

        val camera = frame.camera
        lastTrackingState = camera.trackingState
        if (camera.trackingState != TrackingState.TRACKING) {
            return assembler.assemble(frame.timestamp, false, cameraMatrix, null, null)
        }

        // The physical camera's pose (sensor axes), not the display-oriented one: depth pixels
        // are in sensor orientation.
        camera.pose.toMatrix(cameraMatrix, 0)
        val haveDepth = copyDepth(frame)
        val intrinsics = if (haveDepth) {
            val k = camera.imageIntrinsics
            CameraIntrinsics(
                fx = k.focalLength[0],
                fy = k.focalLength[1],
                cx = k.principalPoint[0],
                cy = k.principalPoint[1],
                width = k.imageDimensions[0],
                height = k.imageDimensions[1],
            ).scaledTo(depthImage.width, depthImage.height)
        } else {
            null
        }
        collectPlanes(session)

        return assembler.assemble(
            timestampNanos = frame.timestamp,
            tracking = true,
            cameraToWorld = cameraMatrix,
            depth = if (haveDepth) depthImage else null,
            depthIntrinsics = intrinsics,
            planes = planes,
        )
    }

    /** Copies ARCore's newest depth image into [depthImage]. False when there is nothing new. */
    private fun copyDepth(frame: Frame): Boolean = try {
        frame.acquireDepthImage16Bits().use { image ->
            // ARCore hands back the same depth image for several camera frames; skip the copy.
            if (!assembler.isNewDepth(image.timestamp)) {
                false
            } else {
                val plane = image.planes[0]
                val buffer = plane.buffer.order(ByteOrder.nativeOrder())
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                depthImage.resize(image.width, image.height)
                depthImage.timestampNanos = image.timestamp
                val out = depthImage.depthMm
                var index = 0
                for (v in 0 until image.height) {
                    val rowBase = v * rowStride
                    for (u in 0 until image.width) {
                        out[index++] = buffer.getShort(rowBase + u * pixelStride)
                    }
                }
                true
            }
        }
    } catch (e: NotYetAvailableException) {
        // Depth is simply not ready yet. The engine tolerates gaps and only escalates to SCAN
        // once depth has been missing for longer than depthStarvationMillis.
        false
    } catch (e: IllegalStateException) {
        // Session in a state where depth cannot be acquired (e.g. paused mid-frame).
        false
    }

    private fun collectPlanes(session: Session) {
        planes.clear()
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            if (plane.subsumedBy != null) continue
            val center = plane.centerPose
            planes.add(PlaneObservation(center.tx(), center.ty(), center.tz(), plane.extentX * plane.extentZ))
        }
    }

    /** Resolves a semantic observation against the depth of the frame it was seen in. Any thread. */
    fun resolve(
        normalizedX: Float?,
        normalizedY: Float?,
        timestampNanos: Long?,
    ): DepthSnapshotStore.Resolution = assembler.resolve(normalizedX, normalizedY, timestampNanos)

    /** Keeps the depth for a frame whose RGB image was just captured for perception. GL thread. */
    fun pinDepth(timestampNanos: Long) = assembler.pinDepth(timestampNanos)

    private companion object {
        const val TAG = "ArCoreFrameProcessor"
        const val NO_RESET = -1
    }
}
