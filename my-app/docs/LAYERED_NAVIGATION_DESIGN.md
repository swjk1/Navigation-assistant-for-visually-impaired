# Layered navigation: simulation design

**Status:** being built, 14 October 2026. See §11 for what exists and what it measured.
**Goal:** a paper on hypothesis-conditioned active perception for map-free room search, then a
real-world implementation once the hardware is final.
**Scope now:** the simulator only. The robot reaches the right room as often as possible.

This document fits two outside designs to this codebase:

- [`hypothesis-active-perception/technical_implementation.md`](hypothesis-active-perception/technical_implementation.md)
  (with its diagrams and `prototype_config.yaml`): the research core. Hypotheses, active
  perception actions, a decision model that scores candidate actions, and eventually a trained
  cost-to-go scorer. Its section numbers are cited below as **TI §n**.
- [`BELIEF_DRIVEN_NAVIGATION_PLAN.md`](BELIEF_DRIVEN_NAVIGATION_PLAN.md): the evidence-ledger world
  model and contracts. Its real-world parts (iOS, BLE wristbands, field tests) are shelved until
  the hardware is final.

Where those documents and this one differ (language, simulator, sensors), this one is what will be
built. §9 lists the differences.

## 1. Decisions

| # | Question | Decision |
|---|---|---|
| 1 | What "accurate" means | The agent reaching the right room, verified at the door |
| 2 | Sensors | Chest mount: phone (camera, depth, tracking) plus a 2D 360° LiDAR, under $200 (§2) |
| 3 | Who walks | The robot proxy only |
| 4 | Floors | Single floor first; multi-floor (lifts first) once single floor works |
| 5 | Buildings | A procedural generator in the single-floor phase, because the paper needs many buildings and held-out families (§8) |
| 6 | Testing hypotheses | Deterministic code decides confirmed or refuted; models propose (§4.3) |
| 7 | Paid models | OpenAI. Built and wired in; **never run without an explicit go-ahead** for that run (§6) |
| 8 | Short-term planner | Classical search: A*, frontier exploration |
| 9 | Skills | The expanded set in §5, including the active perception actions from TI §3.2 |
| 10 | World model | The evidence-ledger model (§3) |
| 11 | Arrival | Only after reading the destination's label at its door (TI §3.3) |
| 12 | Engine | Refactor `NavigationEngine` in place |
| 13 | Language | Kotlin for everything that runs. *Proposed exception:* training the scorer in Python, exported to ONNX for Kotlin inference (§7) |
| 14 | Phone-held mode | Results at the refactor's starting commit are frozen as the reference; the hand-held path is not maintained |
| 15 | Decision model | An off-the-shelf model now (OpenAI's Decisions API); the trained scorer stays in scope for later (§7) |
| 16 | Perception mode | Both: perfect-if-visible for developing the planner, realistic noise for the headline results |
| 17 | Camera | The phone's real field of view (to be measured); TI's 80°×55° preset as a benchmark variant |
| 18 | `ask_user_or_staff` | Dropped while the agent is a robot |
| 19 | Rigour | Paper standard: primary metric and held-out families declared before results are run |

Long term the hardware is a custom wearable LiDAR and camera, tethered to a phone for local compute,
with the phone talking to the cloud. The layers are drawn so the sensors can change underneath.

## 2. Sensor rig

| Part | Choice | Cost | Notes |
|---|---|---|---|
| LiDAR | Slamtec RPLIDAR C1 (alternatives: LDROBOT LD19, YDLIDAR X2L) | ~$80 | 2D, 360°, 0.05–12 m, 10 Hz. Check the spec sheet for angular resolution and noise before fixing the model |
| Camera, depth, pose | The phone, on a chest harness | phone | RGB for signs, depth for the floor ahead, visual-inertial tracking for pose |
| Mount | Chest harness for both | ~$20–40 | LiDAR above the phone, scan plane horizontal |

No true 3D 360° LiDAR exists under $200. The cheapest found is the Unitree L1 at $349.

What the simulator has to model:

- **One scan plane** at about 1.2 m. It sees walls, doorways and columns. It misses anything
  entirely below or above the plane: boxes, benches, tables, stair wells, floor drops.
- **The wearer's body blocks the rear** of the scan. The blocked sector is a parameter (default
  150°), to be measured on the real harness.
