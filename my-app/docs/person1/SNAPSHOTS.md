# Snapshot inventory (through Step 2)

Refresh with:

```bash
npm run test:step1
npm run test:step2
npm run test:step2:live
npm run test:hybrid
npm run test:prd
```

| Snapshot | Meaning |
|---|---|
| `snapshot_step1_hardware.json` | Camera budgets (offline utilities; phone capture pending) |
| `snapshot_step1_timing_profile.json` | Step 1 timing table |
| `snapshot_step2_perception_frame.json` | Mock PerceptionFrame (door @ 11, ROOM 204) |
| `snapshot_step2_perception_frame_live.json` | Live Gemini Flash call (or safe fallback) |
| `snapshot_step3_edge_cases.json` | Edge cases EC-01..05 (mock/synthetic) |
| `snapshot_hybrid_yolo_ocr_vlm.json` | YOLO + ML Kit + **gated** Gemini merge |
| `snapshot_prd_alignment.json` | PRD checklist verdict |

**Not yet:** `snapshot_step4_latency_profile.json`, `snapshot_step5_final_contract.json` (partial `src/index.js` exists)
