package expo.modules.navigationnative

import expo.modules.kotlin.Promise
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

        // The app went to the background: ARCore must release the camera, and guidance stops.
        OnActivityEntersBackground {
            NavigationRuntime.onHostBackground()
            NavigationRuntime.pauseSession()
        }

        // Undo exactly what going to the background did - a user pause stays paused.
        OnActivityEntersForeground {
            NavigationRuntime.resumeSession()
            NavigationRuntime.onHostForeground()
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
            NavigationRuntime.submitSemanticObservations(parsed)
        }

        // ---------------------------------------------------------------- introspection

        /**
         * A picture of the occupancy grid for the debug UI: free space, obstacles, frontiers,
         * the planned path and the user's pose. The grid itself is never sent - 14 400 cells
         * several times a second is exactly the traffic this module exists to avoid.
         */
        AsyncFunction("getMapImage") { promise: Promise ->
            NavigationRuntime.renderMap { result ->
                result.fold(
                    onSuccess = { map ->
                        promise.resolve(
                            mapOf(
                                "base64" to map.base64,
                                "width" to map.width,
                                "height" to map.height,
                                "resolutionMeters" to map.resolutionMeters,
                                "sizeMeters" to map.sizeMeters,
                            ),
                        )
                    },
                    onFailure = { error ->
                        promise.reject("ERR_MAP_RENDER", error.message ?: "Render failed", error as? Exception)
                    },
                )
            }
        }

        /**
         * A picture of the RAW depth returns of the latest frame, in the same window and scale as
         * getMapImage, so the two can be toggled between and compared directly.
         *
         * The point cloud itself still never crosses the bridge - 6 000 points several times a
         * second is exactly the traffic getMapImage exists to avoid. This is a few kilobytes of
         * PNG plus the per-class counts, pulled on demand and only in debug mode.
         */
        AsyncFunction("getDepthImage") { promise: Promise ->
            NavigationRuntime.renderDepth { result ->
                result.fold(
                    onSuccess = { depth ->
                        promise.resolve(
                            mapOf(
                                "base64" to depth.base64,
                                "width" to depth.width,
                                "height" to depth.height,
                                "resolutionMeters" to depth.resolutionMeters,
                                "sizeMeters" to depth.sizeMeters,
                                "totalReturns" to depth.totalReturns,
                                "floorReturns" to depth.floorReturns,
                                "obstacleReturns" to depth.obstacleReturns,
                                "overheadReturns" to depth.overheadReturns,
                                "belowFloorReturns" to depth.belowFloorReturns,
                                "outOfRangeReturns" to depth.outOfRangeReturns,
                                "offWindowReturns" to depth.offWindowReturns,
                                "floorY" to depth.floorY,
                                "floorConfidence" to depth.floorConfidence,
                                "ageMillis" to depth.ageMillis,
                                "hasFrame" to depth.hasFrame,
                            ),
                        )
                    },
                    onFailure = { error ->
                        promise.reject("ERR_DEPTH_RENDER", error.message ?: "Render failed", error as? Exception)
                    },
                )
            }
        }

        Function("getSnapshot") {
            NavigationRuntime.currentSnapshot().toEventMap()
        }

        Function("setDebugEnabled") { enabled: Boolean ->
            NavigationRuntime.debugEnabled = enabled
        }

        /**
         * Hands ARCore session ownership to another native module (see NavigationSensorBridge).
         * Call before start(). While external, this module creates no session and
         * <NavigationArView /> is unnecessary.
         */
        Function("setExternalFrameSource") { external: Boolean ->
            NavigationRuntime.useExternalFrameSource(external)
        }

        Function("isExternalFrameSource") {
            NavigationRuntime.externalFrameSource
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
