# navigation-sim

A desktop simulator for the navigation engine, so mapping, planning and guidance can be tested
without walking around with a phone.

```
scripted person ─► phone pose (portrait, pitch, sway) ─► simulated ARCore depth
      ▲                                                        │
      │                                   navigation-core SensorFrameAssembler  ← the code the phone runs
      │                                                        │
      └──── instruction (after a reaction time) ◄──── NavigationEngine
                                ▲
   signs in view ─► perception stand-in (recall, OCR errors, latency) ─┘
```

Everything is pure JVM: no Android SDK, no ARCore, no device. The core is compiled from source, as
the Android module does, so a run always uses the core as it is on disk.

## Running

```bash
./gradlew test                                        # scenario suite: what must pass does
./gradlew run --args="--list"                         # scenarios and what they check
./gradlew run --args="--all --seeds 10"               # batch report; PNG of every failure
./gradlew run --args="--scenario find-room-314 --trace"   # every change of engine state
./gradlew run --args="--all --seeds 3 --mcap recordings"  # record every run for Foxglove
./gradlew run --args="--live 8765 --scenario doorway"     # watch (and drive) it live
```

A failing run writes a top-down picture to `sim-reports/` (`build/sim-reports/` from the tests):
the real building, the engine's occupancy grid overlaid (green free, red occupied), and the path
walked, coloured by the instruction being followed (green straight, blue turning, yellow scan,
red stop). Red crosses are collisions.

## Watching it in Foxglove