- **The phone covers what the plane misses:** the floor ahead, low obstacles and drops.
- **The phone's pitch is fixed by the mount.** The default is 10° down, as TI §5.3 also proposes,
  to be tuned by benchmark.
- **Glass:** most of the scan passes through it.

### Two kinds of free space

A LiDAR ray at chest height says the air is empty, not that there is floor underneath.

| State | Source | Used for |
|---|---|---|
| Floor-seen free | Phone depth saw floor there | Walking: the only state the robot may enter |
| Plane-clear | Only the LiDAR saw it empty | Planning and frontier choice: routes may go through it, but the robot stops before it unless the floor is seen |
| Occupied | Either sensor | Never entered |
| Unknown | Neither | Frontiers |

This keeps today's rule that free space comes only from seen floor, so drops stay safe.

## 3. World model

This replaces `SemanticHintStore`'s 30-second hint list. It lasts one trip.

- **Evidence ledger (append-only).** Every observation becomes an `ObservationEvent`: ID, time,
  pose, kind, value, how it was placed (depth, LiDAR, bearing-only, ranged), score, source
  capture. Events are never edited or deleted. Everything derived can be recomputed from them.
  Action outcomes go into the same ledger.
- **Detected is not readable** (TI §5.1). A sign can be seen without being legible. The ledger then
  gets an "unreadable sign here" event, which is what makes `inspect_sign` worth choosing. Today
  the perception stand-in knows a sign's fate (too small, oblique, occluded) but tells the engine
  nothing.
- **Correlated evidence** (TI §4.3). Two readings of the same capture (OCR and a vision model) are
  one observation with two estimates, never two independent pieces of evidence.
- **Landmarks** with stable IDs (`door-12`, `plate-218`, `sign-4`). Associating events with
  landmarks is a separate, tested component.
- **Facts vs inferences.** Observations and landmarks are facts. Numbering fits, target estimates
  and hypotheses live in the belief graph (§4.3).
- **Map.** The occupancy grid covers the whole floor reached so far, not a 12 m window, with
  chunked storage. The topological graph gains landmark nodes with edges, each edge
  `known_accessible`, `blocked` or `unknown`.
- **`WorldStateSnapshot`.** A bounded, versioned view for the long-term planner and the decision
  model: goal, pose, relevant subgraph and revision number, nearby landmarks, competing hypotheses
  with their evidence, frontiers, recent outcomes, allowed skills, what is still missing.
- **Storage.** In memory during a run, written out as JSONL per trip for replay and training
  data. No SQLite.

## 4. Architecture

```
sensors (phone camera/depth/pose, 2D LiDAR)
   → perception → ObservationEvents
   → world model (ledger, landmarks, grid, graph, beliefs)
   → long-term planner
        generate candidates  (hypotheses + candidate actions: rules, or a language model)
        validate             (grounded targets, preconditions)
        score                (decision model: heuristic, OpenAI Decisions API, later trained)
        select               (minimum expected cost among feasible, safe candidates)
   → StrategicSubgoal
   → short-term planner: skill executor, A*, frontier search, viewpoint planning, controller
   → commands → robot
   → ActionOutcome → ledger → long-term planner (on surprise, completion or contradiction)
safety gate: sees sensors and commands directly; final veto on every command
```

All of this is refactored inside `navigation-core`, so the simulator keeps driving the code that
would ship.

### 4.1 Long-term planner

- **When it runs:** on events, not every frame. A subgoal ended, a relevant landmark appeared, a
  hypothesis changed status, a timeout.
- **States** (TI §9.1): waiting → deliberating → executing → verifying → replan, arrived or
  paused.
- **Candidate generation:** rule templates (free), or a language model proposing hypotheses and
  actions. Either way, every candidate must name an existing landmark, frontier, viewpoint or
  node, and carry a travel limit, a deadline and stop conditions.
- **Validation:** a candidate referring to anything that doesn't exist is rejected. One retry with
  the reason, then the rule-based choice.
- **Scoring:** the decision model estimates, for each candidate, whether it will produce usable
  evidence, whether that evidence would tell the competing hypotheses apart, and the expected
  remaining cost (TI §6.1). The selector takes the lowest expected cost among feasible candidates.
  Safety is a hard filter, never a penalty term.
