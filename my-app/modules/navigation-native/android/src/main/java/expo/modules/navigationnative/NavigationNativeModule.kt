package expo.modules.navigationnative

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * The JavaScript-facing surface of the navigation engine.
 *
 * Deliberately small (architecture Rule 2): JS sends commands and receives a compact
 * NavigationSnapshot. Depth maps, camera frames, point clouds and occupancy grids never cross
 * this boundary. No ARCore type is exposed either, so the identical API can be implemented by a
 * Swift/ARKit module without React Native code changing.
 */
class NavigationNativeModule : Module() {

    override fun definition() = ModuleDefinition {
        Name("NavigationNative")

        Events(EVENT_NAVIGATION_STATE)

        OnCreate {
            NavigationRuntime.attach(appContext.reactContext ?: return@OnCreate)
            NavigationRuntime.snapshotListener = { snapshot ->
                try {
                    sendEvent(EVENT_NAVIGATION_STATE, snapshot.toEventMap())
                } catch (e: Throwable) {
                    // The bridge can be torn down between the engine thread producing a snapshot
                    // and this callback running; losing one snapshot is harmless.
                }
            }
        }

        OnDestroy {
            NavigationRuntime.snapshotListener = null
            NavigationRuntime.stop()
            NavigationRuntime.destroySession()
        }

        // The app went to the background: ARCore must release the camera.
        OnActivityEntersBackground {
            NavigationRuntime.pause()
            NavigationRuntime.pauseSession()
        }

        OnActivityEntersForeground {
            NavigationRuntime.resumeSession()
        }

        // ---------------------------------------------------------------- capability

        Function("isSupported") {
            val context = appContext.reactContext
            if (context == null) {
                mapOf(
                    "arCoreSupported" to false,
                    "depthSupported" to false,
                    "trackingAvailable" to false,
                    "reason" to "No React context",
                )
            } else {
                val support = NavigationRuntime.support(context)
                mapOf(
                    "arCoreSupported" to support.arCoreSupported,
                    "depthSupported" to support.depthSupported,
                    "trackingAvailable" to support.trackingAvailable,
                    "reason" to support.reason,
                )
            }
        }

        // ---------------------------------------------------------------- session control

        AsyncFunction("start") { target: TargetRecord? ->
            val context = appContext.reactContext
                ?: throw IllegalStateException("No React context")
            NavigationRuntime.initializeSession(context, appContext.activityProvider?.currentActivity)
            NavigationRuntime.start((target ?: TargetRecord()).toNavigationTarget())
        }

        AsyncFunction("stop") {
            NavigationRuntime.stop()
        }

        AsyncFunction("pause") {
            NavigationRuntime.pause()
        }

        AsyncFunction("resume") {
            NavigationRuntime.resume()
        }

        AsyncFunction("resetMap") {
            NavigationRuntime.resetMap()
        }

        AsyncFunction("setTarget") { target: TargetRecord ->
            NavigationRuntime.setTarget(target.toNavigationTarget())
        }

        // ---------------------------------------------------------------- perception hand-off

        AsyncFunction("submitSemanticObservations") { observations: List<SemanticObservationRecord> ->
            val parsed = observations.mapNotNull { it.toObservation() }
            NavigationRuntime.submitSemanticObservations(parsed, System.currentTimeMillis())
        }

        // ---------------------------------------------------------------- introspection

        Function("getSnapshot") {
            NavigationRuntime.currentSnapshot().toEventMap()
        }

        Function("setDebugEnabled") { enabled: Boolean ->
            NavigationRuntime.debugEnabled = enabled
        }

        // ---------------------------------------------------------------- the AR view

        View(NavigationArView::class) {
            Prop("debug") { _: NavigationArView, enabled: Boolean ->
                NavigationRuntime.debugEnabled = enabled
            }
        }
    }

    private companion object {
        const val EVENT_NAVIGATION_STATE = "onNavigationState"
    }
}
