/**
 * Step 5 — Integration & Mock Handover final contract.
 *
 * PRD review: Can Person 2 consume getLatestPerceptionFrame() with zero schema errors?
 *
 * Writes: snapshots/snapshot_step5_final_contract.json
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

process.env.PERCEPTION_MODE = 'mock';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

const {
  getLatestPerceptionFrame,
  getLatestTeammateHandoff,
  analyzeAndStore,
  getMockPerceptionFrame,
  getMockTeammateHandoff,
  listNavAnchors,
  listObstacles,
  listReadableSigns,
  listSpeakableLines,
  toPublicPerceptionFrame,
} = await import('../src/index.js');

const {
  ALLOWED_LABELS,
  ALLOWED_CLOCK,
  MAX_DISTANCE_METERS,
} = await import('../src/constants/perceptionCatalog.js');

const SCENARIOS = [
  'hallway',
  'stairs',
  'trashcan',
  'clear_hallway',
  'blurry',
];

const PUBLIC_KEYS = new Set([
  'timestamp',
  'latencyMs',
  'objects',
  'text',
  'floorDetected',
  'immediateHazard',
  'hazardDescription',
]);

/**
 * Structural schema check mirroring PerceptionFrame (Person 2 consumer view).
 * @returns {string[]} failures
 */
function assertFrameSchema(frame, label) {
  const fails = [];
  if (!frame || typeof frame !== 'object') {
    return [`${label}: frame missing`];
  }

  for (const key of Object.keys(frame)) {
    if (!PUBLIC_KEYS.has(key)) {
      fails.push(`${label}: unexpected public key "${key}" (meta leak?)`);
    }
  }
  for (const key of PUBLIC_KEYS) {
    if (!(key in frame)) fails.push(`${label}: missing field ${key}`);
  }

  if (typeof frame.timestamp !== 'number') {
    fails.push(`${label}: timestamp not number`);
  }
  if (typeof frame.latencyMs !== 'number') {
    fails.push(`${label}: latencyMs not number`);
  }
  if (typeof frame.floorDetected !== 'boolean') {
    fails.push(`${label}: floorDetected not boolean`);
  }
  if (typeof frame.immediateHazard !== 'boolean') {
    fails.push(`${label}: immediateHazard not boolean`);
  }
  if (
    frame.hazardDescription !== null &&
    typeof frame.hazardDescription !== 'string'
  ) {
    fails.push(`${label}: hazardDescription must be string|null`);
  }
  if (frame.hazardDescription && frame.hazardDescription.length > 100) {
    fails.push(`${label}: hazardDescription > 100 chars`);
  }
  if (!Array.isArray(frame.objects)) {
    fails.push(`${label}: objects not array`);
  } else {
    for (const [i, obj] of frame.objects.entries()) {
      if (!ALLOWED_LABELS.includes(obj.label)) {
        fails.push(`${label}: objects[${i}] bad label ${obj.label}`);
      }
      if (!ALLOWED_CLOCK.includes(obj.clockPosition)) {
        fails.push(`${label}: objects[${i}] bad clock ${obj.clockPosition}`);
      }
      if (
        typeof obj.approxDistanceMeters !== 'number' ||
        obj.approxDistanceMeters < 0.5 ||
        obj.approxDistanceMeters > MAX_DISTANCE_METERS
      ) {
        fails.push(`${label}: objects[${i}] distance out of range`);
      }
      const b = obj.box || {};
      for (const k of ['x', 'y', 'width', 'height']) {
        const v = Number(b[k]);
        if (Number.isNaN(v) || v < 0 || v > 1) {
          fails.push(`${label}: objects[${i}].box.${k} out of 0..1`);
        }
      }
      if (Number(obj.confidence) < 0 || Number(obj.confidence) > 1) {
        fails.push(`${label}: objects[${i}] confidence out of 0..1`);
      }
    }
  }
  if (!Array.isArray(frame.text)) {
    fails.push(`${label}: text not array`);
  } else {
    for (const [i, item] of frame.text.entries()) {
      if (typeof item.text !== 'string' || !item.text.trim()) {
        fails.push(`${label}: text[${i}] empty`);
      }
      if (item.text && item.text.length > 50) {
        fails.push(`${label}: text[${i}] > 50 chars`);
      }
    }
  }
  return fails;
}

console.log('====================================================');
console.log('   PERSON 1: STEP 5 FINAL CONTRACT (MOCK)           ');
console.log('====================================================\n');

const failures = [];
const scenarioResults = {};

// 1) Before any analyze — latest is null
if (getLatestPerceptionFrame() !== null) {
  failures.push('getLatestPerceptionFrame() should be null before analyze');
}

// 2) Mock fixtures for every scenario — zero schema errors
for (const scenario of SCENARIOS) {
  const frame = getMockPerceptionFrame(scenario);
  const errs = assertFrameSchema(frame, `mock:${scenario}`);
  failures.push(...errs);

  const handoff = getMockTeammateHandoff(scenario);
  if (!handoff?.frame) {
    failures.push(`mock:${scenario} handoff.frame missing`);
  } else {
    failures.push(...assertFrameSchema(handoff.frame, `handoff:${scenario}`));
  }
  if (!Array.isArray(handoff?.speakableLines)) {
    failures.push(`mock:${scenario} speakableLines missing`);
  }
  if ((handoff?.speakableLines?.length || 0) > 5) {
    failures.push(`mock:${scenario} speakableLines exceed cap of 5`);
  }
  if (!['hazard', 'guidance'].includes(handoff?.speechPriority)) {
    failures.push(`mock:${scenario} bad speechPriority`);
  }

  scenarioResults[scenario] = {
    objectCount: frame?.objects?.length ?? 0,
    textCount: frame?.text?.length ?? 0,
    immediateHazard: Boolean(frame?.immediateHazard),
    navAnchorCount: listNavAnchors(frame).length,
    obstacleCount: listObstacles(frame).length,
    signs: listReadableSigns(frame),
    speakableLines: listSpeakableLines(frame),
    speechPriority: handoff?.speechPriority,
    schemaErrors: errs.length,
  };
}

