# Belief-Driven Indoor Navigation
## Full Implementation Plan — Research Prototype to Supervised Wearable Demo

**Version:** 1.0  
**Prepared:** 13 October 2026  
**Scope:** Map-free navigation to arbitrary named/numbered rooms in unfamiliar multistory buildings  
**Target hardware:** Phone, optional vest-mounted camera/depth sensor, two BLE haptic wristbands; Mac mini (32 GB unified memory) for development  
**Planning assumption:** Three contributors at ~10–15 hours/week each for 30 weeks. Estimates are planning targets, not delivery guarantees.

> **Core design rule:** The navigation agent maintains a persistent, evidence-grounded world model. A multimodal language model proposes hypotheses and strategic actions; deterministic software validates them, chooses constrained subgoals, and handles local routing. An independent on-device safety layer can suspend guidance even if all reasoning services are unavailable.

---

# 1. Objectives, boundaries, and measurable success

## 1.1 Research question

Can an agent that **explicitly forms, tests, and revises hypotheses about unknown building structures** reach arbitrary rooms more efficiently and reliably than frontier exploration or direct LLM landmark selection, given the **same observations, action space, and foundation-model budget**?

The distinctive challenge is **goal discovery under uncertainty**, not shortest-path routing on an existing floor plan. The system knows observations it has collected but cannot assume the layout or numbering scheme of the unvisited building.

## 1.2 Required capabilities

1. Accept a destination such as `Room 501`, `Room E-203`, or `Admissions Office` and report ambiguity explicitly.
2. Build its own partial metric/topological model; never require a preloaded building map.
3. Observe and correctly associate doorway labels, signs, directories, elevators, stairs, and room-range hints with locations.
4. Maintain facts separately from inferences, with sources, uncertainty, timestamps, and contradiction history.
5. Form and revise competing hypotheses about floor, wing, numbering patterns, and likely destination regions.
6. Select informative, feasible subgoals: inspect a sign, seek an elevator directory, explore a frontier, follow a numbered room sequence, or ask for assistance.
7. Route to the selected subgoal and provide clear voice/haptic guidance; monitor execution and replan after surprises.
8. Confirm arrival by identifying the destination's label or receiving explicit reliable confirmation, not by proximity alone.
9. Stop or ask for help when localization, perception, communications, or accessibility information is insufficient.
10. Produce reproducible episode logs and quantitative comparisons against baselines.

## 1.3 Non-goals for the research MVP

- Fully autonomous, unsupervised mobility for visually impaired people.
- Reliable detection of all real-world hazards under all lighting/material conditions.
- Universal human-level understanding of every building convention.
- Continuous cloud-LLM inference at video rate or direct LLM control of user movement.
- Updating foundation-model weights online during a walk.
- Multi-building shared cloud mapping, crowd-sourced maps, or outdoor navigation.

## 1.4 Deliverables and completion gates

| Gate | Completion definition | Evidence |
| --- | --- | --- |
| G0 — Contracts | `ObservationEvent`, `StrategicSubgoal`, `ActionOutcome`, `WorldStateSnapshot` validate against versioned schemas | CI tests + sample logs |
| G1 — Simulation | Random multistory buildings generated reproducibly; fog-of-war hides unknown space | Fixed-seed replay and simulator tests |
| G2 — Baselines | Frontier, semantic landmark, and direct-LLM baselines reach valid destinations in solvable episodes | Metrics dashboard and episode traces |
| G3 — Research planner | Explicit hypothesis/evidence graph and action-selection ablations run end-to-end | Held-out evaluation report |
| G4 — Recorded perception | OCR/sign/door associations are spatially grounded using recorded walkthroughs | Annotated recordings and module scores |
| G5 — Live supervised guidance | Phone, local routing, stop states, voice and BLE outputs integrate | Controlled walk logs and safety checklist |
| G6 — Research release | Reproducible configurations, statistical comparisons, failure analysis, and documentation | Repo release, figures, draft paper |

---

# 2. Architecture and runtime boundaries

## 2.1 Six-layer system

1. **Sensors and capture:** RGB/RGB-D, co-mounted IMU, barometer, microphone/user input, wristband output; timestamp synchronization and calibration.
2. **Perception:** Pose tracking, depth, traversability, object/landmark recognition, text/sign parsing, spatial grounding, confidence/failure estimates.
3. **Persistent world model:** Append-only evidence ledger; local metric submaps; topological/floor graph; semantic landmarks; explicit belief graph.
4. **Long-term strategic planning:** Goal interpretation; evidence retrieval; hypothesis and test generation; action grounding, selection, monitoring triggers.
5. **Short-term tactical planning:** Execute bounded subgoal, calculate accessible route, follow waypoints, schedule instructions, report outcomes.
6. **Independent safety and interface:** Local hazard/unknown-state checks, suspend/resume controls, user confirmation, BLE/voice output.

**Data flow:** `Sensors → ObservationEvent → World Model → WorldStateSnapshot → StrategicSubgoal → Tactical Planner → Guidance → ActionOutcome → World Model`.

