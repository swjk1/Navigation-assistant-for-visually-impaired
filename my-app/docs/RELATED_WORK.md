# Related work

What other groups have built for finding a place in an unfamiliar building, and how this project
differs. Collected 2026-10-04 from the papers' own pages; every number below is the paper's own,
measured in its own setting, so none of them is directly comparable with ours. Three of the closest
works are 2026 preprints: check for newer ones before citing this list.

Our own results are in [`../../TECHNICAL.md`](../../TECHNICAL.md) §9.

## At a glance

| Work | Reads signs from a camera | Remembers the trip | Room-number reasoning | Model only biases the choice | Measures how good the model must be | Handles model delay |
|---|---|---|---|---|---|---|
| ReasonNav | Yes | Yes, landmark memory | Left to the model | No: picks the next landmark | No | Not addressed |
| Follow the Signs | No: signs typed into the map | Yes, the seen grid | Yes, by the model | No: its region sets the subgoal | No | Not addressed |
| SignNav | Yes | Partly, learned | Not explicit | No: one network acts | No | Not addressed |
| VLFM | Objects, not signs | Yes, value map | No | Yes | No | Not addressed |
| Slow Brain, Fast Planner | Not applicable (sidewalks) | No | No | Yes | Delay only | Yes |
| This project | Yes | Partly: 12 m map window plus landmarks | Yes, plain code (`RoomNumberModel`) | Yes | Yes (noisy-oracle sweep) | 2 s modelled; planner guesses fade |

## Navigating by signs and room numbers

### Follow the Signs: Using Textual Cues and LLMs to Guide Efficient Robot Navigation
Cao, Kumar, Curtis. arXiv preprint, January 2026. <https://arxiv.org/abs/2601.06652>

- **Task:** reach a goal named by text ("Room 621") in an unfamiliar building.
- **Method:**
  - A language model is asked at every step until the goal is in view. It gets the seen occupancy
    grid, the seen labels (room numbers, sign text) and the agent's position.
  - It returns its reasoning, the patterns it inferred (such as room numbers rising to the right),
    and one of four regions relative to the agent along the map's axes.
  - Each answer adds 1 to every cell of a confidence grid on that side. The grid decays each step,
    and explored cells are reset to 0.
  - The subgoal is the centroid of the highest-confidence region, reached with A*. When that is
    where the agent already stands, it falls back to frontier exploration, so it keeps frontier
    exploration's eventual completeness.
  - The model isn't named.
- **Perception:** none. Room numbers and signs are annotated on the grid, and the agent sees
  everything in a square window around it.
- **Evaluated on:** 7 grid worlds (3 small synthetic, 3 large real floor plans, 1 noisy 3D scan),
  plus a Spot demo on a hand-built, pre-annotated map.
- **Results:**

  | Method | SPL | Success |
  |---|---|---|
  | Theirs | 0.745 | 97% |
  | Frontier exploration | 0.596 | 100% |
  | NavGPT | 0.236 | 80% |
  | LLM alone | 0.160 | 60% |

  On the noisy scan: 80% success against frontier exploration's 100%.
- **Their stated limits:** signs come from annotations, not sensors; ambiguous numbering (600A,
  600B, 617A) misleads it; with few labels its guesses spread evenly over all four directions;
  reconstruction noise can trap the agent. Their future work is reading signs from onboard cameras
  and mapping online.
- **For us:** the closest work. Its mechanism is rebuilt in the simulator as
  `FollowTheSignsAdviser` (`fts-aNN`, `fts-soft-aNN`). At equal accuracy it matched our two tiers.

### Human-like Navigation in a World Built for Humans (ReasonNav)
Chandaka, Wang, Chen, Che, Zhai, Wang. CoRL 2025. <https://arxiv.org/abs/2509.21189>

- **Task:** find a room by number in an unseen building, within 15 minutes.
- **Method:**
  - NanoOWL (an optimised OWL-ViT) detects doors, people and directional signs in a 15 fps RGB-D
    stream.
  - GPT-4.1 reads door labels when a door is approached, and reads signs, grouping their text by
    cardinal direction.
  - A landmark memory holds doors with their labels, people with what they said, signs, and SLAM
    frontiers, each marked visited or unvisited.
  - GPT-4.1 picks the next landmark index from a JSON landmark list plus a top-down map image with
    the landmarks drawn on.
  - It can ask a person for directions by speech; their answer becomes a note in memory.
  - SLAM Toolbox builds the map from LiDAR; NavFn and MPPI drive.
