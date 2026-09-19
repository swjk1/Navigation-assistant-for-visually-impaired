/**
 * Hardware capture targets for Person 1 latency budget.
 * Round-trip pipeline target ≤ 1500 ms (capture ≤ 150 ms).
 */
export const CAMERA_CAPTURE_CONFIG = {
  facing: 'back',
  width: 640,
  height: 480,
  /** Default JPEG quality — Step 4 also profiles 0.3 and 0.5 */
  quality: 0.4,
  maxBase64Bytes: 150 * 1024,
  maxCaptureLatencyMs: 150,
};

/** PRD Step 4 quality candidates for payload / latency tradeoff */
export const JPEG_QUALITY_CANDIDATES = [0.3, 0.4, 0.5];

/** End-to-end perception budget (ms) */
export const PIPELINE_BUDGET_MS = 1500;

/** If network work exceeds this, return deterministic timeout frame (PRD Step 4) */
export const NETWORK_TIMEOUT_FALLBACK_MS = 3000;