The safety supervisor subscribes directly to sensor confidence and hazard signals and can veto any guidance independently of a flagship model.

## 2.2 Two planning timescales

| Dimension | Long-term planner | Short-term planner |
| --- | --- | --- |
| Purpose | Decide **what to learn or accomplish next** | Determine **how to reach a grounded subgoal** |
| Inputs | Goal, relevant map, landmark evidence, beliefs, outcomes | Validated subgoal, metric/topological map, pose, accessible route constraints |
| Outputs | Grounded bounded action (`inspect_directory`, `change_floor`, `explore_frontier`, etc.) | Route/waypoints, instruction schedule, status and measurements |
| Typical trigger | New sign/room clue, contradiction, subgoal completion, timeout, user goal change | Position update, approaching waypoint, blockage, tracking degradation |
| Algorithms | Multimodal reasoner + probabilistic/utility action selection | A*/D* family for routing, local geometric constraints, finite-state execution |
| Safety authority | None | None; local safety layer holds the final veto |

**Architectural invariant:** Long-term models cannot command ungrounded coordinates, bypass stop conditions, or declare arrival without evidence.

## 2.3 Reference stack

| Area | Initial choice | Later alternative / rationale |
| --- | --- | --- |
| Language | Python 3.12+ for research; Swift for iPhone capture/application | C++ only if low-latency profiling requires it |
| Schemas | Pydantic v2 and JSON Schema | Protobuf/FlatBuffers if cross-device bandwidth requires it |
| Simulator | Custom Python semantic building simulator | Habitat-Sim / Habitat-Lab when photorealistic perception experiments begin |
| Routing | NetworkX + occupancy-grid A* | C++ graph planner or incremental D* Lite when needed |
| Local storage | SQLite with indexed evidence/landmark/hypothesis tables | Spatial index extensions if necessary |
| Model interface | Typed provider adapters with validated structured responses | Argon, GPT, Claude, smaller local models interchangeable |
| Experiment tracking | Structured JSONL + SQLite + Git commit/config/model IDs | MLflow/W&B optional; avoid requiring external services |
| Tests | pytest, hypothesis/property testing, Ruff, mypy/pyright | Native iOS unit/UI tests and hardware-in-loop fixtures |
| Perception prototype | ARKit/Vision on compatible iPhone; OpenCV; lightweight local detection | ORB-SLAM3 or other visual-inertial SLAM where ARKit doesn't apply |
| Local small-model R&D | MLX / MLX-LM on Mac mini | Cloud GPU only after benchmarks justify training |
| Deployment | On-device safety and tactical planning; optional cloud strategic model | Offline strategic model when feasible and validated |

Use the phone's own VIO with its camera when possible. A **vest camera and a freely moving phone IMU are not a rigid camera/IMU pair**; either co-mount sensors or explicitly estimate their time-varying relative transform.

---

# 3. Repository and engineering foundation

## 3.1 Suggested monorepo

```text
indoor-nav/
├── README.md
├── pyproject.toml
├── configs/{dev,sim,eval,device}/
├── schemas/                  # Versioned JSON Schema + examples
├── indoor_nav/
│   ├── common/               # Coordinates, clocks, errors, event bus
│   ├── perception/           # Adapters, geometry, semantics, fusion
│   ├── world_model/          # Event store, maps, graph, beliefs
│   ├── planning/
│   │   ├── strategic/        # LLM adapters, hypotheses, utility search
│   │   └── tactical/         # Validation, routing, execution, guidance
│   ├── safety/               # Independent safe-stop state machine
│   ├── simulation/           # Floor generators, sensors, episode loop
│   └── evaluation/           # Baselines, metrics, reporting
├── mobile/                   # iOS app, BLE, speech, sensor bridge
├── datasets/{synthetic,recordings,annotations}/
├── experiments/              # Frozen configs and run manifests
├── tests/{unit,integration,property,scenario,hardware}/
└── docs/{architecture,schemas,safety,research,decisions}/
```

## 3.2 Team roles and workflow

- **Research/planning lead:** simulator task definition, baselines, belief engine, decision algorithms, evaluation and paper.
- **Perception/world-model lead:** data association, semantic observations, localization, storage, geometry/perception integration.
- **Mobile/systems lead:** capture, audio/BLE, tactical instructions, supervisor, runtime/replay tooling.
- **Shared:** weekly integration, annotation, device testing and reproducibility.

Use one-week sprints, issue-based ownership, small PRs, and one required integration demo per week. Define interfaces before parallelizing. Every algorithm experiment records its configuration, model name/version, prompt template hash, random seed, environment family, and commit SHA.

## 3.3 Continuous integration

On every PR: formatting/linting, type checks, schema validation, unit tests, small deterministic simulation suite, privacy-secret scan, and a ten-episode smoke benchmark. Nightly: larger benchmark, replay determinism check, performance regressions, and planner safety invariants. Keep external-model tests optional and budget-capped; use recorded model responses for deterministic CI.

