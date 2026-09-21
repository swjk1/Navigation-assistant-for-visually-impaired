/**
 * The sanitizer is the trust boundary: everything upstream of it (Gemini, YOLO, ML Kit) can
 * return whatever it likes, and everything downstream assumes the contract holds. These tests
 * are therefore written from the attacker's side - malformed, out-of-range and oversized input -
 * rather than from the happy path.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  ALLOWED_CLOCK,
  ALLOWED_LABELS,
  validateAndSanitizeFrame,
} from '../src/services/schemaValidator.js';

/** A minimal well-formed payload that individual tests bend one field at a time. */
function payload(overrides = {}) {
  return {
    objects: [],
    text: [],
    floorDetected: false,
    immediateHazard: false,
    hazardDescription: null,
    ...overrides,
  };
}

describe('validateAndSanitizeFrame', () => {
  it('rejects a non-object root', () => {
    // The one failure it cannot sanitize around: the caller has a transport bug, not a bad
    // model day, and silently returning an empty frame would read as "nothing ahead".
    assert.throws(() => validateAndSanitizeFrame(null, 1), /Root must be an object/);
    assert.throws(() => validateAndSanitizeFrame('nope', 1), /Root must be an object/);
    assert.throws(() => validateAndSanitizeFrame(42, 1), /Root must be an object/);
  });

  it('clamps box coordinates into the unit square', () => {
    const frame = validateAndSanitizeFrame(
      payload({
        objects: [
          {
            label: 'door',
            confidence: 2.5,
            box: { x: -1, y: 5, width: 3, height: -0.2 },
            clockPosition: "12 o'clock",
            approxDistanceMeters: 0.1,
          },
        ],
      }),
      12
    );

    const box = frame.objects[0].box;
    assert.equal(box.x, 0, 'negative x clamps to 0');
    assert.equal(box.y, 1, 'y past the edge clamps to 1');
    assert.equal(box.width, 1, 'width clamps to 1, which still fits from x = 0');
    assert.equal(box.height, 0, 'height cannot extend past y = 1');
    assert.equal(frame.objects[0].confidence, 1, 'confidence clamps to 1');
    assert.ok(
      frame.objects[0].approxDistanceMeters >= 0.5,
      'distances below the floor are raised, never left at 0.1m'
    );
  });

  it('keeps x + width and y + height inside the frame', () => {
    const frame = validateAndSanitizeFrame(
      payload({
        objects: [
          {
            label: 'door',
            confidence: 0.9,
            box: { x: 0.8, y: 0.7, width: 0.9, height: 0.9 },
            clockPosition: "12 o'clock",
          },
        ],
      }),
      1
    );
    const box = frame.objects[0].box;
    assert.ok(box.x + box.width <= 1, `x+width was ${box.x + box.width}`);
    assert.ok(box.y + box.height <= 1, `y+height was ${box.y + box.height}`);
  });

  it('drops labels outside the catalog and keeps the ones inside it', () => {
    const frame = validateAndSanitizeFrame(
      payload({
        objects: [
          { label: 'dragon', confidence: 1, box: {}, clockPosition: "12 o'clock" },
          {
            label: 'person',
            confidence: 0.9,
            box: { x: 0.1, y: 0.1, width: 0.2, height: 0.4 },
            clockPosition: "11 o'clock",
          },
        ],
      }),
      5
    );
    assert.deepEqual(
      frame.objects.map((o) => o.label),
      ['person']
    );
  });

  it('keeps the indoor model labels the fine-tuned detector actually emits', () => {
    // Regression: the runtime allowlist once held only the eight generic labels while the type
    // claimed twelve, so every exit sign, washroom and direction arrow the model found was
    // silently discarded here. In an app that walks a blind user to an exit, that is the worst
    // possible thing to drop.
    const indoorLabels = ['exit sign', 'washroom', 'left arrow', 'right arrow'];
    const frame = validateAndSanitizeFrame(
      payload({
        objects: indoorLabels.map((label) => ({
          label,
          confidence: 0.9,
          box: { x: 0.4, y: 0.4, width: 0.1, height: 0.1 },
          clockPosition: "12 o'clock",
        })),
      }),
      5
    );
    assert.deepEqual(frame.objects.map((o) => o.label), indoorLabels);
  });

  it('falls back to 12 o\'clock for an unrecognised clock position', () => {
    const frame = validateAndSanitizeFrame(
      payload({
        objects: [
          {
            label: 'chair',
            confidence: 0.7,
            box: { x: 0.2, y: 0.2, width: 0.1, height: 0.1 },
            clockPosition: 'north',
          },
        ],
      }),
      3
    );
    assert.equal(frame.objects[0].clockPosition, "12 o'clock");
  });

  it('truncates hazard descriptions and OCR text to speakable lengths', () => {
    const frame = validateAndSanitizeFrame(
      payload({
        text: [{ text: `ROOM ${'9'.repeat(80)}`, confidence: 0.9, box: {} }],
        immediateHazard: true,
        hazardDescription: 'H'.repeat(200),
      }),
      2
    );
    assert.ok(frame.hazardDescription.length <= 100);
    assert.ok(frame.text[0].text.length <= 50);
  });

  it('coerces non-array objects/text into empty arrays rather than throwing', () => {
    const frame = validateAndSanitizeFrame(
      { objects: 'nope', text: 42, floorDetected: 1, immediateHazard: 0 },
      1
    );
    assert.deepEqual(frame.objects, []);
    assert.deepEqual(frame.text, []);
    assert.equal(frame.floorDetected, true, 'truthy coerces to boolean');
    assert.equal(frame.immediateHazard, false, 'falsy coerces to boolean');
  });

  it('drops OCR entries with no usable text', () => {
    const frame = validateAndSanitizeFrame(
      payload({
        text: [
          { text: '   ', confidence: 0.9, box: {} },
          { text: 42, confidence: 0.9, box: {} },
          { text: 'EXIT', confidence: 0.9, box: {} },
        ],
      }),
      1
    );
    assert.deepEqual(frame.text.map((t) => t.text), ['EXIT']);
  });

  it('always stamps a timestamp and a numeric latency', () => {
    const before = Date.now();
    const frame = validateAndSanitizeFrame(payload(), 'not-a-number');
    assert.ok(frame.timestamp >= before);
    assert.equal(frame.latencyMs, 0, 'an unparseable latency becomes 0, never NaN');
  });
});

describe('catalog', () => {
  it('exposes the seven clock positions guidance speech depends on', () => {
    assert.equal(ALLOWED_CLOCK.length, 7);
    for (const clock of ["9 o'clock", "12 o'clock", "3 o'clock"]) {
      assert.ok(ALLOWED_CLOCK.includes(clock), `missing ${clock}`);
    }
  });

  it('includes both the generic and the indoor-model labels', () => {
    for (const label of ['door', 'stairs', 'trashcan', 'exit sign', 'washroom']) {
      assert.ok(ALLOWED_LABELS.includes(label), `missing ${label}`);
    }
  });
});
