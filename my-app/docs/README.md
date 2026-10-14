# Documentation map

Two kinds of document live under `docs/`, and they age differently. Read the current reference
first; treat the build log as history.

## Current reference

Kept up to date. If one of these disagrees with the code, the document is wrong.

| Topic | Where |
| --- | --- |
| System architecture and data flow | [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) |
| Full technical reference | [`../../TECHNICAL.md`](../../TECHNICAL.md) |
| Build and demo walkthrough | [`../../DEMO.md`](../../DEMO.md) |
| Running the app, commands, layout | [`../README.md`](../README.md) |
| Who owns the ARCore session | [`../modules/navigation-native/CAMERA_OWNERSHIP.md`](../modules/navigation-native/CAMERA_OWNERSHIP.md) |
| Navigation engine internals | [`../modules/navigation-native/README.md`](../modules/navigation-native/README.md) |
| Consuming the perception contract | [`perception/TEAMMATE_INTEGRATION.md`](perception/TEAMMATE_INTEGRATION.md) |
| Hybrid YOLO / OCR / VLM design | [`perception/HYBRID_YOLO_OCR_VLM.md`](perception/HYBRID_YOLO_OCR_VLM.md) |
| Camera permission behaviour | [`perception/CAMERA_PERMISSIONS_NOTE.md`](perception/CAMERA_PERMISSIONS_NOTE.md) |
| Similar research, summarised | [`RELATED_WORK.md`](RELATED_WORK.md) |

## Plans

Proposals for work not yet done. They describe intent, not the code; where one disagrees with the
code, the code is what exists.

| Topic | Where |
| --- | --- |
| Layered navigation in simulation: the active design | [`LAYERED_NAVIGATION_DESIGN.md`](LAYERED_NAVIGATION_DESIGN.md) |
| Hypothesis-conditioned active perception: research spec, diagrams, prototype config | [`hypothesis-active-perception/`](hypothesis-active-perception/technical_implementation.md) |
| Belief-driven navigation: research plan to supervised wearable demo (real-world parts shelved) | [`BELIEF_DRIVEN_NAVIGATION_PLAN.md`](BELIEF_DRIVEN_NAVIGATION_PLAN.md) |

## Build log

`perception/STEP_01…STEP_05`, `FINAL_VERIFICATION.md` and `SNAPSHOTS.md` record how the
perception layer was built, in the order it was built. They are a useful account of why certain
budgets and thresholds were chosen, and the numbers in them are real measurements.

They are **not** maintained against the current code. Where a command in them no longer exists it
has been repointed at its replacement, but the surrounding narrative still describes the state of
the project at the time it was written.

Two naming conventions in here have already aged badly and are worth not repeating:

- **`person1` / `person2` / `person3`** — the directory was renamed to `perception/`, but the
  text still refers to people rather than to layers. Ownership changes; the perception layer,
  the navigation engine and the guidance layer do not.
- **`STEP_0N`** — chronological names stop meaning anything once the order stops being the point.

New documents should be named for their subject.

## Snapshots

`../snapshots/*.json` are recorded outputs from the diagnostic scripts, not test fixtures.
Regenerate them with the `report:` and `profile:` scripts in `package.json`. Nothing in `npm test`
reads or writes them — the offline suite asserts on behaviour directly.
