# Technical documentation

Indoor navigation assistant for blind and low-vision users. A phone is carried into a building it
has never seen — no floor plan, no pre-mapped route, no beacons, no installed hardware — and it
discovers walkable space while the user walks, emitting one instruction at a time:

```
STRAIGHT · TURN_LEFT · TURN_RIGHT · STOP · SCAN · ARRIVED
```

**Movement decisions are deterministic.** No LLM or VLM sits in the decision path. Perception
supplies evidence about what is around; geometry and search decide where to step.

Companion documents: [ARCHITECTURE.md](./ARCHITECTURE.md) for the system diagram,
[DEMO.md](./DEMO.md) for build and demo instructions,
[modules/navigation-native/README.md](./my-app/modules/navigation-native/README.md) for the
engine's own reference, and `my-app/docs/perception/` for the perception module's notes.

---

## 1. Ownership

| Person | Owns | Lives in |
|---|---|---|
| 1 | Camera + vision → verified `PerceptionFrame` | `my-app/modules/indoor-perception/`, `my-app/src/services/` |
| 2 | Mapping, planning, navigation logic | `my-app/navigation-core/`, `my-app/modules/navigation-native/` |
| 3 | Guidance UX, speech, haptics | `my-app/src/guidance/`, `my-app/src/app/index.tsx` |

Two frozen contracts join them:

- `my-app/src/types/perception.ts` — Person 1 → Person 2
- `my-app/src/types/NavigationCommand.ts` — Person 2 → Person 3

---

## 2. The three layers

```
LAYER 1 — PLATFORM SENSORS        ARCore (Android) · ARKit (future iOS)
        ↓  canonical NavigationFrame
LAYER 2 — SHARED NAVIGATION CORE  pure Kotlin, no Android, no ARCore
        ↓  NavigationSnapshot
LAYER 3 — EXPO / REACT NATIVE     speech, haptics, debug UI
```

The boundary between 1 and 2 is enforced **by the build**, not by convention. `navigation-core` is
a separate Gradle project compiled and tested *without* the Android or ARCore classpath, then
pulled into the Android module through a `sourceSets` `srcDir`. A stray `import
com.google.ar.core.*` in the core breaks that build immediately.

Verified: zero `android.*`, `com.google.ar.core.*` or `expo.modules.*` imports exist anywhere in
`navigation-core`. The planner has never heard of a `Frame`:

```kotlin
// what this does NOT do
class AStarPlanner(frame: com.google.ar.core.Frame)

// what it does
fun plan(grid: InflatedGrid, start: GridCoordinate, goal: GridCoordinate): List<GridCoordinate>?
```

The core also avoids JVM-only APIs (`PriorityQueue`, `UUID`, `System.currentTimeMillis`) so it can
move to `commonMain` for Kotlin Multiplatform without rewriting algorithms. A/B: A* uses a
hand-written primitive binary heap; ids come from counters; every timestamp is passed in by the
platform adapter.

---

## 3. Per-frame pipeline

```
ARCore Frame
  → tracking check              not TRACKING → LOST_TRACKING + STOP
  → camera pose                 → canonical Pose3D
  → depth image (16-bit)        → subsample → camera space → world → canonical points
  → floor estimation            lowest ARCore plane, else depth histogram
  → obstacle classification     0.10 m .. 2.10 m above the floor
  → occupancy grid              log-odds ray integration + temporal decay
  → obstacle inflation          body radius + clearance field
  → goal selection              target known → straight at it, else next graph hop
                                target unknown → best-scoring frontier
                                nothing left → backtrack through the topological graph
  → local A*                    → line-of-sight smoothing
  → lookahead waypoint          → smoothed heading error → hysteresis
  → NavigationCommand           → throttled snapshot to JS
```

### 3.1 Coordinate conventions

Two frames exist; everything below the adapter uses the second.

**ARCore world** — right-handed, +Y up (gravity-aligned), X/Z arbitrary at session start. Cameras
look along their own **−Z**.

**Canonical navigation frame** — fixed from the first well-tracked pose:

```
origin  device position when navigation started
+y      up
+z      initial device forward, projected onto the floor
+x      right
yaw     atan2(x, z)  → 0 is straight ahead, POSITIVE yaw turns RIGHT
```

`ArCorePoseProvider` builds the basis `right = forward × up`. That basis is deliberately
*left*-handed — that is what makes positive yaw mean "turn right". The change of basis is an
orthonormal reflection, so distances and angle magnitudes are exact; only the rotation sign flips.

