import type { StyleProp, ViewStyle } from 'react-native';

/**
 * Public types for the navigation engine.
 *
 * Nothing here is Android- or ARCore-specific: the same API is intended to be served by a future
 * Swift/ARKit implementation backed by the same shared navigation core, so React Native code will
 * not change when the platform does.
 */

/** Whether this device can actually run the engine. Never assume; always check. */
export type NavigationSupport = {
  arCoreSupported: boolean;
  /** False means the engine cannot map obstacles. Do NOT navigate on this device. */
  depthSupported: boolean;
  trackingAvailable: boolean;
  /** Human-readable explanation when something is unsupported. */
  reason?: string | null;
};

export type NavigationStatus =
  | 'IDLE'
  | 'INITIALIZING'
  | 'LOCALIZING'
  | 'EXPLORING'
  | 'NAVIGATING'
  | 'BACKTRACKING'
  | 'ARRIVED'
  | 'LOST_TRACKING'
  | 'NO_ROUTE'
  | 'PAUSED'
  | 'ERROR';

/**
 * The movement instruction to give the user.
 *
 * STOP is the safe default and is emitted whenever the engine is not certain the way ahead is
 * known and clear (tracking lost, depth starved, no route, obstacle ahead).
 * SCAN asks the user to stand still and sweep the phone so the engine can see more.
 */
export type NavigationCommand =
  | 'STRAIGHT'
  | 'TURN_LEFT'
  | 'TURN_RIGHT'
  | 'STOP'
  | 'SCAN'
  | 'ARRIVED';

/**
 * Why the engine is holding the user still. Present only alongside `STOP` and `SCAN`.
 *
 * The guidance layer needs this to tell a hazard from a fault: `OBSTACLE_AHEAD` means something is
 * in the user's way and deserves a hazard alert, while `TRACKING_LOST` is the system failing and
 * deserves a different message entirely.
 */
export type StopReason =
  | 'OBSTACLE_AHEAD'
  | 'NO_ROUTE'
  | 'TRACKING_LOST'
  | 'NO_DEPTH'
  | 'MAP_INCOMPLETE'
  | 'NOT_STARTED'
  | 'PAUSED';

export type NavigationDebugInfo = {
  freeCells: number;
  occupiedCells: number;
  unknownCells: number;
  frontierCount: number;
  topologicalNodeCount: number;
  pathLength: number;
  floorY: number;
  floorConfidence: number;
  depthPointsLastFrame: number;
  /** Progress through the initial scan, 0..1. Movement is never instructed before this is 1. */
  scanProgress: number;
  depthAvailable: boolean;
  poseX: number;
  poseY: number;
  poseZ: number;
  yawDegrees: number;
  lastError?: string | null;
};

/**
 * The complete state pushed to JS, at most ~8 Hz (immediately on any status/command change).
 * This is intentionally tiny: no depth maps, frames, point clouds or grids cross the bridge.
 */
export type NavigationSnapshot = {
  timestamp: number;
  status: NavigationStatus;
  command: NavigationCommand;
  /** Why the user is being held still. Set only alongside `STOP` and `SCAN`. */
  stopReason?: StopReason | null;
  /** Negative = next waypoint is to the LEFT, positive = to the RIGHT. */
  headingErrorDegrees?: number | null;
  distanceToWaypointMeters?: number | null;
  /** Distance to the destination if known, otherwise to the next waypoint. */
  distanceMeters?: number | null;
  trackingConfidence: number;
  /** How much of the user's immediate surroundings has been observed, 0..1. */
  mapConfidence: number;
  selectedFrontierId?: string | null;
  target?: string | null;
  debug?: NavigationDebugInfo | null;
};

/** A rendered PNG of the occupancy grid, for debug display. */
export type NavigationMapImage = {
  /** PNG bytes, base64. Use as `data:image/png;base64,${base64}`. */
  base64: string;
  width: number;
  height: number;
  /** Metres per grid cell. */
  resolutionMeters: number;
  /** Side length of the mapped window, in metres. */
  sizeMeters: number;
};