---

# 4. Core data contracts and state ownership

## 4.1 Event-sourced model

Store **observations and outcomes as append-only evidence**. Derived map edges, semantic facts and beliefs may be recomputed or revised. Do not let a model silently overwrite observed facts. Event IDs, pose frames, observation time, confidence, landmark IDs, and source pointers are mandatory for relevant observation types.

### ObservationEvent (schema outline)

```json
{
  "schema_version": 1,
  "event_id": "obs-0832",
  "timestamp_monotonic_ns": 1234567890,
  "kind": "room_label",
  "value": "112",
  "frame_id": "building_session/floor_unknown/submap_01",
  "pose": {"x_m": 12.4, "y_m": 3.2, "heading_rad": 1.57},
  "pose_covariance_ref": "cov-005",
  "landmark_id": "door-41",
  "source": {"sensor_id": "phone_rear_camera", "artifact_ref": "crop-182"},
  "recognition_score": 0.92
}
```

Recognition scores should not be treated as calibrated probabilities without calibration tests. Sign text, arrow direction, arrow-to-text association, language, and doorway association are separate fields/observations where possible.

### StrategicSubgoal (schema outline)

```json
{
  "schema_version": 1,
  "subgoal_id": "sg-049",
  "skill": "inspect_sign",
  "target_landmark_id": "directory-3",
  "purpose": "determine_target_floor",
  "supporting_hypothesis_ids": ["H-12"],
  "expected_observation_kinds": ["floor_directory"],
  "max_travel_m": 80,
  "deadline_s": 180,
  "stop_conditions": ["observation_acquired", "blocked", "unsafe", "user_cancelled"],
  "map_revision": 81
}
```

A subgoal is rejected if its target does not exist in the current graph, its map revision is stale without successful revalidation, its path crosses unknown/unapproved terrain, its action requires unavailable abilities, or stop conditions are absent.

### ActionOutcome (schema outline)

```json
{
  "schema_version": 1,
  "subgoal_id": "sg-049",
  "status": "succeeded",
  "termination_reason": "observation_acquired",
  "travel_distance_m": 34.2,
  "elapsed_s": 62,
  "evidence_event_ids": ["obs-0870"],
  "map_revision_after": 83
}
```

Statuses: `succeeded`, `failed`, `interrupted_unsafe`, `cancelled`, `timed_out`, `partial`. Outcomes feed both statistics and the next belief update.

### WorldStateSnapshot (required sections)

`goal`, `pose + uncertainty`, `known floors`, `relevant subgraph + revision`, `nearby landmarks`, `top ranked competing hypotheses + sources`, `frontiers`, `user/accessibility constraints`, `recent outcomes`, `allowed actions`, `missing information`, `active subgoal`.

## 4.2 Coordinate frames and identity rules

Implement stable IDs for physical doorways, signs, intersections and floor connectors. Record transforms from sensor → local map → floor submap → current building session. Distinguish **floor index** (relative vertical layer) from **floor label** (e.g. B1, G, 1, 2). Preserve uncertainty until signage/user confirmation validates the label. If SLAM loop closure shifts map coordinates, revise derived landmark positions without corrupting evidence provenance.

## 4.3 Belief graph

Represent each hypothesis as `{id, proposition, scope, status, evidence_for, evidence_against, derived_from, revision, update_method}`. Initial status can be `proposed`, `supported`, `contradicted`, `confirmed`, or `retracted`.

For discrete hypotheses such as candidate floors or wings, maintain a normalized belief distribution where feasible. For open-ended LLM-generated propositions, **do not invent percentages**: begin with evidence-based categorical status, and only attach numerical probabilities when there is a specified estimation/calibration method. Track hypothesis dependencies so retracting `numbering encodes floor` can invalidate inferred target-floor claims without deleting source observations.

---

# 5. Simulation-first research environment

## 5.1 Why a custom simulator first

Your primary scientific question is strategic room discovery, so start with perfect synthetic observations and an unknown layout. A full 3D simulator would add unnecessary rendering, embodiment, and sensor errors before the planning research is measurable. Replace perfect semantic events with noisy perception only after the baselines are established.

## 5.2 Procedural generator

Each generated building should contain:

- 1–8 floors, each with corridors, intersections, wings, rooms, dead ends and variable geometric dimensions.
- Stairs/elevators with service constraints; some wings reachable only through specific connectors.
- Numbering conventions: first-digit-is-floor, wing-prefixed labels, irregular suites, missing numbers, multiple room ranges, basement/ground labels, and exceptions.
- Signs and directories with placement, arrow orientation, room ranges, visibility limits, and possibly stale/misleading information.
- Door labels, room name synonyms, blocked/closed paths, accessible/non-accessible transitions, and optional reception/person landmarks.
- Target distributions including easy near-start rooms, complex multistory rooms, and deliberately ambiguous destinations.