Planning happens on **X/Z only**. Y is used solely to classify points against the floor.

Depth unprojection handles two axis flips and one resolution mismatch:

```
depth resolution ≠ RGB resolution   → intrinsics rescaled to the depth image
depth v axis points DOWN            → camY = -(v - cy) * d / fy
camera looks along -Z               → camZ = -d
```

Camera-space → ARCore world → canonical is collapsed into a single 3×4 transform computed once
per frame, so a few thousand points cost nine multiply-adds each rather than two matrix multiplies
and an allocation.

### 3.2 Floor estimation

Two sources, in priority order:

1. **ARCore horizontal upward-facing planes** — the **lowest** qualifying plane, minimum 0.6 m²,
   between 0.7 m and 2.6 m below the device. Confidence capped at 0.8.
2. **Depth histogram fallback** — coarse height histogram, lowest well-supported band, refined to
   the mean of points within one bin.

Both pick the *lowest* plausible surface, and the reason is a bug that shipped and was fixed: the
plane path originally picked the **largest** plane. Early in a session the largest plane ARCore has
tracked is often a desk or table — close, textured, well lit — so `floorY` came back as desk
height. Everything below it then classified as floor and was integrated as free space, producing a
map where green bled through walls and obstacles survived only in patches.

Two guards now exist:

- lowest, not largest, with a minimum area so specks cannot win;
- points more than `floorBandBelowMeters` (0.12 m) **below** the estimate are discarded, because
  they are evidence the estimate is wrong, not evidence of free space. Discarding fails safe —
  less claimed free space, never more.

No point is classified as an obstacle until floor confidence reaches `floorMinConfidence` (0.45).

### 3.3 Occupancy grid

A **rolling, local** grid over X/Z. Long-range memory is the topological graph's job, not a
building-sized 10 cm grid.

| Parameter | Default | Note |
|---|---|---|
| `gridResolutionMeters` | 0.10 | |
| `gridSizeMeters` | 12 | 120 × 120 cells |
| `gridRecenterThresholdMeters` | 2.0 | shifts in whole cells; overlap preserved |
| `logOddsHit` / `logOddsMiss` | +0.85 / −0.45 | evidence, not a hard enum |
| `logOddsOccupiedThreshold` | 0.9 | ≥ ⇒ OCCUPIED |
| `logOddsFreeThreshold` | −0.5 | ≤ ⇒ FREE |
| `decayPerSecondUnconfirmed` | 0.80 | a stray return that never decided a cell fades in seconds |
| `inflationRadiusMeters` | 0.45 | the user is not a point |
| `clearanceCostRadiusMeters` | 0.85 | soft cost keeping paths off walls |
| `minObstacleHeightMeters` | 0.10 | below ⇒ floor |
| `maxObstacleHeightMeters` | 2.10 | above ⇒ overhead, ignored |
| `depthSampleStride` | 3 | every 3rd depth pixel |
| `maxDepthMeters` | 6.0 | accuracy degrades badly beyond |

Every observation is **ray-integrated** (Bresenham): cells between sensor and measured point gain
FREE evidence, the endpoint gains OCCUPIED evidence. Without this the map would be obstacle dots in
a sea of UNKNOWN, and frontier detection — a FREE cell beside an UNKNOWN cell — could never fire.

Evidence is stored as log-odds rather than a hard enum, so one noisy reading cannot permanently
block a corridor, and decay clears returns that never added up to anything.

Decay applies **only to cells that have not reached FREE or OCCUPIED**. Decided cells are never
faded by the passage of time: decay runs over the whole 12 m window every frame while the depth
sensor sees a narrow cone of it, so a time-based rule is one-way for everything out of view and
erases the corridor behind the user. A chair that moved is forgotten by *looking through where it
was* — the rays give the cell FREE evidence — and confirmed map data otherwise leaves only by
scrolling out of the rolling window.

**UNKNOWN is never traversable.** The single exception is a cell within
`allowUnknownNearGoalCells` (3) of an *exploration* goal, so a frontier goal sitting exactly on the
known/unknown boundary stays reachable. Navigation to a known target never uses it.

### 3.4 Planning

