/**
 * Strict PerceptionFrame sanitizer for Person 1 → Person 2/3 handoff.
 */

import {
  ALLOWED_LABELS,
  ALLOWED_CLOCK,
  MAX_DISTANCE_METERS,
} from '../constants/perceptionCatalog.js';

function clamp01(value, fallback = 0) {
  const n = Number(value);
  if (Number.isNaN(n)) return fallback;
  return Math.max(0, Math.min(1, n));
}

/**
 * Clamp box to unit square and ensure x+width / y+height stay ≤ 1.
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
 * @param {object} rawPayload
 * @param {number} latencyMs
 * @returns {object} PerceptionFrame
 */
export function validateAndSanitizeFrame(rawPayload, latencyMs) {
  if (!rawPayload || typeof rawPayload !== 'object') {
    throw new Error('Invalid payload: Root must be an object');
  }

  const immediateHazard = Boolean(rawPayload.immediateHazard);
  const hazardDescription = rawPayload.hazardDescription
    ? String(rawPayload.hazardDescription).slice(0, 100)
    : null;
  const floorDetected = Boolean(rawPayload.floorDetected);

  const validObjects = [];
  if (Array.isArray(rawPayload.objects)) {
    for (const obj of rawPayload.objects) {
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

  const validText = [];
  if (Array.isArray(rawPayload.text)) {
    for (const item of rawPayload.text) {
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