Represent the **ground-truth full graph privately inside the simulator**. The agent sees only discovered nodes, edges and observations. Separate `known accessible`, `known blocked`, and `unknown` states. Do not let shortest-path calls leak ground-truth connectivity into the agent.

## 5.3 Simulator API and skills

Minimum actions: `move_to_known_node`, `inspect_door`, `inspect_sign`, `inspect_directory`, `explore_frontier`, `change_floor`, `ask_person` (optional), `request_help`, `stop`, `verify_destination`.

Each action has preconditions, simulated travel cost, observation distribution, termination criteria, and failure outcomes. Active observation actions reveal only content visible from their simulated pose; they must not reveal the target globally.

## 5.4 Episode definition and observability

An episode consists of `{building_seed, generator_family, start_pose, user_goal, user_mobility_profile, observation_mode, max_distance, max_steps, allowed_skills}`. Full history and current belief snapshots are saved for replay. Provide a human-readable visualization showing exploration path, current map, observed landmarks, hypotheses, and why each strategic action was chosen.

## 5.5 Holdout rules

Partition by **building-generator family**, not just random episode seed. For example, train/tune on conventional floor-digit numbering and test on wing-prefixed or mixed numbering. Hold out sign layouts, vertical connectivity types, and room-numbering exceptions. Track data leakage: no exact test map, seed, or hidden floor mapping may enter prompts, memory, or training data.

---

# 6. Navigation baselines

Implement in increasing complexity, but keep the same skill catalog and short-term path-cost model for all methods.

| ID | Baseline | Decision process |
| --- | --- | --- |
| B0 | Nearest reachable frontier | Greedy frontier exploration; inspect destination labels opportunistically |
| B1 | Semantic frontier | Heuristics from room numbers, ranges, signs and floor clues |
| B2 | ReasonNav-inspired landmark planner | One multimodal model selects the next inspectable landmark/frontier from map + structured memory |
| B3 | Direct LLM full history | Model chooses subgoal from complete observation/action transcript |
| B4 | Direct LLM structured state | Same model chooses from retrieved compact snapshot |
| P1 | Proposed belief-driven planner | Model proposes hypotheses and candidate tests; grounded information-value selector chooses action |

**Fairness:** Hold model identity, prompt budget, perception, available skills, failure handling, simulator physics, and evaluation episodes constant when comparing planners. If some baselines lack a concept (such as belief state), allow equivalent evidence input, but do not secretly provide target oracle information.

Every baseline must return the *same* `StrategicSubgoal` schema and use the *same* tactical executor and safety constraints. This separates strategic planning improvements from movement/control differences.

---

# 7. Long-term planning implementation

## 7.1 Control loop

1. Consume relevant new `ObservationEvent` and last `ActionOutcome`.
2. Update the evidence/fact graph; detect contradictions, floor changes and changed route feasibility.
3. Retrieve a bounded `WorldStateSnapshot`: nearby graph, active goal hypotheses, recent failures, informative landmark candidates, map image only if helpful.
4. Have the reasoning model propose **claims, evidence needed, candidate tests/subgoals, and expected observations**; do not ask for unrestricted raw control instructions.
5. Validate every proposed claim reference and action ID. Reject invented landmarks, unobserved routes, and inappropriate capabilities.
6. Estimate action outcome/value, with calibrated or empirically learned priors where available. Apply hard accessibility/safety eligibility checks before ranking.
7. Commit one bounded strategic subgoal, with a clear interruption/timeout policy.
8. Execute tactically; collect outcome and new evidence. Replan on meaningful events, not at arbitrary frame frequency.

## 7.2 Belief revision

For example, `H1: room 501 lies on floor 5` depends on `H2: leading digit encodes floor`. Observing a directory assigning 500–599 to floor 6 provides a direct contradiction. The system should preserve both observations, revise H2's scope or reject it, and revise H1 accordingly. A retracted hypothesis cannot remain a silent basis for future decisions.

Implement a deterministic first pass: dependency propagation, source priority (verified directory/door label vs a weak pattern), conflict detection and human review flags. A probabilistic revision engine can follow once likelihood models are available.

## 7.3 Active information acquisition

For action `a` and belief `b`, formal expected information gain is:

`IG(a) = H(b) − sum_o P(o | a, b) H(b | o, a)`.

Optimize a constrained objective such as:

`U(a) = w_p * expected_goal_progress(a) + w_i * IG(a) − w_d * travel_distance(a) − w_t * time(a) − w_u * user_burden(a)`.

Safety is a **constraint**, not merely a low-weight penalty. For the first implementation, use deterministic heuristic scores and visibly mark them as approximations. Then estimate observation likelihoods from simulator runs and compare one-step information gain to short-horizon lookahead or Monte Carlo tree search.

## 7.4 Model adapters

Expose a common `propose_decision(snapshot, allowed_actions, deadline, model_config)` interface. Adapters for Gemini/Claude/GPT/local models should handle input formatting, map crops, typed JSON/function calling, retries, timeouts, budget caps and provider errors. Never make your belief graph or skill registry depend on one provider's message format.

