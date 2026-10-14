# Technical documentation

Indoor navigation assistant for blind and low-vision users. A phone is carried into a building it
has never seen — no floor plan, no pre-mapped route, no beacons, no installed hardware — and it
discovers walkable space while the user walks, emitting one instruction at a time:

```
STRAIGHT · TURN_LEFT · TURN_RIGHT · STOP · SCAN · ARRIVED
```

**Movement decisions are deterministic.** No LLM or VLM sits in the decision path. Perception
supplies evidence about what is around; geometry and search decide where to step.

The core does accept two kinds of outside opinion: **advice** (a short-term adviser scoring which
way to explore) and **planner hypotheses** (a long-term planner's guesses at where the destination
is). Both only re-weight the choice of frontier, through one term each in its score; neither can
plan a path, steer, lift a stop or declare arrival (§5.1). The app does not use them yet. In the
simulator they are fed by oracle and noisy stand-ins, and by a language-model adviser that is
switched off while paid API use is paused.

Companion documents: [ARCHITECTURE.md](./ARCHITECTURE.md) for the system diagram,
[DEMO.md](./DEMO.md) for build and demo instructions,
[modules/navigation-native/README.md](./my-app/modules/navigation-native/README.md) for the
engine's own reference, `my-app/docs/perception/` for the perception module's notes,
[`my-app/navigation-sim/README.md`](./my-app/navigation-sim/README.md) for the simulator, and
[`my-app/docs/RELATED_WORK.md`](./my-app/docs/RELATED_WORK.md) for similar research.

---

## 0. At a glance

### What is built

| Part | Where | What it does |
|---|---|---|
| Navigation core | `my-app/navigation-core/` (pure Kotlin) | Depth → floor estimate → occupancy grid → frontiers → A* → one instruction. Topological memory, three-tier routing, a fail-safe controller, sign hints, room-number model, advice and planner inputs. 187 unit and scenario tests |
| Android adapter | `my-app/modules/navigation-native/` | ARCore pose and depth into the core, on its own thread; snapshots to JS at ≤ 8 Hz |
| Perception | `my-app/modules/indoor-perception/`, `my-app/src/perception/` | Fine-tuned YOLO26n (ONNX, 17 classes, mAP50 0.833) and ML Kit OCR on device, optional Gemini check; signs mapped to semantic observations |
| Guidance | `my-app/src/guidance/` | Speech and haptics for each instruction; hazard before direction |
| Simulator | `my-app/navigation-sim/` (JVM) | Closed loop against 3D buildings with ARCore-like depth noise, the detector's recall, OCR errors, glass, drift, five sensor rigs; a person or a robot acting on the engine; 31 scenarios, 18 tests |
| Benchmark | `navigation-sim/bench/` | 44 trips in 3 building types plus 15 held out; paired bootstrap comparison; about 90 designs, each also runnable with the robot |
| Advisers | `navigation-sim/advice/` | Oracle, noisy oracle, outage, oracle and noisy planners, Follow the Signs' mechanism, a language-model adviser (Claude or Gemini), and a recorder of labelled decisions |

### Key numbers

| Measure | Value | Source |
|---|---|---|
| Original engine, simulated person | 28.0% success | `v0-original-10`, 440 runs |
| Shipped engine, simulated person | 51.1% (simple 94.0, school 66.3, mall 12.7) | `p0-current`, 440 runs |
| Shipped engine with a perfect route adviser, person | 90.0% (mall 87.3) | `p0-oracle-advice`, 440 runs |
| Robot, engine alone | 45.6% (simple 87.0, school 54.8, mall 17.0), SPL 0.283 | `r1-current`, 880 runs |
| Robot, held-out malls | 44.3% | `r1-heldout-current`, 300 runs |
| What reading signs is worth, robot | +9.5 points overall, +29.7 in held-out malls | vs `frontier-only` |
| Perfect short-term adviser, robot | +23.6 points (69.2%); +39.3 in held-out malls (83.7%) | `r1-oracle-advice` |
| Perfect long-term planner, robot | +14.5 points (60.5%) | `r1-planner-oracle` |
| Adviser accuracy needed to help significantly | about 70% at full weight | noisy-oracle sweep |
| Falls | 0 in 18,060 robot runs | every `r1-*` result |
| Detector | mAP50 0.833, precision 0.919, recall 0.738 (validation) | §4.1 |
| Language-model pilot | Claude Haiku: 45.5% against 36.4% without it, one seed, older engine; about $1.13 spent in all | §5.2 |

Success is the share of trips ending in a declared arrival within 2 m of a true destination; all of
it is measured in simulation (§9).

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
  → floor estimation            ARCore plane fused with a depth histogram
  → obstacle classification     0.10 m .. 2.10 m above the floor
  → occupancy grid              floor-carved free space, one update per cell per frame, decay
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

Two sources, fused:

1. **ARCore horizontal upward-facing planes** — the **lowest** qualifying plane, minimum 0.6 m²,
   between 0.7 m and 2.6 m below the device. Confidence capped at 0.8.
2. **Depth histogram** — coarse height histogram over the same 0.7–2.6 m band below the phone,
   lowest well-supported band, refined to the mean of points within one bin. It runs every frame,
   not only when there is no plane.

