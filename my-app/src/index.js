/**
 * Public handoff surface of the JavaScript perception stack.
 *
 * SCOPE - read this before wiring anything new to it. The shipping navigation screen
 * (`src/app/navigate.tsx`) does NOT go through this module. It talks to the native
 * `indoor-perception` module directly, which runs YOLO + ML Kit OCR on-device against the live
 * ARCore session. What lives behind this file is the cloud/mock pipeline: Gemini Flash plus
 * offline fixtures, reachable from `src/app/perception-harness.tsx` and the offline tests.
 *
 * That makes this a development and integration surface, not a production path. It is kept
 * because it is the only way to exercise the perception contract without an ARCore phone in
 * hand, and because the fixtures make the sanitizer testable. Treat a change here as a change
 * to the test harness unless you have also wired it into `navigate.tsx`.
 *
 * It only ever answers "what is ahead?" - never routing, never speech playback.
 */
import { processFrame, getPerceptionMode } from './services/perceptionEngine.js';
import { processHybridFrame } from './services/hybridPerception.js';
import {
  selectMockRawPayload,
  MOCK_HALLWAY_RAW,
  MOCK_STAIRS_RAW,
  MOCK_TRASHCAN_RAW,
  MOCK_CLEAR_HALLWAY_RAW,
  MOCK_BLURRY_RAW,
} from './constants/mockPerception.js';
import { validateAndSanitizeFrame } from './services/schemaValidator.js';
import {
  toPublicPerceptionFrame,
  listNavAnchors,
  listObstacles,
  listReadableSigns,
  listSpeakableLines,
  buildTeammateHandoff,
} from './services/teammateHandoff.js';

/** @typedef {import('./types/perception').PerceptionFrame} PerceptionFrame */
/** @typedef {import('./services/teammateHandoff.js').TeammateHandoff} TeammateHandoff */
/** @typedef {import('./constants/mockPerception.js').MockScenario} MockScenario */

/** @type {PerceptionFrame | null} */
let latestFrame = null;
/** @type {TeammateHandoff | null} */
let latestHandoff = null;

/**
 * Last verified PerceptionFrame (contract fields only), or null.
 * @returns {PerceptionFrame | null}
 */
export function getLatestPerceptionFrame() {
  return latestFrame ? toPublicPerceptionFrame(latestFrame) : null;
}

/**
 * Last handoff bundle (anchors + speakable lines), or null.
 * @returns {TeammateHandoff | null}
 */
export function getLatestTeammateHandoff() {
  return latestHandoff;
}

/**
 * Run perception, then cache the public frame and handoff bundle.
 *
 * @param {string} base64Image
 * @param {import('./services/hybridPerception.js').HybridOptions & {
 *   hybrid?: boolean,
 *   mode?: 'mock' | 'live',
 *   scenario?: MockScenario,
 * }} [options] `hybrid: false` forces the Gemini-only path. The rest is forwarded verbatim to
 *   whichever pipeline runs, so this bag is the union of both pipelines' options.
 * @returns {Promise<TeammateHandoff>}
 */
export async function analyzeAndStore(base64Image, options = {}) {
  const useHybrid = options.hybrid !== false;
  const rawFrame = useHybrid
    ? await processHybridFrame(base64Image, options)
    : await processFrame(base64Image, options);

  latestFrame = toPublicPerceptionFrame(rawFrame);
  latestHandoff = buildTeammateHandoff(latestFrame);
  return latestHandoff;
}

/**
 * Offline fixtures, so the navigation and guidance layers can integrate with neither a camera
 * nor a Gemini key.
 *
 * @param {MockScenario} [scenario]
 * @returns {PerceptionFrame}
 */
export function getMockPerceptionFrame(scenario = 'hallway') {
  // No `toPublicPerceptionFrame` here: the sanitizer already returns exactly the contract
  // fields and never attaches debug `meta`, so stripping it again was a no-op that only made
  // the return type nullable.
  return validateAndSanitizeFrame(selectMockRawPayload(scenario), 0);
}

/**
 * Offline handoff bundle.
 *
 * @param {MockScenario} [scenario]
 * @returns {TeammateHandoff}
 */
export function getMockTeammateHandoff(scenario = 'hallway') {
  return buildTeammateHandoff(getMockPerceptionFrame(scenario));
}

export {
  processFrame,
  processHybridFrame,
  getPerceptionMode,
  toPublicPerceptionFrame,
  listNavAnchors,
  listObstacles,
  listReadableSigns,
  listSpeakableLines,
  buildTeammateHandoff,
  MOCK_HALLWAY_RAW,
  MOCK_STAIRS_RAW,
  MOCK_TRASHCAN_RAW,
  MOCK_CLEAR_HALLWAY_RAW,
  MOCK_BLURRY_RAW,
};
