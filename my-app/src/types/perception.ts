/**
 * Person 1 — Perception Engine data contract.
 *
 * Overall product goal: help a blind / low-vision user walk indoors
 * (e.g. toward a door) using camera perception + teammate navigation + TTS.
 *
 * The perception layer ONLY produces this structured "what's ahead" frame.
 * - mapping / planning consumes objects, clocks, distances and OCR text
 * - guidance / TTS speaks hazardDescription plus clock/distance phrases
 *
 * Do not put pathfinding, routing graphs, or speech synthesis in this module.
 *
 * The label and clock unions below are DERIVED from the runtime allowlists in
 * `constants/perceptionCatalog.js`. Do not restate them here - that is how they drifted apart
 * once already, and the validator silently dropped every label the type claimed to allow.
 */

import {
  ALLOWED_CLOCK,
  ALLOWED_LABELS,
  NAV_ANCHOR_LABELS,
  OBSTACLE_LABELS,
} from '../constants/perceptionCatalog.js';


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

export type ObjectLabel = (typeof ALLOWED_LABELS)[number];

/**
 * Clock facing relative to the user walking forward.
 * 12 o'clock = straight ahead (center corridor).
 * Useful for guidance speech: "door at 11 o'clock".
 */
export type ClockDirection = (typeof ALLOWED_CLOCK)[number];

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

/** Labels the navigation layer treats as anchors / destinations. */
export type NavAnchorLabel = (typeof NAV_ANCHOR_LABELS)[number];

/** Labels the navigation layer treats as path obstacles. */
export type ObstacleLabel = (typeof OBSTACLE_LABELS)[number];

export { ALLOWED_LABELS, ALLOWED_CLOCK, NAV_ANCHOR_LABELS, OBSTACLE_LABELS };
