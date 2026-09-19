# navigation-native

An indoor navigation engine for blind and low-vision users, built as a local Expo native module.

It takes a phone into a building it has never seen — no floor plan, no pre-mapped route, no
beacons — discovers walkable space as the user walks, and emits one of six instructions:

```
STRAIGHT · TURN_LEFT · TURN_RIGHT · STOP · SCAN · ARRIVED
```

Movement decisions are made by deterministic geometry and search. No LLM or VLM is anywhere in
the decision path.

---

## 1. Architecture

```
                    Expo / React Native
                           │   small commands + one compact snapshot
                  NavigationNative API
                           │
             ┌─────────────┴─────────────┐
             │                           │
       Android adapter            (future) iOS adapter
          Kotlin                        Swift
             │                           │
          ARCore                       ARKit
             │                           │
             └─────────────┬─────────────┘
                           │  canonical NavigationFrame
                  Navigation core (pure Kotlin)
                           │
   ┌──────────┬────────────┼────────────┬───────────┐
 Mapping   Exploration  Planning    Topology    Semantics
```

The per-frame pipeline:

```
ARCore Frame
  → tracking check              (not TRACKING → LOST_TRACKING + STOP)
  → camera pose                 → canonical Pose3D
  → depth image (16-bit)        → subsampled → camera space → world → canonical points
  → floor estimation            (ARCore plane hint, else depth histogram)
  → obstacle classification     (0.10 m .. 2.10 m above the floor)
  → occupancy grid              (log-odds ray integration + temporal decay)
  → obstacle inflation          (body radius + clearance field)
  → goal selection              target known ? route to it : best-scoring frontier
                                nothing left → backtrack through the topological graph
  → local A*                    → line-of-sight smoothing
  → lookahead waypoint          → smoothed heading error → hysteresis
  → NavigationCommand           → throttled snapshot to JS
```

### Where the code lives

| Path | Contents | May import Android/ARCore? |
|---|---|---|
| `modules/navigation-native/android/.../navigationnative/` | Expo module, AR view, runtime/threading | **yes** |
| `modules/navigation-native/android/.../navigationnative/platform/` | ARCore session, depth, pose, frame processing | **yes** |
| `my-app/navigation-core/src/main/kotlin/com/navassist/navcore/` | mapping, exploration, planning, topology, semantics, state | **no** |
| `modules/navigation-native/src/` | TypeScript API | – |

The core is a **separate Gradle project** that is compiled and tested without the Android or
ARCore classpath, and is pulled into the Android module through a `sourceSets` `srcDir`. That is
what keeps the boundary honest: a stray `import com.google.ar.core.*` in the core breaks the core
build immediately rather than quietly creating a platform dependency.

The planner has never heard of a `Frame`:

```kotlin
// what this module does NOT do
class AStarPlanner(frame: com.google.ar.core.Frame)

// what it does
class AStarPlanner(config: NavigationConfig)
fun plan(grid: InflatedGrid, start: GridCoordinate, goal: GridCoordinate): List<GridCoordinate>?
```

---

## 2. Module setup

The module is a local Expo module. Two things make it link:

1. `modules/navigation-native/expo-module.config.json` declares the Android module class.
2. The app's `package.json` points autolinking at the folder:

```json
"expo": { "autolinking": { "nativeModulesDir": "./modules" } }
```

Verify with:

```bash
npx expo-modules-autolinking search -p android   # navigation-native should be listed
```

**Expo Go cannot run this module.** Expo Go ships a fixed set of native modules and cannot load a
custom one, and ARCore requires native code and a camera session. A development build (or a
release build) is required — see §11.

---

## 3. How ARCore is configured

`ArCoreSessionManager` creates one `Session` and configures:

| Setting | Value | Why |
|---|---|---|
| `depthMode` | `AUTOMATIC` **if** `isDepthModeSupported` | Depth is the engine's only obstacle source. If unsupported it is left `DISABLED` and `isSupported()` reports `depthSupported: false`. The engine does not pretend. |
| `planeFindingMode` | `HORIZONTAL` | Supplies the optional floor hint. |
| `updateMode` | `BLOCKING` | One AR frame per rendered frame; no busy-wait. |
| `lightEstimationMode` | `DISABLED` | Unused; saves work. |
| `focusMode` | `AUTO` | Better depth on close surfaces. |

**ARCore owns the camera.** Do not open CameraX or Camera2 anywhere in the app while navigation
is running — the second consumer will either fail or evict ARCore. If another feature needs RGB
frames, expose them from the ARCore frame inside `ArCoreFrameProcessor` rather than starting a
competing capture session.

`NavigationArView` hosts a `GLSurfaceView` that creates one external OES texture, hands it to the
session, and calls `Session.update()` once per frame. It renders **nothing** — ARCore simply
requires a GL context and a camera texture to produce frames. Mount the view for the whole time
navigation runs; unmounting it stops the sensor feed.

