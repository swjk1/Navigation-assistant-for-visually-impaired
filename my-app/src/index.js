/**
 * Person 1 public handoff surface for Person 2 (navigation) and Person 3 (TTS).
 *
 * Project goal: help a blind user walk indoors (e.g. to a door).
 * Person 1 only answers "what is ahead?" — not routing and not speech playback.
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

let latestFrame = null;
let latestHandoff = null;

/**
 * Last verified PerceptionFrame (PRD fields only), or null.
 * @returns {import('./types/perception').PerceptionFrame | null}
 */
export function getLatestPerceptionFrame() {
  return latestFrame ? toPublicPerceptionFrame(latestFrame) : null;
}

/**
 * Last teammate handoff bundle (anchors + speakable lines), or null.
 */
export function getLatestTeammateHandoff() {
  return latestHandoff;
}

/**
 * Run perception, cache public frame + handoff for teammates.
 * @param {string} base64Image
 * @param {object} [options]
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
 * Offline fixtures so Person 2/3 can integrate without camera/Gemini.
 * @param {'hallway'|'stairs'|'trashcan'|'clear_hallway'|'blurry'|'room_sign'} [scenario]
 */
export function getMockPerceptionFrame(scenario = 'hallway') {
  const raw = selectMockRawPayload(scenario);
  return toPublicPerceptionFrame(validateAndSanitizeFrame(raw, 0));
}

/**
 * Offline handoff bundle for Person 2/3.
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