Cache or record every response for deterministic replay. Log tokens, latency, error count, model version, reasoning settings, total API cost, and whether the action was accepted by validation. Use compact prompts first; measure whether adding complete history or image maps improves outcomes.

## 7.5 Fallback and guardrails

If candidate actions are invalid, uncertain, or unsafe: retry with validation feedback at most once, then select a safe deterministic frontier action only if the corresponding local route is validated; otherwise pause and request assistance. Avoid infinite reflection loops by limiting repeated hypothesis generation, same-action retries, and no-progress cycles.

---

# 8. Tactical planning, guidance, and safety

## 8.1 Tactical executor

Implement a state machine:

`IDLE → VALIDATING → ROUTING → GUIDING → INSPECTING/ARRIVED → REPORTING`; exceptional transitions: `BLOCKED`, `LOST_LOCALIZATION`, `USER_PAUSED`, `UNSAFE`, `TIMEOUT`.

The tactical layer receives a *grounded* destination landmark/region and produces route segments with visible start/end checks, branch points, expected landmarks, and stop instructions. Route cost can combine distance, unknown space penalty (normally ineligible), turns, congestion estimates, stairs, accessibility restrictions, and user cognitive burden.

## 8.2 Human-aware local guidance

- Convey low-frequency strategic information via speech and near-turn confirmations via haptics.
- Distinguish advisory guidance from guaranteed safe clearance; never claim the path is safe merely because the forward camera sees open space.
- Require explicit user confirmation for elevator entry/selection, stairs, or situations where automation cannot determine safety reliably.
- Provide `pause`, `repeat`, `cancel`, `help`, and `resume` commands with immediate local execution.
- Avoid rapid contradictory haptic commands and chatter; implement minimum instruction interval and confirmation at decision points.

## 8.3 Independent safety supervisor

Treat the system as a **research assistive prototype**, not a certified mobility aid. It must fail conservative: uncertainty is not equivalent to safe space.

Safety signals include loss of pose quality, invalid depth, unknown/drop-off region, moving obstacles, blocked corridor, ambiguous glass/reflective surfaces, camera misalignment, communication timeouts, stale tactical commands, and explicit user pause/cancel. A supervisor may suppress guidance or issue a stop/request-help state; it does not depend on a cloud round trip.

Implement executable safety properties:

1. No motion guidance is issued on stale localization beyond the configured freshness threshold.
2. `UNSAFE` or `USER_PAUSED` always overrides a strategic subgoal.
3. Cloud timeout cannot suppress a local stop instruction.
4. Unverified destination label never becomes `arrival_confirmed`.
5. No route traverses an unknown or explicitly blocked edge unless the action is a controlled exploration segment with locally validated visibility and supervision.
6. After a localization reset, old route coordinates cannot be reused until the map transform is re-established.

These are engineering safeguards, not a substitute for mobility expertise, accessibility testing, or formal safety validation.

---

# 9. Perception integration plan

## 9.1 Recorded walkthrough stage

Collect consented, non-sensitive recordings of corridors, junctions, doors, elevator lobbies and directories. Record timestamps, camera pose if available, floor transitions, and user remarks. Create a minimal annotation schema for text bbox/content, signs+arrows, door instance/number association, elevator/stairs, traversable passage, and map-grounded coordinates. Prefer a manageable set of high-quality annotations over a large unverified collection.

## 9.2 Modules and ownership

| Module | First implementation | Output/acceptance check |
| --- | --- | --- |
| Time sync + transforms | Single-phone sensors first, calibrated frames | Alignment and timestamp audit; reject unsynchronized records |
| VIO / pose | ARKit world tracking or alternative SLAM | Pose stream with quality/covariance and reset detection |
| Depth / geometry | ARKit scene depth on supported LiDAR devices, geometric fallback otherwise | Valid/unknown mask, near-field obstacle representation |
| Semantic detector | Doors, signs, stairs, elevators, intersection cues | Grounded landmark proposals with IDs |
| OCR | Text regions + recognizer, crop on demand | Exact text plus confidence and source crop |
| Sign parsing | Arrow/text pairing, floor/room-range extraction | Structured sign facts, direction in sign and map frames |
| Object association | Match sign/label with correct door and map landmark | Association correctness test, not just OCR word accuracy |
| Floor tracker | Relative elevation + observed labels + connector event | Floor hypotheses; avoid false absolute-floor certainty |
| Observation fusion | Event builder with source provenance | Meets schema invariants and location confidence gate |

Use a small VLM only for ambiguous or information-rich observations; OCR and deterministic geometry should handle easy cases. Object detection by itself doesn't tell you that a particular sign's arrow refers to a particular hallway: parse the relation and anchor it spatially.

## 9.3 Why localization and semantic reliability matter

A correctly read room label associated with the wrong door can be worse than an unread label. Likewise, an incorrect floor change can contaminate all future routing. Evaluate **end-to-end grounded fact precision** (label + landmark + pose + floor), not just detector mAP or OCR character error rate.

