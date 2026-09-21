import { CAMERA_CAPTURE_CONFIG } from '../constants/cameraConfig.js';

/** @typedef {import('../types/perception').CameraCaptureResult} CameraCaptureResult */

/**
 * The slice of Expo's `CameraView` ref this module actually uses.
 *
 * Typed structurally rather than as `CameraView` so the Node-side tests can pass a stub without
 * pulling in Expo natives - which is the same reason `expo-image-manipulator` is imported lazily
 * further down.
 *
 * @typedef {object} CameraRefLike
 * @property {(options: {
 *   quality?: number,
 *   base64?: boolean,
 *   skipProcessing?: boolean,
 *   exif?: boolean,
 * }) => Promise<{ uri?: string, base64?: string } | undefined>} takePictureAsync
 */

/**
 * @typedef {object} CaptureOverrides
 * @property {number} [quality]
 * @property {number} [width]
 * @property {number} [height]
 */

/**
 * @typedef {CameraCaptureResult & {
 *   uri: string | null,
 *   withinLatencyBudget: boolean,
 *   withinSizeBudget: boolean,
 * }} HarnessCaptureResult
 */

/**
 * Low-latency camera frame harness for Expo CameraView / takePictureAsync.
 *
 * Target: ~640×480 JPEG @ quality 0.4, capture ≤ 150 ms, payload < 150 KB.
 */

/**
 * Estimate decoded byte size of a Base64 string (without data-URI prefix).
 * @param {string} base64
 * @returns {number}
 */
export function estimateBase64Bytes(base64) {
  if (!base64 || typeof base64 !== 'string') return 0;
  const cleaned = base64.replace(/^data:image\/\w+;base64,/, '');
  const padding = (cleaned.match(/=+$/) || [''])[0].length;
  return Math.floor((cleaned.length * 3) / 4) - padding;
}

/**
 * Strip a data-URI prefix if present so Gemini receives raw Base64.
 * @param {string} base64OrDataUri
 * @returns {string}
 */
export function stripDataUriPrefix(base64OrDataUri) {
  if (!base64OrDataUri) return '';
  return base64OrDataUri.replace(/^data:image\/\w+;base64,/, '');
}

/**
 * Capture a single compressed frame from an Expo CameraView instance.
 * Always downscales to the PRD budget — Android otherwise returns full sensor size.
 *
 * @param {CameraRefLike | null | undefined} cameraRefValue
 * @param {CaptureOverrides} [overrides]
 * @returns {Promise<HarnessCaptureResult>}
 */
export async function captureFrame(cameraRefValue, overrides = {}) {
  if (!cameraRefValue || typeof cameraRefValue.takePictureAsync !== 'function') {
    throw new Error(
      'captureFrame: camera ref is missing or does not support takePictureAsync. Ensure CameraView is mounted and permission granted.'
    );
  }

  const quality = overrides.quality ?? CAMERA_CAPTURE_CONFIG.quality;
  const width = overrides.width ?? CAMERA_CAPTURE_CONFIG.width;
  const height = overrides.height ?? CAMERA_CAPTURE_CONFIG.height;

  const start =
    typeof performance !== 'undefined' ? performance.now() : Date.now();

  // skipProcessing:true returns full-res (~4K) and ignores quality — do NOT use it.
  const photo = await cameraRefValue.takePictureAsync({
    quality,
    base64: false,
    skipProcessing: false,
    exif: false,
  });

  if (!photo?.uri) {
    throw new Error('captureFrame: takePictureAsync returned no image uri.');
  }

  // Lazy import so Node mock tests can use estimateBase64Bytes without Expo natives
  const { SaveFormat, manipulateAsync } = await import(
    'expo-image-manipulator'
  );

  const resized = await manipulateAsync(
    photo.uri,
    [{ resize: { width, height } }],
    {
      compress: quality,
      format: SaveFormat.JPEG,
      base64: true,
    }
  );

  const end =
    typeof performance !== 'undefined' ? performance.now() : Date.now();
  const captureLatencyMs = Math.round(end - start);

  const base64 = stripDataUriPrefix(resized?.base64 || '');
  if (!base64) {
    throw new Error('captureFrame: resize returned no Base64 payload.');
  }

  const estimatedBytes = estimateBase64Bytes(base64);

  return {
    base64,
    width: resized.width ?? width,
    height: resized.height ?? height,
    uri: resized.uri ?? photo.uri ?? null,
    captureLatencyMs,
    estimatedBytes,
    mimeType: 'image/jpeg',
    withinLatencyBudget:
      captureLatencyMs <= CAMERA_CAPTURE_CONFIG.maxCaptureLatencyMs,
    withinSizeBudget: estimatedBytes <= CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
  };
}

/**
 * Build a hardware verification record for the capture-timing harness.
 *
 * Two different capture paths feed this: [captureFrame] above (Expo `CameraView`) and
 * `IndoorPerception.captureFrame()` (a frame lifted straight off the live ARCore session).
 * They agree on the measured fields but only the first carries the budget verdicts, so those
 * are optional here and recomputed from the same config when missing. Recomputing rather than
 * reporting `undefined` keeps every snapshot comparable no matter which path produced it.
 *
 * @param {{
 *   base64?: string,
 *   width?: number,
 *   height?: number,
 *   captureLatencyMs: number,
 *   estimatedBytes: number,
 *   mimeType?: string,
 *   withinLatencyBudget?: boolean,
 *   withinSizeBudget?: boolean,
 * }} captureResult
 * @param {{ permissionGranted?: boolean, platform?: string }} [meta]
 */
export function buildHardwareSnapshot(captureResult, meta = {}) {
  const withinLatencyBudget =
    captureResult.withinLatencyBudget ??
    captureResult.captureLatencyMs <= CAMERA_CAPTURE_CONFIG.maxCaptureLatencyMs;
  const withinSizeBudget =
    captureResult.withinSizeBudget ??
    captureResult.estimatedBytes <= CAMERA_CAPTURE_CONFIG.maxBase64Bytes;

  return {
    step: 1,
    name: 'hardware_camera_verification',
    timestamp: Date.now(),
    permissionGranted: Boolean(meta.permissionGranted),
    platform: meta.platform || 'unknown',
    config: { ...CAMERA_CAPTURE_CONFIG },
    capture: {
      width: captureResult.width,
      height: captureResult.height,
      captureLatencyMs: captureResult.captureLatencyMs,
      estimatedBytes: captureResult.estimatedBytes,
      mimeType: captureResult.mimeType,
      base64Length: captureResult.base64?.length ?? 0,
      base64Prefix: (captureResult.base64 || '').slice(0, 32),
      looksLikeBase64: /^[A-Za-z0-9+/]+=*$/.test(
        (captureResult.base64 || '').slice(0, 64).replace(/\s/g, '')
      ),
    },
    budgets: {
      maxCaptureLatencyMs: CAMERA_CAPTURE_CONFIG.maxCaptureLatencyMs,
      maxBase64Bytes: CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
      withinLatencyBudget,
      withinSizeBudget,
    },
    reviewCheckpoint:
      'Did camera capture a sample image with Base64 size < 150KB and latency < 150ms?',
  };
}