- **Scorers**, from simplest to the research target:

  | Scorer | What it is | Baseline |
  |---|---|---|
  | Heuristic | One-step information gain over a closed hypothesis set, with hand-set likelihoods | B4 |
  | Off-the-shelf | OpenAI's Decisions API: typed questions over text and images, returning probabilities; an explicit cost rule turns those into a choice | B5 (the working default) |
  | Trained | A cost-to-go model trained on simulator rollouts (§7) | P (later) |

  A raw API probability is not a calibrated navigation probability (TI §8.3). Its outputs are
  logged and calibrated against outcomes before they are trusted as likelihoods.

### 4.2 Short-term planner

Classical for now: A* over the grid, frontier search, the topological graph for backtracking,
viewpoint planning over **known** geometry only, and today's `NavigationController` for steering.
It runs one skill at a time and reports an `ActionOutcome` (succeeded, failed, blocked, timed out,
interrupted by safety) with the evidence gathered.

### 4.3 Hypotheses

A hypothesis is a claim plus the predictions it makes. It has an ID, a status (proposed, supported,
contradicted, confirmed, retracted), evidence for and against (ledger event IDs), the hypotheses it
depends on, and its revision history. Contradicted and retracted hypotheses are kept for replay.

- **Who proposes:** rule templates, a decision model, or a language model.
- **Who decides:** a deterministic verifier compares predictions against new events and sets the
  status. A model may propose another interpretation when a hypothesis fails, but never sets
  the status.
- **Probabilities only for closed sets** (which way numbers rise, which side odd numbers are on,
  floor vs wing). Open-ended claims stay categorical (TI §4.2).

| Hypothesis | Predicts | Can fail in |
|---|---|---|
| Numbers rise in direction D along this corridor | The next plate in D is higher | Reversed numbering |
| Odd numbers are on side S | The next odd plate is on side S | Swapped sides |
| Numbers advance at k per metre | Room N is about (N − n)/k metres from plate n | Gaps, suites |
| Range sign R covers the target and points that way | Plates that way approach the target | Stale or misleading signs |
| The label prefix is the wing (E-203) | Plates in wing E carry E | Wing prefixes |
| **The leading digit is the floor** | Every plate on this floor starts with that digit; the target's digit names its floor | Offset numbering; wing-coded digits |
| The leading digit is the wing | Plates change leading digit between wings on one floor | Floor-coded digits |

The last two compete: they explain the same plates differently (TI §4.2, H1/H2), and choosing a
test that separates them is exactly the decision the scorer is for. Today's built-in assumptions
in `RoomNumberModel` and `targetLikelyOnAnotherFloor` become hypotheses the planner can drop. The
floor-digit hypothesis is only fully testable once there are floors to visit (phase 6).

## 5. Skills

| Skill | Does | Succeeds when |
|---|---|---|
| `explore_frontier(id)` | Walk to a frontier within a budget | The frontier is cleared |
| `go_to_landmark(id)` | Walk to a known landmark | Within reach of it |
| `go_to_node(id)` | Walk to a node of the walked graph (backtracking) | At the node |
| `rotate_in_place(angle)` | Turn to bring the camera round | Turn completed |
| `scan_intersection` | A set scan pattern at a junction | Pattern completed |
| `approach_viewpoint(id)` | Move to a planned viewpoint (from known geometry) | At the viewpoint |
| `inspect_sign(id)` | Approach a sign to reading distance and face it | Read, or unreadable from every reachable spot |
| `inspect_room_label(door)` | Read a door's plate and tie it to that door | Plate read and associated |
| `follow_numbering(direction)` | Walk a corridor reading plates | A plate passes the target, or the corridor ends |
| `verify_destination(door)` | Read the candidate's plate at its door | Match (arrival) or mismatch (candidate refuted) |
| `stop` | Give up and report what is unresolved | — |
| `take_lift(floor)` | *Multi-floor phase* | Out of the lift, floor display read |

Every skill has a time cost, including looking (3 s per look, as in `prototype_config.yaml`), so
information-seeking actions compete fairly with movement.

## 6. Paid models

- **Provider:** OpenAI, through an adapter beside the existing Claude and Gemini clients:
  - the Decisions API for scoring;
  - a generative model for proposing candidates (B2, B3).
- **Key:** `OPENAI_API_KEY`, never committed.
- **Replay:** every request goes through the response cache, `benchmarks/llm-cache/`, so any run
  can be replayed for free.
