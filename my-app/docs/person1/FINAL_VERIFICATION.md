# Person 1 — Final Verification (PRD §7–§8)

**Owner:** Person 1  
**Checkpoint:** `snapshots/snapshot_final_verification.json`  
**Note:** The original PRD has **no Step 6**. After Step 5, remaining work is the verification table (§7) and complete README (§8).

---

## PRD §7 review answers

| Step | Review question | Result |
|---|---|---|
| 1 | Base64 &lt; 150KB and capture &lt; 150ms? | Size **pass** (offline). Capture latency **phone pending**. |
| 2 | Clock direction match target? | **Pass** — door @ 11 o'clock, ROOM 204 |
| 3 | Hazards on stairs/trash + OCR rooms? | **Pass** — EC-01..05 suite |
| 4 | Avg &lt; 1500ms + timeout fallback? | **Pass** |
| 5 | Person 2 zero schema errors? | **Pass** |

## Run

```bash
npm run test:final
npm run test:prd
```

## What “complete” means

Person 1’s perception module is ready for Person 2/3 integration via `src/index.js`.

Still optional (not a numbered PRD step):

- Live phone `/perception-harness` capture latency fill-in
- Android YOLO model on-device (`download:yolo26` + `expo run:android`)