The plane is preferred, but when depth shows a well-supported surface more than
`floorBandBelowMeters` **below** it, the plane is furniture and depth wins. The plane used to
short-circuit depth entirely, so a table plane at 0.75 m held the floor at table height even
with the real floor in view. Once established, the estimate also ignores a rise of more than
`floorMaxRiseMeters` (0.25 m) unless it persists for `floorRiseConfirmMillis` (2 s): floors do
not jump by a desk height, but a ramp is still followed.

Both sources pick the *lowest* plausible surface, and the reason is a bug that shipped and was
fixed: the plane path originally picked the **largest** plane. Early in a session the largest plane ARCore has
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
| `gridRecenterThresholdMeters` | 1.0 | shifts in whole cells; overlap preserved. Depth is only integrated while it stays inside the window with a margin — reaching the border hid the frontier that says "the corridor continues" |
| `logOddsHit` / `logOddsMiss` | +0.85 / −0.45 | evidence, not a hard enum |
| `logOddsOccupiedThreshold` | 0.9 | ≥ ⇒ OCCUPIED |
| `logOddsFreeThreshold` | −0.5 | ≤ ⇒ FREE |
| `decayPerSecondUnconfirmed` | 0.80 | a stray return that never decided a cell fades in seconds |
| `inflationRadiusMeters` | 0.30 | the HARD envelope: body radius plus a margin. Was 0.45, which needed ~1 m of clear width: every doorway was impassable and a 2 m corridor fired "obstacle ahead" at 20 cm of drift (found in the simulator) |
| `clearanceCostRadiusMeters` | 0.85 | soft cost keeping paths off walls — the "stay central" job, separate from the envelope |
| `minObstacleHeightMeters` | 0.10 | below ⇒ floor |
| `maxObstacleHeightMeters` | 2.10 | above ⇒ overhead, ignored |
| `depthSampleStride` | 3 | every 3rd depth pixel |
| `maxDepthMeters` | 6.0 | accuracy degrades badly beyond; floor carves free space this far |
| `maxObstacleRangeMeters` | 4.0 | obstacles only marked this close: long-range noise put phantom walls across corridors |
| `depthEdgeRejectFraction` | 0.06 | flying pixels at depth edges dropped; they thickened door jambs enough to close a door |
| `dropOffDepthMeters` | 0.2 | a return this far below the floor marks a drop-off where its ray crossed floor level |
| `footprintFreeRadiusMeters` / `footprintBehindPhoneMeters` | 0.25 / 0.3 | the body's footprint, centred behind the phone — centred on the phone it reached over a stairwell edge |
| `blindZoneMeters` | 0.5 | the unseen strip in front of the phone is bridged only where seen floor continues beyond it |

**Free space comes only from floor the sensor actually sees.** A ray that reaches the floor 3 m
away proves only that nothing along it was taller than the ray itself at each point, so where it
passes 60 cm above the floor a 40 cm box could sit underneath. Only the stretch of each floor ray
below `minObstacleHeightMeters` is carved FREE (Bresenham), which is `range × 0.10 / sensorHeight`
long and grows with range the same way the spacing between floor samples does. Obstacle returns
mark only their own cell. The engine used to flatten every ray to 2D and clear the whole line: a
box in front of a wall lost to the rays aimed at the wall above it, and was erased within a
second (`DepthIntegrationTest`).

Two consequences:

- The floor directly under and around a person holding a phone upright is never in view, so the
  user's own footprint (`footprintFreeRadiusMeters`, 0.3 m) is marked FREE each frame. Cells
  already OCCUPIED are skipped, so a wall beside the user is never walked out of the map.
- The floor between the footprint and the first visible floor (about 1–2 m ahead, depending on
  pitch) stays UNKNOWN until the user has seen it, from further back or by tilting the phone
  down. That is deliberate: "a ray passed over it" is not evidence that it is clear.

**One frame is one observation.** Evidence is collected per frame and applied once per touched
cell, with an obstacle return winning over floor seen in the same cell. Applied per ray, a single
frame saturated cells to the log-odds limits, and two stray returns made a cell permanently
OCCUPIED. Floor evidence against a cell that is already OCCUPIED counts at
`logOddsMissOnOccupiedScale` (0.33): a 10 cm cell at the foot of a wall holds wall and floor, and
at shallow angles most frames see only the floor strip.

Evidence is stored as log-odds rather than a hard enum, so one noisy reading cannot permanently
block a corridor, and decay clears returns that never added up to anything.

Decay applies **only to cells that have not reached FREE or OCCUPIED**. Decided cells are never
faded by the passage of time: decay runs over the whole 12 m window every frame while the depth
sensor sees a narrow cone of it, so a time-based rule is one-way for everything out of view and
erases the corridor behind the user. A chair that moved is forgotten by *seeing the floor where it
stood*, after roughly 20 looks, and confirmed map data otherwise leaves only by scrolling out of
the rolling window.

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
- **Turns end early**: the controller measures the user's turn rate and ends the turn
  `turnLeadMillis` (500 ms) before they would face the path, so their reaction time does not
  carry them past it. Ending a turn is never held by the dwell.