## 9.4 Transition to live capture

First run recorded events through the same event API as simulation. Next run perception live while still using a replayable offline long-term planner. Finally enable live strategic model calls only after pose, confidence, stop rules and active inspection workflows pass integration checks.

---

# 10. Mobile, vest, and wristband engineering

## 10.1 MVP device topology

Start with one supported smartphone as the **sensor reference frame, compute node, display and audio output**. Add external vest camera only if a stable forward-looking perspective is necessary; it creates synchronization, mounting, and calibration complexity. Use BLE wristbands as outputs, not as primary safety sensors.

## 10.2 Mobile app modules

- Camera/depth/IMU session management, background/power limitations, synchronized capture.
- Local map cache, current pose/quality and tactical route state.
- Semantic event transport with retries, queue limits and privacy controls.
- Speech output, speech/user input (optional), controls, BLE pairing and reconnection.
- Accessibility settings for instruction verbosity, preferred stairs/elevators, haptic pattern, walking speed and supported language.
- Debug/record/replay mode separate from real-time guidance mode.

## 10.3 Haptic protocol

Define `LEFT`, `RIGHT`, `STOP`, `REPEAT`, and `CONFIRM` patterns with distinct cadence, maximum duration and acknowledgments. Test pattern recognition while stationary before walking. If BLE disconnects, instructions should downgrade to voice or enter an explicit degraded state; no invented haptic delivery success.

## 10.4 Latency and power budgets

**Illustrative engineering targets, not measured device guarantees:** tracking/depth at device-supported frame rate; local obstacle/unknown checks targeted near 10–30 Hz; tactical progress updates around 1–10 Hz; long-term reasoning on events/after subgoals. Measure p50/p95 latencies, thermal throttling, battery use, time to first instruction, speech/haptic actuation delay, and network loss behavior on the exact hardware chosen.

---

# 11. Research evaluation protocol

## 11.1 Study design

1. Freeze the environment-generator versions and held-out families before comparing planners.
2. Run paired episodes with identical buildings, starts, destinations, user constraints and observation process for every baseline.
3. Vary task difficulty by floors, vertical connectors, signage reliability, room-numbering novelty and ambiguity.
4. Evaluate perfect-observation mode first; then controlled noise (missed OCR, wrong arrow, incorrect room association, location drift); then recorded and supervised physical walkthroughs.
5. Report both aggregate and subgroup performance, confidence intervals across paired episodes/buildings, seed lists and failure categories.

## 11.2 Primary metrics

- **Task success rate:** Verified destination within action/time/distance budget.
- **Travel inefficiency:** Actual travelled distance / shortest physically feasible ground-truth distance (evaluated only for solvable tasks; disclose privileged evaluator oracle).
- **Search waste:** Wrong-floor visits, revisited edges, irrelevant rooms inspected, excess transitions.
- **Information efficiency:** Useful evidence gained per metre or action; time to first correct floor/wing hypothesis.
- **Hypothesis quality:** Contradiction recovery time; false-claim persistence; calibration (Brier/ECE only for valid probability outputs).
- **Operational:** Model latency, context tokens, cost per successful episode, rejected actions, timeouts and intervention counts.
- **Safety-related:** Number/reason of safe-stop triggers and constraint violations in simulation; no unvalidated real-world safety claim.

## 11.3 Minimum experiment matrix

| Factor | Levels |
| --- | --- |
| Planner | B0/B1/B2/B3/B4/P1 |
| Observations | Perfect semantic; noisy semantic; recorded perception |
| Model input | Compact structured state; full history; state + relevant map image |
| Reasoner | At least two model providers; one small model if budget permits |
| Numbering layout | Familiar; wing-prefixed; inconsistent; adversarial exceptions |
| Signage | Accurate; missing; partially obscured; conflicting |
| Planning | Direct selection; hypothesis only; hypothesis + information value |

## 11.4 Key ablations

- Remove explicit belief graph but retain the same facts.
- Remove hypothesis dependencies/contradiction propagation.
- Replace information-value scoring with nearest-candidate or LLM rank.
- Remove image map while keeping graph/text facts.
- Remove short-term action-outcome feedback from strategic prompts.
- Replace full history with relevant subgraph retrieval at matched token budget.

A positive result requires **improvements across held-out families**, not simply on the particular procedural rule set used to design the heuristic.

---

# 12. Timeline and dependencies (30-week baseline)

