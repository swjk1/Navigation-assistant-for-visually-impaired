import type { ClockDirection, DetectedObject, OCRDetection, PerceptionFrame } from '@/types/perception';
import type {
  SemanticDirection,
  SemanticObservation,
} from 'navigation-native';

/**
 * The seam between Person 1 (perception) and Person 2 (mapping + planning).
 *
 * Person 1 produces a `PerceptionFrame` — what the camera can see. The engine consumes
 * `SemanticObservation[]` — evidence that biases which unexplored branch to try, and which can
 * turn into a destination once it has a world position.
 *
 * Neither side imports the other; this module is the only place that knows both.
 *
 * What the engine deliberately ignores:
 *  - `approxDistanceMeters` — it resolves depth itself from ARCore, which beats a monocular guess
 *  - `floorDetected` — it estimates the floor plane from the depth cloud
 *  - `immediateHazard` — that reaches the guidance layer directly; see the note at the bottom
 */

/** Clock face → the coarse direction the engine reasons with. */
const DIRECTION_BY_CLOCK: Record<ClockDirection, SemanticDirection> = {
  "9 o'clock": 'LEFT',
  "10 o'clock": 'LEFT',
  "11 o'clock": 'FORWARD',
  "12 o'clock": 'FORWARD',
  "1 o'clock": 'FORWARD',
  "2 o'clock": 'RIGHT',
  "3 o'clock": 'RIGHT',
};

/** Centre of a bounding box, in the 0..1 image space the engine expects. */
function centreOf(box: OCRDetection['box']) {
  return {
    normalizedX: box.x + box.width / 2,
    normalizedY: box.y + box.height / 2,
  };
}

/**
 * "ROOM 204" / "Rooms 300-349" → a room label or a range.
 *
 * A range on a sign is directional evidence ("that way for the 300s"); a bare label is a place.
 * `TargetMatcher` in the engine already matches "ROOM 204" against a `Room("204")` target by its
 * numeric part, so the raw OCR text can be passed through as the label.
 */
const RANGE_PATTERN = /(\d{2,4})\s*(?:-|–|—|to)\s*(\d{2,4})/;

function rangeFrom(
  detection: OCRDetection,
  direction: SemanticDirection
): SemanticObservation | null {
  const match = RANGE_PATTERN.exec(detection.text);
  if (!match) return null;
  const min = Number(match[1]);
  const max = Number(match[2]);
  if (!Number.isFinite(min) || !Number.isFinite(max) || max < min) return null;
  return {
    type: 'ROOM_RANGE',
    min,
    max,
    direction,
    confidence: detection.confidence,
  };
}

/**
 * Direction implied by where something sits in the image, used for signs, which have no
 * clock position of their own. Left third / middle / right third.
 */
function directionFromImageX(normalizedX: number): SemanticDirection {
  if (normalizedX < 0.35) return 'LEFT';
  if (normalizedX > 0.65) return 'RIGHT';
  return 'FORWARD';
}

function fromObject(object: DetectedObject): SemanticObservation | null {
  const direction = DIRECTION_BY_CLOCK[object.clockPosition] ?? 'FORWARD';
  const centre = centreOf(object.box);
  const base = { confidence: object.confidence, ...centre };

  switch (object.label) {
    case 'stairs':
      return { type: 'STAIRS', ...base };
    case 'elevator':
      return { type: 'ELEVATOR', ...base };
    case 'exit sign':
      // A sign pointing the way out, not the way out itself - so it is directional evidence.
      // Its own position is still useful, since walking towards it is the right move.
      return { type: 'EXIT', direction, ...base };
    case 'left arrow':
      // A wayfinding arrow says "that way" about whatever is written beside it. Without OCR to
      // pair it with, treat it as a weak directional exit hint rather than dropping it.
      return { type: 'EXIT', direction: 'LEFT', confidence: object.confidence * 0.4 };
    case 'right arrow':
      return { type: 'EXIT', direction: 'RIGHT', confidence: object.confidence * 0.4 };
    case 'washroom':
      // A washroom door is a room the user may be looking for, and always sits on a wall the
      // corridor runs along - useful as a landmark even when the plate is unreadable.
      return { type: 'ROOM', label: 'washroom', ...base };
    case 'door':
      // Reported as a door, not an exit. The engine scores it as partial evidence for an exit or
      // a room without ever treating it as a match for either - a door may lead outside, into a
      // room, or into a cupboard.
      return { type: 'DOOR', direction, ...base };
    // person / chair / trashcan / wall are obstacles, not landmarks. The engine already sees
    // them geometrically through depth, so re-reporting them here would add nothing.
    default:
      return null;
  }
}

function fromText(detection: OCRDetection): SemanticObservation | null {
  const centre = centreOf(detection.box);
  const direction = directionFromImageX(centre.normalizedX);

  const range = rangeFrom(detection, direction);
  if (range) return range;

  const text = detection.text.trim();
  if (text.length === 0) return null;

  if (/\b(exit|way\s*out)\b/i.test(text)) {
    return { type: 'EXIT', direction, confidence: detection.confidence, ...centre };
  }
  if (/\bstairs?\b/i.test(text)) {
    return { type: 'STAIRS', confidence: detection.confidence, ...centre };
  }
  if (/\b(elevator|lift)\b/i.test(text)) {
    return { type: 'ELEVATOR', confidence: detection.confidence, ...centre };
  }
  // Anything containing a number is treated as a room plate. TargetMatcher pulls the digits out.
  if (/\d/.test(text)) {
    return { type: 'ROOM', label: text, confidence: detection.confidence, ...centre };
  }
  return null;
}

/**
 * Translates one perception frame into observations for the engine.
 *
 * NOTE ON TIMESTAMPS: `timestampNs` is deliberately NOT set from `PerceptionFrame.timestamp`.
 * The engine expects ARCore's `Frame.getTimestamp()` — nanoseconds since boot — while
 * `PerceptionFrame.timestamp` is a wall clock in milliseconds. Passing one for the other puts
 * every observation billions of nanoseconds out of range and the depth association rejects them
 * all, silently. Omitting it makes the engine use its most recent depth frame, which is correct
 * whenever perception is running off the live ARCore feed.
 */
export function toSemanticObservations(frame: PerceptionFrame): SemanticObservation[] {
  const observations: SemanticObservation[] = [];
  for (const object of frame.objects) {
    const observation = fromObject(object);
    if (observation) observations.push(observation);
  }
  for (const detection of frame.text) {
    const observation = fromText(detection);
    if (observation) observations.push(observation);
  }
  return observations;
}

/**
 * True when perception is reporting something the user must be warned about right now.
 *
 * The engine detects hazards geometrically (something solid blocking the path); perception
 * detects them visually (descending stairs, a person approaching). Both end up driving the same
 * `hazard.detected` flag in the guidance contract, so whoever wires the UI has to OR them
 * together rather than letting one overwrite the other.
 */
export function perceptionHazard(frame: PerceptionFrame): string | null {
  return frame.immediateHazard ? (frame.hazardDescription ?? 'Hazard ahead') : null;
}