Depth images are acquired with `frame.acquireDepthImage16Bits()` inside `use {}` so they are
always closed. `NotYetAvailableException` is normal (especially in the first second) and is
swallowed; the engine only escalates to `SCAN` once depth has been missing for
`depthStarvationMillis`.

---

## 4. JavaScript API

```ts
import NavigationNative, {
  NavigationArView,
  addNavigationStateListener,
} from '../../modules/navigation-native';

// 1. Check before you start. Never assume depth exists.
const support = NavigationNative.isSupported();
// { arCoreSupported, depthSupported, trackingAvailable, reason? }

// 2. Mount the AR view for as long as navigation runs.
<NavigationArView style={{ width: 1, height: 1 }} debug />

// 3. Drive the session.
await NavigationNative.start({ type: 'ROOM', value: '314' });
await NavigationNative.setTarget({ type: 'EXIT' });
await NavigationNative.pause();
await NavigationNative.resume();
await NavigationNative.resetMap();
await NavigationNative.stop();

// 4. Listen for state (<= ~8 Hz; immediate on any status/command change).
const sub = addNavigationStateListener((snapshot) => {
  speak(snapshot.command);
});
sub.remove();

// or poll
const now = NavigationNative.getSnapshot();

NavigationNative.setDebugEnabled(true);
```

The snapshot:

```ts
{
  timestamp: 1712345678,
  status: 'NAVIGATING',
  command: 'TURN_RIGHT',
  headingErrorDegrees: 28,     // negative = left, positive = right
  distanceToWaypointMeters: 1.5,
  distanceMeters: 3.2,         // to the destination when known
  trackingConfidence: 0.9,
  mapConfidence: 0.42,
  selectedFrontierId: 'F17',
  target: 'Room 314',
  debug: { /* only when setDebugEnabled(true) */ }
}
```

Depth maps, camera frames, point clouds, grids and coordinate lists **never** cross the bridge.

The app is responsible for the runtime `CAMERA` permission (e.g. `expo-camera`'s
`useCameraPermissions()` or `PermissionsAndroid`). The module declares the permission but does not
request it.

---

## 5. Coordinate conventions

Two frames exist. Everything below the adapter uses the second one.

**ARCore world frame** — right-handed, +Y up (gravity-aligned), X/Z chosen arbitrarily at session
start. The camera looks along its own **−Z**.

**Canonical navigation frame** — established from the first well-tracked pose:

```
origin  device position when navigation started
+y      up
+z      initial device forward, projected onto the floor
+x      right
yaw     atan2(x, z)   → 0 is straight ahead, POSITIVE yaw is a turn to the RIGHT
```

`ArCorePoseProvider` builds the orthonormal basis `right = forward × up` and expresses every pose
and point relative to it. `(right, up, forward)` is *left*-handed on purpose — that is what makes
positive yaw mean "turn right". The change of basis is an orthonormal reflection, so distances and
angle magnitudes are exact; only the rotation sign convention flips.

Planning happens on **X/Z only**. Y is used solely to classify points against the floor.

Depth unprojection accounts for two axis flips and one resolution mismatch:

```
depth resolution ≠ RGB resolution   → camera intrinsics are rescaled to the depth image
depth image v axis points DOWN      → camY = -(v - cy) * d / fy
camera looks along -Z               → camZ = -d
```

A future ARKit adapter performs the identical construction from ARKit's own y-up world frame and
produces identical `NavigationFrame`s.

---

## 6. Occupancy grid parameters

A **rolling, local** grid over X/Z. Long-range memory is the topological graph's job, not a
building-sized 10 cm grid.

| Parameter | Default | Note |
|---|---|---|
| `gridResolutionMeters` | 0.10 | |
| `gridSizeMeters` | 12 | 120 × 120 cells, small enough to replan often |
| `gridRecenterThresholdMeters` | 2.0 | window shifts in whole cells; overlap is preserved |
| `logOddsHit / logOddsMiss` | +0.85 / −0.45 | evidence, not a hard enum |
| `logOddsOccupiedThreshold` | 0.9 | ≥ this ⇒ OCCUPIED |
| `logOddsFreeThreshold` | −0.5 | ≤ this ⇒ FREE |
| `decayPerSecondWeak` | 0.80 | a person who walked past fades in a couple of seconds |
| `decayPerSecondStable` | 0.97 | confirmed walls persist |
| `stableEvidenceThreshold` | 2.0 | \|log-odds\| above which decay slows |
| `inflationRadiusMeters` | 0.45 | the user is not a point |
| `clearanceCostRadiusMeters` | 0.85 | soft cost that keeps paths off walls |
| `minObstacleHeightMeters` | 0.10 | below ⇒ floor |
| `maxObstacleHeightMeters` | 2.10 | above ⇒ overhead, ignored |
| `depthSampleStride` | 3 | every 3rd depth pixel |
| `maxDepthMeters` | 6.0 | depth accuracy degrades badly beyond this |

