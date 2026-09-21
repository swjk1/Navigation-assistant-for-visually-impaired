/**
 * Guards the single-source-of-truth rule for perception catalogs.
 *
 * `src/types/perception.ts` derives its unions from `constants/perceptionCatalog.js`, so the
 * type and the runtime allowlist cannot drift by construction. What a type cannot catch is a
 * label that exists in both but that some downstream layer has no handling for - which is the
 * same silent drop one layer further along. These tests cover that second gap.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  ALLOWED_LABELS,
  NAV_ANCHOR_LABELS,
  OBSTACLE_LABELS,
} from '../src/constants/perceptionCatalog.js';
import { listSpeakableLines } from '../src/services/teammateHandoff.js';
import { validateAndSanitizeFrame } from '../src/services/schemaValidator.js';

/** One frame containing exactly one object with the given label. */
function frameWith(label) {
  return validateAndSanitizeFrame(
    {
      objects: [
        {
          label,
          confidence: 0.9,
          box: { x: 0.45, y: 0.4, width: 0.1, height: 0.2 },
          clockPosition: "12 o'clock",
          approxDistanceMeters: 3,
        },
      ],
      text: [],
      floorDetected: true,
      immediateHazard: false,
    },
    5
  );
}

describe('catalog alignment', () => {
  it('lists every anchor and obstacle label in the master allowlist', () => {
    for (const label of [...NAV_ANCHOR_LABELS, ...OBSTACLE_LABELS]) {
      assert.ok(
        ALLOWED_LABELS.includes(label),
        `"${label}" is an anchor/obstacle but the sanitizer would drop it`
      );
    }
  });

  it('keeps anchors and obstacles disjoint', () => {
    // A label in both sets would be announced twice per frame, once as a destination and once
    // as something to avoid.
    const overlap = NAV_ANCHOR_LABELS.filter((l) => OBSTACLE_LABELS.includes(l));
    assert.deepEqual(overlap, []);
  });

  it('produces a speakable line for every anchor label', () => {
    // An anchor the perception layer detects but guidance has no phrase for is never spoken,
    // which for the user is indistinguishable from never detecting it.
    for (const label of NAV_ANCHOR_LABELS) {
      const lines = listSpeakableLines(frameWith(label));
      assert.ok(
        lines.length > 0,
        `"${label}" is a navigation anchor but produces no speakable line`
      );
    }
  });

  it('produces a speakable line for every obstacle label', () => {
    for (const label of OBSTACLE_LABELS) {
      const lines = listSpeakableLines(frameWith(label));
      assert.ok(
        lines.length > 0,
        `"${label}" is an obstacle but produces no speakable line`
      );
    }
  });

  it('survives the sanitizer for every label it claims to allow', () => {
    for (const label of ALLOWED_LABELS) {
      const frame = frameWith(label);
      assert.equal(
        frame.objects.length,
        1,
        `"${label}" is in ALLOWED_LABELS but the sanitizer dropped it`
      );
    }
  });
});