`AStarPlanner` — 8-connected A* over the inflated grid. Cost = step distance + obstacle-proximity
penalty + turn penalty. Octile heuristic (exact for 8-connected, therefore admissible). Two
practical relaxations: the goal snaps to the nearest reachable cell when inflation swallowed it,
and the path may escape the inflation halo *around the start*, because a user routinely stands
closer to a wall than their own body radius — never through a genuinely occupied cell.

`PathSmoother` — greedy line-of-sight shortcutting. Raw grid A* stair-steps, which as spoken
guidance becomes an unusable stream of alternating slight-left/slight-right.

`NavigationController` — converts path geometry into one instruction:

- **Lookahead** 1.5 m along the path, not the next 10 cm cell.
- **Circular** heading smoothing (linear averaging breaks at the ±180° seam).
- **Two thresholds**: turning starts at 15°, ends below 8°, so the command does not chatter.
- **Forward safety gate**: `STRAIGHT` is suppressed if any blocked *or unknown* cell lies within
  0.9 m straight ahead.
- **STOP bypasses all smoothing** — a safety stop is never delayed.

### 3.5 Timing

Every gate is **time-based, not frame-count based**. This was a shipped bug: `commandStabilityFrames = 3`
was written assuming ~10 Hz engine ticks, but the engine is driven by the ARCore render loop at
~30 fps, so three frames was 100 ms and the instruction could change ten times a second. On device
that was reported as "it just tells me left, then right, and I make no progress".

| Parameter | Default | Purpose |
|---|---|---|
| `commandStabilityMillis` | 350 | a command must hold this long before being emitted |
| `commandMinDwellMillis` | 1400 | once a turn is announced it stands this long — a person needs a beat to hear it and begin turning |
| `minScanMillis` | 10 000 | initial scan before *any* movement instruction |
| `arrivalStableMillis` | 600 | continuous time inside the arrival radius |
| plan / frontier / inflate | 200 / 650 / 160 ms | ~5 Hz, ~1.5 Hz, ~6 Hz |
| `snapshotIntervalMillis` | 120 | ≤ ~8 Hz to JS, immediate on any change |

`minScanMillis` counts only frames with tracking **and** depth available, so a slow ARCore warm-up
or lost tracking does not consume the budget. Standing still does not satisfy it either — map
confidence is measured over a 3 m radius, so the user must actually sweep. `scanProgress` (0..1)
is surfaced to the UI.

### 3.6 Exploration

Frontier detection: FREE cells with a 4-connected UNKNOWN neighbour, flood-filled into 8-connected
clusters, clusters below `minFrontierCells` (6) rejected as depth speckle, centroid snapped back
onto an actual frontier cell. The grid's border ring is excluded — out-of-bounds reads report
UNKNOWN, so including it would ring the window with phantom frontiers that are an artefact of the
window size.

Scoring:

```
score = 1.5 · informationGain
      + 3.0 · semanticScore
      − 1.0 · normalisedDistance
      − 2.0 · revisitPenalty
      − 2.0 · riskPenalty
```

Distance is normalised by half the grid size; raw metres would swamp every other term the moment a
frontier was a few metres away, and no semantic clue could ever outweigh it.

**Commitment.** A chosen waypoint is held through frontier splits, merges and score changes, and
released only on arrival, blockage, repeated route failure, or 20 s without approaching by 0.3 m
(progress renews the budget). Frontier ids regenerate every detection pass and centroids shift as
the map fills, so score hysteresis alone was not enough — the previous choice was often not
recognised at all and a fresh winner was picked, sometimes on the other side.

Route failures are counted at most once per second, so a burst cannot blacklist a branch before the
user has had time to turn.

### 3.7 Topological memory and backtracking

The 10 cm grid is local and forgets a corridor once the user is two rooms away. `TopologicalMap` is
the building-scale memory: a sparse graph of places, with nodes carrying `floorId` from day one so
multi-floor routing is a graph question later rather than a rewrite.

- **Revisit detection** — a new node within `topoMergeRadiusMeters` (1.6 m) on a compatible floor
  merges into the existing one, so walking a loop does not create parallel duplicate corridors.
  This is *not* visual SLAM loop closure; ARCore provides locally consistent tracking and this
  layer only needs to be good enough to remember topology.
- **Backtracking** — graph A* answers "how do I get back to the last junction with unexplored ways
  out". Without it the engine would walk a user into the same dead end forever. A plain cul-de-sac
  with no junction falls back to retreating the way the user came.
- **Node creation is conservative** — every 2.5 m travelled or on a significant turn, never per
  frame, which would destroy revisit detection.

