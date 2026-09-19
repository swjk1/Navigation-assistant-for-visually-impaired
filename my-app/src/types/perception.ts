/**
 * Person 1 — Perception Engine data contract.
 *
 * Overall product goal: help a blind / low-vision user walk indoors
 * (e.g. toward a door) using camera perception + teammate navigation + TTS.
 *
 * Person 1 ONLY produces this structured "what's ahead" frame.
 * - Person 2 (mapping / planning): uses objects, clocks, distances, OCR text
 * - Person 3 (guidance / TTS): speaks hazardDescription + clock/distance phrases
 *
 * Do not put pathfinding, routing graphs, or speech synthesis in this module.
 */

export interface BoundingBox {
  /** 0.0 to 1.0 (normalized horizontal coordinate) */
  x: number;
  /** 0.0 to 1.0 (normalized vertical coordinate) */
  y: number;
  /** 0.0 to 1.0 */
  width: number;
  /** 0.0 to 1.0 */
  height: number;
}

export type ObjectLabel =
  | 'door'
  | 'person'
  | 'stairs'
  | 'elevator'
  | 'chair'
  | 'trashcan'
  | 'wall'
  | 'sign'
  // Emitted by the fine-tuned indoor YOLO26 model. Signs are reported as their SUBJECT rather
  // than flattened to 'sign': an "exit sign" is how a user finds an exit, and the navigation
  // engine can act on that, whereas a generic 'sign' tells it nothing.
  | 'exit sign'
  | 'left arrow'
  | 'right arrow'
  | 'washroom';

/**
 * Clock facing relative to the user walking forward.
 * 12 o'clock = straight ahead (center corridor).
 * Useful for Person 3 speech: "door at 11 o'clock".
 */
export type ClockDirection =
  | "9 o'clock"
  | "10 o'clock"
  | "11 o'clock"
  | "12 o'clock"
  | "1 o'clock"
  | "2 o'clock"
  | "3 o'clock";

export interface DetectedObject {
  label: ObjectLabel;
  /** 0.0 to 1.0 */
  confidence: number;
  box: BoundingBox;
  clockPosition: ClockDirection;
  approxDistanceMeters: number;
}

export interface OCRDetection {
  /** e.g. "ROOM 204" — Person 3 may speak this to confirm destination */
  text: string;
  confidence: number;
  box: BoundingBox;
}

/**
 * Verified perception snapshot for one camera frame.
 * Stable handoff contract — prefer this over raw Gemini/YOLO/ML Kit payloads.
 */
export interface PerceptionFrame {
  timestamp: number;
  latencyMs: number;
  objects: DetectedObject[];
  text: OCRDetection[];
  floorDetected: boolean;
  /** If true, Person 3 should prioritize a stop / caution utterance */
  immediateHazard: boolean;
  /** Short phrase safe to speak nearly verbatim (≤ ~100 chars) */
  hazardDescription: string | null;
}

/** Result of a single low-latency camera capture (Person 1 internal → perception). */
export interface CameraCaptureResult {
  base64: string;
  width: number;
  height: number;
  captureLatencyMs: number;
  estimatedBytes: number;
  mimeType: 'image/jpeg';
}

/** Labels Person 2 typically treats as navigation anchors / destinations. */
export type NavAnchorLabel =
  | 'door'
  | 'elevator'
  | 'stairs'
  | 'sign'
  | 'exit sign'
  | 'washroom';

/** Labels Person 2/3 typically treat as path obstacles. */
export type ObstacleLabel = 'person' | 'chair' | 'trashcan';

export const NAV_ANCHOR_LABELS: NavAnchorLabel[] = [
  'door',
  'elevator',
  'stairs',
  'sign',
  'exit sign',
  'washroom',
];

export const OBSTACLE_LABELS: ObstacleLabel[] = [
  'person',
  'chair',
  'trashcan',
];
