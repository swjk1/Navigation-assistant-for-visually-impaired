/**
 * Single source of truth for Person 1 perception catalogs (runtime JS).
 * Types in perception.ts should stay aligned with these lists.
 */

export const ALLOWED_LABELS = [
  'door',
  'person',
  'stairs',
  'elevator',
  'chair',
  'trashcan',
  'wall',
  'sign',
];

export const ALLOWED_CLOCK = [
  "9 o'clock",
  "10 o'clock",
  "11 o'clock",
  "12 o'clock",
  "1 o'clock",
  "2 o'clock",
  "3 o'clock",
];

/** Person 2 navigation anchors / destinations */
export const NAV_ANCHOR_LABELS = ['door', 'elevator', 'stairs', 'sign'];

/** Person 2/3 path obstacles */
export const OBSTACLE_LABELS = ['person', 'chair', 'trashcan'];

/** Max speakable lines for Person 3 TTS queue */
export const MAX_SPEAKABLE_LINES = 5;

/** Cap absurd model distances (meters) */
export const MAX_DISTANCE_METERS = 12;
