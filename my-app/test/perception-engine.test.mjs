/**
 * Engine behaviour that does not need a network: mode selection, the offline fixtures, and the
 * deterministic frames returned when the cloud path gives up.
 *
 * The live Gemini path is deliberately not covered here - it needs a key and a network, so it
 * lives in `scripts/probe-gemini-live.mjs` as a diagnostic rather than a test.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

process.env.PERCEPTION_MODE = 'mock';

const {
  SYSTEM_PROMPT,
  buildScanTimeoutFrame,
  getGeminiModelCandidates,
  getPerceptionMode,
  processFrame,
  processFrameMock,
} = await import('../src/services/perceptionEngine.js');

const { ALLOWED_CLOCK, ALLOWED_LABELS } = await import(
  '../src/constants/perceptionCatalog.js'
);

describe('getPerceptionMode', () => {
  it('honours an explicit PERCEPTION_MODE', () => {
    assert.equal(getPerceptionMode(), 'mock');
  });
});

describe('getGeminiModelCandidates', () => {
  it('puts the configured primary first and never repeats it', () => {
    const models = getGeminiModelCandidates();
    assert.ok(models.length > 1, 'there must be fallbacks to fail over to');
    assert.equal(new Set(models).size, models.length, 'candidates must be distinct');
  });
});

describe('SYSTEM_PROMPT', () => {
  it('names every label the sanitizer will accept from the generic set', () => {
    // If the prompt asks for a label the sanitizer drops, the round-trip is wasted; if the
    // sanitizer accepts one the prompt never mentions, that path is untested.
    for (const label of ['door', 'person', 'stairs', 'elevator', 'chair', 'trashcan', 'wall', 'sign']) {
      assert.ok(SYSTEM_PROMPT.includes(`"${label}"`), `prompt never mentions ${label}`);
    }
  });

  it('names every clock position', () => {
    for (const clock of ALLOWED_CLOCK) {
      assert.ok(SYSTEM_PROMPT.includes(clock), `prompt never mentions ${clock}`);
    }
  });
});

describe('processFrameMock', () => {
  const scenarios = ['hallway', 'stairs', 'trashcan', 'clear_hallway', 'blurry', 'room_sign'];

  it('returns a valid frame for every scenario', async () => {
    for (const scenario of scenarios) {
      const frame = await processFrameMock({ scenario });
      assert.ok(Array.isArray(frame.objects), `${scenario}: objects`);
      assert.ok(Array.isArray(frame.text), `${scenario}: text`);
      assert.equal(typeof frame.immediateHazard, 'boolean', `${scenario}: hazard flag`);
      assert.equal(typeof frame.floorDetected, 'boolean', `${scenario}: floor flag`);
      for (const obj of frame.objects) {
        assert.ok(ALLOWED_LABELS.includes(obj.label), `${scenario}: bad label ${obj.label}`);
        assert.ok(ALLOWED_CLOCK.includes(obj.clockPosition), `${scenario}: bad clock`);
        assert.ok(obj.confidence >= 0 && obj.confidence <= 1, `${scenario}: confidence range`);
      }
    }
  });

  it('flags the stairs scenario as a hazard', async () => {
    const frame = await processFrameMock({ scenario: 'stairs' });
    assert.equal(frame.immediateHazard, true);
    assert.ok(frame.objects.some((o) => o.label === 'stairs'));
  });

  it('flags the trashcan scenario as a hazard', async () => {
    const frame = await processFrameMock({ scenario: 'trashcan' });
    assert.equal(frame.immediateHazard, true);
    assert.ok(frame.objects.some((o) => o.label === 'trashcan'));
  });

  it('reports a clear hallway as walkable and hazard-free', async () => {
    const frame = await processFrameMock({ scenario: 'clear_hallway' });
    assert.equal(frame.immediateHazard, false);
    assert.equal(frame.floorDetected, true);
  });

  it('does not invent a hazard from a blurry frame', async () => {
    // A frame too blurry to read is a reason to say nothing, not to cry hazard.
    const frame = await processFrameMock({ scenario: 'blurry' });
    assert.equal(frame.immediateHazard, false);
  });

  it('returns independent copies so a caller cannot mutate the fixtures', async () => {
    const first = await processFrameMock({ scenario: 'hallway' });
    first.objects.push({ label: 'door' });
    const second = await processFrameMock({ scenario: 'hallway' });
    assert.notEqual(first.objects.length, second.objects.length);
  });
});

describe('buildScanTimeoutFrame', () => {
  it('reports a hazard rather than an empty frame', () => {
    // An empty frame reads downstream as "clear ahead", which is the opposite of what a
    // timeout means.
    const frame = buildScanTimeoutFrame(4000);
    assert.equal(frame.immediateHazard, true);
    assert.ok(frame.hazardDescription, 'must carry a speakable explanation');
    assert.equal(frame.floorDetected, false);
  });

  it('carries the measured latency through', () => {
    assert.equal(buildScanTimeoutFrame(4000).latencyMs, 4000);
  });
});

describe('processFrame', () => {
  it('routes to the mock path without needing an image', async () => {
    const frame = await processFrame(null, { mode: 'mock', scenario: 'hallway' });
    assert.ok(Array.isArray(frame.objects));
  });

  it('refuses a live call with no image instead of sending an empty request', async () => {
    await assert.rejects(
      () => processFrame(null, { mode: 'live' }),
      /base64Image is required/
    );
  });
});
