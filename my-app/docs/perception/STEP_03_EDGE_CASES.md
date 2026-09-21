# Step 3 — Schema Validation & Edge-Case Testing

**Owner:** Person 1  
**Checkpoint:** `snapshots/snapshot_step3_edge_cases.json` (+ `snapshot_step3_schema.json`)  
**Review:** *Did hazard alerts fire on stairs and trash cans? Did OCR pick up room numbers?*

---

## Goal

Strict schema sanitization and automated regressions across five edge cases from the PRD.

## Delivered

| Artifact | Path |
|---|---|
| Schema validator | `src/services/schemaValidator.js` |
| Schema regressions | `test/schema-validator.test.mjs` → `snapshots/snapshot_step3_schema.json` |
| Edge-case suite | `test/perception-engine.test.mjs` → `snapshots/snapshot_step3_edge_cases.json` |
| Mock scenarios | `src/constants/mockPerception.js` (stairs, trashcan, room sign, clear, blurry) |
| Fixture JPEGs | `test/fixtures/edge_*.jpg` (synthetic stand-ins until venue captures) |

## Edge cases (PRD)

| ID | Name | Expect |
|---|---|---|
| EC-01 | Descending stairs | hazard + `stairs` |
| EC-02 | Trashcan / cart | hazard + `trashcan` |
| EC-03 | Room sign OCR | text contains `204` |
| EC-04 | Clear hallway | no hazard, floor detected |
| EC-05 | Motion blur / low contrast | no forced hazard |

## Run

```bash
npm test
npm test
```

Offline mode uses deterministic mocks mapped from each fixture name. Replace `test/fixtures/edge_*.jpg` with venue photos later; the runner auto-detects files.

## Schema rules enforced

- Labels filtered to PRD set  
- Clocks must match PRD set (else `12 o'clock`)  
- Boxes / confidence clamped to `[0, 1]`  
- Distance floored at `0.5` m  
- Hazard description ≤ 100 chars; OCR ≤ 50 chars  