- **Forward safety gate**: `STRAIGHT` is suppressed if any blocked *or unknown* cell lies within
  0.9 m straight ahead. Blocked means `STOP` / `OBSTACLE_AHEAD`; merely unknown means `SCAN` /
  `UNKNOWN_AHEAD` (look ahead) - standing still would never reveal it. Known obstacles beyond the
  end of the path are ignored (a destination on a wall); unknown space never is (it may be a drop).
  When straight ahead is blocked but the path itself bends away by 3° or more
  (`driftCorrectionDegrees`), the user has drifted towards a wall inside the 15° tolerance: they
  are steered back onto the path, not stopped. A hazard STOP there froze users in doorways and
  1.5 m corridors, told about an obstacle that was only beside them.
- **STOP bypasses all smoothing** — a safety stop is never delayed.

A robot that steers continuously at the 1.5 m lookahead cuts inside bends, into the 0.3 m
inflation margin, where the forward gate always reads blocked. The simulated robot (§9) follows the
path at 0.6 m instead.

### 3.5 Timing

Every gate is **time-based, not frame-count based**. This was a shipped bug: `commandStabilityFrames = 3`
was written assuming ~10 Hz engine ticks, but the engine is driven by the ARCore render loop at
~30 fps, so three frames was 100 ms and the instruction could change ten times a second. On device
that was reported as "it just tells me left, then right, and I make no progress".

| Parameter | Default | Purpose |
|---|---|---|
| `commandStabilityMillis` | 350 | a command must hold this long before being emitted |
| `commandMinDwellMillis` | 1400 | a turn is not flipped to the OPPOSITE turn within this long. It no longer delays ENDING a turn: with a person turning at 60°/s that overshot the path by ~80°, and the engine oscillated left/right indefinitely (reproduced and fixed in the simulator) |
| `turnLeadMillis` / `turnEndStabilityMillis` | 500 / 150 | end a turn this far ahead of facing the path; ending needs this much consistent evidence |
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
      + 2.0 · adviceScore          only when an adviser has spoken (§5.1)
      + 2.0 · planScore            only when a planner has spoken (§5.1)
      + 0   · alongLongAxis        shape cue, off: no significant gain measured
      + 0   · clutter              shape cue, off: more glass contacts in malls