- **Evaluated on:** 12 real trials in two university buildings and 14 in a simulated hospital.
- **Results:** 58.3% success real, 57.1% simulated. Without signs or people, 8.3% real. Without
  the map image, 16.6% real.
- **Their stated limits:** the object detector is the bottleneck; the landmark categories are fixed;
  the model can only choose frontiers, not intermediate waypoints; it needs an internet connection.
- **For us:** the closest system with real perception. It supports giving the planner layout
  information, since success dropped from 58% to 17% without the map image.

### SignNav: Leveraging Signage for Semantic Visual Navigation in Large-Scale Indoor Environments
arXiv preprint, March 2026. <https://arxiv.org/abs/2603.16166>

- **Task:** follow the hints on signs to a destination in hospitals and airport terminals.
- **Method:** an end-to-end Spatial-Temporal Aware Transformer (START). Its spatial module grounds a
  sign's hint in the scene, and its temporal module keeps history. Trained in two stages with DAgger
  on their LSI-Dataset.
- **Results:** 80% success and 0.74 NDTW on unseen validation scenes, plus a real deployment without
  a prebuilt map.
- **For us:** the learned, end-to-end alternative. Ours is modular and needs no training.

### SignScene: Visual Sign Grounding for Mapless Navigation
Zimmerman, Loo, Koh, Wang, Hsu. arXiv preprint, February 2026 (revised May 2026).
<https://arxiv.org/abs/2602.12686>

- **Task:** ground what a sign says ("toilets left, past the lifts") in the 3D scene, for mapless
  navigation.
- **Method:** a sign-centred spatial-semantic representation that a vision-language model reasons
  over.
- **Results:** 88% grounding accuracy on 114 queries across nine environment types; real-world
  mapless navigation on a Spot robot.
- **For us:** a better sign parser than our regex rules. It could serve as the vision parser for
  hard sign crops.

### Sign Language: Towards Sign Understanding for Robot Autonomy
Agrawal, Loo, Zimmerman, Hsu. arXiv preprint, June 2025. <https://arxiv.org/abs/2506.02556>

- **Task:** navigational sign understanding, parsing places and their directions from signs.
- **Method:** a benchmark of signs from hospitals, malls and transport hubs, with vision-language
  model baselines. Code and data are public.
- **For us:** a ready test set for the sign parser, independent of our simulator.

### Signage-Aware Exploration in Open World using Venue Maps
Chen, Lu, Yang, Zhang, Chen, Jia, Pan. IEEE RA-L 2025. <https://arxiv.org/abs/2410.10143>

- **Task:** find a shop in a large mall.
- **Method:** diffusion-based text instance retrieval for reading signs, 2D-to-3D semantic fusion,
  and a planner that balances exploring with checking signs, guided by the mall's **venue map**.
- **Results:** better sign recognition than state-of-the-art text spotters, and faster search than
  traditional exploration, in real shopping malls.
- **For us:** the only mall-scale real system, but it needs a venue map. We don't.

### Open-Architecture End-to-End System for Real-World Autonomous Robot Navigation
Devarakonda, Kaypak, Goswami, Patel, Khorrambakht, Krishnamurthy, Khorrami. arXiv preprint,
October 2024 (revised April 2026). <https://arxiv.org/abs/2410.06239>

- **Task:** follow natural-language navigation instructions in unknown indoor environments.
- **Method:** onboard localisation and mapping, a continuously updated open-vocabulary semantic
  object map, a hierarchical scene graph, and an LLM planner that builds and revises multi-step
  plans as the graph grows. Runs on ROS2.
- **Results:** over 88% task success, zero-shot, on a Unitree Go2 quadruped in several indoor
  environments.
- **For us:** evidence that an LLM planning over a growing scene graph works on a real robot. This
  is the role of our long-term planner.

### Floorplan2Guide: LLM-Guided Floorplan Parsing for BLV Indoor Navigation
Ayanzadeh, Oates. IEEE BigData 2025. <https://arxiv.org/abs/2512.12177>

