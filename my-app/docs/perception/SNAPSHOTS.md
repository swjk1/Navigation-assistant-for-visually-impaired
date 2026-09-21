# Snapshot inventory (Person 1)

Refresh with:

```bash
npm run profile:capture
npm test
npm run probe:hybrid
npm test
npm run profile:latency
npm test
npm run report:verification
npm run report:handoff
npm run audit:security
npm run report:prd
```

| Snapshot | Meaning |
|---|---|
| `snapshot_step1_hardware.json` | Camera budgets (offline utilities; phone capture pending) |
| `snapshot_step1_timing_profile.json` | Step 1 timing table |
| `snapshot_step2_perception_frame.json` | Mock PerceptionFrame (door @ 11, ROOM 204) |
| `snapshot_step2_perception_frame_live.json` | Live Gemini Flash call (or safe fallback) |
| `snapshot_hybrid_yolo_ocr_vlm.json` | YOLO + ML Kit + gated Gemini merge |
| `snapshot_step3_edge_cases.json` | Edge cases EC-01..05 (mock/synthetic) |
| `snapshot_step3_schema.json` | Schema sanitizer regressions |
| `snapshot_step4_latency_profile.json` | 10-run latency + JPEG quality + timeout fallback |
| `snapshot_security_audit.json` | Secret / .env leak audit |
| `snapshot_teammate_handoff.json` | Person 2/3 speakable handoff |
| `snapshot_step5_final_contract.json` | Final public contract — zero schema errors for Person 2 |
| `snapshot_final_verification.json` | PRD §7 all five review questions answered |
| `snapshot_prd_alignment.json` | PRD checklist verdict |