Graph edges mean **"I have walked this"**. A landmark seen across a lobby becomes a node but gets
**no edge** to where the user stood: visibility is not walkability, and that edge previously let
global routing plan straight through a wall.

### 3.8 Routing to a located destination

Three tiers:

1. **Straight at it** — the common case, since a sighting resolves from depth within a few metres.
2. **Next hop on the walked graph** — once the direct route starts failing, the destination is
   behind something the 12 m window cannot see around and the straight-line bearing points *into*
   the obstacle. Topological A* picks a remembered place; local A* walks there.
3. **Abandon the sighting** after `targetRouteAbandonMillis` (4 s) of every route failing.

Tier 3 is necessary, not optional: a pinned sighting suppresses frontier selection, and semantic
evidence is aged out **every frame** rather than only when observations arrive — otherwise a user
stuck facing a wall generates no observations, the bad sighting never expires, and the engine sits
in `NO_ROUTE`/`SCAN` indefinitely. That latch shipped once and is now covered by tests.

Two clocks are tracked separately: one for "is the direct route working" (drives tier 1→2) and one
for "is anything working" (drives tier 3). Collapsing them made a successful graph hop reset the
direct-route clock, sending the next frame back to the route that had just failed — the engine
oscillated between `NAVIGATING` and `NO_ROUTE` once per frame.

### 3.9 State machine

```
IDLE ──start──► INITIALIZING ──tracking──► LOCALIZING ──map ready──► EXPLORING
                                                                        │
                                                    target located ─────┤
                                                                        ▼
                                                                   NAVIGATING ──arrived──► ARRIVED
```

Plus `BACKTRACKING`, `LOST_TRACKING`, `NO_ROUTE`, `PAUSED`, `ERROR`.

Two rules dominate the transition table:

1. **Tracking loss beats everything.** From any moving state it goes straight to `LOST_TRACKING`,
   paired with `STOP`. There is no "carry on and hope" edge.
2. **Transient interruptions remember what they interrupted.** `LOST_TRACKING` and `NO_ROUTE`
   return the user to what they were doing rather than restarting exploration.

### 3.10 Fail-safe behaviour

`STRAIGHT` is never emitted when tracking is invalid, depth has been missing longer than
`depthStarvationMillis`, the floor estimate is not yet confident, any blocked **or unknown** cell
lies within the forward safety distance, or the planner has no route.

Every halt carries a `stopReason`: `OBSTACLE_AHEAD`, `NO_ROUTE`, `TRACKING_LOST`, `NO_DEPTH`,
`MAP_INCOMPLETE`, `NOT_STARTED`, `PAUSED`. This is not cosmetic — the guidance layer gives HAZARD
priority over direction, and from outside, a `STOP` because something is in the way and a `STOP`
because tracking died look identical. Guessing would mean a hazard alert for a software fault, or
silence for a real obstacle.

**This is an accessibility prototype, not a certified mobility device.**

---

## 4. Perception

### 4.1 The fine-tuned model

`my-app/modules/indoor-perception/android/src/main/assets/indoor_yolo26.onnx` — 9.8 MB.

Training notebook: <https://www.kaggle.com/code/leonzhang07/notebook0c74a6eb54>

Verified from the model file's own metadata:

| Field | Value |
|---|---|
| Architecture | Ultralytics **YOLO26n** |
| Ultralytics version | 8.4.155 |
| Exported | 2026-09-19 |
| Dataset | `indoor-navigation-clean` (`data.yaml`) |
| Task / head | `detect` / `Detect` |
| Input | `images`, 640 × 640 × 3, batch 1 |
| Output | `output0` |
| Stride | 32 |
| Export args | `simplify: True`, `dynamic: False`, `nms: False`, `quantize: None` |
| **`end2end`** | **True** |
| License | AGPL-3.0 |

**17 classes**, chosen for indoor wayfinding rather than general objects:

```
 0 accessibility      6 fire extinguisher   12 right arrow
 1 door               7 handle              13 stair sign
 2 elevator           8 left arrow          14 trash can
 3 elevator sign      9 men-s washroom      15 water dispenser
 4 exit sign         10 person              16 women-s washroom
 5 fire alarm        11 push handle
```

#### Training run

The notebook's own cell outputs were cleared, so these figures come from its saved run artifacts
(`results.csv`, `summary.json`) and from re-running validation against `best.pt` on the same
split. That re-run reproduces the best epoch to four decimal places, which confirms the shipped
ONNX derives from that checkpoint.