Every observation is **ray-integrated**: cells between the sensor and the measured point gain FREE
evidence, the endpoint gains OCCUPIED evidence. Without this the map would be obstacle dots in a
sea of UNKNOWN, and frontier detection (a FREE cell beside an UNKNOWN cell) could never fire.

**UNKNOWN is never traversable.** The single exception is a cell within
`allowUnknownNearGoalCells` (3) of an *exploration* goal, so a frontier goal sitting exactly on
the known/unknown boundary stays reachable. Navigation to a known target never uses it.

All parameters live in `NavigationConfig`. There are no magic numbers scattered through the
algorithms, and the defaults are tuning starting points, not measured optima.

---

## 7. Frontier scoring

```
score = weightInformation * informationGain     (+1.5)
      + weightSemantic    * semanticScore       (+3.0)
      + weightDistance    * normalisedDistance  (−1.0)
      + weightRevisit     * revisitPenalty      (−2.0)
      + weightRisk        * riskPenalty         (−2.0)
```

- **informationGain** — fraction of unknown cells around the frontier, 0..1.
- **semanticScore** — see §9. Roughly −1..1.5.
- **normalisedDistance** — metres ÷ half the grid size. Raw metres would swamp every other term
  the moment a frontier was a few metres away, and no clue could ever outweigh it.
- **revisitPenalty** — how densely the topological graph already covers that area.
- **riskPenalty** — how tightly the frontier is wedged against an obstacle.

Selection adds two behaviours that matter more than the weights:

- **Hysteresis** — frontier ids are regenerated every detection pass, so the manager tracks its
  choice by *position* and only switches when a rival wins by `frontierSwitchHysteresis` (0.8).
  Without it the user gets "left, right, left" halfway down a corridor.
- **Blacklisting** — after `frontierFailuresBeforeBlacklist` (3) failed planning attempts a
  frontier is abandoned.

Clusters smaller than `minFrontierCells` (6) are rejected as depth speckle. The grid's border ring
is excluded from detection: out-of-bounds reads report UNKNOWN, so including it would ring the
window with phantom frontiers that are an artefact of the window size.

---

## 8. Navigation state machine

```
IDLE ──start──► INITIALIZING ──tracking──► LOCALIZING ──map ready──► EXPLORING
                                                                        │
                                                    target located ─────┤
                                                                        ▼
                                                                   NAVIGATING ──arrived──► ARRIVED
```

Two rules dominate the table:

1. **Tracking loss beats everything.** From any moving state (`EXPLORING`, `NAVIGATING`,
   `BACKTRACKING`) it goes straight to `LOST_TRACKING`, paired with `STOP`. There is no
   "carry on and hope" edge.
2. **Transient interruptions remember what they interrupted.** `LOST_TRACKING` and `NO_ROUTE`
   return the user to what they were doing rather than restarting exploration.

Other states: `BACKTRACKING` (local branch exhausted, routing back through the graph), `PAUSED`,
`ERROR`.

### Fail-safe behaviour

`STRAIGHT` is never emitted when:

- ARCore tracking is invalid or confidence is below `minTrackingConfidence`
- depth has been missing for longer than `depthStarvationMillis` (→ `SCAN`)
- the floor estimate is not yet confident enough to classify obstacles (→ `SCAN`)
- any blocked **or unknown** cell lies within `forwardSafetyDistanceMeters` (0.9 m) straight ahead
- the planner has no route (→ `SCAN`)

`STOP` bypasses the command-stability counter entirely: a safety stop is never delayed by
smoothing. Turn commands use two thresholds (enter at 15°, release at 8°) so guidance does not
chatter, and the heading error is smoothed *circularly* — averaging angles naively breaks at the
±180° seam.

This is an accessibility prototype, **not a certified mobility device**.

---

## 9. Submitting semantic observations

The engine implements **no** recognition. OCR, sign detection and exit recognition belong to the
perception task; this is the hand-off point.

```ts
await NavigationNative.submitSemanticObservations([
  { type: 'ROOM_RANGE', min: 300, max: 349, direction: 'RIGHT', confidence: 0.9 },
  { type: 'ROOM', label: '314', confidence: 0.92, normalizedX: 0.62, normalizedY: 0.4,
    timestampNs: frameTimestampNs },
  { type: 'EXIT', direction: 'FORWARD', confidence: 0.8 },
  { type: 'FLOOR', floor: '3', confidence: 0.95 },
]);
```

- `normalizedX/Y` (0..1 image space) are resolved to a canonical world position **on the native
  side**, against a retained copy of the recent depth image — the core never sees depth images or
  intrinsics. Observations older than 500 ms are rejected rather than associated with the wrong
  part of the corridor. A 5×5 depth window is averaged because a single pixel on a door plate is
  easily invalid.