- **Task:** step-by-step routes for blind and low-vision users from an architectural floor plan.
- **Method:** a foundation model turns the floor plan into a knowledge graph; an LLM gives
  instructions from it, with few-shot prompting.
- **Results:** Claude 3.7 Sonnet was best: 92.3% on short routes, 76.9% on medium, 61.5% on long.
  A graph representation beat direct visual reasoning by 15.4 points.
- **For us:** the reason our planner gets the trip as a text graph rather than images. It needs a
  floor plan, which we don't have.

## Language models choosing where to explore

### Navigation with Large Language Models: Semantic Guesswork as a Heuristic for Planning (LFG)
Shah, Equi, Osinski, Xia, Ichter, Levine. CoRL 2023. <https://arxiv.org/abs/2310.10103>

- **Method:** a language model's knowledge scores frontiers as a search heuristic, not as a plan,
  because its narratives aren't grounded in the real scene.
- **Results:** beats uninformed exploration and other ways of using a language model, in simulation
  and in real environments.
- **For us:** the same principle as our advice channel. We add a measurement of how accurate the
  model must be.

### ESC: Exploration with Soft Commonsense Constraints for Zero-shot Object Navigation
Zhou, Zheng, Pryor, Shen, Jin, Getoor, Wang. ICML 2023. <https://arxiv.org/abs/2301.13166>

- **Method:** a vision-language model grounds objects and rooms; a commonsense language model
  reasons about where things usually are; that knowledge becomes soft logic constraints on frontier
  choice. No training on the environments.
- **Results:** state of the art for zero-shot object navigation on MP3D, HM3D and RoboTHOR at the
  time; 288% relative success improvement over CoW on MP3D.

### L3MVN: Leveraging Large Language Models for Visual Target Navigation
Yu, Kasaei, Cao. IROS 2023. <https://arxiv.org/abs/2304.05501>

- **Method:** builds a semantic map and uses a language model, zero-shot or feed-forward, to pick
  the frontier most likely to lead to the target as the long-term goal.
- **Results:** 54.2% success and SPL 0.255 on HM3D; also shown on a real robot.

### VLFM: Vision-Language Frontier Maps for Zero-Shot Semantic Navigation
Yokoyama, Ha, Batra, Wang, Bucher. arXiv, December 2023. <https://arxiv.org/abs/2312.03275>

- **Method:** frontiers come from an occupancy map built from depth; a pre-trained vision-language
  model turns RGB into a language-grounded value map that ranks them for the target object.
- **Results:** best SPL at the time on Gibson, HM3D and MP3D in Habitat; deployed on Spot in an
  office building.
- **For us:** the same structure as our engine, frontiers on a depth map ranked by semantics.
  Theirs ranks by object likelihood; ours ranks by what signs say.

### SG-Nav: Online 3D Scene Graph Prompting for LLM-based Zero-shot Object Navigation
Yin, Xu, Wu, Zhou, Lu. NeurIPS 2024. <https://arxiv.org/abs/2410.08189>

- **Method:** builds a hierarchical 3D scene graph online (objects, groups, rooms) and prompts a
  language model with chain-of-thought over its nodes and edges. A re-perception step corrects
  doubtful detections.
- **Results:** more than 10 points of success over earlier zero-shot methods on MP3D, HM3D and
  RoboTHOR; the first zero-shot method to beat supervised ones on MP3D.
- **For us:** the case for re-checking doubtful sightings, and for giving a planner structure
  rather than a flat list.

### ApexNav: An Adaptive Exploration Strategy for Zero-Shot Object Navigation with Target-centric Semantic Fusion
Zhang, Du, Wu, et al. 2025. <https://arxiv.org/abs/2504.14478>

- **Method:** follows semantic reasoning when the cues are strong and pure geometric exploration
  when they are weak, and keeps a long-term memory of the target and similar-looking objects to
  avoid misidentification.
- **Results:** better success and SPL than earlier methods on HM3Dv1, HM3Dv2 and MP3D; real-world
  trials found every target.
- **For us:** the model for our fusion rule of weighting advice by confidence and handing back to
  geometry when cues are weak.