/**
 * A rendered PNG of the raw depth returns of the latest frame, plus what the engine made of them.
 *
 * Same window and scale as {@link NavigationMapImage}, so the two can be shown in one place and
 * toggled between. The counts are the useful part: they say whether depth is arriving at all and
 * which height band it landed in, which is what decides whether the grid fills.
 */
export type NavigationDepthImage = {
  /** PNG bytes, base64. Use as `data:image/png;base64,${base64}`. */
  base64: string;
  width: number;
  height: number;
  /** Metres per grid cell. Matches the occupancy map. */
  resolutionMeters: number;
  /** Side length of the window, in metres. Matches the occupancy map. */
  sizeMeters: number;
  /** Returns in the frame, before any filtering. 0 means the sensor gave us nothing. */
  totalReturns: number;
  /** Below `minObstacleHeightMeters`: walkable ground, integrated as free space. */
  floorReturns: number;
  /** Inside the obstacle band: what actually blocks a cell. */
  obstacleReturns: number;
  /** Above `maxObstacleHeightMeters`: passes over the user and is discarded. */
  overheadReturns: number;
  /** Below the floor estimate by more than `floorBandBelowMeters`: discarded as untrustworthy. */
  belowFloorReturns: number;
  /** Outside [`minDepthMeters`, `maxDepthMeters`]: discarded. */
  outOfRangeReturns: number;
  /** Fell outside the rendered window entirely. */
  offWindowReturns: number;
  /** Current floor height estimate, in metres, and how much the engine trusts it. */
  floorY: number;
  floorConfidence: number;
  /** Age of the rendered frame in milliseconds. A large value means depth has stalled. */
  ageMillis: number;
  /** False until the first depth frame arrives. */
  hasFrame: boolean;
};

export type NavigationTarget =
  | { type: 'ROOM'; value: string }
  | { type: 'EXIT' }
  | { type: 'DOOR' }
  | { type: 'STAIRS' }
  | { type: 'ELEVATOR' }
  | { type: 'EXPLORE' };

export type SemanticDirection = 'LEFT' | 'RIGHT' | 'FORWARD';

type SemanticBase = {
  /** 0..1. Observations below ~0.3 are discarded by the engine. */
  confidence: number;
  /**
   * Nanosecond timestamp of the camera frame this came from (ARCore `Frame.getTimestamp()`).
   * Used to reject observations too old to associate with current depth.
   */
  timestampNs?: number;
  /** Image-space position, 0..1. The native adapter resolves it to a world position via depth. */
  normalizedX?: number;
  normalizedY?: number;
  /** Supply these directly if the caller already resolved a canonical world position. */
  worldX?: number;
  worldY?: number;
  worldZ?: number;
};

/**
 * Evidence from the perception layer (OCR, sign recognition, ...).
 *
 * The navigation engine does not implement any recognition itself, and semantic evidence never
 * issues a movement command - it only biases which unexplored branch is chosen.
 */
export type SemanticObservation =
  | (SemanticBase & { type: 'ROOM'; label: string })
  | (SemanticBase & {
      type: 'ROOM_RANGE';
      min: number;
      max: number;
      direction: SemanticDirection;
    })
  | (SemanticBase & { type: 'EXIT'; direction?: SemanticDirection })
  /**
   * A door, with no claim about what is behind it. Deliberately distinct from EXIT and ROOM:
   * a door may lead outside, into a room, or into a cupboard. The engine treats it as partial
   * evidence for those targets rather than a match for them.
   */
  | (SemanticBase & { type: 'DOOR'; direction?: SemanticDirection })
  | (SemanticBase & { type: 'STAIRS' })
  | (SemanticBase & { type: 'ELEVATOR' })
  | (SemanticBase & { type: 'FLOOR'; floor: string });

export type NavigationNativeModuleEvents = {
  onNavigationState: (snapshot: NavigationSnapshot) => void;
};

export type NavigationArViewProps = {
  style?: StyleProp<ViewStyle>;
  /** Include the `debug` block in emitted snapshots. */
  debug?: boolean;
};
