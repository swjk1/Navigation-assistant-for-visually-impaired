# Perception Engine (Person 1)

The Perception Engine handles real-time camera capture, spatial object recognition, OCR reading, and hazard detection for the indoor assistive navigation system.

> **Integration note:** This module lives inside `my-app/` (Expo SDK 57). Paths below are relative to `my-app/` unless stated otherwise.

---

## Setup & Installation

### 1. Environment Configuration

Ensure your API keys are protected. Never commit `.env` or hardcode keys.

```bash
cd my-app
cp .env.example .env
```

Open `.env` and add your valid Google Gemini API key:

```env
GEMINI_API_KEY=your_gemini_api_key_here
```

Prefer `GEMINI_API_KEY` over `EXPO_PUBLIC_GEMINI_API_KEY` — the public Expo prefix embeds the key in the client bundle.
### 2. Verify Security Guardrails

Check that `.env` is ignored by git before staging files:

```bash
git status --ignored
# Verify .env appears under "Ignored files"
```

### 3. Install Dependencies

```bash
npm install
npx expo install expo-camera
```

---

## Architecture (Person 1 scope)

```text
Camera (640×480 JPEG @ 0.4)
        │ Base64
        ▼
hybridPerception.js  →  YOLO26n + ML Kit OCR + gated Gemini Flash
        │ raw JSON
        ▼
schemaValidator.js   →  verified PerceptionFrame
        │
        ▼
src/index.js (getLatestPerceptionFrame / mocks)
        │
        ▼
Person 2 (Mapping) / Person 3 (Guidance / TTS)
```

Latency budget (round-trip ≤ 1500 ms): capture ≤ 150 ms · inference+parse ≤ 1200 ms · schema ≤ 15 ms.

---

## Step documentation

| Step | Doc | Snapshot |
| --- | --- | --- |
| 1 Hardware & secrets | [docs/person1/STEP_01_HARDWARE.md](docs/person1/STEP_01_HARDWARE.md) | `snapshots/snapshot_step1_hardware.json` |
| 2 Perception service | [docs/person1/STEP_02_PERCEPTION.md](docs/person1/STEP_02_PERCEPTION.md) | `snapshots/snapshot_step2_perception_frame.json` |
| Hybrid YOLO+OCR+VLM | [docs/person1/HYBRID_YOLO_OCR_VLM.md](docs/person1/HYBRID_YOLO_OCR_VLM.md) | `snapshots/snapshot_hybrid_yolo_ocr_vlm.json` |
| 3 Schema & edge cases | [docs/person1/STEP_03_EDGE_CASES.md](docs/person1/STEP_03_EDGE_CASES.md) | `snapshots/snapshot_step3_edge_cases.json` |
| 4 Latency profile | [docs/person1/STEP_04_LATENCY.md](docs/person1/STEP_04_LATENCY.md) | `snapshots/snapshot_step4_latency_profile.json` |
| 5 Integration contract | [docs/person1/STEP_05_INTEGRATION.md](docs/person1/STEP_05_INTEGRATION.md) | `snapshots/snapshot_step5_final_contract.json` |

---

## Testing & Snapshots

### Run regressions

```bash
npm run test:step5
npm run test:handoff
npm run test:step3
npm run test:step4
```

### Snapshot Structure

Every pipeline stage produces a frozen JSON snapshot in `/snapshots`:

* `snapshot_step1_hardware.json` — Hardware & camera verification metrics
* `snapshot_step2_perception_frame.json` — Base live perception response
* `snapshot_step3_edge_cases.json` — Regression results across hazards, drop-offs, and signs
* `snapshot_step4_latency_profile.json` — Processing times and token efficiency
* `snapshot_step5_final_contract.json` — Validated contract signed off for Person 2

---

## Public contract (after Step 5)

```js
import { getLatestPerceptionFrame } from './src/index.js';
```

Persons 2 and 3 should depend only on `PerceptionFrame` / handoff helpers from [`src/index.js`](src/index.js) — see [`docs/person1/TEAMMATE_INTEGRATION.md`](docs/person1/TEAMMATE_INTEGRATION.md).
