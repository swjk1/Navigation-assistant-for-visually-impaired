# my-app

The Expo client, the two native modules and the navigation engine. Everything that runs on the
phone lives here.

This README used to cover only the perception layer. The layer-specific material is now in
[`docs/`](docs/README.md); what follows is how to run and check the app as a whole.

---

## Setup

```bash
npm install
cp .env.example .env
```

`.env` is gitignored. Read the comments in `.env.example` before choosing which Gemini key name
to set — `GEMINI_API_KEY` and `EXPO_PUBLIC_GEMINI_API_KEY` are not interchangeable, and only the
second one is visible to code running on a phone.

Confirm the guardrails before staging anything:

```bash
git status --ignored     # .env must appear under "Ignored files"
npm run audit:security
```

## Running

```bash
npm run android     # dev build; Expo Go cannot load the native modules
```

An ARCore device with Depth API support is required for navigation. Perception alone works on any
Android device once the dev build is installed.

| Screen | Route | What it is |
| --- | --- | --- |
| Home | `/` | Voice input, transcript, spoken command playback |
| Navigate | `/navigate` | The integrated flow: perception, mapping, guidance, live scan map |
| Navigation debug | `/navigation-debug` | Engine-only diagnostics. Owns the ARCore session itself |
| Perception harness | `/perception-harness` | Single-frame perception, for checking models and latency |

`/navigate` and `/navigation-debug` take opposite sides of ARCore session ownership. Do not open
both at once — see [`modules/navigation-native/CAMERA_OWNERSHIP.md`](modules/navigation-native/CAMERA_OWNERSHIP.md).

## Checks

```bash
npm run verify      # lint + type-check + tests. What CI runs.

npm test            # offline suite, node:test
npm run test:watch
npm run lint
npm run typecheck   # tsconfig.json (app) then tsconfig.node.json (scripts + tests)
npm run test:core   # Kotlin navigation engine; needs JDK 21 on PATH
```

Nothing under `npm test` touches the network, a Gemini key or a device.

### Diagnostics

These do need a network, a key or a phone, so they are never part of `npm test` or CI. Each
writes a JSON record under `snapshots/`.

```bash
npm run probe:gemini        # one live Gemini round-trip
npm run probe:hybrid        # the YOLO + OCR + gated-VLM pipeline
npm run profile:capture     # camera capture timing against the budget
npm run profile:latency     # end-to-end latency distribution
npm run audit:security      # key handling and gitignore guardrails
npm run report:prd          # PRD checklist against recorded snapshots
npm run report:handoff      # the consumer-facing handoff bundle
npm run report:verification # aggregate verification record
```

## Layout

```
src/
  app/            Expo Router screens
  guidance/       Speech + haptics, and the engine-snapshot -> command seam
  perception/     PerceptionFrame -> semantic observations for the engine
  services/       JS perception stack: Gemini, sanitizer, handoff  (see src/index.js)
  constants/      perceptionCatalog.js is the ONE source of truth for labels and clocks
  types/          The frozen contracts. Label unions derive from perceptionCatalog.js
modules/
  indoor-perception/    Owns the ARCore session. YOLO (ONNX) + ML Kit OCR
  navigation-native/    ARCore pose/depth -> navigation engine; the JS bridge
navigation-core/        The engine. Pure Kotlin, separate Gradle project, no Android SDK
test/                   Offline suite
scripts/                Diagnostics
snapshots/              Recorded diagnostic output. Not test fixtures
```

### Two perception paths

Worth knowing before changing either:

- **Shipping path.** `/navigate` calls the native `indoor-perception` module directly — YOLO and
  ML Kit OCR on-device, against the live ARCore session.
- **Development path.** `src/index.js` and `src/services/` are the Gemini + fixtures pipeline.
  They are reached from `/perception-harness` and the offline tests, and are how the perception
  contract can be exercised without an ARCore phone. `/navigate` does not use them.

## The perception contract

Consumers depend only on `PerceptionFrame` and the handoff helpers:

```js
import {
  analyzeAndStore,
  getLatestPerceptionFrame,
  getMockTeammateHandoff,
} from './src/index.js';

// Offline bootstrap - no camera, no key
const handoff = getMockTeammateHandoff('hallway');

// After a capture
await analyzeAndStore(base64Jpeg);
const frame = getLatestPerceptionFrame();
```

Labels and clock positions come from `src/constants/perceptionCatalog.js`, and the TypeScript
unions in `src/types/perception.ts` are derived from it. Add a label in the catalog and the type
follows; there is nowhere else to add one.

Latency budget, round-trip ≤ 1500 ms: capture ≤ 150 ms · inference + parse ≤ 1200 ms ·
schema ≤ 15 ms.

Full integration notes: [`docs/perception/TEAMMATE_INTEGRATION.md`](docs/perception/TEAMMATE_INTEGRATION.md).

## Documentation

See [`docs/README.md`](docs/README.md) for the map, including which documents are maintained and
which are a historical build log.
