/**
 * The VLM gate decides when a frame is worth a Gemini round-trip.
 *
 * Getting it wrong is expensive in both directions: too eager burns the free-tier quota and the
 * latency budget on frames the on-device models already answered, too shy means a blind user
 * walks past a hazard the native model has no class for. All the functions here are pure, so
 * they are cheap to pin down precisely.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  clockFromBox,
  distanceFromBox,
  explainVlmGate,
  isMlKitUnsure,
  isYoloUnsure,
  mergeNativeToRaw,
  shouldInvokeVlm,
} from '../src/services/hybridPerception.js';

/** A native result that looks entirely healthy. */
function healthyNative(overrides = {}) {
  return {
    objects: [],
    text: [{ text: 'ROOM 204', confidence: 0.92, box: {} }],
    yoloMs: 40,
    ocrMs: 25,
    totalMs: 70,
    modelLoaded: true,
    yoloError: null,
    ocrError: null,
    platform: 'android',
    ...overrides,
  };
}

describe('clockFromBox', () => {
  it('maps horizontal position to a clock face', () => {
    const at = (x) => clockFromBox({ x, width: 0 });
    assert.equal(at(0.0), "9 o'clock");
    assert.equal(at(0.2), "10 o'clock");
    assert.equal(at(0.35), "11 o'clock");
    assert.equal(at(0.5), "12 o'clock");
    assert.equal(at(0.65), "1 o'clock");
    assert.equal(at(0.8), "2 o'clock");
    assert.equal(at(0.95), "3 o'clock");
  });

  it('uses the box centre, not its left edge', () => {
    // A wide box starting on the left but centred ahead is straight ahead.
    assert.equal(clockFromBox({ x: 0.3, width: 0.4 }), "12 o'clock");
  });

  it('treats a missing box as straight ahead', () => {
    assert.equal(clockFromBox(undefined), "9 o'clock");
    assert.equal(clockFromBox({}), "9 o'clock");
  });
});

describe('distanceFromBox', () => {
  it('reads a taller box as nearer', () => {
    assert.ok(distanceFromBox({ height: 0.8 }) < distanceFromBox({ height: 0.1 }));
  });

  it('clamps to a plausible indoor range', () => {
    assert.ok(distanceFromBox({ height: 1 }) >= 0.5);
    assert.ok(distanceFromBox({ height: 0.0001 }) <= 12);
  });
});

describe('isMlKitUnsure', () => {
  it('is unsure when OCR errored', () => {
    assert.equal(isMlKitUnsure(healthyNative({ ocrError: 'boom' })), true);
  });

  it('is unsure when the best text is low confidence', () => {
    const native = healthyNative({ text: [{ text: 'R00M 2O4', confidence: 0.2, box: {} }] });
    assert.equal(isMlKitUnsure(native), true);
  });

  it('is NOT unsure when OCR simply found nothing', () => {
    // A blank corridor legitimately has no text. Treating that as uncertainty would call Gemini
    // on almost every frame.
    assert.equal(isMlKitUnsure(healthyNative({ text: [] })), false);
  });

  it('is not unsure on confident text', () => {
    assert.equal(isMlKitUnsure(healthyNative()), false);
  });
});

describe('isYoloUnsure', () => {
  it('is unsure on a real detector failure', () => {
    assert.equal(isYoloUnsure(healthyNative({ yoloError: 'onnx failed' })), true);
  });

  it('is unsure when the native module is absent', () => {
    assert.equal(isYoloUnsure(healthyNative({ platform: 'unavailable' })), true);
  });

  it('is unsure when the model failed to load on a real device', () => {
    assert.equal(
      isYoloUnsure(healthyNative({ modelLoaded: false, platform: 'android' })),
      true
    );
  });

  it('is NOT unsure merely because nothing was detected', () => {
    // Empty detections are expected when only doors and walls are in view.
    assert.equal(isYoloUnsure(healthyNative({ objects: [] })), false);
  });
});

describe('shouldInvokeVlm', () => {
  it('stays closed on a healthy frame', () => {
    assert.equal(shouldInvokeVlm(healthyNative()), false);
  });

  it('opens on force and on vlmOnTop', () => {
    assert.equal(shouldInvokeVlm(healthyNative(), { forceVlm: true }), true);
    assert.equal(shouldInvokeVlm(healthyNative(), { vlmOnTop: true }), true);
  });

  it('opens on native failure', () => {
    assert.equal(shouldInvokeVlm(healthyNative({ yoloError: 'x' })), true);
    assert.equal(shouldInvokeVlm(healthyNative({ ocrError: 'x' })), true);
  });

  it('opens on caution keywords in OCR', () => {
    const native = healthyNative({
      text: [{ text: 'CAUTION WET FLOOR', confidence: 0.95, box: {} }],
    });
    assert.equal(shouldInvokeVlm(native), true);
  });

  it('opens on every Nth frame only when a frame index is supplied', () => {
    assert.equal(shouldInvokeVlm(healthyNative(), { everyNth: 5, frameIndex: 10 }), true);
    assert.equal(shouldInvokeVlm(healthyNative(), { everyNth: 5, frameIndex: 11 }), false);
    assert.equal(shouldInvokeVlm(healthyNative(), { everyNth: 5 }), false);
  });
});