// 3) Hallway contract specifics (Person 2 door walk)
const hallway = getMockPerceptionFrame('hallway');
const door = hallway.objects.find((o) => o.label === 'door');
if (!door || door.clockPosition !== "11 o'clock") {
  failures.push('hallway door must be at 11 o\'clock for Person 2/3 language');
}
if (!hallway.text.some((t) => t.text.includes('204'))) {
  failures.push('hallway must expose ROOM 204 for destination confirm');
}

// 4) analyzeAndStore → getLatestPerceptionFrame (mock hybrid path)
const stored = await analyzeAndStore('ZmFrZQ==', {
  allowLiveVlm: false,
  useMockVlmOnGate: true,
  vlmOnTop: false,
  nativeResult: {
    objects: [
      {
        label: 'door',
        confidence: 0.9,
        box: { x: 0.18, y: 0.22, width: 0.28, height: 0.55 },
      },
    ],
    text: [
      {
        text: 'ROOM 204',
        confidence: 0.9,
        box: { x: 0.55, y: 0.18, width: 0.2, height: 0.12 },
      },
    ],
    yoloMs: 10,
    ocrMs: 12,
    totalMs: 25,
    modelLoaded: true,
    yoloError: null,
    ocrError: null,
    platform: 'android-mock',
  },
});

const latest = getLatestPerceptionFrame();
const latestHandoff = getLatestTeammateHandoff();
if (!latest) failures.push('getLatestPerceptionFrame() null after analyzeAndStore');
if (!latestHandoff) failures.push('getLatestTeammateHandoff() null after analyzeAndStore');
failures.push(...assertFrameSchema(latest, 'latest'));
if (latest && 'meta' in (latest || {})) {
  failures.push('latest frame must not expose meta to Person 2');
}
if (stored?.frame && JSON.stringify(stored.frame) !== JSON.stringify(latest)) {
  failures.push('analyzeAndStore return frame should match getLatestPerceptionFrame');
}

// 5) toPublicPerceptionFrame strips meta
const withMeta = { ...hallway, meta: { secret: true, pipeline: 'test' } };
const publicOnly = toPublicPerceptionFrame(withMeta);
if (publicOnly && 'meta' in publicOnly) {
  failures.push('toPublicPerceptionFrame leaked meta');
}
failures.push(...assertFrameSchema(publicOnly, 'publicStrip'));

const passed = failures.length === 0;
const snapshot = {
  step: 5,
  name: 'final_contract_person2_handover',
  status: passed ? 'contract_verified' : 'contract_failed',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  prdReviewQuestion:
    'Can Person 2 consume getLatestPerceptionFrame() with zero schema errors?',
  reviewAnswers: {
    getLatestPerceptionFrameExported: typeof getLatestPerceptionFrame === 'function',
    mockFixturesAvailable: SCENARIOS.every(
      (s) => getMockPerceptionFrame(s) != null
    ),
    zeroSchemaErrorsAcrossMocks: SCENARIOS.every(
      (s) => scenarioResults[s].schemaErrors === 0
    ),
    canPerson2ConsumeWithZeroSchemaErrors: passed,
    doorAt11OClock: door?.clockPosition === "11 o'clock",
    room204ForDestinationConfirm: hallway.text.some((t) =>
      t.text.includes('204')
    ),
    analyzeAndStoreCachesLatest: Boolean(latest),
    publicFrameHasNoMeta: latest && !('meta' in latest),
    speakableLinesCappedAt5: SCENARIOS.every(
      (s) => (scenarioResults[s].speakableLines?.length || 0) <= 5
    ),
  },
  signOff: {
    person1: passed ? 'verified' : 'failed',
    /** Offline contract ready — Person 2 live app wiring is their step */
    person2: passed ? 'ready_for_integration' : 'blocked',
    person3: passed ? 'ready_for_tts_lines' : 'blocked',
  },
  publicApi: [
    'getLatestPerceptionFrame',
    'getLatestTeammateHandoff',
    'analyzeAndStore',
    'getMockPerceptionFrame',
    'getMockTeammateHandoff',
    'listNavAnchors',
    'listObstacles',
    'listReadableSigns',
    'listSpeakableLines',
    'toPublicPerceptionFrame',
    'buildTeammateHandoff',
  ],
  scenarios: scenarioResults,
  examples: {
    hallwayFrame: hallway,
    hallwayHandoff: getMockTeammateHandoff('hallway'),
    stairsSpeakable: listSpeakableLines(getMockPerceptionFrame('stairs')),
    latestAfterAnalyze: latest,
  },
  failures,
};

const outPath = path.join(root, 'snapshots', 'snapshot_step5_final_contract.json');
fs.writeFileSync(outPath, JSON.stringify(snapshot, null, 2));

console.log(passed ? '✅ CONTRACT VERIFIED' : '❌ CONTRACT FAILED');
failures.forEach((f) => console.log(`   • ${f}`));
console.log(`📁 ${outPath}`);
console.log(
  `PRD review: Person 2 zero schema errors → ${passed ? 'YES' : 'NO'}`
);

if (!passed) process.exitCode = 1;
