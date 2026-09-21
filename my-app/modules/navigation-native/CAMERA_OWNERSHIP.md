# Who owns the camera

**Short answer: `indoor-perception` owns the ARCore session. `navigation-native` is fed from it.**

This file previously proposed the opposite arrangement — navigation owning the session and
handing frames to perception — as work still to be done. That is not what was built, and the
inverted description outlived the decision. What follows describes the code as it stands.

---

## Why there can only be one owner

ARCore requires **exclusive** access to the camera device, and the navigation engine cannot run
without ARCore: depth and 6DoF pose are its only inputs. A second `CameraView` or Camera2 session
will either fail to open or silently evict the first — no crash, no error, one of the two
features simply stops seeing.

So the module that opens the camera must also be the module that runs the ARCore session. They
cannot be two different modules.

## How ownership actually sits

`indoor-perception` opens the camera, because it is the module that needs the RGB image (YOLO, ML
Kit OCR, and optionally Gemini). Navigation needs only pose and depth, which can be read off the
same frame.

```
PerceptionArView mounts
  └─ ArFrameSource.initialize(context)
       ├─ NavigationSensorBridge.takeOverFrameSource()    ← navigation stands down
       ├─ Session(context), depthMode = AUTOMATIC
       └─ NavigationSensorBridge.reportCapabilities(depthSupported)

per rendered frame, on the GL thread:
  ArFrameSource.onDrawFrame()
    └─ session.update()
         ├─ NavigationSensorBridge.submitFrame(session, frame)   → pose + depth → engine
         └─ frame.acquireCameraImage()                           → YOLO / OCR / Gemini
```

`ArFrameSource.destroy()` closes the session and calls `releaseFrameSource()`, handing ownership
back.

## What enforces it

This is not a convention anyone has to remember. In `NavigationRuntime`:

- `initializeSession()` returns early while `externalFrameSource` is set, so navigation cannot
  claim the camera out from under the owner.
- `onGlFrame()` returns early for the same reason, so a stray `NavigationArView` renders nothing
  rather than driving a second update loop.
- `useExternalFrameSource(true)` pauses any session this module already had, releasing its claim.
- `useExternalFrameSource(false)` clears the cached external `Session` reference, so `session()`
  cannot hand out a closed session after the owner tears down.

`NavigationArView` is not needed in this mode and `/navigate` does not mount it.

## The one screen that owns it the other way

`/navigation-debug` is an engine-only diagnostics screen with no perception. It calls
`setExternalFrameSource(false)` on mount to reclaim ownership, then mounts `NavigationArView` and
runs its own session.

The flag is global and process-wide, which is what makes this work — and also the thing to be
careful about. **Do not mount both screens at once**, and if you add a third screen, decide
explicitly which side owns the session on mount.

## Adding a new consumer of camera frames

Do not open a camera. Read the frame you are given:

1. Depend on this module in your `android/build.gradle`:

   ```groovy
   implementation project(':navigation-native')
   ```

2. Call `NavigationSensorBridge.takeOverFrameSource()` once, before navigation starts.

3. Create and configure the ARCore session. Depth is required by the engine:

   ```kotlin
   config.depthMode = Config.DepthMode.AUTOMATIC   // when isDepthModeSupported
   config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
   ```

4. Report what the session can do, once:

   ```kotlin
   NavigationSensorBridge.reportCapabilities(depthSupported)
   ```

   Without this, `isSupported()` reports `depthSupported: false` on a perfectly capable phone and
   navigation refuses to start — the flag can only be read from an open session, and in external
   mode this module never opens one.

5. Per frame, on whichever thread called `Session.update()`:

   ```kotlin
   val frame = session.update()
   NavigationSensorBridge.submitFrame(session, frame)   // navigation
   frame.acquireCameraImage().use { image -> /* your work */ }
   ```

The depth image is acquired, copied and closed inside `submitFrame`, so the frame stays usable
afterwards. The RGB image is none of this module's business.

Everything on the JavaScript side is unchanged either way: `NavigationNative.start()`, the
snapshot event and the guidance adapter behave identically.
