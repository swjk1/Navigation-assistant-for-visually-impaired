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

export type NavigationTarget =
  | { type: 'ROOM'; value: string }
  | { type: 'EXIT' }
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
