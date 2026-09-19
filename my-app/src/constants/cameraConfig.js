/**
 * Hardware capture targets for Person 1 latency budget.
 * Capture should stay ≤ 150 ms with Base64 payload ideally < 150 KB.
 */
export const CAMERA_CAPTURE_CONFIG = {
  /** Rear / environment-facing camera */
  facing: 'back',
  width: 640,
  height: 480,
  /** JPEG quality 0.4 — balance between size and OCR readability */
  quality: 0.4,
  /** Soft size budget for the encoded frame */
  maxBase64Bytes: 150 * 1024,
  /** Soft latency budget for capture alone */
  maxCaptureLatencyMs: 150,
};
