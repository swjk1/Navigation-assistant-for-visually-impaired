/**
 * Person 1 — Perception Engine data contract.
 * Consumed by Person 2 (Mapping/Planning) and Person 3 (Guidance/UX).
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
  | 'sign';

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
  text: string;
  confidence: number;
  box: BoundingBox;
}

export interface PerceptionFrame {
  timestamp: number;
  latencyMs: number;
  objects: DetectedObject[];
  text: OCRDetection[];
  floorDetected: boolean;
  immediateHazard: boolean;
  hazardDescription: string | null;
}

/** Result of a single low-latency camera capture. */
export interface CameraCaptureResult {
  base64: string;
  width: number;
  height: number;
  captureLatencyMs: number;
  estimatedBytes: number;
  mimeType: 'image/jpeg';
}
