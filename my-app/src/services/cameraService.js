import { CAMERA_CAPTURE_CONFIG } from '../constants/cameraConfig.js';

/**
 * Low-latency camera frame harness for Expo CameraView / takePictureAsync.
 *
 * Target: 640×480 JPEG @ quality 0.4, capture ≤ 150 ms, payload < 150 KB.
 *
 * Usage (from a screen that holds a CameraView ref):
 *   const result = await captureFrame(cameraRef.current);
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
 *
 * @param {object} cameraRefValue - Result of cameraRef.current (must expose takePictureAsync)
 * @param {object} [overrides]
 * @param {number} [overrides.quality]
 * @param {number} [overrides.width]
 * @param {number} [overrides.height]
 * @returns {Promise<{
 *   base64: string,
 *   width: number,
 *   height: number,
 *   captureLatencyMs: number,
 *   estimatedBytes: number,
 *   mimeType: 'image/jpeg',
 *   withinLatencyBudget: boolean,
 *   withinSizeBudget: boolean,
 * }>}
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

  const start = typeof performance !== 'undefined' ? performance.now() : Date.now();

  const photo = await cameraRefValue.takePictureAsync({
    quality,
    base64: true,
    skipProcessing: true,
    exif: false,
    // Hint preferred size when the native layer supports it
    ...(width && height ? { imageSize: { width, height } } : {}),
  });

  const end = typeof performance !== 'undefined' ? performance.now() : Date.now();
  const captureLatencyMs = Math.round(end - start);

  const base64 = stripDataUriPrefix(photo?.base64 || '');
  if (!base64) {
    throw new Error('captureFrame: takePictureAsync returned no Base64 payload.');
  }

  const estimatedBytes = estimateBase64Bytes(base64);

  return {
    base64,
    width: photo.width ?? width,
    height: photo.height ?? height,
    uri: photo.uri ?? null,
    captureLatencyMs,
    estimatedBytes,
    mimeType: 'image/jpeg',
    withinLatencyBudget: captureLatencyMs <= CAMERA_CAPTURE_CONFIG.maxCaptureLatencyMs,
    withinSizeBudget: estimatedBytes <= CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
  };
}

/**
 * Build a Step-1 hardware verification record (for snapshots).
 * Call after a successful captureFrame().
 *
 * @param {object} captureResult - return value of captureFrame
 * @param {object} [meta]
 * @param {boolean} [meta.permissionGranted]
 * @param {string} [meta.platform]
 * @returns {object}
 */
export function buildHardwareSnapshot(captureResult, meta = {}) {
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
      // Never persist full Base64 in committed snapshots — only a short prefix for validity
      base64Prefix: (captureResult.base64 || '').slice(0, 32),
      looksLikeBase64: /^[A-Za-z0-9+/]+=*$/.test(
        (captureResult.base64 || '').slice(0, 64).replace(/\s/g, '')
      ),
    },
    budgets: {
      maxCaptureLatencyMs: CAMERA_CAPTURE_CONFIG.maxCaptureLatencyMs,
      maxBase64Bytes: CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
      withinLatencyBudget: captureResult.withinLatencyBudget,
      withinSizeBudget: captureResult.withinSizeBudget,
    },
    reviewCheckpoint:
      'Did camera capture a sample image with Base64 size < 150KB and latency < 150ms?',
  };
}
