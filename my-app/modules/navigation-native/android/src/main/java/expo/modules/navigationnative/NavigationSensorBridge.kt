package expo.modules.navigationnative

import com.google.ar.core.Frame
import com.google.ar.core.Session

/**
 * The integration point for a module that owns the ARCore session itself.
 *
 * WHY THIS EXISTS
 * ---------------
 * ARCore requires exclusive access to the camera, and this navigation engine cannot work without
 * ARCore - depth and 6DoF pose are its only inputs. So the module that opens the camera must also
 * be the module that runs the ARCore session; they cannot be two different modules, and a second
 * `CameraView` or Camera2 session will either fail to open or silently evict the first.
 *
 * That does not mean this module has to be the owner. This bridge lets the perception module own
 * the session and the camera, and simply pass each frame through. The engine downstream is
 * unchanged: it still only ever sees a canonical NavigationFrame.
 *
 * USAGE FROM THE OWNING MODULE
 * ----------------------------
 * 1. Add a dependency in that module's `android/build.gradle`:
 *
 *        implementation project(':navigation-native')
 *
 * 2. Tell this module to stand down, once, before navigation starts:
 *
 *        NavigationSensorBridge.takeOverFrameSource()
 *
 * 3. Create and configure the ARCore session as usual. Depth is required:
 *
 *        config.depthMode = Config.DepthMode.AUTOMATIC   // if isDepthModeSupported
 *        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
 *
 * 4. Per frame, on whichever thread called `Session.update()`:
 *
 *        val frame = session.update()
 *        NavigationSensorBridge.submitFrame(session, frame)   // navigation
 *        frame.acquireCameraImage().use { image -> ... }      // your YOLO / OCR / Gemini
 *
 * The RGB image is none of this module's business - read it from the same frame. Only the pose
 * and the depth image are consumed here, and the depth image is acquired, copied and closed
 * inside [submitFrame], so the frame remains usable afterwards.
 *
 * Everything on the JavaScript side is unchanged: `NavigationNative.start()`, the snapshot event
 * and the guidance adapter all behave identically. `NavigationArView` is simply not needed, since
 * this module no longer needs a GL surface of its own.
 */
object NavigationSensorBridge {

    /**
     * Hands session ownership to the caller. Call once, before `start()`.
     * Any session this module had open is released so the new owner can claim the camera.
     */
    fun takeOverFrameSource() = NavigationRuntime.useExternalFrameSource(true)

    /** Returns ownership to this module (it will create its own session again). */
    fun releaseFrameSource() = NavigationRuntime.useExternalFrameSource(false)

    val isExternallyOwned: Boolean get() = NavigationRuntime.externalFrameSource

    /**
     * Feeds one ARCore frame to the navigation engine.
     *
     * Cheap and bounded: a pose conversion and one subsampled depth unprojection. The heavy
     * mapping and planning work happens on the engine's own thread, so this is safe to call from
     * a render loop. Frames are conflated - if mapping falls behind, older frames are dropped
     * rather than queued, because steering someone from a stale map is worse than skipping it.
     */
    fun submitFrame(session: Session, frame: Frame) =
        NavigationRuntime.onExternalArFrame(session, frame)
}
