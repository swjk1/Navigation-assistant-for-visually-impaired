# Step 1 — Secure Repository Setup & Hardware Bootstrap

**Owner:** Person 1 (Perception Engineer)  
**Checkpoint snapshot:** `snapshots/snapshot_step1_hardware.json`  
**Review question:** *Did camera capture a sample image with Base64 size < 150KB and latency < 150ms?*

---

## Goal

Create a secure project layout, isolate Gemini credentials, and provide a low-latency camera capture harness that produces compressed Base64 frames suitable for multimodal inference.

## What was delivered

| Artifact | Path | Purpose |
| --- | --- | --- |
| Root ignore rules | `/.gitignore` | Blocks `.env`, keys, Expo caches, live captures, logs |
| App ignore rules | `my-app/.gitignore` | Same secret + artifact policy inside the Expo app |
| Env template | `my-app/.env.example` | Documents required keys without committing secrets |
| Perception contract | `my-app/src/types/perception.ts` | Shared `PerceptionFrame` types for Persons 2 & 3 |
| Env sanitizer | `my-app/src/services/envCheck.js` | Throws if key missing or still the placeholder |
| Camera config | `my-app/src/constants/cameraConfig.js` | 640×480 JPEG @ 0.4, ≤150 ms / ≤150 KB budgets |
| Capture harness | `my-app/src/services/cameraService.js` | `captureFrame()` + `buildHardwareSnapshot()` |
| Live capture sink | `my-app/fixtures/live_captures/` | Ignored folder for venue photos |
| Edge fixtures stub | `my-app/test/fixtures/` | Placeholder for Step 3 golden JPEGs |

## Capture targets

- **Resolution:** 640 × 480  
- **Format:** JPEG, quality `0.4`  
- **Facing:** rear / environment  
- **Latency budget (capture only):** ≤ 150 ms  
- **Payload budget:** Base64 decoded size < 150 KB  

## How to verify on hardware

1. Copy env template and add your Gemini key (needed from Step 2 onward; not required for pure camera capture):

   ```bash
   cd my-app
   cp .env.example .env
   ```

2. Confirm `.env` is ignored:

   ```bash
   git status --ignored
   # .env should appear under Ignored files
   ```

3. Install camera dependency (if not already):

   ```bash
   npx expo install expo-camera
   ```

4. From a mounted `CameraView` ref, call:

   ```js
   import { captureFrame, buildHardwareSnapshot } from '@/services/cameraService';
   import * as FileSystem from 'expo-file-system'; // optional: write snapshot JSON

   const result = await captureFrame(cameraRef.current);
   const snapshot = buildHardwareSnapshot(result, {
     permissionGranted: true,
     platform: Platform.OS,
   });
   // Persist to snapshots/snapshot_step1_hardware.json
   ```

5. Pass criteria for this checkpoint:
   - `permissionGranted === true`
   - `capture.looksLikeBase64 === true`
   - `budgets.withinSizeBudget === true` (< 150 KB)
   - `budgets.withinLatencyBudget === true` (< 150 ms) — soft on simulators; prioritize physical device

## Security notes

- Never commit `.env` or paste API keys into source, snapshots, or chat logs.
- Snapshots store only a short Base64 *prefix*, never the full frame.
- `requireGeminiApiKey()` blocks the placeholder string `your_gemini_api_key_here`.

## Status

- [x] Directory layout  
- [x] `.gitignore` + `.env.example`  
- [x] Types + camera harness + env guard  
- [x] Offline timed snapshot run (`npm run profile:capture`)  
- [ ] Live device capture confirming <150 KB and <150 ms (needs physical phone)

### Offline timing (latest)

```bash
npm run profile:capture
```

Writes:

- `snapshots/snapshot_step1_hardware.json`
- `snapshots/snapshot_step1_timing_profile.json`

Live `captureLatencyMs` stays `null` until `/perception-harness` runs on a phone.