- **Run gate:** a design that calls a paid model refuses to start unless `NAV_LLM_ALLOW=1` is set,
  and a budget cap (`LLM_BUDGET_USD`) is always in force.
- **Before any paid run:** a dry run counts the calls and tokens without sending them, giving a
  cost estimate. The paid run happens only on an explicit go-ahead.
- **Matched budgets:** model calls per trip are capped and matched across compared designs
  (TI §11.2), so no design wins by spending more.

## 7. The trained scorer (later)

Kept in scope for the paper, not built until the off-the-shelf scorer and the heuristic work.

- **Data:** at decision points the simulator is forked. Each candidate action is tried, then a
  fixed, disclosed continuation policy runs to the end, K times. The mean total cost is the label;
  the inputs are only what the agent had at that moment (TI §7.1).
- **Speed:** forking needs the simulator to save and restore its full state, which it can't do
  today. It also needs many rollouts. Before this phase, the cost of one forked decision point is
  measured. If the geometric simulator is too slow, a faster abstract simulator is built for
  training data only.
- **Model:** text-only first, then graph features, then images (TI §7.3). Calibrated on a
  validation split, with Brier score and calibration error reported.
- **Language:** training in Python (PyTorch), the model exported to ONNX, inference in Kotlin.
  ONNX Runtime is already used in the app for YOLO. *Needs your approval* (decision 13).

## 8. Evaluation

- **Agent:** robot only.
- **Reference:** the refactor's starting commit, benchmarked once and frozen. Today's best known
  is `r1-current` at 45.6%.
- **Primary metric:** verified success on held-out buildings, predeclared before results.
  Secondary: distance ratio against the evaluator's shortest route, total search time.
- **Other metrics:**
  - false arrivals;
  - wrong hypotheses held at the end, time to the correct hypothesis;
  - inspections and turns per trip;
  - subgoal outcomes by skill;
  - model calls, cost and latency;
  - rejected candidates;
  - safety stops.
- **Statistics:** 95% paired bootstrap **over buildings**, not episodes (TI §11.3). This is why the
  procedural generator comes early: five hand-built buildings can't support it.
- **Held-out families:** split by numbering convention and sign layout, never just by seed.
- **Baselines** (TI §11.1), all with the same skills, perception and tactical planner:

  | ID | What | Here |
  |---|---|---|
  | B0 | Nearest frontier | today's `frontier-only` |
  | B1 | Semantic frontier | today's `current` |
  | B2 | A language model picks landmarks and views directly | OpenAI, paid |
  | B3 | Beliefs + language-model subgoals | OpenAI, paid |
  | B4 | Beliefs + one-step information gain | free |
  | B5 | Beliefs + off-the-shelf decision model | OpenAI Decisions API, paid |
  | P | Beliefs + trained cost-to-go scorer | later |
  | Oracle | Knows the route | upper bound, as today |

  The comparisons that matter for the paper are P against B4 and B5.
- **Leakage tests** (TI §10.5): no observation outside the field of view or through walls; no
  ground-truth fields in planner state; travel estimates from the known graph only; forks never
  write back; every run replays from seed, inputs and cached model responses.

## 9. Where this differs from the source documents

| Source says | Here | Why |
|---|---|---|
| Python, Pydantic, SQLite, NetworkX | Kotlin; in-memory ledger with JSONL; Python only for training | Keeps one codebase that can ship to a phone |
| Topological simulator with 2.5D visibility first | The existing geometric simulator, with a perfect-if-visible mode | It already exists and drives the real engine; a faster simulator only if training data needs it |
| LiDAR optional, later | 2D LiDAR from phase 1 | Chosen rig |
| Human walker, body-turn burden | Robot proxy; turns still cost time | Removes compliance noise while the planner is studied |
| `ask_user_or_staff` | Dropped | No one to ask with a robot |

## 10. Phases

Each phase ends with a paired benchmark against the one before. Phases 0–4 and 6 are free; 5 is
built and dry-run only until you approve a paid run.

