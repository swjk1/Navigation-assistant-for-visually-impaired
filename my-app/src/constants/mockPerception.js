/**
 * Deterministic mock perception payloads for offline / no-Gemini development.
 */

export const MOCK_HALLWAY_RAW = {
  objects: [
    {
      label: 'door',
      confidence: 0.92,
      box: { x: 0.18, y: 0.22, width: 0.28, height: 0.55 },
      clockPosition: "11 o'clock",
      approxDistanceMeters: 3.5,
    },
    {
      label: 'wall',
      confidence: 0.88,
      box: { x: 0.0, y: 0.0, width: 0.15, height: 1.0 },
      clockPosition: "9 o'clock",
      approxDistanceMeters: 1.2,
    },
    {
      label: 'sign',
      confidence: 0.85,
      box: { x: 0.55, y: 0.18, width: 0.2, height: 0.12 },
      clockPosition: "1 o'clock",
      approxDistanceMeters: 4.0,
    },
  ],
  text: [
    {
      text: 'ROOM 204',
      confidence: 0.91,
      box: { x: 0.55, y: 0.18, width: 0.2, height: 0.12 },
    },
  ],
  floorDetected: true,
  immediateHazard: false,
  hazardDescription: null,
};

export const MOCK_STAIRS_RAW = {
  objects: [
    {
      label: 'stairs',
      confidence: 0.94,
      box: { x: 0.35, y: 0.4, width: 0.3, height: 0.45 },
      clockPosition: "12 o'clock",
      approxDistanceMeters: 2.0,
    },
  ],
  text: [],
  floorDetected: true,
  immediateHazard: true,
  hazardDescription: 'Descending stairs ahead within 3 meters. Stop.',
};

export const MOCK_TRASHCAN_RAW = {
  objects: [
    {
      label: 'trashcan',
      confidence: 0.9,
      box: { x: 0.4, y: 0.5, width: 0.22, height: 0.35 },
      clockPosition: "12 o'clock",
      approxDistanceMeters: 1.5,
    },
  ],
  text: [],
  floorDetected: true,
  immediateHazard: true,
  hazardDescription: 'Obstacle in path within 3 meters.',
};

/** Clear corridor — floor only, no obstacles/hazards. */
export const MOCK_CLEAR_HALLWAY_RAW = {
  objects: [
    {
      label: 'wall',
      confidence: 0.8,
      box: { x: 0.0, y: 0.0, width: 0.12, height: 1.0 },
      clockPosition: "9 o'clock",
      approxDistanceMeters: 1.0,
    },
    {
      label: 'wall',
      confidence: 0.8,
      box: { x: 0.88, y: 0.0, width: 0.12, height: 1.0 },
      clockPosition: "3 o'clock",
      approxDistanceMeters: 1.0,
    },
  ],
  text: [],
  floorDetected: true,
  immediateHazard: false,
  hazardDescription: null,
};

/** Blurry / low contrast — uncertain, no forced hazard. */
export const MOCK_BLURRY_RAW = {
  objects: [],
  text: [],
  floorDetected: false,
  immediateHazard: false,
  hazardDescription: null,
};

export const MOCK_TIMEOUT_SAFE_FRAME = {
  objects: [],
  text: [],
  floorDetected: false,
  immediateHazard: true,
  /** PRD Step 4 deterministic fallback copy (safe for Person 3 TTS) */
  hazardDescription: 'Scan timeout. Stop and hold position.',
};

function clone(obj) {
  return structuredClone
    ? structuredClone(obj)
    : JSON.parse(JSON.stringify(obj));
}

/**
 * @param {string} [scenario]
 * @returns {object}
 */
export function selectMockRawPayload(scenario = 'hallway') {
  switch (scenario) {
    case 'stairs':
      return clone(MOCK_STAIRS_RAW);
    case 'trashcan':
      return clone(MOCK_TRASHCAN_RAW);
    case 'clear':
    case 'clear_hallway':
      return clone(MOCK_CLEAR_HALLWAY_RAW);
    case 'blurry':
    case 'blur':
      return clone(MOCK_BLURRY_RAW);
    case 'hallway':
    case 'room_sign':
    default:
      return clone(MOCK_HALLWAY_RAW);
  }
}