| Weeks | Track | Tasks | Exit artifact |
| --- | --- | --- | --- |
| 1–2 | Foundations | Requirements, user scenarios, schemas, state-machine design, repo CI, architectural decision records | G0 contracts and test harness |
| 3–5 | Simulator | Procedural buildings, fog of war, room/sign generators, observation/action API, replay visualizer | G1 solvable deterministic episodes |
| 6–7 | Routing baselines | Topological A*, frontier selection, skill executors, task verifier | B0 metrics and replay |
| 8–10 | Agent baselines | Semantic heuristic, one model adapter, landmark planner, full-history vs snapshot policies | G2 B0–B4 comparisons |
| 11–13 | Belief system | Evidence ledger, revision dependencies, contradictory signs, goal/floor hypotheses | Unit/integration invariants |
| 14–16 | Research planner | Candidate tests, active information utility, selection, ablations, initial held-out report | G3 reproducible research MVP |
| 17–19 | Recorded perception | Walkthrough protocol, pose and OCR, sign/room association, annotations | Validated observation exports |
| 20–22 | World-model bridge | Submap/topology updates, floor-change events, recorded-replay navigation | G4 real-recording replay |
| 23–25 | Device guidance | Phone client, haptic BLE, tactical guidance FSM, safe-stop behaviors | Integrated supervised bench demo |
| 26–27 | Controlled walking | Consent/supervision, validation checklists, failure review, iterative fixes | G5 supervised pilot logs |
| 28–30 | Research output | Broad held-out suite, statistics, sensitivity/cost analyses, reproducibility package, paper/poster | G6 complete report |

**Critical path:** schemas → simulator → comparable baselines → belief planner → held-out evaluation. Recorded perception, BLE prototypes and dataset curation can start in parallel after G0, but must not block the research MVP. Add 8–16+ weeks or more for additional sensor hardware, field robustness, and extensive human-subject testing.

## 12.1 Parallel task ownership

| Workstream | Primary owner | Safe parallel start |
| --- | --- | --- |
| Simulator and strategic planner | Research lead | Week 1 |
| World model, schemas and semantic perception | Perception lead | Week 1 |
| Mobile, BLE and safety prototype | Systems lead | After G0, primarily Weeks 6+ |
| Evaluation and reproducibility | Shared, research lead accountable | Week 1 |
| Ethical/safety review and field-test preparation | Shared, systems lead accountable | Prior to collecting human data |

---

# 13. Risk register and mitigation

| Risk | Impact | Mitigation / test |
| --- | --- | --- |
| Synthetic rules are too simple and reward memorization | Research validity | Held-out generator families; sign/rule contradictions; difficulty audit |
| LLM invents landmarks or acts on unsupported claims | Wrong routes | Schema-constrained outputs, ground IDs, explicit validation and rejection |
| Belief confidence is uncalibrated | Poor decisions | Separate qualitative beliefs from probability models; calibration dataset |
| Repeated reasoning loops waste time/tokens | Poor usability/cost | Bounded subgoals, action repetition limits, event-triggered planning, budgets |
| Image/text interpretation associates the wrong door | Wrong destination | Grounded crop/pose association tests; multi-view verification |
| Vertical localization errors | Multistory failure | Relative-floor IDs, explicit connector events, independent signage/user verification |
| Camera/IMU mismatch | Localization collapse | Co-locate sensors for MVP; document extrinsics and synchronization |
| Glass, stairs, drops or dynamic obstacles go undetected | Physical safety | Conservative unknown-state handling; independent stop controller; supervised testing only |
| Cloud outage/high latency | Plan interruption | Local tactical fallback; timeout; clearly tell user to pause/request help |
| Sensitive video or bystander capture | Privacy | Consent, minimization, on-device filtering, short retention, access control |
| BLE disconnect or battery exhaustion | Lost cues | Acknowledgments, voice fallback, disconnect alarms, degraded-state testing |
| Scope spreads to full robotics stack | Timeline failure | Freeze research G3 before expensive integration; defer advanced sensors |

---

# 14. Safety, privacy, and human-testing process

Real-world assistance for visually impaired people is safety-relevant. The first physical pilots should use sighted, trained testers or supervised participants with appropriate mobility expertise and informed consent. Testing with visually impaired participants requires a carefully designed protocol, applicable institutional ethics review, and qualified supervision. This prototype is not a replacement for a white cane, guide dog, or trained mobility assistance.

Write a hazard analysis before device tests; create explicit abort criteria and incident reporting. For personal or bystander video: obtain appropriate permission, avoid sensitive areas, minimize raw storage, protect local data, separate research recordings from inference, and define retention and deletion procedures. Treat cloud inference as optional and disclose when imagery or text leaves the device. Implement encrypted storage and configurable opt-out from research logging.

---

# 15. Compute, staffing, and budget planning

The 32 GB Mac mini is well suited for procedural simulation, Python graph processing, reproducible evaluations, lightweight VLM/LLM inference, offline replay, and small-model LoRA experiments. It is **not** a substitute for a LiDAR-capable phone, a validated wearable safety system, or large-scale foundation-model training.

Prioritize research spending in this order:

1. **Almost-free work:** schemas, simulator, heuristics, local storage, baseline routing and reproducibility.
2. **Metered cloud calls:** limit to landmark-planning benchmarks after decision interface is stable. Estimate cost using recorded input/output tokens per episode and actual provider rates; set hard experiment caps.
3. **Sensing hardware:** buy only after settling device placement, sensor frame, and the perception failure modes that actually block field work.
4. **Model fine-tuning/cloud GPUs:** defer until measurements show prompting, tool use, and memory organization aren't enough.