| Phase | Work | Done when |
|---|---|---|
| 0 | Freeze the reference: benchmark the starting commit with the robot | Results committed |
| 1 | The rig: chest phone and 2D LiDAR in the simulator; two kinds of free space | Rig vs phone alone compared and understood |
| 2 | World model: ledger, landmarks, unreadable-sign events, whole-floor grid, snapshot; `SemanticHintStore` retired; perfect-if-visible mode | Parity or better; the ledger replays to the same landmarks |
| 3 | Planner split: candidates, validator, selector, skills, outcomes; today's logic as B0 and B1 | Parity with phase 2 |
| 4 | Hypotheses and verifier; door-verified arrival; procedural single-floor generator with numbering families; B4 | B4 better than B1 on held-out families; false arrivals down |
| 5 | OpenAI adapters (Decisions API, generative); B2, B3, B5 built and dry-run | Cost estimate ready for a go-ahead |
| 6 | Multi-floor: lifts, floor display, multi-floor generator; the floor-digit hypothesis tested for real | Cross-floor trips succeed |
| 7 | Simulator forking and training-data generation; speed measured first | A labelled dataset on held-out splits |
| 8 | Trained scorer P; calibration; P vs B4 and B5 | Paper results |

## 11. Progress (14 October 2026)

All figures: robot, 3 seeds, paired against the same trips and seeds unless noted. Benchmark files
are in `navigation-sim/benchmarks/`; the frozen starting point is `p0r-*` with its sources in
`p0-reference-src.tgz` and `p0-reference.patch`.

**Phase 0 - reference.** The starting engine, robot, 20 seeds: 45.6% on the 44 main trips
(frontier-only 36.0%, oracle 69.2%), 44.3% on the held-out malls. It reproduces the earlier
`r1-*` numbers exactly. On seeds 1-3, the same engine scores 47.0%.

**Phase 1 - the rig.** Built: `sensor/PlanarLidar.kt`, `NavigationFrame.lidar`, the grid's
plane-clear layer, a chest-mounted robot. What the runs showed and what changed because of it:

| Finding | Change |
|---|---|
| A fixed camera never sees the floor just past the body: the robot never moved | Bridge up to 1.6 m of unseen floor, past the footprint, only to seen floor (`blindZoneSkipsFootprint`) |
| "Look down" cannot be done with a fixed camera; the robot swept away instead | Chest robot holds still for "look down ahead" |
| The LiDAR maps the walls beside the robot, so it often stands inside an inflation halo | Forward gate trusts a clear line to the waypoint; halo escape allowed while clear of the body or moving away (`forwardGateToWaypoint`, `haloEscapeMinClearanceMeters`) |
| A* cut through the halo of a wall end beside a robot that was not in any halo; the controller refused, the planner repeated | Halo escape only from inside a halo (`haloEscapeOnlyFromHalo`) |
| A LiDAR maps a corridor's air in a second and leaves no frontier: the robot never looked at the signs | Frontiers follow what the camera has seen (`frontiersFromCamera`) |
| Frontier distance was scaled by the window size | A fixed 6 m scale (`frontierDistanceScaleMeters`; unchanged for 12 m) |

Result: chest + LiDAR 65.2%, chest phone alone 61.4%, hand-held robot 47.0%. The LiDAR removes the
collisions a phone alone has with glass in the school, not yet in the mall (9% of runs touch
something, nearly all mall glass). A 28 m window to use the LiDAR's full range scored 50%: not yet
understood, so the rig keeps the 12 m window.

**Phase 2 - world model.** Built: `world/WorldModel.kt` (append-only ledger, landmarks by ID with
range-weighted label votes, replay), `SemanticObservation.UnreadSign` and its perception
(`+unread`), `WorldStateSnapshot`, `--ledger`. Parity: identical results run for run with the
model present.

**Phase 3 - planner split.** Built: `strategy/` (skills, subgoals, outcomes, the executor, the
validator, `LongTermPlanner`), viewpoints square to the wall a landmark is on. Parity: `+b1`
identical to no planner, run for run.

**Phase 4 - hypotheses.** Built: `belief/` (direction, side, leading digit, range signs; a
deterministic verifier), B4 (`CandidatePlanner` + `HeuristicScorer`), verification at the door
(by the skill or a close reading on the way), procedural floors with six numbering families, truth
judging of confirmed hypotheses. Latest:

| Set | B1 | B4 |
|---|---|---|
| Main 44 trips | 65.2% | 62.1% |
| Procedural, 108 trips | 81.8% | 80.2% (SPL 0.467 vs 0.458) |