[Foxglove](https://foxglove.dev) (desktop app, free for up to 3 users) opens the recordings and the
live stream.

- **Recordings:** `--mcap DIR`, then *Open local file* and pick the `.mcap`. A 60 s run is ~15 MB.
- **Live:** `--live PORT`, then *Open connection → Foxglove WebSocket → ws://localhost:PORT*. The
  simulation waits for the connection, then runs in real time.
- **Drive it yourself (live):** publish a `geometry_msgs/Twist`-shaped JSON message on `/cmd_vel`
  — Foxglove's Teleop panel does this. `linear.x` is walking speed, `angular.z` turns left. Stop
  publishing for half a second and the scripted user takes over again. Verified with a scripted
  WebSocket client; the Teleop panel itself has not been tried against this server.

A ready layout: *Layouts → Import from file* → `foxglove-layout.json` (this folder). Four tabs:
**Overview** (building, grid, path, camera, depth, frontier scores, memory counts, logs), **Follow**
(a third-person camera behind the walker), **Memory map** (top-down, the engine's belief only) and
**Planner** (landmarks by ID, hypotheses, the active subgoal and every outcome).

| Topic | What |
|---|---|
| `/world`, `/user`, `/tf` | The building, the walker, and frames: `plan`, `engine` (where the engine fixed its origin) and `body` (the walker, +x forward, for follow cameras) |
| `/engine/grid`, `/engine/plan`, `/engine/points` | Occupancy grid (colour mode *map*), planned path, frontiers and goal, depth returns |
| `/lidar/points` | Each 2D LiDAR revolution, placed with the engine's pose estimate |
| `/camera/depth` | 16-bit depth in mm, sensor orientation (rotate 90° to see it upright) |
| `/perception/camera`, `/perception/signs`, `/perception/placements`, `/perception/log` | Each capture with every sign boxed by its fate; every sign's history; where each reading was placed against the truth |
| `/engine/memory`, `/engine/frontiers`, `/engine/memory_summary` | Signs and plates held, numbering estimate, sighting, walked graph, ruled-out areas; every frontier score term |
| `/engine/landmarks` | World-model landmarks by ID (`plate-12 218`, unread ones grey), the active subgoal's target ringed |
| `/engine/strategy`, `/engine/strategy_log` | The active subgoal and every hypothesis with its status; one log line per subgoal outcome |
| `/engine/status`, `/log` | Status, instruction, confidences; instruction changes, collisions, arrival |

`plan` is the floor plan (x east, y north, z up). The engine's data is in its own `engine` frame,
placed by `/tf` where the engine fixed its origin — so where the grid disagrees with the building,
the engine is wrong about the world.

## Watching it in Webots

```bash
./gradlew run --args="--scenario find-room-314 --webots webots-out"
webots webots-out/find-room-314-seed1/worlds/scene.wbt       # Windows: D:\Webots\msys64\mingw64\bin\webots.exe
```

Each run becomes a small Webots project: the building as solid geometry (walls grey, low obstacles
orange, glass translucent, signs as coloured plates), the user as a blue figure with a cone showing
where the phone points, a trail of discs coloured by instruction (green straight, blue turning,
yellow scan, red stop), and the engine's occupancy map drawn on the floor as it grew (green
believed free, red believed occupied). The top-left label is the time, engine status and the
instruction being spoken. The overlay on the 3D view is the phone's camera: what it sees as the user
walks, turns and scans (drag its corner to resize it, double-click it for its own window). The
engine's map and the trail show up in it too, as an AR-style overlay; the depth the engine actually
got, with sensor noise, is `/camera/depth` in Foxglove.

Webots is only the viewer: everything ran in the simulator, and a supervisor controller replays the
recording. Use Webots' play, fast-forward and pause buttons; reset the world to watch again. The
controller also writes `controllers/sim_replay/replay.log`.

## What is modelled, and what is not

| Modelled | Not modelled |
|---|---|
| Axis-aligned walls, furniture, doors, glass, dark surfaces, floor openings | Curved or diagonal geometry |
| Multi-storey buildings: stairs as steps (up is in the way, down is a drop), escalators, atriums | Climbing stairs or riding a lift: a run stays on one floor |
| Portrait phone at chest height, pitch, walking sway | The phone rolling, or being put in a pocket |
| Depth: z-depth in mm, sensor orientation, range-squared noise, dropouts, grazing-angle loss, flying pixels at edges, glass seen through, 15 Hz one frame late | ARCore's own depth smoothing and temporal filtering; noise calibrated on a real device |
| Tracking loss windows; pose drift (`drift-mild`, `drift-strong`: yaw and scale error, occasional relocalisation) | Visual loop closure |
| Plane tracking: a surface appears once enough of it is seen, extent grows | Plane merging, vertical planes |
| Perception: signs in view, occlusion, apparent size, the shipped model's recall per class, OCR misreads, a capture every 1 s (0.3–0.8 s on device; every 4th may wait 0.6–2.5 s on Gemini), capture timestamps | Pixels. No image is rendered; YOLO and OCR are not run |
| A person: reaction time, turn rate, speed, scanning habits, holding the phone as asked (tilt, point, sweep), collisions, falls | Hearing the wrong instruction, or ignoring one |
| A robot (`robot-` designs): follows the engine's path at 0.6 m lookahead, turns in place, sweeps its camera ±40° while hunting a room | Wheel slip, motor limits, a real robot's camera mount |

The perception stand-in re-implements the label mapping of `src/perception/perceptionToSemantic.ts`
(TypeScript can't run here). Keep the two in step.

The noise parameters in `DepthNoise` are plausible, not measured. Before trusting absolute numbers,
record a real session and compare depth statistics.

## Large buildings

Two multi-storey buildings sit beside the single-feature scenes:

- **School** (`world/School.kt`, 3 floors): a 60 m locker-lined corridor of numbered classrooms
  (1xx-3xx), a wing, a switchback stairwell at each end, a lift niche, washrooms, room-range signs
  with arrows; an entrance lobby on floor 1, EXIT signs on the stairwells above it.
- **Mall** (`world/Mall.kt`, 2 levels): an 84 m concourse between glass-fronted shops with shelving
  and name signs, an atrium behind a glass balustrade, escalators, kiosks, a food court, and a
  services corridor with lifts and washrooms.

`School.build(floor)` / `Mall.build(level)` put the chosen floor at height 0, so everything that
assumes one floor still holds; the others are real geometry above and below it (visible through
stair openings and the atrium, drawn see-through in Webots and Foxglove). Draw a new one with
`SceneBuilder.storey(elevation) { ... }` and `stairwell(...)`. Their scenarios are prefixed
`school-` and `mall-`; most are `KNOWN_LIMITATION`s, and their notes say what the runs showed.

## Chest rig, planners and the evidence ledger

The design being built is `../docs/LAYERED_NAVIGATION_DESIGN.md`. In the simulator:

- **Rigs** (`sensor/SensorRig.kt`): `rig-chest-c1` is the rig of record - the phone strapped to the
  chest (fixed 10° pitch, turns only with the body) plus a 2D 360° LiDAR (`sensor/PlanarLidar.kt`,
  RPLIDAR C1 class: 10 Hz, 0.05-12 m, the wearer's body blocking 150° behind). `rig-chest-phone` is
  the harness without the LiDAR. Use them with the robot: `robot-rig-chest-c1`.
- **Modifiers** stack on any design with `+`: `+unread` (perception reports text seen but not read),
  `+perfect` (perfect-if-visible perception), `+verify` (arrival only once the destination is read at
  or near its door), `+tactics` (the chest rigs' tactical fixes on any rig), `+b1` (today's tactics
  through the planner machinery: must equal no planner), `+b4` (hypotheses and one-step information
  gain), and the paid model baselines `+b5` (OpenAI Decisions API), `+b3`, `+b2` (a generative chooser
  with and without the hypotheses).
- **Paid runs are gated.** A model baseline makes live calls only with `OPENAI_API_KEY` *and*
  `NAV_LLM_ALLOW=1`; otherwise cached replies only, and the planner falls back to its heuristic.
  `NAV_LLM_DRY_RUN=1` sends nothing and prints how many requests and tokens a run would use.
  `LLM_BUDGET_USD` (default 1.00) caps spend across runs (`benchmarks/llm-cache/openai-spend.txt`).
  The generative chooser also needs `OPENAI_MODEL`, `OPENAI_PRICE_IN` and `OPENAI_PRICE_OUT`.
- **Evidence ledger:** `--ledger DIR` writes each run's observation events, landmarks, subgoal
  outcomes, rejections and hypotheses as JSON lines, and checks that replaying the events rebuilds the
  same landmarks.
- **Procedural floors** (`world/ProceduralFloor.kt`): six numbering families - floor digit, wing
  blocks, one run up and back (U), gaps and suites, lettered halves (E-203 / W-203), digit = wing -
  with accurate, missing or misleading range signs. `--filter proc` runs all 108 trips,
  `--filter proc-tune` the two families planners may be tuned on, `--filter proc-test` the four held
  out. The benchmark judges confirmed hypotheses against each floor's true numbering.
- **Multi-floor buildings** (`world/MultiFloorBuilding.kt`): 3-4 floors over one footprint with a
  lift. The robot rides it by asking for a floor at its door (the `TAKE_LIFT` skill); the ride takes
  time, tracking drops in the car, and on arrival the display is read - the engine gets the floor's
  label. Families: `floor-digit` (tuned on), `offset` (the lift's labels one higher than the rooms'
  digits) and `digit-is-wing` (the lift is a trap) held out. `--filter mf`, `mf-tune`, `mf-test`.
- **Training data:** `--filter proc-tune --seeds N --fork-data FILE` forks runs at the planner's
  decisions (deterministic replay, reseeded noise after the fork) and labels every option with its
  cost-to-go (`bench/ForkData.kt`). `FORK_DECISIONS`, `FORK_K` set how many decisions and continuations.
- **The trained scorer:** `--train-scorer DATA MODEL` fits P (`bench/TrainScorer.kt`: ridge regression
  of relative cost-to-go, validated on held-out buildings, judged by regret); `+p` runs it, from
  `NAV_SCORER_MODEL` (default `datasets/scorer-v1.json`).
- **Comparisons** (`--compare A.json B.json`) give a second interval resampling whole buildings when
  there are at least 8 (the procedural and multi-floor sets), since trips in one building are not
  independent either.

## Benchmark

```bash
./gradlew run --args="--episodes"                                       # the 44 trips (+15 held out)
./gradlew run --args="--benchmark --name my-run --design robot-current --seeds 20"
./gradlew run --args="--compare benchmarks/a.json benchmarks/b.json"    # paired, 95% CI
./gradlew run --args="--filter school-f2 --design robot-current --diagnose 1,2"
```

A design changes every run's settings so two can be compared on the same trips and seeds:
`current`, `frontier-only`, `numbering-off`, `oracle-advice`, `noisy-aNN-wNN` (an adviser of known
accuracy and weight), `planner-oracle`, `planner-noisy-aNN`, `two-tier-aNN`, `fts-aNN` /
`fts-soft-aNN` (Follow the Signs' mechanism), `llm-advice` (paid), and more in `bench/BenchmarkCli.kt`.
Prefix any of them with `robot-` to run it with the robot instead of the person. Held-out malls run
only with `--filter heldout`. Results and what they showed are in `../../TECHNICAL.md` §9.

The machine is short of memory: cap threads and heap (`BENCH_THREADS=3`, `JAVA_OPTS=-Xmx2g` on the
installed launcher), and run one design at a time.

## Adding a scenario

Buildings are drawn in floor-plan coordinates in `world/Buildings.kt`; scenarios, with their pass
criteria, go in `Scenarios.kt`. Mark one `KNOWN_LIMITATION` (with a `limitation` note saying what
the simulator showed) when it documents something the engine cannot do yet; promote it to `PASS`
when it starts passing.
