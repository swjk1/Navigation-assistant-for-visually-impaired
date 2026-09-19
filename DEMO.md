# Running the demo

Verified: the debug APK builds and contains ARCore, ONNX Runtime and the fine-tuned indoor model.
Not verified: anything actually running on a phone.

---

## ⚠ Build from a path with no spaces

This is the one thing that will waste your afternoon.

```
ninja: error: manifest 'build.ninja' still dirty after 100 tries
```

React Native's C++ build (reanimated / worklets) fails on Windows when the project path contains
spaces — and this repo lives in `navigation assistant for visually impaired`. The Kotlin modules
build fine; only the native C++ step breaks, so it looks unrelated.

Make a junction and build through that:

```powershell
New-Item -ItemType Junction -Path D:\navdemo -Target "D:\projects\navigation assistant for visually impaired"
cd D:\navdemo\my-app
```

The junction points at the same files, so edits either way are the same working tree.

---

## Build

```bash
cd my-app
npm install
npx expo prebuild --platform android

# android/local.properties — forward slashes; a lone backslash is a properties escape
echo 'sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk' > android/local.properties

cd android
./gradlew :app:assembleDebug -PreactNativeArchitectures=arm64-v8a
```

`-PreactNativeArchitectures=arm64-v8a` builds one ABI instead of four. Every ARCore-capable phone
is arm64; the full build takes about four times as long for nothing.

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or `npx expo run:android`
with a device attached.

**Expo Go will not work.** It cannot load custom native modules, and this app has two.

---

## What you need

| | |
|---|---|
| Device | Physical Android phone on the [ARCore supported list](https://developers.google.com/ar/devices) **with Depth API support** |
| Google Play Services for AR | Installed (the app prompts) |
| Model | `modules/indoor-perception/android/src/main/assets/indoor_yolo26.onnx` — committed, nothing to download |
| Gemini API key | **Optional.** Without it perception runs YOLO + ML Kit OCR on-device and `getPerceptionMode()` reports `mock` for the VLM stage only. The navigation demo does not need it. |

---

## Demo path

Launch → **Live navigation**.

1. Grant camera permission.
2. Tap **Explore**. Expect `SCAN` — "Stop. Look around slowly." Sweep the phone across the
   corridor for two or three seconds.
3. `LOCALIZING` → `EXPLORING`. Watch the debug line: `depth` in the low thousands, `floor`
   confidence climbing past 0.45, then `cells` and `frontiers` growing.
4. Walk. You should get `STRAIGHT` with occasional `TURN_LEFT` / `TURN_RIGHT`.
5. Put something in the path → `STOP`, hazard rhythm, "Stop. Obstacle ahead."
6. Tap **Find exit** or **Find door**. Perception feeds exit signs, doors and arrows to the
   engine every 2.5 s; once one is localized, status flips `EXPLORING` → `NAVIGATING`.
7. Cover the camera → `STOP` within a frame or two.

Other screens: **Engine debug** (navigation only, no perception) and **Perception** (capture and
inspect one frame).

---

## If something looks wrong

| Symptom | Likely cause |
|---|---|
| Stuck on `SCAN`, `depth` stays 0 | Device has no Depth API support. `isSupported()` reports it. |
| `SCAN` forever, depth fine, floor confidence low | Point the phone further down; the floor estimator needs to see the ground. |
| Perception finds nothing, no error | **Check the JPEG first.** The YUV→JPEG conversion has never run on hardware; a wrong row stride yields a sheared or green image that compiles and detects nothing. Dump one `captureFrame()` result and look at it. |
| `ERR_CAPTURE_TIMEOUT` | `<PerceptionArView />` is not mounted, or the AR session died. |
| Camera dead, no error at all | Two ARCore sessions. Only one screen may own the camera; check `setExternalFrameSource`. |

---

## Known limitations

- Nothing has been run on a device. Every line compiles; none has executed.
- The YUV→JPEG path is the most likely first failure.
- Boxes are mapped with a plain resize to 640×640, not letterboxed, so a very non-square capture
  distorts slightly.
- Multi-floor exists structurally (nodes carry `floorId`, vertical edges route) but nothing
  automates using a lift.
- This is an accessibility prototype, not a certified mobility device.