B4 does better on gappy numbering and lettered halves, worse where its hypotheses do not apply (a
single run up and back). Of 558 confirmed hypotheses that could be judged, 36 (6.5%) were wrong.
Lessons that changed it: information is only worth a detour at a decision point; an unread plate is
also a chance at the destination; a camera that cannot tilt cannot verify a shop's name from close.

**Phase 5 - model baselines.** Built and dry-run only: `advice/OpenAiClient.kt` (Decisions and
Responses APIs, gated, cached, budgeted), B5 (`DecisionsScorer`), B3/B2 (`ChooserScorer`). A dry
run of B5 on 8 trips: 99 requests, ~35k input tokens, about $0.0035; the full main and procedural
sets would be well under $0.25. Not run: waiting for a go-ahead.

**Phase 6 - multi-floor.** Built: `world/MultiFloorBuilding.kt` (3-4 floors, a lift; families
floor-digit, offset, digit-is-wing), lift rides in the simulator (time, lost tracking, the display
read on arrival), floors in the world model and the engine (a new floor is a new map and graph; the
ledger and hypotheses carry on), the `TAKE_LIFT` skill (turning to face out on arrival), and the
leading-digit hypothesis tested directly against floor labels with a learned lift offset. The full
loop runs: in an offset building, the agent rides to "4" for room 404, reads 3xx plates, revises
the offset to +1, rides to "5" and arrives - "the leading digit is the floor" confirmed, 20 met.

**Phase 7 - training data.** Built: `bench/ForkData.kt` (`--fork-data`), deterministic forks with
reseeded noise (`SwitchableRandom`, parity checked), `strategy/CandidateFeatures.kt` shared by data
generation and the scorer. `datasets/fork-tune-v1.jsonl`: 1786 labelled options at 639 decisions,
from 3572 forked runs on the two tuning families.

**Phase 8 - the trained scorer, version 1.** `LearnedScorer` (ridge regression of relative
cost-to-go), `--train-scorer`, `+p`. On held-out buildings its regret is worse than both B4's and
always exploring's (40.7 s vs 38.1 s vs 32.1 s): in this data, detours rarely pay. Labels are noisy
(2 continuations each).

**Final comparison** (robot, chest rig, 3 seeds; differences paired, 95% intervals, resampling whole
buildings where there are at least 8):

| Set | B1 | B4 | P | B4 - B1 | P - B1 |
|---|---|---|---|---|---|
| Main, 44 trips | 65.2% | 62.1% | 62.1% | -3.0 (-12.1, +6.1) | -3.0 (-10.6, +4.5) |
| Procedural, 108 trips | 83.3% | 83.0% | 84.3% | -0.3 (-6.2, +6.2) | +0.9 (-4.9, +7.7) |
| Multi-floor, 36 trips | 13.9% | 37.0% | 37.0% | +23.1 (+6.5, +39.8) | +23.1 (+9.3, +36.1) |

- On one floor, neither planner beats the tactics yet. Both help where numbering defeats the
  tactics (digit-is-wing single floors: B1 61%, B4 74%, P 82%) and cost time where it does not
  (floor-digit: B1 98%, B4 91%, P 89%).
- Across floors, hypotheses are what make the trip possible at all: B1 cannot choose to take a lift.
- False arrivals rise with the planners (multi-floor B4 4.6%, P 3.7%), nearly all in digit-is-wing
  buildings, where every floor repeats the same numbers: the planner rides to another floor before
  reading the wing that would have contradicted "the digit is the floor", and finds a room with the
  right number on the wrong floor. The episode is ambiguous by construction; the planner should
  still test the cheap alternative (the wing here) before the expensive one (the lift).

**Known problems, in order of what they cost:**
1. Mall glass: 20% of mall runs touch a shop front. The LiDAR mostly passes through glass.
2. The chest camera cannot look sideways: plates on side walls, a lift niche beside the corridor,
   are passed unseen unless an inspection is chosen. Inspections cost a stop and a turn each.
3. Labels for P are noisy; more continuations, and features for "what lies ahead along the
   corridor", are the next things to try. A non-linear model only after that.
4. A 28 m map window (to use the LiDAR's range) scores 15 points worse, not yet run down.

**Not done:** the model baselines B2, B3, B5 have not been run live (waiting for a go-ahead; a
dry run puts the whole set under $0.25); nothing is committed.