### ReasonNavi: Human-Inspired Global Map Reasoning for Zero-Shot Embodied Navigation
Ao, Wang, Tai, Tang. arXiv preprint, January 2026. <https://arxiv.org/abs/2602.15864>

- **Method:** reason, then act. A top-down map is split into rooms and sampled candidate nodes; a
  multimodal model picks a waypoint; a deterministic planner drives using an occupancy map.
  Pretrained detectors and segmenters recognise the goal.
- **Results:** beats methods that need training or heavy scene modelling, without fine-tuning, on
  three navigation tasks.

## Fast–slow systems

### OmniNav: A Unified Framework for Prospective Exploration and Visual-Language Navigation
Xue, Hu, et al. ICLR 2026. <https://arxiv.org/abs/2509.25687>

- **Method:** a fast module predicts continuous waypoints from short-horizon views at up to 5 Hz; a
  slow module plans over long-horizon observations and frontiers to pick the next subgoal. One
  framework for instruction, object and point goals and for exploration.
- **Finding:** the bottleneck is understanding instructions and objects, not policy learning.
- **For us:** the fast–slow split our short-term and long-term tiers follow, except that our fast
  layer is the deterministic engine, not a learned policy.

### Slow Brain, Fast Planner: Latency-Resilient VLM-Augmented Urban Navigation
Peng, He, Li, Ma, Zhou. arXiv preprint, June 2026. <https://arxiv.org/abs/2606.20458>

- **Method:** a fast planner proposes candidate trajectories; a vision-language model, at 1–3 s per
  query, picks one. A training-free fusion layer re-matches late answers to current candidates by
  geometric similarity and fades them exponentially.
- **Results:** over 80% success with up to 5 s of delay in simulation; 30% lower path error than
  the planner's own pick on about 2,000 real sidewalk scenarios.
- **For us:** the model for handling late answers (fusion rule 3 in the architecture doc).

### FSR-VLN: Fast and Slow Reasoning for Vision-Language Navigation with Hierarchical Multi-modal Scene Graph
Zhou, Xiao, Liu, et al. arXiv preprint, September 2025. <https://arxiv.org/abs/2509.13733>

- **Method:** fast matching on a hierarchical scene graph picks candidate rooms, views and objects;
  a vision-language model refines the goal only when fast matching is unsure.
- **Results:** state-of-the-art retrieval success with 82% less response time than model-only
  methods; real tests on a Unitree G1 humanoid and four indoor datasets.
- **For us:** call the model only when the cheap path is unsure. This is how to keep model calls
  rare.

## Foundations

- **Task definition and metrics:** [ObjectNav Revisited](https://arxiv.org/abs/2006.13171) (Batra et
  al., 2020) standardised object-goal navigation in unseen environments.
  [On Evaluation of Embodied Navigation Agents](https://arxiv.org/abs/1807.06757) (Anderson et al.,
  2018) set out the evaluation measures, including SPL.
- **Modular semantic exploration:** [Object Goal Navigation using Goal-Oriented Semantic
  Exploration](https://arxiv.org/abs/2007.00643) (Chaplot, Gandhi, Gupta, Salakhutdinov, NeurIPS
  2020) builds an episodic semantic map and explores by goal category. It won the 2020 Habitat
  ObjectNav challenge and transferred to a real robot.
- **Frontier exploration:** [A frontier-based approach for autonomous
  exploration](https://ieeexplore.ieee.org/document/613851) (Yamauchi, CIRA 1997) explores by moving
  to the boundary between open and unexplored space. It ran on a Nomad 200 in real offices.

## Where this project sits

What no single work above covers, and this project aims to:

1. Reading signs **from a camera**, including shop names too high and far for depth, which are
   ranged from the angle they're seen at.
2. Room numbering reasoned in **plain code** first (`RoomNumberModel`), so a model gets clean facts.
3. Models that can only **bias** which opening is explored, never the path, a stop or arrival.
4. A measured **accuracy curve**: how good an adviser must be before it helps (60% adds about 6
   points, 90% about 20, a perfect one 23.6).
5. Two tiers, a short-term adviser and a long-term planner, both evaluated with free stand-ins
   before any paid model.

What doesn't hold up yet:

- Giving the model less authority didn't beat Follow the Signs' mechanism at equal accuracy.
- Navigation success is still simulated.
