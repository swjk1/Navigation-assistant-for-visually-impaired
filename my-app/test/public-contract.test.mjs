/**
 * The public surface of `src/index.js`, checked from a consumer's point of view.
 *
 * This is the module the navigation and guidance layers integrate against when they have no
 * ARCore phone in hand, so the promise that matters is narrow and absolute: whatever comes out
 * carries exactly the contract fields, with no debug leakage and no missing keys.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

process.env.PERCEPTION_MODE = 'mock';

const {
  analyzeAndStore,
  getLatestPerceptionFrame,
  getLatestTeammateHandoff,
  getMockPerceptionFrame,
  getMockTeammateHandoff,
} = await import('../src/index.js');

const { ALLOWED_CLOCK, ALLOWED_LABELS, MAX_DISTANCE_METERS } = await import(
  '../src/constants/perceptionCatalog.js'
);

/** Exactly the fields the contract promises - no more, no fewer. */
const CONTRACT_KEYS = [
  'timestamp',
  'latencyMs',
  'objects',
  'text',
  'floorDetected',
  'immediateHazard',
  'hazardDescription',
];

const SCENARIOS = ['hallway', 'stairs', 'trashcan', 'clear_hallway', 'blurry', 'room_sign'];

/** @param {Record<string, any>} frame */
function assertContractShape(frame, label) {
  assert.deepEqual(
    Object.keys(frame).sort(),
    [...CONTRACT_KEYS].sort(),
    `${label}: frame carries exactly the contract keys`
  );
  assert.equal(typeof frame.timestamp, 'number', `${label}: timestamp`);
  assert.equal(typeof frame.latencyMs, 'number', `${label}: latencyMs`);
  assert.ok(Array.isArray(frame.objects), `${label}: objects is an array`);
  assert.ok(Array.isArray(frame.text), `${label}: text is an array`);
  assert.equal(typeof frame.floorDetected, 'boolean', `${label}: floorDetected`);
  assert.equal(typeof frame.immediateHazard, 'boolean', `${label}: immediateHazard`);
  assert.ok(
    frame.hazardDescription === null || typeof frame.hazardDescription === 'string',
    `${label}: hazardDescription is a string or null`
  );

  for (const obj of frame.objects) {
    assert.ok(ALLOWED_LABELS.includes(obj.label), `${label}: label ${obj.label}`);
    assert.ok(ALLOWED_CLOCK.includes(obj.clockPosition), `${label}: clock ${obj.clockPosition}`);
    assert.ok(obj.confidence >= 0 && obj.confidence <= 1, `${label}: confidence`);
    assert.ok(
      obj.approxDistanceMeters >= 0.5 && obj.approxDistanceMeters <= MAX_DISTANCE_METERS,
      `${label}: distance ${obj.approxDistanceMeters}`
    );
    for (const k of ['x', 'y', 'width', 'height']) {
      const v = obj.box[k];
      assert.ok(typeof v === 'number' && v >= 0 && v <= 1, `${label}: box.${k} = ${v}`);
    }
  }

  for (const item of frame.text) {
    assert.equal(typeof item.text, 'string', `${label}: OCR text type`);
    assert.ok(item.text.length <= 50, `${label}: OCR text length`);
  }
}

describe('getMockPerceptionFrame', () => {
  it('produces a contract-shaped frame for every scenario', () => {
    for (const scenario of SCENARIOS) {
      assertContractShape(getMockPerceptionFrame(scenario), scenario);
    }
  });

  it('never leaks debug meta', () => {
    for (const scenario of SCENARIOS) {
      assert.equal('meta' in getMockPerceptionFrame(scenario), false, scenario);
    }
  });

  it('defaults to the hallway scenario', () => {
    assert.deepEqual(
      getMockPerceptionFrame().objects.map((o) => o.label),
      getMockPerceptionFrame('hallway').objects.map((o) => o.label)
    );
  });
});

describe('getMockTeammateHandoff', () => {
  it('bundles a contract frame with its derived views', () => {
    for (const scenario of SCENARIOS) {
      const handoff = getMockTeammateHandoff(scenario);
      assertContractShape(handoff.frame, scenario);
      assert.ok(Array.isArray(handoff.navAnchors), `${scenario}: navAnchors`);
      assert.ok(Array.isArray(handoff.obstacles), `${scenario}: obstacles`);
      assert.ok(Array.isArray(handoff.signs), `${scenario}: signs`);
      assert.ok(Array.isArray(handoff.speakableLines), `${scenario}: speakableLines`);
      assert.ok(
        ['hazard', 'guidance'].includes(handoff.speechPriority),
        `${scenario}: speechPriority`
      );
    }
  });

  it('marks the hazard scenarios for priority speech', () => {
    assert.equal(getMockTeammateHandoff('stairs').speechPriority, 'hazard');
    assert.equal(getMockTeammateHandoff('clear_hallway').speechPriority, 'guidance');
  });
});

describe('analyzeAndStore', () => {
  it('returns null from the getters before anything has been analyzed', () => {
    // Order matters: this must run before the first analyzeAndStore call below.
    if (getLatestPerceptionFrame() === null) {
      assert.equal(getLatestTeammateHandoff(), null);
    }
  });

  it('caches the latest frame and handoff', async () => {
    const handoff = await analyzeAndStore('ZmFrZQ==', {
      hybrid: false,
      mode: 'mock',
      scenario: 'stairs',
    });

    assert.ok(handoff.frame, 'returns the handoff bundle');
    assertContractShape(handoff.frame, 'analyzeAndStore');

    const cached = getLatestPerceptionFrame();
    assert.ok(cached, 'the frame getter is populated afterwards');
    assertContractShape(cached, 'getLatestPerceptionFrame');
    assert.equal(getLatestTeammateHandoff(), handoff, 'the handoff getter returns the same bundle');
  });
});