```

Distance is normalised by half the grid size; raw metres would swamp every other term the moment a
frontier was a few metres away, and no semantic clue could ever outweigh it.

How much the semantic term is worth was measured with a robot in the simulator (§9): with
`weightSemantic` 0 — classic frontier exploration that only reacts to seeing the destination —
success fell from 45.6% to 36.0% on the benchmark, and from 44.3% to 14.7% in held-out malls.

**Commitment.** A chosen waypoint is held through frontier splits, merges and score changes, and
released only on arrival, blockage, repeated route failure, 20 s without approaching by 0.3 m
(progress renews the budget), or once no frontier cell remains within
`frontierCommitUnknownRadiusMeters` (1 m) of it — a sweep routinely maps a waypoint picked a
second earlier, and holding it sent the user back to look at known space. Frontier ids regenerate every detection pass and centroids shift as
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

Every halt carries a `stopReason`: `OBSTACLE_AHEAD`, `UNKNOWN_AHEAD`, `NO_ROUTE`,
`TRACKING_LOST`, `NO_DEPTH`, `MAP_INCOMPLETE`, `NOT_STARTED`, `PAUSED`. Only `OBSTACLE_AHEAD` is a
hazard; `UNKNOWN_AHEAD` accompanies `SCAN` and means "look ahead / tilt the phone down". This is not cosmetic — the guidance layer gives HAZARD
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

**Timestamps must be the capture's** — `captureFrame().timestampNs`, which is ARCore's
`Frame.getTimestamp()` (nanoseconds since boot), never `Date.now()`. Perception takes seconds, so
the frame an observation came from is long gone when it arrives. At capture time the session owner
calls `NavigationSensorBridge.onFrameCaptured(timestamp)`, which pins that frame's depth image and
camera transform; the observation is then resolved against it, and the same lookup supplies the
pose the user stood at, which directional hints are relative to. With no timestamp the newest depth
frame is used, which is wrong whenever the user has turned since. A timestamp matching no retained
frame is rejected. An out-of-domain timestamp (a wall clock) is logged and treated as absent.

The engine ages semantic evidence on **its own clock** (`NavigationEngine.clockMillis`, the frame
timestamps). The module used to stamp observations with `System.currentTimeMillis()`, so every
comparison against frame time came out negative: hints never aged and sightings never expired on a
device, while the tests - which use one clock - passed.

**Image coordinates are sensor coordinates.** Captures are rotated upright before any model or VLM
sees them (`rotationDegrees` on the capture), so boxes and clock positions mean what the user sees.
`toSemanticObservations(frame, { timestampNs, rotationDegrees })` rotates box centres back to the
sensor orientation the depth image uses.

**A placement must make sense.** Depth is read over a 3×3 window on the observation's point, which
must be mostly valid and one surface; and when the observation carries its box size
(`normalizedWidth` / `normalizedHeight`), the size that depth implies must be at least half and at
most three times what such an object usually measures (a door ~2 m, a lift ~2.1 m, an exit sign
~0.4 m, a room plate ~0.12 m). Otherwise it is not placed — it still biases exploration, but cannot
become a destination. Without this, a lift 14 m away (beyond depth range, or seen past a wall's
end) was placed on nearer floor or a wall corner and the user was told "arrived" 7 m short.

Room-range signs take their direction from **their arrow** (`→ -> ← <-` in the OCR text), falling
back to where the sign sits in the picture only when there is none.

Semantics only ever **bias** frontier choice. The planner decides movement.

Room numbering is a heuristic, never a guarantee. A sign covering the target is strong positive
evidence (+1); a sign that does *not* cover it is only weak negative evidence (−0.25), so an
alternative branch is never eliminated.

Plates read along the way feed `RoomNumberModel`, which fits how the numbers run:

- **The corridor's axis** from the spread of nearby plates (within 20 m of the latest, at most 8),
  measured within each wall's plates so the corridor's width does not tilt it.
- **One slope, two walls.** Odd and even numbers are fitted as two parallel runs sharing a slope,
  because 302 usually faces 301. Where each wall sits tells which side the target is on.
- **One misread survives.** When the fit is not clean, it is refitted without each plate in turn
  and the clearly better fit kept: a single "389" for "309" spreads its error over a least-squares
  fit, so its residual alone does not stand out.
- **Out:** where the target's plate should be, a confidence (fit quality × plate count ×
  how far it extrapolates), and a flag when every plate read shares a hundreds digit the target
  does not — the target is probably on another floor.

With three or more plates, frontiers towards the estimate gain up to `roomNumberModelMaxScore`
(0.8) × confidence; with fewer, the older first-and-last-plate gradient applies (at most 0.5).
`roomNumberModel = false` restores the gradient alone. **Measured: no effect** (−0.3 points, 95%
CI −1.7 to +1.0, 880 robot runs). The plates read on the benchmark trips rarely leave the number
ordering in doubt; the model stays in because it is what a planner reads (§5.1), not because it
moves the score.

Every name for a washroom is the same place: `TargetMatcher.normalizeLabel` maps "Restrooms",
"Men's toilets", "Bathroom", "Lavatories" and "WC" to `WASHROOM`, for the request and for what
is read alike.

A `DOOR` is deliberately distinct from `EXIT` and `ROOM` — it may lead outside, into a room, or
into a cupboard — so it never *matches* those targets, only scores as partial evidence: 1.0 for a
door request, 0.5 for an exit, 0.3 for a room.

### 5.1 Advice and planner hypotheses

Two inputs let reasoning outside the engine — a language model, a learned decision model, a floor
plan — bias exploration. Both are in canonical coordinates, both only enter the frontier score, and
neither touches the path, a safety stop or arrival: a slow, wrong or silent adviser costs time,
never safety.

| | Advice (short-term) | Planner hypothesis (long-term) |
|---|---|---|
| Call | `engine.submitAdvice(List<AdviceDirection>)` | `engine.submitPlan(List<PlannerHypothesis>)` |
| Says | which way to explore from where the user stood: a direction and a weight in −1..1 | where the destination should be: a place, a radius, a confidence, and a check that would confirm it |
| Scores a frontier by | its angle from the advised direction, tapering to 0 at 60° | its direction from the user towards the guess; near the guess, its distance to it |
| Lifetime | ignored after 20 s (`adviceMaxAgeMillis`) | fades to 1/e over 90 s (`plannerFadeMillis`) |
| When searched | — | spent once the user has been within its radius (at least 2 m) without finding the destination; stays spent if the planner repeats it |
| Weight | `weightAdvice` 2.0 | `weightPlanner` 2.0 |

`engine.situation()` gives an adviser what the engine has perceived — never ground truth: the
target, pose, walked distance, the engine's own scored frontiers, up to 60 signs seen (kind, text,
arrow, position, where they were read from), the numbering estimate and other-floor flag, and the
planner guesses still held with their remaining weight.

Advice used to act outside the score in two ways: turning the user to look where it pointed when no
frontier lay that way, and dropping a committed frontier it did not point at. Both are now off by
default (`adviceTurnsToLook`, `adviceReleasesCommitment`), so the frontier score is the only way in.

How good an adviser must be was measured with stand-ins of known accuracy (§9): a perfect one adds
23.6 points; one right 90% of the time adds 20; at 60% about 6, and at 40% nothing — but it costs
nothing either, because the engine's own scoring still carries the trip.

In the simulator the adviser is asked every 8 s while exploring and its answer arrives 2 s later;
the planner is asked every 45 s and answers after 5 s. Answers keep arriving while the engine
carries on, as they would over a network.

### 5.2 The language-model adviser (simulator only)

`navigation-sim/advice/LlmAdviser.kt` asks a language model which opening to explore.

- **Providers.** Claude through the Anthropic Java SDK with a JSON-schema answer, or Gemini over
  REST (temperature 0, 300 output tokens). `LLM_PROVIDER` picks one; models come from
  `ANTHROPIC_MODEL` / `GEMINI_MODEL`.
- **Prompt.** What the engine perceived, never a map: metres walked and seconds elapsed; up to 12
  signs, merged when within 2.5 m or 25°, each with its type, text, clock direction and distance,
  and how often and how long ago it was seen; up to 8 of the engine's openings with clock
  direction and distance; and layout rules of thumb (corridors connect places, shops are dead ends,
  numbers run in order, lifts and washrooms sit off side corridors, exits at the ends).
- **Answer.** A probability per opening plus a reason of at most 15 words. Each opening becomes
  advice towards it with weight `(p − 1/n) / (1 − 1/n)`, negative below an even share.
- **Cost control.** A question is only asked when the openings (to 2 m) or the signs change; the
  last answer is reused otherwise. Replies are cached on disk by a SHA-256 of the prompt; calls are
  at least 250 ms apart; a per-model ledger enforces `LLM_BUDGET_USD` (default $1);
  `LLM_CACHE_ONLY` replays the cache; `llm-dry-run` counts questions without asking any.
- **Pilot.** Claude Haiku 4.5, one seed, on an older engine: 45.5% success against 36.4% without
  it, and 4 of 15 mall trips against none. Its answers hedged: the median strongest weight was 0.20.
  The cache holds 885 replies, 731,271 input and 78,885 output tokens — about $1.13, or roughly
  $0.0013 a question. Paid use is paused, so no result exists on the current engine.
- **Labelled decisions.** `SituationRecorder` (design `record`) writes every new question the
  adviser would be asked, with the exact prompt, the engine's own score for each opening, and the
  true remaining walk through each, so any model can be scored offline. In a first small run the
  engine's own top opening was truly best 39% of the time.

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
capture is requested** — converting every frame would burn battery for nothing. The GL thread only
copies the pixels out and pins the depth; rotation to upright and JPEG encoding run on a
background thread, because the GL thread is also the one feeding navigation. `captureFrame()`
carries a 3 s watchdog, because the capture is served by the GL thread and an unmounted AR view
would otherwise hang the promise and take the caller's perception loop with it.

`isDepthModeSupported()` can only be answered by an open session, so the owner reports it via
`NavigationSensorBridge.reportCapabilities()`. Without that, `isSupported()` returned
`depthSupported: false` on a perfectly capable Pixel 9 Pro and navigation refused to start.

---

## 7. Threading

```
GL / AR thread     session.update() → pose + depth unprojection   (cheap, bounded)
     │  latest-frame slot — stale frames DROPPED, never queued