- A resolved sighting of the current target is what flips `EXPLORING → NAVIGATING`. A sighting
  with no resolvable position is kept as a directional hint but never becomes a destination.
- Observations below `semanticMinConfidence` (0.3) are dropped; all hints age out after
  `semanticMaxAgeMillis` (30 s).

**Room numbering is a heuristic, never a guarantee.** A sign that *covers* the target is strong
positive evidence (+1). A sign that does *not* cover it is only weak negative evidence (−0.25), so
an alternative branch is never permanently eliminated. The "301, 303, 305 → keep going" gradient
contributes at most 0.5 and shrinks as the numeric gap grows.

Semantics only ever bias frontier choice. The planner decides movement.

---

## 10. Running the tests

All algorithmic tests run against the platform-independent core — no ARCore session, no Android
context, no Expo module, no device:

```bash
cd my-app/navigation-core
./gradlew test       # gradlew.bat on a Windows shell
```

The only requirement is a JDK (17 or newer). If `java` is not on your PATH, point `JAVA_HOME` at
the JDK that ships with Android Studio:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

Coverage: occupancy grid (coordinate round-trips, bounds, ray marking, decay, recentering),
obstacle inflation and clearance, A* (empty room, corridor, wall with a gap, free-standing
obstacle, no route, unknown-space refusal), path smoothing, frontier detection (corridor,
T-junction, none, noise rejection, centroid snapping), frontier scoring (semantic clue vs
distance, revisit, risk), topological map (merge, routing, blocked edges, multi-floor), semantic
hint store, the state machine, the navigation controller, and full-pipeline scenarios driven by a
synthetic ray-cast world.

---

## 11. Running on a physical device

Expo Go will not work. Build a development build.

```bash
# once
npm install
npx expo prebuild --platform android

# device connected, USB debugging on
npx expo run:android

# or just the module
cd android && ./gradlew :navigation-native:assembleDebug
```

Requirements:

- An **ARCore-supported device** (https://developers.google.com/ar/devices) that also supports the
  Depth API. `isSupported()` reports both; if `depthSupported` is false, do not navigate.
- Google Play Services for AR installed (the module requests installation when an Activity is
  available).
- Camera permission granted at runtime by the app.
- `android/local.properties` with `sdk.dir=...`, or `ANDROID_HOME` set. On Windows use forward
  slashes — `sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk` — because a `.properties` file
  treats a lone backslash as an escape character and silently mangles the path.

A diagnostics screen is included at `src/app/navigation-debug.tsx` (route `/navigation-debug`). It
mounts the AR view, exposes start/stop/target buttons and prints the full debug block. It is
deliberately unstyled: the accessible speech/haptic UI is a separate task.

On device you should see `depthPointsLastFrame` in the low thousands, `floorConfidence` climbing
above 0.45 within a second or two, then `freeCells` and `occupiedCells` growing as you sweep the
phone, and `frontierCount` becoming non-zero once there is open space ahead.

---

## 12. Known limitations

- **Not verified on physical hardware.** Everything here is covered by core unit tests and a
  synthetic-world integration test; the ARCore adapter compiles but has not been run against a
  real device in this work.
- **Depth encoding assumption.** `acquireDepthImage16Bits()` is read as 16-bit millimetres per
  ARCore's documentation. If a device packs confidence into the top 3 bits (the generic Android
  `DEPTH16` layout), readings will land outside `[minDepth, maxDepth]` and be discarded — the
  engine degrades to `SCAN` rather than inventing free space, but depth would be effectively
  unusable on that device.
- **Semantic image-coordinate mapping is approximate.** Normalized coordinates are assumed to
  address the same field of view as the depth image; no display-rotation or aspect correction is
  applied yet.
- **No visual loop closure.** Revisit detection is position-proximity plus floor compatibility.
  Over a long walk, ARCore drift will eventually create duplicate graph nodes.
- **Local grid only, 12 m.** Goals beyond the window are clamped onto its edge along the line to
  the goal. Routing across a whole building relies on the topological graph being dense enough.
- **Multi-floor is structural only.** Nodes carry `floorId`, vertical edges exist and route
  correctly, but nothing automates using a lift or stairs.
- **Moving obstacles** are handled only by evidence decay — there is no object tracking or
  prediction. A person walking towards the user is a stale obstacle for a second or so.
- **Frontier weights are guesses.** They are configuration, not measured optima, and need tuning
  on real hardware with real users.
- **Single session per process.** `NavigationRuntime` is a singleton; two simultaneous engines are
  not supported.
- **Android only.** The iOS adapter is deliberately out of scope; the core is structured so it can
  move to `commonMain` without algorithm changes.