| Setting | Value |
|---|---|
| Base checkpoint | `yolo26n.pt`, pretrained |
| Epochs | 50 requested, 50 completed — `patience` 15 never triggered |
| Best epoch | 41 |
| Image size | 640 × 640 |
| Batch | 16 |
| Optimizer | AdamW, `lr0` 0.001 |
| Augmentation | `mosaic` 0.5, `mixup` 0.0, `copy_paste` 0.0 |
| Reproducibility | `seed` 0, `deterministic: True` |
| Wall-clock | 22.2 min |

Dataset: Roboflow `akhash/indoor-navigation-xs4of` v10, **CC BY 4.0** — separate from the model's
own AGPL-3.0. Roboflow segmentation polygons were converted to detection boxes before training.

| Split | Images | Boxes |
|---|---|---|
| train | 2 844 | 8 935 |
| val | 105 | 306 |
| test | 62 | 179 |

Validation-split results:

| Metric | Best (epoch 41) | Final (epoch 50) |
|---|---|---|
| mAP50 | **0.833** | 0.819 |
| mAP50-95 | **0.635** | 0.624 |
| Precision | **0.919** | 0.901 |
| Recall | 0.738 | 0.738 |

Precision well above recall is the right bias here: the model misses things rather than inventing
them, and a hallucinated `exit sign` would actively send someone the wrong way.

#### Per class

Sorted worst-last, with validation support, because the averages hide the part that matters.

| Class | AP50 | AP50-95 | P | R | val boxes |
|---|---|---|---|---|---|
| water dispenser | 0.995 | 0.930 | 1.000 | 0.957 | 10 |
| accessibility | 0.995 | 0.877 | 0.987 | 1.000 | 31 |
| women-s washroom | 0.995 | 0.861 | 0.990 | 1.000 | 19 |
| men-s washroom | 0.991 | 0.805 | 0.964 | 0.950 | 20 |
| push handle | 0.995 | 0.796 | 0.909 | 1.000 | 3 |
| elevator | 0.912 | 0.787 | 0.828 | 0.750 | 4 |
| fire extinguisher | 0.992 | 0.782 | 0.999 | 0.977 | 85 |
| trash can | 0.963 | 0.743 | 0.922 | 0.667 | 12 |
| door | 0.828 | 0.637 | 0.821 | 0.634 | 29 |
| person | 0.808 | 0.613 | 1.000 | 0.728 | 4 |
| fire alarm | 0.798 | 0.489 | 0.927 | 0.677 | 31 |
| stair sign | 0.680 | 0.427 | 0.919 | 0.545 | 11 |
| exit sign | 0.701 | 0.395 | 0.707 | 0.727 | 11 |
| left arrow | 0.563 | 0.366 | 1.000 | 0.331 | 9 |
| right arrow | 0.444 | 0.355 | 0.740 | 0.333 | 12 |
| handle | 0.667 | 0.300 | 0.982 | 0.533 | 15 |
| **elevator sign** | — | — | — | — | **0** |

Three things in this table matter more than the headline number.

**The navigation-critical classes are the weak ones.** `exit sign` (AP50-95 0.395) and the
directional arrows (`left arrow` 0.366, `right arrow` 0.355) sit near the bottom, and both arrows
recall barely a third of their instances — roughly two in three are missed. The classes the model
is best at, `water dispenser` and the washroom signs, are the ones wayfinding needs least. The
case for fine-tuning made below still holds — COCO could not emit these labels at all — but
"present in the label set" is not the same as "reliable", and the guidance layer should treat a
single arrow detection as a hint rather than an instruction.

**`elevator sign` is completely unmeasured.** It has 60 training boxes and zero validation
instances, so it contributes nothing to mAP and nothing verifies it was learned. It is the one
class in the list whose real accuracy is unknown.

**The validation split is small.** 105 images and 306 boxes across 17 classes means several
per-class figures rest on a handful of instances: `push handle` scores AP50 0.995 on three boxes,
`elevator` and `person` on four each. Those rows are noise, not measurement. Only
`fire extinguisher` (85), `accessibility` (31), `fire alarm` (31) and `door` (29) have enough
support to take at face value.

These are validation-set numbers from the training run. They are not on-device accuracy: they say
nothing about motion blur, the 640 × 640 non-letterboxed resize (§4.2), or the phone's camera at
walking pace.

