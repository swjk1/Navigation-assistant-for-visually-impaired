# Person 1 → Teammate Integration

## Project goal (shared)

Help a **blind / low-vision user walk indoors** (for example toward a door).

| Person | Owns |
|---|---|
| **1 (this module)** | Camera + vision → verified `PerceptionFrame` |
| **2** | Mapping / planning / navigation logic |
| **3** | Guidance UX + **text-to-speech** |

Person 1 does **not** compute routes or speak. We format “what’s ahead” so 2 & 3 can.

---

## Import (teammates)

```js
import {
  getMockPerceptionFrame,
  getMockTeammateHandoff,
  getLatestPerceptionFrame,
  analyzeAndStore,
  listSpeakableLines,
  listNavAnchors,
} from './src/index.js';
```

### Offline (no camera)

```js
const handoff = getMockTeammateHandoff('hallway');
// handoff.frame              → PerceptionFrame
// handoff.navAnchors         → doors / elevators / stairs / signs (nearest first)
// handoff.obstacles          → person / chair / trashcan
// handoff.signs              → ["ROOM 204", ...]
// handoff.speakableLines     → strings Person 3 can feed to TTS
// handoff.speechPriority     → "hazard" | "guidance"
```

### Live (after a capture)

```js
await analyzeAndStore(base64Jpeg);
const frame = getLatestPerceptionFrame();
const lines = listSpeakableLines(frame);
```

---

## Contract (`PerceptionFrame`)

See `src/types/perception.ts`.

Important for walking to doors:

- `objects[].label` + `clockPosition` + `approxDistanceMeters` → Person 2 targets / Person 3 “door at 11 o’clock”
- `text[]` → destination confirmation (“Sign reads ROOM 204”)
- `immediateHazard` + `hazardDescription` → Person 3 should speak **first** and stop the user if needed

---

## Speakable line examples (hallway mock)

- `Door at 11 o'clock, about 3.5 meters.`
- `Sign reads ROOM 204.`

Hazard example (stairs mock):

- `Descending stairs ahead within 3 meters. Stop.`

Person 3 chooses voice engine, pacing, and interruption — we only supply the lines.

---

## Step 5 checkpoint

Run `npm test` → `snapshots/snapshot_step5_final_contract.json`.

Full guide: [STEP_05_INTEGRATION.md](./STEP_05_INTEGRATION.md).
