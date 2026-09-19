# Handing the camera to the perception module

**For:** Person 1 (perception — `modules/indoor-perception`)
**From:** Person 2 (mapping + planning — `modules/navigation-native`)

Short version: your module opens the camera. You just have to open it **through ARCore** rather
than through `expo-camera`'s `CameraView`, and pass each frame along. Everything downstream of
`analyzeFrame(base64)` — YOLO, ML Kit, Gemini, `PerceptionFrame` — is untouched.

---

## Why this is needed

ARCore requires **exclusive** access to the camera device. The navigation engine cannot run
without ARCore: depth and 6DoF pose are its only inputs, and it has no other way to know where a
wall is.

`CameraView` opens the camera device too. Android will not give the same camera to two sessions,
so whichever starts second either fails to open or silently evicts the first. No crash, no error —
one of the two features simply stops seeing.

Nothing is broken today, because the perception harness and the navigation screen are separate
screens. It breaks the first time both are live together.

The good news: your heavy lifting is already decoupled from capture. `IndoorPerceptionModule`
takes a base64 image and doesn't touch the camera. Only the few lines that *source* the pixels
change.

---

## What changes

### 1. Gradle — depend on this module

`modules/indoor-perception/android/build.gradle`:

```groovy
dependencies {
  implementation project(':navigation-native')
  implementation "com.google.ar:core:1.56.0"
}
```

### 2. Tell the navigation module to stand down

Once, before navigation starts. From Kotlin:

```kotlin
NavigationSensorBridge.takeOverFrameSource()
```

or from JS:

```ts
NavigationNative.setExternalFrameSource(true);
```

After this, `navigation-native` creates no ARCore session of its own, and `<NavigationArView />`
is no longer needed.

### 3. Create the session — depth is required

```kotlin
val session = Session(context)
val config = session.config
if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
    config.depthMode = Config.DepthMode.AUTOMATIC
} // if not supported, navigation must not run on this device
config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL   // floor hint
config.updateMode = Config.UpdateMode.BLOCKING
session.configure(config)
```

ARCore needs a GL context and a camera texture to produce frames. The simplest route is to copy
`NavigationArView.kt` from this module — it is about 60 lines and renders nothing; it exists only
to give ARCore a surface and call `update()` once per frame.

### 4. Per frame — one grab, two consumers

```kotlin
val frame = session.update()

// navigation: pose + depth
NavigationSensorBridge.submitFrame(session, frame)

// perception: the RGB image, straight off the same frame
frame.acquireCameraImage().use { image ->
    val base64 = yuvToJpegBase64(image)      // replaces takePictureAsync
    // ... existing analyzeFrame(base64) pipeline, unchanged
}
```

`submitFrame` is cheap and bounded — a pose conversion and one subsampled depth unprojection.
The mapping and planning work happens on the engine's own thread, so it is safe from a render
loop. Frames are conflated: if mapping falls behind, old frames are dropped rather than queued.

The depth image is acquired, copied and closed inside `submitFrame`, so the frame stays usable.

### 5. Drop `CameraView`

`perception-harness.tsx` and `cameraService.captureFrame()` can go, along with
`takePictureAsync`. You will need a YUV_420_888 → JPEG conversion in their place; ARCore's
`hello_ar` sample has one, or ML Kit can take the `Image` directly without any conversion.

---

## Two things you get for free

**Correct timestamps.** Frames sourced from ARCore carry `frame.timestamp` — nanoseconds since
boot, the value `SemanticObservation.timestampNs` expects. Right now `PerceptionFrame.timestamp`
is a wall clock in milliseconds, which is a different clock domain entirely; passing it would put
every observation billions of nanoseconds out of range and the depth association would reject all
of them silently. (This module currently detects that and logs a warning, but getting it right at
the source is better.)

**A pose per observation.** Each frame comes with the camera pose, so a sign you read can be
placed in the world rather than just described relative to the user.

---

## If you would rather not own ARCore

The alternative is a third module that owns the session and both of you consume. Same single
session, but neither module depends on the other — which also means your module stays buildable
and testable on its own. Happy to set that up instead; it is the same work arranged differently.

Either way, the constraint is the same: **exactly one ARCore session, and nothing else opens the
camera.**