This replaced a generic COCO checkpoint, and the improvement is categorical rather than
incremental. COCO has **no door, no exit sign and no lift** — the three things this application
most needs — so the old label mapping could physically only ever emit `person` and `chair`. Every
navigation-relevant detection was unreachable.

Fine-tuning on an indoor-navigation dataset is therefore not an optimisation; it is what makes the
semantic half of the system possible at all.

### 4.2 Why ONNX Runtime rather than TFLite

The export is **end-to-end** (`end2end: True`): NMS is inside the graph, and the model emits final
detections as `[1, N, 6]` — `x1, y1, x2, y2, score, classId` in the 640 × 640 input space,
score-sorted. The previous TFLite detector's score decoding, anchor maths and non-maximum
suppression were all deleted rather than ported; none of it applies.

Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`, 4 intra-op threads, all graph
optimisations. The input buffer (3 × 640 × 640 floats, 4.9 MB) is allocated once and reused —
allocating per frame would churn the heap at capture rate.

Coordinates: the bitmap is resized straight to 640 × 640 rather than letterboxed. That distorts
aspect slightly but makes box mapping exact — divide by 640 and the result is already normalised
against the original image, which is what the `PerceptionFrame` contract wants.

### 4.3 Label mapping

Signs are reported as their **subject**, not flattened to a generic `sign`:

| Model class | `ObjectLabel` | → `SemanticObservation` |
|---|---|---|
| `door` | `door` | `DOOR` |
| `exit sign` | `exit sign` | `EXIT` + direction |
| `stair sign` | `stairs` | `STAIRS` |
| `elevator`, `elevator sign` | `elevator` | `ELEVATOR` |
| `left arrow` / `right arrow` | same | weak directional `EXIT` hint (0.4×) |
| washrooms, `accessibility` | `washroom` | `ROOM` landmark |
| `person`, `trash can` | `person`, `trashcan` | dropped — the engine already sees them via depth |
| handles, fire alarm, extinguisher, dispenser | — | dropped |

An "exit sign" is *how a user finds an exit*; flattening it to `sign` throws away the only useful
part.

### 4.4 Hybrid pipeline

`captureFrame` → YOLO (ONNX) + ML Kit OCR on device → optional Gemini Flash VLM gate → validated
`PerceptionFrame`. **A Gemini key is optional**: without one, YOLO and OCR still run entirely on
device and `getPerceptionMode()` reports `mock` for the VLM stage only. The navigation demo does
not need it.

---

## 5. Semantic interface

The engine implements **no** recognition. This is the hand-off:

```ts
await NavigationNative.submitSemanticObservations([
  { type: 'ROOM_RANGE', min: 300, max: 349, direction: 'RIGHT', confidence: 0.9 },
  { type: 'ROOM', label: '314', confidence: 0.92, normalizedX: 0.62, normalizedY: 0.4,
    timestampNs: frameTimestampNs },
  { type: 'EXIT', direction: 'FORWARD', confidence: 0.8 },
  { type: 'DOOR', confidence: 0.7 },
  { type: 'FLOOR', floor: '3', confidence: 0.95 },
]);
```

`normalizedX/Y` are resolved to a canonical world position **on the native side**, against a
retained copy of the recent depth image — the core never sees depth images or intrinsics. A 5 × 5
depth window is averaged, because a single pixel on a door plate is easily invalid.

**Timestamps must be ARCore's** `Frame.getTimestamp()` — nanoseconds since boot, not `Date.now()`.
Passing a wall clock puts every observation billions of nanoseconds out of range and the depth
association rejects them all *silently*. Since perception now captures from the ARCore session, the
correct value is available by construction; the adapter additionally detects an out-of-domain
timestamp, logs it, and falls back to the latest depth frame rather than dropping the observation.

Semantics only ever **bias** frontier choice. The planner decides movement.

Room numbering is a heuristic, never a guarantee. A sign covering the target is strong positive
evidence (+1); a sign that does *not* cover it is only weak negative evidence (−0.25), so an
alternative branch is never eliminated. The "301, 303, 305 → keep going" gradient contributes at
most 0.5 and shrinks as the numeric gap grows.

A `DOOR` is deliberately distinct from `EXIT` and `ROOM` — it may lead outside, into a room, or
into a cupboard — so it never *matches* those targets, only scores as partial evidence: 1.0 for a
door request, 0.5 for an exit, 0.3 for a room.

---

## 6. Camera ownership

**ARCore requires exclusive access to the camera, and the engine cannot run without ARCore** —
depth and 6DoF pose are its only inputs. So the module that opens the camera must also be the one
running the session; they cannot be two different modules. A second `CameraView` or Camera2 session
either fails to open or silently evicts the first, with no crash and no error.

`indoor-perception` owns the single session (`ArFrameSource` + `PerceptionArView`) and feeds
navigation from the same frames:

```kotlin
val frame = session.update()
NavigationSensorBridge.submitFrame(session, frame)   // pose + depth → navigation
frame.acquireCameraImage().use { image -> ... }      // RGB → YOLO / OCR / Gemini
```

One camera open, two consumers of the same frame. The RGB image is encoded to JPEG **only when a
capture is requested** — converting every frame would burn battery for nothing. `captureFrame()`
carries a 3 s watchdog, because the capture is served by the GL thread and an unmounted AR view
would otherwise hang the promise and take the caller's perception loop with it.

`isDepthModeSupported()` can only be answered by an open session, so the owner reports it via
`NavigationSensorBridge.reportCapabilities()`. Without that, `isSupported()` returned
`depthSupported: false` on a perfectly capable Pixel 9 Pro and navigation refused to start.

---

## 7. Threading

```
GL / AR thread     session.update() → pose + depth unprojection   (cheap, bounded)
     │  conflated channel — stale frames DROPPED, never queued
