# Step 4 — Efficiency Optimization & Latency Clamp

**Owner:** Person 1  
**Checkpoint:** `snapshots/snapshot_step4_latency_profile.json`  
**Review:** *Is average round-trip latency under 1500ms? Does timeout gracefully fallback?*

---

## Goal

Keep the perception pipeline under the PRD latency budget and shrink payloads where possible, with a deterministic timeout safety frame for Person 3 TTS.

## Budgets

| Stage | Budget |
|---|---|
| Capture | ≤ 150 ms |
| Inference + parse | ≤ 1200 ms (live) |
| Schema | ≤ 15 ms |
| **Round-trip** | **≤ 1500 ms** |
| Network fallback | if **> 3000 ms** → timeout frame |

Timeout copy (exact):

> Vision service slow. Hold still and try again.

## What was delivered

| Item | Path |
|---|---|
| Latency profile (10 runs) | `scripts/profile-latency.mjs` |
| Quality candidates 0.3 / 0.4 / 0.5 | `src/constants/cameraConfig.js` |
| Timeout helper | `buildScanTimeoutFrame()` in `perceptionEngine.js` |
| Security audit | `scripts/security_audit.mjs` → `snapshot_security_audit.json` |
| Snapshot | `snapshots/snapshot_step4_latency_profile.json` |

## Run

```bash
npm run profile:latency
npm run audit:security
npm test
```

## Notes

- Profile runs offline (mock hybrid) so results are deterministic without burning Gemini quota.
- Live Gemini still uses retries + 3000 ms network fallback + redacted error text (no API keys in speakable hazards).
- Default JPEG quality remains **0.4**; Step 4 snapshot recommends **0.3** when size/tokens matter and OCR remains readable.
