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

A useful layout:

| Panel | Topics / settings |
|---|---|
| 3D | display frame `plan`; `/world`, `/user`, `/engine/grid` (colour mode *map*), `/engine/plan`, `/engine/points` |
| Image | `/camera/depth` (16-bit depth, sensor orientation — rotated 90° from what the user sees) |
| Plot | `/engine/status.mapConfidence`, `.scanProgress`, `.headingErrorDegrees` |
| State Transitions | `/engine/status.command`, `/engine/status.status` |
| Log | `/log` — every instruction change, collisions, arrival |

`plan` is the floor plan (x east, y north, z up). The engine's data is in its own `engine` frame,
placed by `/tf` where the engine fixed its origin — so where the grid disagrees with the building,
the engine is wrong about the world.

## Watching it in Webots

```bash
./gradlew run --args="--scenario find-room-314 --webots webots-out"
webots webots-out/find-room-314-seed1/worlds/scene.wbt       # Windows: D:\Webots\msys64\mingw64in\webots.exe
```

Each run becomes a small Webots project: the building as solid geometry (walls grey, low obstacles
orange, glass translucent, signs as coloured plates), the user as a blue figure with a cone showing
where the phone points, a trail of discs coloured by instruction (green straight, blue turning,
yellow scan, red stop), and the engine's occupancy map drawn on the floor as it grew (green
believed free, red believed occupied). The top-left label is the time, engine status and the
instruction being spoken.

Webots is only the viewer: everything ran in the simulator, and a supervisor controller replays the
recording. Use Webots' play, fast-forward and pause buttons; reset the world to watch again. The
controller also writes `controllers/sim_replay/replay.log`.

## What is modelled, and what is not

| Modelled | Not modelled |
|---|---|
| Axis-aligned walls, furniture, doors, glass, dark surfaces, floor openings | Curved or diagonal geometry, stairs as steps |
| Portrait phone at chest height, pitch, walking sway | The phone rolling, or being put in a pocket |
| Depth: z-depth in mm, sensor orientation, range-squared noise, dropouts, grazing-angle loss, flying pixels at edges, glass seen through, 15 Hz one frame late | ARCore's own depth smoothing and temporal filtering; noise calibrated on a real device |
| Tracking loss windows | Pose drift, relocalisation jumps |
| Plane tracking: a surface appears once enough of it is seen, extent grows | Plane merging, vertical planes |
| Perception: signs in view, occlusion, apparent size, the shipped model's recall per class, OCR misreads, 0.6–2.5 s latency, capture timestamps | Pixels. No image is rendered; YOLO and OCR are not run |
| A person: reaction time, turn rate, speed, scanning habits, collisions, falls | Hearing the wrong instruction, or ignoring one |

The perception stand-in re-implements the label mapping of `src/perception/perceptionToSemantic.ts`
(TypeScript can't run here). Keep the two in step.

The noise parameters in `DepthNoise` are plausible, not measured. Before trusting absolute numbers,
record a real session and compare depth statistics.

## Adding a scenario

Buildings are drawn in floor-plan coordinates in `world/Buildings.kt`; scenarios, with their pass
criteria, go in `Scenarios.kt`. Mark one `KNOWN_LIMITATION` (with a `limitation` note saying what
the simulator showed) when it documents something the engine cannot do yet; promote it to `PASS`
when it starts passing.