engine thread      occupancy, inflation, frontiers, A*, control
     │  throttled
JS                 one small NavigationSnapshot at ≤ ~8 Hz
```

Only the latest frame is kept on purpose: if mapping falls behind, the right answer is to skip old
depth, not accumulate latency and steer a walking person from a two-second-old map.

All engine access happens on one dedicated thread, so the core stays synchronous and lock-free —
exactly the property that lets a future iOS adapter choose its own threading. Depth clouds cross
the thread boundary in pooled buffers that are **owned**: one side at a time, returned when done.
The pool used to be a round-robin that assumed the engine finished within two GL frames, which
fails exactly when frames are being skipped.

The pose basis in `ArCoreFrameProcessor` is GL-thread state. Other threads only request a reset
of it; the GL thread applies it at the start of its next frame and tags frames with a generation,
and the engine discards frames from an older generation. A map reset keeps the basis — semantic
memory survives it and is in canonical coordinates — and sends the state machine back to
LOCALIZING, so the scan gate applies again.

Depth is deduplicated by the depth image's own timestamp and unprojected with the camera pose
recorded at that timestamp: depth usually updates more slowly than the camera, and re-integrating
the same image with a newer pose counted one observation many times and smeared walls sideways
while turning. Scan time is credited while depth is at most `scanDepthFreshMillis` (300 ms) old.

Going to the background pauses guidance; coming back resumes it only if the lifecycle paused it,
through LOCALIZING. Before, nothing resumed it and a phone locked mid-route stayed PAUSED.

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
drawn on the engine thread, PNG-encoded off it, and pulled on demand rather than pushed with every
snapshot.

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

**187 tests**, all against the platform-independent core — no ARCore session, no Android context,
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
| `RoomNumberModelTest` | position along the run, odd and even walls, a target behind, one misread, another corridor, a repeated plate, another floor |
| `FacilitySynonymTest` | every washroom name normalised alike, other names untouched |
| `PlannerHypothesisTest` | pull towards a guess, fading, a searched guess spent and kept spent, near-guess scoring |
| `AimPolicyTest` | when to tilt or sweep the phone for a sign |
| `GridRetentionTest` | decided cells survive time out of view |
| `SpaceShapeTest` | long-axis and clutter cues |
| `NavigationStateMachineTest` | every transition rule |
| `NavigationControllerTest` | heading → command, hysteresis, timing contract, arrival |
| `StopReasonTest` | each reason code, hazard vs fault |
| `TargetRoutingTest` | all three routing tiers, the latch regression, phantom landmark edges |
| `DepthIntegrationTest` | low obstacles survive rays over them, one update per frame, sensor origin, footprint |
| `FloorEstimatorTest` | plane vs depth fusion, desk rejection, rise confirmation |
| `MapResetTest` | map reset re-enters the scan, scan credit with intermittent depth, engine clock |
| `SensingTest` | platform-to-canonical conversion: axes, portrait mounting, depth reuse and lag, pinned captures, floor planes |
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

### Simulator

`my-app/navigation-sim` runs the engine in closed loop against a virtual building. The depth goes
through the core's own `SensorFrameAssembler` — the code the phone runs — so the adapter math is
exercised too. Everything is pure JVM; runs are deterministic per seed, record to MCAP for
Foxglove, stream live, and replay in Webots. See `my-app/navigation-sim/README.md`.

| Modelled | Values |
|---|---|
| Buildings | Simple single-feature scenes; a 3-floor **school** (60 m corridor, rooms 1xx–3xx, odd and even sides, range signs with arrows, two stairwells, a lift, washrooms); a 2-level **mall** (84 m concourse, glass shop fronts with names 4.1 m up on fascias, an atrium behind a glass balustrade, escalators, a food court, a services corridor); three held-out parametric malls |
| Depth | Noise σ = 5 mm + 8 mm × z²; dropout 2% + 3% per metre; up to +60% at grazing angles and on dark surfaces; glass returns 10% of the time, otherwise seen through; flying pixels at 30% of edges over 0.25 m; 8 m maximum; 160 × 120; 15 Hz, one frame late |
| Perception | Signs projected through the camera (640 × 480), occluded by solid geometry but not glass. Recall from the detector's validation: door 0.63, exit 0.73, stairs 0.55, lift 0.75, washroom 0.95, arrows 0.33. OCR reads 85% of legible text and gets a character wrong 8% of the time. Detection needs 14 px, reading 12 px. A capture every 1 s, 0.3–0.8 s latency; every 4th may wait 0.6–2.5 s on Gemini |
| Tracking | Loss windows; pose drift, mild (1°/min yaw, 1% of distance) or strong (3°/min, 3%, relocalising every 60–150 s and keeping 30% of the error) |
| Sensor rigs | Phone; wide-angle phone (100°); chest stereo camera (87°, 12 m depth); the same seeing glass; chest LiDAR (25 m, low noise) |
| A person | Reacts 0.45 s after an instruction; walks 1.0 m/s, turns 60°/s; scans ±60° every 4 s, tilting down; ±3° sway; also cautious (0.8 s, 0.6 m/s), hurried (0.3 s, 1.3 m/s) and never-tilts profiles |
| A robot | Follows the engine's path (below) |

**31 scenarios** with their own pass criteria: 11 pass (corridors, a table that must not become the
floor, stairwells and escalator heads that must not be fallen down, tracking loss, room 314 by its
plate, a 1.5 m office corridor, clutter, a lift lobby), and 20 are recorded `KNOWN_LIMITATION`s
whose notes say what the simulator showed (doorways, glass, dark carpet, blind corners, most
school and mall searches).

```bash
cd my-app/navigation-sim && ./gradlew test
```

### Robot mode

The research question was narrowed to a free-moving robot: placed anywhere on one floor of an
unseen building, can it reach a destination named in text? `UserProfile.ROBOT` replaces the person.
Any design runs with it when prefixed `robot-` (`robot-current`, `robot-oracle-advice`).

- It follows the engine's planned path itself, aiming 0.6 m along it from the engine's own pose
  estimate — never the simulator's truth — and reacts on the next frame.
- It only moves forward while the engine says `STRAIGHT`; it turns in place (up to 90°/s, easing
  in proportionally) on a turn, stops on `STOP`, sweeps the camera on `SCAN`. Safety stays with the
  engine.
- While hunting a room it sweeps its camera ±40° every 4 s. It drives at 1.0 m/s with no sway.

How it was tuned (5 seeds unless noted, so differences of a few points are noise):

| Version | Success | Note |
|---|---|---|
| First robot, steering at the engine's 1.5 m lookahead | 42.3% (20 seeds), mall 14.0% | 8.6% of runs collided; cut corners into the inflation margin and deadlocked on `OBSTACLE_AHEAD` until the 20 s stall timeout |
| Same, signs ignored | 33.3% (20 seeds), mall 4.3% | |
| Slower: 0.7 m/s / 0.5 m/s | 41.5% / 37.0% (40 trips) | fewer trips finish in time; rejected |
| ±40° sweep every 2 s | 52.0% (40 trips) | aliased with the 1 s capture: the same two angles every time |
| Sweep every 2.6 s / 3.3 s / 4 s | 42.7% / 44.5% / 48.2% | not significantly different; 4 s kept |
| **Final: 0.6 m lookahead on the path, 4 s sweep** | **45.6%** (20 seeds), mall 17.0% | +3.3 over the first robot (CI −0.9 to +8.0); collisions down to 2.6% of runs |

Against the simulated person on the same 10 seeds: alone, 45.9% against 51.1% (−5.2, CI −12.5 to
+2.5: not significant); with a perfect adviser, 69.3% against 90.0% (−20.7, CI −27.3 to −14.5).
Most robot failures never see the destination: smooth driving past plates at a grazing angle reads
fewer of them than a person's sway and pauses do.

### Benchmark

44 trips — 5 simple scenes, 24 school, 15 mall — plus 15 trips in three held-out mall layouts,
run only with `--filter heldout`. A trip succeeds when the agent declares arrival within 2 m of a
true destination before 120 s + 6 s per metre of the shortest walk (at most 480 s). SPL weights
each success by the shortest walk over the walk taken. Results record the commit, the design and
the agent. `--compare` pairs two results by trip and seed, bootstraps a 95% interval over trips
(10,000 resamples), and rejects any design that adds falls or more than a point of false arrivals —
and, for a person, more than 5% instruction churn. Use 20 seeds for decisions: at 10, differences
of about 3 points are noise.

#### The engine's history, simulated person

| Stage (result file) | Engine alone | With a perfect route adviser |
|---|---|---|
| Original engine (`v0-original-10`) | 28.0% (mall 0.0) | — |
| `deterministic-reference` | 34.3% | — |
| `deterministic-v2` / `oracle-v2` | 38.9% (mall 2.0) | 67.0% |
| `w9-current` / `w9-level` | 37.7% | 90.0% |
| `g2`, 20 seeds | — | 91.5% |
| `i1-oracle-advice`, 20 seeds | — | 89.7%, 0 falls in 880 runs |
| **Shipped** (`p0-current`, `p0-oracle-advice`) | **51.1%** (simple 94.0, school 66.3, mall 12.7), SPL 0.305 | **90.0%** (mall 87.3), SPL 0.671 |

10 seeds unless stated. Of the shipped engine's failures, 206 of 215 never located the destination;
in the mall, exploration typically entered a shop through its 3 m opening and stayed there.

#### What each feature was worth, simulated person

| Change | Result | Verdict |
|---|---|---|
| A capture every 1 s instead of 2.5 s, with the oracle | 73.9% against 59.3% | kept |
| Tilting the phone up 10° for shop names, with the oracle, 7 mall shop trips × 40 seeds | 87.1% against 56.1% without | kept |
| Shop tilt angle: level / +10° / +20° | 85.7% / 88.0% / 80.9% | +10° kept |
| Following up plates read without depth ("glimpses"), 20 seeds | 91.5% against 89.7% without | kept |
| Assumed fascia height for ranging 3.5 / 4.0 / 4.5 m, with the oracle | 63.6% / 67.0% / 66.1% | 4.0 m kept |
| Sweeping the phone while hunting a room, person | 80.2% against 84.8% | off for a person; the robot sweeps |
| Advice turning to look / releasing a commitment, 20 seeds | both 90.1%, neither 89.7%, no release 88.0%, no turn 90.3% | not significant; both now off |
| Close-range floor check, 20 seeds | 89.7% against 90.2% without | kept for safety |
| Shape cues, no adviser: axis 1 / axis 2 / clutter 1 / clutter 3 | 51.8% / 51.1% / 52.5% / 53.4% against 51.1% | none significant; clutter 3 added glass contacts; off |
| Advice strength with the oracle: full / half / quarter weight | 90.0% / 78.9% / 68.0% against 51.1% | — |

#### Robot results

2026-10-04, one build (`b3c715c+dirty`), 18,060 runs, no falls. 20 seeds for the first block, 10 for
the rest; the 10-seed changes are against the engine alone on the same seeds (45.9%).

| Design | Success | SPL | Simple | School | Mall | Change (95% CI) |
|---|---|---|---|---|---|---|
| `robot-current` — the engine alone | 45.6% | 0.283 | 87.0% | 54.8% | 17.0% | reference |
| `robot-frontier-only` — signs ignored | 36.0% | 0.218 | 80.0% | 46.0% | 5.3% | −9.5 (−14.9 to −4.4) |
| `robot-numbering-off` | 45.2% | 0.281 | 87.0% | 54.2% | 17.0% | −0.3 (−1.7 to +1.0) |
| `robot-oracle-advice` — perfect adviser | 69.2% | 0.491 | 86.0% | 70.6% | 61.3% | +23.6 (+16.2 to +31.5) |
| Held-out malls, engine alone | 44.3% | 0.248 | | | | reference |
| Held-out malls, signs ignored | 14.7% | 0.080 | | | | −29.7 (−42.7 to −16.7) |
| Held-out malls, perfect adviser | 83.7% | 0.533 | | | | +39.3 (+27.3 to +50.7) |
| `robot-planner-oracle` — perfect planner | 60.5% | 0.421 | 88.0% | 62.1% | 48.7% | +14.5 (+7.3 to +21.8) |
| `robot-planner-noisy-a80` | 58.6% | 0.395 | 88.0% | 62.9% | 42.0% | +12.7 (+5.7 to +19.8) |
| `robot-planner-noisy-a60` | 56.6% | 0.377 | 88.0% | 62.5% | 36.7% | +10.7 (+4.1 to +17.5) |
| `robot-planner-noisy-a40` | 55.9% | 0.369 | 88.0% | 60.8% | 37.3% | +10.0 (+2.5 to +18.0) |
| `robot-two-tier-a80` — both tiers right 80% | 60.5% | 0.427 | 80.0% | 66.7% | 44.0% | +14.5 (+7.5 to +21.6) |
| `robot-two-tier-a60` | 56.8% | 0.387 | 82.0% | 63.3% | 38.0% | +10.9 (+3.9 to +18.0) |
| `robot-two-tier-a40` | 52.0% | 0.363 | 76.0% | 58.3% | 34.0% | +6.1 (−1.8 to +14.1) |

Both tiers beat the short-term adviser alone at 80% (+6.4, CI +1.4 to +11.4); at 60% the gain is
not significant (+5.7, CI −0.9 to +12.5).

#### The accuracy curve

A short-term adviser right a given share of the time (`robot-noisy-aNN-wNN`), each answer at a
given weight; when wrong, it points at an opening the true route does not take. Success, robot,
10 seeds; the engine alone scores 45.9% on these seeds and a perfect adviser 69.2% (20 seeds).

| Right | Full weight | Half weight | Quarter weight | Full weight vs alone (95% CI) |
|---|---|---|---|---|
| 40% | 46.8% | 48.2% | 47.3% | +0.9 (−5.5 to +7.3) |
| 50% | 49.3% | 47.0% | 46.8% | +3.4 (−2.7 to +9.8) |
| 60% | 52.3% | 51.1% | 48.6% | +6.4 (−0.5 to +13.4) |
| 70% | 54.8% | 51.4% | 49.8% | +8.9 (+0.2 to +17.5) |
| 80% | 58.6% | 54.1% | 52.3% | +12.7 (+4.5 to +21.1) |
| 90% | 65.9% | 55.5% | 52.3% | +20.0 (+11.4 to +28.9) |

A wrong adviser costs nothing — at 40% success stays where it was — and help becomes significant
from about 70% right at full weight. Weight matters most when the adviser is good: at 90%, half
weight gives +9.5 (CI +2.3 to +16.8) and quarter weight +6.4 (CI +0.0 to +12.7).

#### Follow the Signs' mechanism

[Cao, Kumar and Curtis, 2026](https://arxiv.org/abs/2601.06652), rebuilt as `FollowTheSignsAdviser`:
one of four map-axis directions every 2 s, right with a given probability, accumulated in a
confidence grid (1 m cells, decay 0.9 per answer, searched cells within 2 m zeroed), the agent
heading for the centroid of the cells within 90% of the best. `robot-fts-aNN` gives that subgoal
an advice weight of 10, so it decides the frontier as in their method; `robot-fts-soft-aNN` fuses it
at the usual weight of 2.

| Accuracy | Subgoal decides | vs alone (95% CI) | Subgoal fused | Decides vs fused |
|---|---|---|---|---|
| 60% | 56.6% | +10.7 (+0.7 to +20.5) | 56.8% | +0.2 (−5.9 to +6.4) |
| 80% | 61.6% | +15.7 (+5.7 to +25.7) | 61.8% | +0.2 (−5.9 to +6.4) |
| 100% | 68.6% | +22.7 (+12.7 to +32.5) | 63.2% | −5.5 (−11.8 to +0.9) |

How much authority the stand-in has made no significant difference, and at 80% their mechanism
matches both tiers together (61.6% against 60.5%, −1.1, CI −8.0 to +6.4).

#### Caveats

- `NoisyPlanner`'s one wrong belief per trip is spent once searched, which flatters a 40%-accurate
  planner; real models err less tidily.
- Accuracy is defined differently for each stand-in (the right opening, the right side of the map,
  the right place), so equal percentages are only roughly comparable.
- The held-out malls are easier than the main malls (44.3% against 17.0% for the engine alone).
- Navigation success is simulated; perception is a statistical stand-in for the detector and OCR,
  not images. Depth noise is plausible, not measured on a device.

```bash
cd my-app/navigation-sim
./gradlew installDist
JAVA_OPTS=-Xmx2g BENCH_THREADS=3 build/install/navigation-sim/bin/navigation-sim \
  --benchmark --name r1-current --design robot-current --seeds 20
