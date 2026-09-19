/**
 * Strict PerceptionFrame sanitizer for Person 1 → Person 2/3 handoff.
 * Clamps coordinates, filters labels/clocks, and always returns a valid frame shape.
 */

const ALLOWED_LABELS = [
  'door',
  'person',
  'stairs',
  'elevator',
  'chair',
  'trashcan',
  'wall',
  'sign',
];

const ALLOWED_CLOCK = [
  "9 o'clock",
  "10 o'clock",
  "11 o'clock",
  "12 o'clock",
  "1 o'clock",
  "2 o'clock",
  "3 o'clock",
];

function clamp01(value, fallback = 0) {
  const n = Number(value);
  if (Number.isNaN(n)) return fallback;
  return Math.max(0, Math.min(1, n));
}

function sanitizeBox(box = {}) {
  return {
    x: clamp01(box.x, 0),
    y: clamp01(box.y, 0),
    width: clamp01(box.width, 0),
    height: clamp01(box.height, 0),
  };
}

/**
 * @param {object} rawPayload
 * @param {number} latencyMs
 * @returns {import('../types/perception').PerceptionFrame}
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

      validObjects.push({
        label: obj.label,
        confidence: clamp01(obj.confidence, 0.5),
        box: sanitizeBox(obj.box),
        clockPosition: ALLOWED_CLOCK.includes(obj.clockPosition)
          ? obj.clockPosition
          : "12 o'clock",
        approxDistanceMeters: Math.max(
          0.5,
          Number(obj.approxDistanceMeters) || 2.0
        ),
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
