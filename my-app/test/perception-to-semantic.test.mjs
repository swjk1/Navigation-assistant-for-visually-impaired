/**
 * The seam between perception and navigation.
 *
 * Two things cross it that are easy to get silently wrong: WHEN the image was taken (the engine
 * resolves image positions against that frame's depth, not whatever the camera sees seconds
 * later), and WHICH WAY UP the coordinates are (models see the upright image, depth stays in the
 * sensor's orientation).
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  toSemanticObservations,
  uprightToSensor,
} from '../src/perception/perceptionToSemantic.ts';

/** The forward mapping ArFrameSource applies: rotate the sensor image clockwise. */
function sensorToUpright({ x, y }, degrees) {
  switch (degrees) {
    case 90:
      return { x: 1 - y, y: x };
    case 180:
      return { x: 1 - x, y: 1 - y };
    case 270:
      return { x: y, y: 1 - x };
    default:
      return { x, y };
  }
}

function frame({ objects = [], text = [] } = {}) {
  return { objects, text, timestamp: Date.now() };
}

const box = (x, y, width = 0.1, height = 0.1) => ({ x, y, width, height });

describe('uprightToSensor', () => {
  for (const degrees of [0, 90, 180, 270]) {
    it(`undoes a ${degrees} degree capture rotation`, () => {
      const sensor = { x: 0.2, y: 0.7 };
      const back = uprightToSensor(sensorToUpright(sensor, degrees), degrees);
      assert.ok(Math.abs(back.x - sensor.x) < 1e-9 && Math.abs(back.y - sensor.y) < 1e-9);
    });
  }

  it('maps the top of a portrait image to the sensor image left edge for the usual 90 degrees', () => {
    // Back cameras are mounted landscape at 90 degrees: turning the sensor image a quarter turn
    // clockwise to stand it upright brings its left edge to the top of the screen.
    const sensor = uprightToSensor({ x: 0.5, y: 0 }, 90);
    assert.deepEqual(sensor, { x: 0, y: 0.5 });
  });
});

describe('toSemanticObservations', () => {
  it('stamps every observation with the capture timestamp', () => {
    const observations = toSemanticObservations(
      frame({
        objects: [
          { label: 'door', confidence: 0.8, box: box(0.4, 0.4), clockPosition: "12 o'clock" },
          { label: 'left arrow', confidence: 0.9, box: box(0.1, 0.4), clockPosition: "10 o'clock" },
        ],
        text: [
          { text: 'Rooms 300-349', confidence: 0.9, box: box(0.8, 0.2) },
          { text: '314', confidence: 0.9, box: box(0.5, 0.3) },
        ],
      }),
      { timestampNs: 123_456_789_000, rotationDegrees: 90 }
    );
    assert.equal(observations.length, 4);
    for (const observation of observations) {
      assert.equal(observation.timestampNs, 123_456_789_000, observation.type);
    }
  });

  it('reports image positions in sensor orientation and directions in upright orientation', () => {
    // A sign on the right of what the user sees, captured at the usual 90 degrees.
    const [exit] = toSemanticObservations(
      frame({ text: [{ text: 'EXIT', confidence: 0.9, box: box(0.8, 0.45) }] }),
      { timestampNs: 1, rotationDegrees: 90 }
    );
    assert.equal(exit.type, 'EXIT');
    assert.equal(exit.direction, 'RIGHT', 'right of the screen is to the user\'s right');
    const expected = uprightToSensor({ x: 0.85, y: 0.5 }, 90);
    assert.ok(Math.abs(exit.normalizedX - expected.x) < 1e-9);
    assert.ok(Math.abs(exit.normalizedY - expected.y) < 1e-9);
  });

  it('omits the timestamp rather than inventing one when the capture has none', () => {
    const [door] = toSemanticObservations(
      frame({
        objects: [{ label: 'door', confidence: 0.8, box: box(0.4, 0.4), clockPosition: "12 o'clock" }],
      })
    );
    assert.equal('timestampNs' in door, false);
  });
});