build/install/navigation-sim/bin/navigation-sim --compare benchmarks/r1-current.json benchmarks/r1-frontier-only.json
build/install/navigation-sim/bin/navigation-sim --filter school-f2 --design robot-current --diagnose 1,2
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

The JVM builds (`navigation-core`, `navigation-sim`) need `JAVA_HOME`; Android Studio's bundled JDK
works: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.

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
- **Semantic image-coordinate mapping is approximate** — rotation is handled, but the RGB and
  depth images are assumed to cover the same field of view.
- **Near-field floor is unknown until seen.** Free space is carved only from visible floor, so at
  the very start the user may be asked to scan until the floor just ahead has been in view.
- **Two independent hazard producers.** Geometric blockage from the engine and perceptual hazards
  from `PerceptionFrame.immediateHazard` both drive the same `hazard.detected` flag. Nobody owns
  merging them, so one can mask the other. **Open.**
- **Single session per process.** `NavigationRuntime` is a singleton.
- **No adviser or planner in the app.** Both inputs exist in the core and are exercised only in the
  simulator; no language-model result exists on the current engine (the one pilot ran on an older
  one, for one seed).
- **The robot gains less from guidance than a person.** Alone it is not significantly worse
  (−5.2 points, CI −12.5 to +2.5), but with a perfect adviser it reaches 69.3% against the person's
  90.0%. Most of its failures never see the destination (§9).
- **No real-world navigation result.** Every success rate here is simulated; the device check in §10
  covers the pipeline running, not trips completed.
- **The numbering model has not moved the benchmark.** It is kept for what it tells a planner.
- **Android only.** The iOS adapter is out of scope; the core is structured to move to
  `commonMain` without algorithm changes.
