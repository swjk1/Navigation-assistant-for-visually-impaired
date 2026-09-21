/**
 * The handoff layer turns one verified frame into the few short facts the navigation and
 * guidance layers consume. Its failure mode is not a crash - it is announcing the wrong thing,
 * or announcing nothing, so these tests read the produced strings rather than just the shapes.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  buildTeammateHandoff,
  listNavAnchors,
  listObstacles,
  listReadableSigns,
  listSpeakableLines,
  toPublicPerceptionFrame,
} from '../src/services/teammateHandoff.js';
import { MAX_SPEAKABLE_LINES } from '../src/constants/perceptionCatalog.js';
import { validateAndSanitizeFrame } from '../src/services/schemaValidator.js';

function object(label, distance, clock = "12 o'clock") {
  return {
    label,
    confidence: 0.9,
    box: { x: 0.45, y: 0.4, width: 0.1, height: 0.2 },
    clockPosition: clock,
    approxDistanceMeters: distance,
  };
}

function frame(overrides = {}) {
  return validateAndSanitizeFrame(
    {
      objects: [],
      text: [],
      floorDetected: true,
      immediateHazard: false,
      hazardDescription: null,
      ...overrides,
    },
    10
  );
}

describe('toPublicPerceptionFrame', () => {
  it('returns null for anything that is not an object', () => {
    assert.equal(toPublicPerceptionFrame(null), null);
    assert.equal(toPublicPerceptionFrame('frame'), null);
  });

  it('strips debug meta from the public view', () => {
    const withMeta = { ...frame(), meta: { pipeline: 'debug-only' } };
    assert.equal('meta' in toPublicPerceptionFrame(withMeta), false);
  });
});

describe('listNavAnchors', () => {
  it('returns anchors nearest first', () => {
    const f = frame({
      objects: [object('door', 8), object('elevator', 2), object('stairs', 5)],
    });
    assert.deepEqual(
      listNavAnchors(f).map((o) => o.label),
      ['elevator', 'stairs', 'door']
    );
  });

  it('excludes obstacles', () => {
    const f = frame({ objects: [object('person', 1), object('door', 4)] });
    assert.deepEqual(listNavAnchors(f).map((o) => o.label), ['door']);
  });

  it('includes the indoor-model anchors', () => {
    const f = frame({ objects: [object('exit sign', 6), object('washroom', 3)] });
    assert.deepEqual(
      listNavAnchors(f).map((o) => o.label),
      ['washroom', 'exit sign']
    );
  });
});

describe('listObstacles', () => {
  it('returns only obstacle labels', () => {
    const f = frame({
      objects: [object('person', 1), object('chair', 2), object('door', 3)],
    });
    assert.deepEqual(
      listObstacles(f).map((o) => o.label),
      ['person', 'chair']
    );
  });
});

describe('listReadableSigns', () => {
  it('returns the OCR strings in order', () => {
    const f = frame({
      text: [
        { text: 'ROOM 204', confidence: 0.9, box: {} },
        { text: 'EXIT', confidence: 0.8, box: {} },
      ],
    });
    assert.deepEqual(listReadableSigns(f), ['ROOM 204', 'EXIT']);
  });
});

describe('listSpeakableLines', () => {
  it('puts the hazard line first', () => {
    const f = frame({
      objects: [object('door', 2)],
      immediateHazard: true,
      hazardDescription: 'Stairs down ahead.',
    });
    assert.equal(listSpeakableLines(f)[0], 'Stairs down ahead.');
  });

  it('falls back to a safe hazard phrase when the description is missing', () => {
    const f = frame({ immediateHazard: true, hazardDescription: null });
    assert.match(listSpeakableLines(f)[0], /Hazard ahead/);
  });

  it('announces an exit sign rather than silently skipping it', () => {
    // Regression: the anchor loop matched on four hard-coded labels, so anchors added to the
    // catalog later were listed as anchors but never spoken.
    const lines = listSpeakableLines(frame({ objects: [object('exit sign', 4, "11 o'clock")] }));
    assert.equal(lines.length, 1);
    assert.match(lines[0], /^Exit sign at 11 o'clock, about 4\.0 meters\.$/);
  });

  it('announces a washroom', () => {
    const lines = listSpeakableLines(frame({ objects: [object('washroom', 2.5)] }));
    assert.match(lines[0], /^Washroom at 12 o'clock, about 2\.5 meters\.$/);
  });

  it('caps the queue so it stays actionable', () => {
    const f = frame({
      objects: [
        object('door', 1),
        object('elevator', 2),
        object('stairs', 3),
        object('washroom', 4),
        object('exit sign', 5),
        object('person', 6),
        object('chair', 7),
      ],
      immediateHazard: true,
      hazardDescription: 'Careful.',
    });
    assert.equal(listSpeakableLines(f).length, MAX_SPEAKABLE_LINES);
  });

  it('de-duplicates identical lines', () => {
    const f = frame({ objects: [object('door', 3), object('door', 3)] });
    assert.equal(listSpeakableLines(f).length, 1);
  });

  it('returns nothing for an empty frame', () => {
    assert.deepEqual(listSpeakableLines(frame()), []);
  });
});

describe('buildTeammateHandoff', () => {
  it('flags hazard frames so guidance can preempt navigation chatter', () => {
    const hazard = buildTeammateHandoff(
      frame({ immediateHazard: true, hazardDescription: 'Wet floor.' })
    );
    assert.equal(hazard.speechPriority, 'hazard');
    assert.equal(buildTeammateHandoff(frame()).speechPriority, 'guidance');
  });

  it('bundles every derived view of the frame', () => {
    const handoff = buildTeammateHandoff(
      frame({
        objects: [object('door', 3), object('person', 1)],
        text: [{ text: 'ROOM 204', confidence: 0.9, box: {} }],
      })
    );
    assert.deepEqual(handoff.navAnchors.map((o) => o.label), ['door']);
    assert.deepEqual(handoff.obstacles.map((o) => o.label), ['person']);
    assert.deepEqual(handoff.signs, ['ROOM 204']);
    assert.ok(handoff.speakableLines.length > 0);
    assert.ok(handoff.frame);
  });

  it('degrades to empty lists rather than throwing on a null frame', () => {
    const handoff = buildTeammateHandoff(null);
    assert.equal(handoff.frame, null);
    assert.deepEqual(handoff.navAnchors, []);
    assert.deepEqual(handoff.speakableLines, []);
    assert.equal(handoff.speechPriority, 'guidance');
  });
});