describe('explainVlmGate', () => {
  it('explains every decision shouldInvokeVlm can make', () => {
    // A gate you cannot explain is a gate you cannot tune, so the two must stay in lockstep.
    const cases = [
      [healthyNative(), {}, 'skipped'],
      [healthyNative(), { forceVlm: true }, 'forced'],
      [healthyNative(), { vlmOnTop: true }, 'vlm_on_top'],
      [healthyNative({ ocrError: 'x' }), {}, 'mlkit_error'],
      [healthyNative({ text: [{ text: 'x', confidence: 0.1, box: {} }] }), {}, 'mlkit_low_confidence'],
      [healthyNative({ yoloError: 'x' }), {}, 'yolo_error'],
      [healthyNative({ platform: 'unavailable' }), {}, 'native_unavailable'],
      [healthyNative({ text: [{ text: 'DANGER', confidence: 0.99, box: {} }] }), {}, 'ocr_caution_keywords'],
      [healthyNative(), { everyNth: 3, frameIndex: 3 }, 'every_nth'],
    ];
    for (const [native, policy, expected] of cases) {
      assert.equal(explainVlmGate(native, policy), expected);
    }
  });

  it('agrees with shouldInvokeVlm about whether the gate opened', () => {
    const cases = [
      [healthyNative(), {}],
      [healthyNative(), { forceVlm: true }],
      [healthyNative({ ocrError: 'x' }), {}],
      [healthyNative({ text: [{ text: 'DANGER', confidence: 0.99, box: {} }] }), {}],
      [healthyNative(), { everyNth: 3, frameIndex: 3 }],
    ];
    for (const [native, policy] of cases) {
      const opened = shouldInvokeVlm(native, policy);
      const reason = explainVlmGate(native, policy);
      assert.equal(
        opened,
        reason !== 'skipped',
        `gate said ${opened} but explained it as "${reason}"`
      );
    }
  });
});

describe('mergeNativeToRaw', () => {
  it('derives clock and distance for native detections', () => {
    const raw = mergeNativeToRaw(
      healthyNative({
        objects: [{ label: 'door', confidence: 0.8, box: { x: 0.45, y: 0.3, width: 0.1, height: 0.4 } }],
      })
    );
    assert.equal(raw.objects[0].clockPosition, "12 o'clock");
    assert.ok(raw.objects[0].approxDistanceMeters > 0);
  });

  it('does not assume a floor when the VLM was skipped', () => {
    // "floorDetected" is a judgement only the VLM makes. Defaulting it to true would tell the
    // navigation layer the ground ahead is walkable when nothing ever looked.
    assert.equal(mergeNativeToRaw(healthyNative()).floorDetected, false);
  });

  it('takes the floor verdict from the VLM when there is one', () => {
    assert.equal(mergeNativeToRaw(healthyNative(), { floorDetected: true }).floorDetected, true);
  });

  it('raises a hazard from OCR caution keywords even without a VLM', () => {
    const raw = mergeNativeToRaw(
      healthyNative({ text: [{ text: 'CAUTION WET FLOOR', confidence: 0.95, box: {} }] })
    );
    assert.equal(raw.immediateHazard, true);
    assert.ok(raw.hazardDescription);
  });

  it('lets the VLM fill labels the native model missed', () => {
    const raw = mergeNativeToRaw(healthyNative({ objects: [] }), {
      objects: [{ label: 'door', confidence: 0.7, box: { x: 0.4, y: 0.3, width: 0.2, height: 0.4 } }],
    });
    assert.deepEqual(raw.objects.map((o) => o.label), ['door']);
  });

  it('does not let the VLM duplicate a label the native model already found', () => {
    const native = healthyNative({
      objects: [{ label: 'door', confidence: 0.9, box: { x: 0.4, y: 0.3, width: 0.2, height: 0.4 } }],
    });
    const raw = mergeNativeToRaw(native, {
      objects: [{ label: 'door', confidence: 0.5, box: { x: 0.1, y: 0.1, width: 0.1, height: 0.1 } }],
    });
    assert.equal(raw.objects.filter((o) => o.label === 'door').length, 1);
  });

  it('only falls back to VLM text when ML Kit found none', () => {
    const withText = mergeNativeToRaw(healthyNative(), {
      text: [{ text: 'GEMINI GUESS', confidence: 0.5, box: {} }],
    });
    assert.deepEqual(withText.text.map((t) => t.text), ['ROOM 204']);

    const withoutText = mergeNativeToRaw(healthyNative({ text: [] }), {
      text: [{ text: 'GEMINI GUESS', confidence: 0.5, box: {} }],
    });
    assert.deepEqual(withoutText.text.map((t) => t.text), ['GEMINI GUESS']);
  });

  it('tolerates a null native result', () => {
    const raw = mergeNativeToRaw(null);
    assert.deepEqual(raw.objects, []);
    assert.deepEqual(raw.text, []);
    assert.equal(raw.immediateHazard, false);
  });
});
