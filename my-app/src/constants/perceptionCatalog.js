/**
 * The ONE runtime source of truth for perception catalogs.
 *
 * `src/types/perception.ts` derives its union types from these arrays rather than restating
 * them, because restating them is exactly how they drifted before: the type listed the
 * fine-tuned model's indoor labels ('exit sign', 'washroom', 'left arrow', 'right arrow') while
 * this allowlist still held only the eight generic ones, so `validateAndSanitizeFrame` silently
 * dropped every exit sign the model found. In an app whose job is walking a blind user to an
 * exit, that is the worst possible thing to drop.
 *
 * Add a label HERE and the type follows automatically. There is nowhere else to add it.
 */

/**
 * Labels the perception layer will accept. Anything else is discarded as model noise.
 *
 * The JSDoc const assertion on each array below is load-bearing: it is what lets
 * `perception.ts` read the exact string literals back out as a union. Without it these are
 * just `string[]`, the derived type collapses to `string`, and nothing is checked.
 */
export const ALLOWED_LABELS = /** @type {const} */ ([
  'door',
  'person',
  'stairs',
  'elevator',
  'chair',
  'trashcan',
  'wall',
  'sign',
  // Emitted by the fine-tuned indoor YOLO26 model (see IndoorLabels.kt). Signs are reported as
  // their SUBJECT rather than flattened to 'sign': an "exit sign" is how a user finds an exit,
  // and the navigation engine can act on that, whereas a generic 'sign' tells it nothing.
  'exit sign',
  'left arrow',
  'right arrow',
  'washroom',
]);

/**
 * Clock facing relative to the user walking forward. 12 o'clock is straight ahead.
 */
export const ALLOWED_CLOCK = /** @type {const} */ ([
  "9 o'clock",
  "10 o'clock",
  "11 o'clock",
  "12 o'clock",
  "1 o'clock",
  "2 o'clock",
  "3 o'clock",
]);

/** Navigation anchors / destinations: things a route can be planned TO. */
export const NAV_ANCHOR_LABELS = /** @type {const} */ ([
  'door',
  'elevator',
  'stairs',
  'sign',
  'exit sign',
  'washroom',
]);

/** Path obstacles: things a route must be planned AROUND. */
export const OBSTACLE_LABELS = /** @type {const} */ ([
  'person',
  'chair',
  'trashcan',
]);

/** Max speakable lines per frame, so the TTS queue stays short enough to act on. */
export const MAX_SPEAKABLE_LINES = 5;

/** Cap absurd model distances (meters). */
export const MAX_DISTANCE_METERS = 12;