Maintain a budget sheet with categories `provider inference`, `device`, `camera/sensor`, `BLE prototype`, `storage`, `annotation`, `testing`, and `contingency`. Actual prices depend on region, vendor and test volume; do not rely on generic estimates for purchase approval.

---

# 16. First 10 working days: actionable sprint backlog

| Day(s) | Work item | Definition of done |
| --- | --- | --- |
| 1 | Finalize use cases (room 501, wing room E-203, unusual numbering), scope, and failure rules | `docs/requirements.md` with 10 acceptance scenarios |
| 2 | Initialize monorepo/CI; add Pydantic contracts and sample events | Contract tests pass; schema versions fixed |
| 3 | Implement generator primitives: floors, corridors, rooms, elevators, stairs | Valid connected ground-truth building graph |
| 4 | Add unknown/seen graph states, action API and discovery visibility | No oracle leakage in agent snapshot |
| 5 | Build metric/semantic renderer and episode recorder | Deterministic replay of fixed seed |
| 6 | Implement A* route cost and `move_to_known_node` | Grounded, reproducible path lengths |
| 7 | Implement frontier exploration B0 | Solves easy one-floor destination task |
| 8 | Add room labels, signs/directories, `inspect_*` skills | Semantic clues drive observations |
| 9 | Add SQLite evidence store and relevant-state retrieval | State reconstruction matches event log |
| 10 | Run 50 smoke episodes, classify failures, write Week 3 plan | Baseline metrics and prioritized issues |

## Starter acceptance scenarios

1. Single floor, sequential rooms: find target without observing entire building.
2. Five floors, conventional room numbering: infer likely floor and inspect an elevator directory.
3. Building where 500-range is on floor 6: contradict and revise the floor-5 hypothesis.
4. Two wings with overlapping room suffixes: preserve multiple target candidates.
5. Unreadable sign: explore/ask instead of inventing directions.
6. Elevator does not serve intended floor: select alternative connector or stop.
7. Found door label but ambiguous doorway association: require verification.
8. Route blocked during execution: terminate bounded subgoal and replan.
9. Localization confidence lost during guidance: issue immediate local stop.
10. LLM returns an invalid landmark ID: reject without executing.

---

# 17. Research publication strategy

Treat the hypothesis-driven planner as the proposed contribution, not the mere use of a stronger VLM. Write the introduction around **arbitrary-room search in unfamiliar buildings** and the methodological gap between semantic landmark selection and explicit belief-space experimentation.

Required figures: (1) system architecture, (2) hypothesis-evidence-action loop, (3) example navigation episode with contradictory evidence, (4) success versus distance/cost frontier, (5) ablation table, (6) performance by unseen numbering convention. Include actual failures and model-cost trade-offs.

For research integrity, publish seeds, environment-generator specification, action schemas, evaluation code, prompt templates, model versions, and aggregated statistics. If a baseline is only *inspired by* ReasonNav rather than an exact reproduction, label it accordingly.

---

# 18. Final delivery checklist

- [ ] Every runtime module has one owner and a versioned interface.
- [ ] Perfect simulated observation cannot reveal hidden rooms/floors or oracle path costs.
- [ ] Observations, inferred facts, and hypotheses are separate and auditable.
- [ ] Strategic actions reference grounded landmarks and bounded stop conditions.
- [ ] All baselines share identical tactical navigation and observation capabilities.
- [ ] Experiment runs can be replayed from seed, config, event log, and model outputs.
- [ ] Hypothesis contradictions lead to revision rather than silent overwrite.
- [ ] The safety supervisor responds locally and takes precedence over all planners.
- [ ] Real-world testing has an approved supervision, consent and stop protocol.
- [ ] The final report includes held-out performance, ablations, latency and cost.

---

# 19. Sources and design lineage

**Project-specific source materials:** The previously developed `indoor_nav_architecture_spec.md` and Mermaid diagrams, plus the project's *autonomous planning* notes. These establish the geometric/semantic perception split, continuous hypothesis → subgoal → execution → feedback loop, explicit long-term and short-term planning, and the idea of combining local object/depth perception with spatial memory.

**External implementation references (verify versions before installation):**

- ReasonNav (CoRL 2025) project/paper: https://proceedings.mlr.press/v305/chandaka25a.html
- ARKit tracking overview: https://developer.apple.com/documentation/arkit/
- ARKit scene depth: https://developer.apple.com/documentation/arkit/arframe/scenedepth
- Habitat-Lab and Habitat-Sim: https://github.com/facebookresearch/habitat-lab
- ROS 2 Nav2 behavior-tree subsystem: https://github.com/ros-navigation/navigation2
- MLX-LM: https://github.com/ml-explore/mlx-lm

**Planning assumption disclosure:** The 30-week schedule, role split, technology selections, score functions, acceptance cases, and engineering targets in this document are *proposed design choices*, not claims about an existing deployed system or measured performance.