engine thread      occupancy, inflation, frontiers, A*, control
     │  throttled
JS                 one small NavigationSnapshot at ≤ ~8 Hz
```

The channel is conflated on purpose: if mapping falls behind, the right answer is to skip old depth,
not accumulate latency and steer a walking person from a two-second-old map.

All engine access happens on one dedicated thread, so the core stays synchronous and lock-free —
exactly the property that lets a future iOS adapter choose its own threading. A small round-robin
pool hands depth clouds across the thread boundary, because the depth provider reuses one buffer
per frame and publishing it directly would let the GL thread rewrite points mid-integration.

---

## 8. JavaScript API

```ts
NavigationNative.isSupported()            // { arCoreSupported, depthSupported, trackingAvailable }
NavigationNative.start({ type: 'ROOM', value: '314' })
NavigationNative.setTarget({ type: 'EXIT' })
NavigationNative.pause() / resume() / stop() / resetMap()
NavigationNative.submitSemanticObservations([...])
NavigationNative.getSnapshot()
NavigationNative.getMapImage()            // rendered PNG of the occupancy grid, debug only
NavigationNative.setDebugEnabled(true)
NavigationNative.setExternalFrameSource(true)
addNavigationStateListener((snapshot) => ...)
```

Depth maps, camera frames, point clouds, grids and coordinate lists **never** cross the bridge.
The debug map is the one exception and is deliberately a *picture*: 14 400 cells several times a
second is exactly the traffic this boundary exists to prevent, whereas a PNG is a few kilobytes,
rendered on the engine thread and pulled on demand rather than pushed with every snapshot.

### Connecting to guidance

`src/guidance/navigationCommandSource.ts` is the only module that knows both the engine and the
frozen `NavigationCommand` contract:

| Contract field | Source |
|---|---|
| `action` | `STRAIGHT`, `TURN_LEFT`→`LEFT`, `TURN_RIGHT`→`RIGHT`, `STOP`, `SCAN`, `ARRIVED` |
| `hazard.detected` | `stopReason === 'OBSTACLE_AHEAD'` — never inferred from the command |
| `hazard.type` | a spoken noun ("Obstacle"), because the guidance layer reads it aloud |
| `target` | `snapshot.target`, null while merely exploring |
| `confidence` | 1 for a halt; otherwise `trackingConfidence × (mapConfidence / 0.5)`, clamped |

`SCAN` was added to the frozen contract by agreement. It is **not** a variant of `STOP`: STOP means
"wait, I will tell you when it is safe"; SCAN means "I cannot tell you anything until you move the
phone". Delivered as STOP, a user stands still while the engine waits for camera motion that never
comes — and it is the first thing the engine says in every session. It has its own haptic rhythm
(`._ __ ._`) and is spoken as "Stop. Look around slowly."

---

## 9. Testing

**123 tests**, all against the platform-independent core — no ARCore session, no Android context,
no Expo module, no device.

| Suite | Covers |
|---|---|
| `OccupancyGridTest` | coordinate round-trips, bounds, ray marking, decay, recentering |
| `ObstacleInflatorTest` | inflation radius, clearance falloff, unknown-never-traversable |
| `AStarPlannerTest` | empty room, corridor, wall with a gap, free-standing obstacle, no route, unknown-space refusal, smoothing |
| `FrontierDetectorTest` | corridor, T-junction, none, noise rejection, centroid snapping |
| `FrontierScorerTest` | semantic clue vs distance, revisit, risk |
| `ExplorationManagerTest` | waypoint held across frontier changes, blocked/stalled release, progress budget |
| `TopologicalMapTest` | merge, routing, blocked edges, multi-floor |
| `SemanticHintStoreTest` | matching, ageing, directional bias, door semantics |
| `NavigationStateMachineTest` | every transition rule |
| `NavigationControllerTest` | heading → command, hysteresis, timing contract, arrival |
| `StopReasonTest` | each reason code, hazard vs fault |
| `TargetRoutingTest` | all three routing tiers, the latch regression, phantom landmark edges |
| `NavigationEngineScenarioTest` | full pipeline against a synthetic ray-cast world |

The scenario tests run `synthetic NavigationFrame → occupancy → frontier → A* → NavigationCommand`
end to end. `SyntheticWorld` casts a fan of rays against wall segments and returns depth points in
canonical coordinates — exactly the shape an ARCore or ARKit adapter produces — so the same tests
will verify the iOS adapter when it exists.

Behavioural regressions were each confirmed to **fail against the previous logic** before being
accepted.

```bash
cd my-app/navigation-core && ./gradlew test
```

---

## 10. Device verification

Verified on a **Pixel 9 Pro, Android 17**, full pipeline live:

```
depth 765 · floor 1.00 · cells 5487/261/8652
frontiers 1 · nodes 1 · path 3
EXPLORING · TURN_LEFT · spoken: LEFT
perception: 3 objects, 1 signs
```

ARCore depth → floor estimate → occupancy grid → frontier detection → A* → command → speech and
haptics, with the fine-tuned ONNX model and ML Kit OCR feeding semantic observations off the same
session. The rendered map shows the characteristic fans of free space swept from each camera
position, obstacles along their far edges, and unknown wedges between sweeps.

### Build notes

The project path matters on Windows. At `D:\projects\navigation assistant for visually impaired`:

- **spaces** broke React Native's C++ build — `ninja: manifest 'build.ninja' still dirty after 100
  tries`, which looks unrelated to the path;
- **length** broke release builds specifically, because the `RelWithDebInfo` variant directory is
  nine characters longer than `Debug` and pushed CMake object paths past Windows' 260-character
  limit. A junction does not help; Gradle resolves it back to the real path.

The repository now lives at **`D:\nav`** (97 characters, no spaces) and release builds succeed:

```bash
cd my-app/android
./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
```

The release APK (94 MB) embeds `index.android.bundle`, so it runs standalone — no laptop, no cable,
no Metro. Debug builds require all three.

---

## 11. Known limitations

- **Multi-floor is structural only.** Nodes carry `floorId`, vertical edges route correctly, but
  nothing automates using a lift or stairs.
- **No visual loop closure.** Revisit detection is position proximity plus floor compatibility;
  over a long walk ARCore drift will eventually create duplicate graph nodes.
- **Local grid is 12 m.** Beyond it the engine depends on the topological graph being dense enough.
- **Moving obstacles** are handled only by re-observation — no tracking or prediction. A person
  walking toward the user is a stale obstacle until rays pass through where they were, and one
  who leaves without the user ever looking back stays on the map until the window scrolls past.
- **Frontier weights are guesses.** They are configuration, not measured optima.
- **Depth encoding assumption.** `acquireDepthImage16Bits()` is read as 16-bit millimetres per
  ARCore's documentation. A device packing confidence into the top three bits would have readings
  discarded as out-of-range — degrading to `SCAN` rather than inventing free space, but unusable.
- **Semantic image-coordinate mapping is approximate** — no display-rotation or aspect correction.
- **Two independent hazard producers.** Geometric blockage from the engine and perceptual hazards
  from `PerceptionFrame.immediateHazard` both drive the same `hazard.detected` flag. Nobody owns
  merging them, so one can mask the other. **Open.**
- **Single session per process.** `NavigationRuntime` is a singleton.
- **Android only.** The iOS adapter is out of scope; the core is structured to move to
  `commonMain` without algorithm changes.
