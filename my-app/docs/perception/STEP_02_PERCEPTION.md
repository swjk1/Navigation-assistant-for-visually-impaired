# Step 2 — Perception Service & Prompt Engineering

**Owner:** Person 1 (Perception Engineer)  
**Checkpoint snapshot:** `snapshots/snapshot_step2_perception_frame.json`  
**Review question:** *Inspect `PerceptionFrame` output. Does clock direction match target position?*

---

## Goal

Build the multimodal perception client with spatial prompt constraints, low temperature, AbortController timeout, and schema sanitization — producing a verified `PerceptionFrame`.

## Gemini policy for this checkpoint

**Default path does not call Gemini.**  
`PERCEPTION_MODE=mock` (or missing API key) returns deterministic fixtures that still go through `validateAndSanitizeFrame()`.

| Mode | When | Network |
| --- | --- | --- |
| `mock` | default / `PERCEPTION_MODE=mock` / no key | none |
| `live` | `PERCEPTION_MODE=live` + valid key | Gemini 2.0 Flash |

## What was delivered

| Artifact | Path |
| --- | --- |
| Schema sanitizer | `src/services/schemaValidator.js` |
| Perception engine | `src/services/perceptionEngine.js` |
| Mock fixtures | `src/constants/mockPerception.js` |
| Timed runner | `test/perception-engine.test.mjs` |
| Snapshot | `snapshots/snapshot_step2_perception_frame.json` |

## Prompt rules (embedded)

- Clock: 12 = center 20% corridor; 10–11 left; 1–2 right; 9/3 edges  
- Hazard: stairs down / obstacles within ~3 m ahead → `immediateHazard: true`  
- Output: JSON only; temperature `0.1`; abort at **3500 ms**

## How to run (no Gemini)

```bash
cd my-app
npm test
```

Forces mock mode, runs 10 timed iterations, writes the Step 2 snapshot.

## Hallway mock expectations (Step 2 fixture)

- Door at **11 o'clock**
- OCR includes **ROOM 204**
- `floorDetected: true`, `immediateHazard: false`

## Live Gemini (real performance)

1. Get a free key: https://aistudio.google.com/apikey  
2. Put it in `my-app/.env` (replace `your_gemini_api_key_here` on **both** key lines).  
3. Keep `PERCEPTION_MODE=live`.  
4. Smoke-test from PC:

```bash
npm run probe:gemini
```

That hits Gemini 2.0 Flash for real and writes `snapshots/snapshot_step2_perception_frame_live.json`.

**Never paste the key into chat or commit `.env`.**
