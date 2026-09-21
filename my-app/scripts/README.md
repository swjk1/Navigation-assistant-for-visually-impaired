# scripts/

Diagnostics, profilers and report generators. **None of these are tests**, and none of them run
in CI.

The distinction is what each one needs to produce an answer:

| | `test/` | `scripts/` |
| --- | --- | --- |
| Needs a network | no | some |
| Needs a Gemini key | no | some |
| Needs a phone | no | some |
| Deterministic | yes | no — they measure |
| Runs in CI | every push | never |

Everything here was previously in `test/`, named by the build step it belonged to
(`run_step4_latency.mjs` and so on). That made `npm test` a thing nobody could run on a clean
checkout, and it meant a genuinely failing assertion looked the same as a missing API key.

| Script | npm | What it answers |
| --- | --- | --- |
| `probe-gemini-live.mjs` | `probe:gemini` | Does a real Gemini round-trip work, and how long does it take? |
| `probe-hybrid-pipeline.mjs` | `probe:hybrid` | How do YOLO, OCR and the gated VLM combine on a real frame? |
| `profile-capture-timing.mjs` | `profile:capture` | Is camera capture inside its 150 ms budget? |
| `profile-latency.mjs` | `profile:latency` | What is the end-to-end latency distribution? |
| `security_audit.mjs` | `audit:security` | Are the key-handling and gitignore guardrails in place? |
| `report-prd-alignment.mjs` | `report:prd` | Which PRD items do the recorded snapshots cover? |
| `report-teammate-handoff.mjs` | `report:handoff` | What does a consumer actually receive? |
| `report-final-verification.mjs` | `report:verification` | Aggregate verification record |
| `bench-gemini-vision.mjs` | — | Compare Gemini models and thinking settings |
| `diagnose-gemini.mjs` | — | Why is a Gemini call failing? |
| `list-gemini-models.mjs` | — | Which models does this key have access to? |
| `download-yolo26.js` | `download:yolo26` | Fetch the ONNX weights |
| `reset-project.js` | `reset-project` | Expo template reset |

Output lands in `snapshots/`. Those files are records, not fixtures — nothing in `npm test` reads
them.

## A note on `report-prd-alignment.mjs`

It reports on a PRD checklist, and parts of it assert that particular **files exist** rather than
that anything behaves correctly. Treat its output as a documentation checklist, not a test
result: moving a file can turn it red without anything being broken, which is precisely why it
does not belong in `test/`.
