/**
 * Strict PerceptionFrame sanitizer.
 *
 * Everything upstream of this - Gemini, YOLO, ML Kit OCR - produces output that is only
 * *probably* the right shape. Model output is untrusted input: a label the catalog does not
 * know, a confidence of 2.5, a bounding box that runs off the edge of the frame, or a 4 KB
 * "hazard description" are all things that have actually come back. This is the one place that
 * turns any of that into a {@link PerceptionFrame} or throws.
 *
 * Downstream consumers (guidance speech, the navigation handoff) may assume every field is
 * present and in range precisely because nothing reaches them without passing through here.
 */

import {
  ALLOWED_LABELS,
  ALLOWED_CLOCK,
  MAX_DISTANCE_METERS,
} from '../constants/perceptionCatalog.js';

/** @typedef {import('../types/perception').PerceptionFrame} PerceptionFrame */
/** @typedef {import('../types/perception').BoundingBox} BoundingBox */
/** @typedef {import('../types/perception').DetectedObject} DetectedObject */
/** @typedef {import('../types/perception').OCRDetection} OCRDetection */
/** @typedef {import('../types/perception').ObjectLabel} ObjectLabel */
/** @typedef {import('../types/perception').ClockDirection} ClockDirection */

/**
 * @param {unknown} value
 * @param {number} [fallback]
 * @returns {number}
 */
function clamp01(value, fallback = 0) {
  const n = Number(value);
  if (Number.isNaN(n)) return fallback;
  return Math.max(0, Math.min(1, n));
}

/**
 * Clamp a box to the unit square and keep x+width / y+height within it.
 *
 * @param {Partial<Record<keyof BoundingBox, unknown>>} [box]
 * @returns {BoundingBox}
 */
function sanitizeBox(box = {}) {
  let x = clamp01(box.x, 0);
  let y = clamp01(box.y, 0);
  let width = clamp01(box.width, 0);
  let height = clamp01(box.height, 0);
  width = Math.min(width, 1 - x);
  height = Math.min(height, 1 - y);
  return { x, y, width, height };
}

/**
 * @param {unknown} rawPayload Raw model output. Assumed hostile until proven otherwise.
 * @param {number} latencyMs
 * @returns {PerceptionFrame}
 * @throws {Error} If the payload is not an object at all - the one failure this cannot sanitize
 *   its way out of, and which means the caller has a transport bug rather than a bad model day.
 */
export function validateAndSanitizeFrame(rawPayload, latencyMs) {
  if (!rawPayload || typeof rawPayload !== 'object') {
    throw new Error('Invalid payload: Root must be an object');
  }

  /** @type {Record<string, any>} */
  const raw = /** @type {Record<string, any>} */ (rawPayload);

  const immediateHazard = Boolean(raw.immediateHazard);
  const hazardDescription = raw.hazardDescription
    ? String(raw.hazardDescription).slice(0, 100)
    : null;
  const floorDetected = Boolean(raw.floorDetected);

  /** @type {DetectedObject[]} */
  const validObjects = [];
  if (Array.isArray(raw.objects)) {
    for (const obj of raw.objects) {
      if (!obj || !ALLOWED_LABELS.includes(obj.label)) continue;

      const distance = Math.max(
        0.5,
        Math.min(
          MAX_DISTANCE_METERS,
          Number(obj.approxDistanceMeters) || 2.0
        )
      );

      validObjects.push({
        label: obj.label,
        confidence: clamp01(obj.confidence, 0.5),
        box: sanitizeBox(obj.box),
        clockPosition: ALLOWED_CLOCK.includes(obj.clockPosition)
          ? obj.clockPosition
          : "12 o'clock",
        approxDistanceMeters: distance,
      });
    }
  }

  /** @type {OCRDetection[]} */
  const validText = [];
  if (Array.isArray(raw.text)) {
    for (const item of raw.text) {
      if (!item || typeof item.text !== 'string' || item.text.trim() === '') {
        continue;
      }
      validText.push({
        text: item.text.trim().slice(0, 50),
        confidence: clamp01(item.confidence, 0.5),
        box: sanitizeBox(item.box),
      });
    }
  }

  return {
    timestamp: Date.now(),
    latencyMs: Number(latencyMs) || 0,
    objects: validObjects,
    text: validText,
    floorDetected,
    immediateHazard,
    hazardDescription,
  };
}

export { ALLOWED_LABELS, ALLOWED_CLOCK };
