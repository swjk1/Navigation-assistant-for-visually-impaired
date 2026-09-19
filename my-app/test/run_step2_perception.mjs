/**
 * Step 2 timed snapshot — mock perception (no Gemini network calls).
 * Usage: node test/run_step2_perception.mjs
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

// Force mock for this runner regardless of .env
process.env.PERCEPTION_MODE = 'mock';

const {
  processFrame,
  getPerceptionMode,
  SYSTEM_PROMPT,
} = await import('../src/services/perceptionEngine.js');
const { ALLOWED_CLOCK, ALLOWED_LABELS } = await import(
  '../src/services/schemaValidator.js'
);

const RUNS = 10;
const SCENARIO = 'hallway';

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const idx = Math.min(
    sorted.length - 1,
    Math.ceil((p / 100) * sorted.length) - 1
  );
  return sorted[idx];
}

function summarize(arr) {
  const sorted = [...arr].sort((a, b) => a - b);
  const sum = sorted.reduce((a, b) => a + b, 0);
  return {
    n: sorted.length,
    avgMs: Number((sum / sorted.length).toFixed(3)),
    p90Ms: Number(percentile(sorted, 90).toFixed(3)),
    maxMs: Number(sorted[sorted.length - 1].toFixed(3)),
    minMs: Number(sorted[0].toFixed(3)),
  };
}

console.log('====================================================');
console.log('   PERSON 1: STEP 2 PERCEPTION SNAPSHOT (MOCK)      ');
console.log('====================================================\n');
console.log(`Mode: ${getPerceptionMode()} | scenario: ${SCENARIO}`);
console.log(`System prompt chars: ${SYSTEM_PROMPT.length}\n`);

const samples = [];
const latencies = [];
let lastFrame = null;

// Tiny synthetic base64 stand-in (unused by mock path, but mirrors camera handoff)
const fakeBase64 = Buffer.alloc(1024, 0x41).toString('base64');

for (let i = 0; i < RUNS; i++) {
  const t0 = performance.now();
  const frame = await processFrame(fakeBase64, {
    mode: 'mock',
    scenario: SCENARIO,
  });
  const wallMs = performance.now() - t0;
  latencies.push(wallMs);
  samples.push({
    run: i + 1,
    wallClockMs: Number(wallMs.toFixed(3)),
    frameLatencyMs: frame.latencyMs,
    objectCount: frame.objects.length,
    textCount: frame.text.length,
    immediateHazard: frame.immediateHazard,
  });
  lastFrame = frame;
  console.log(
    `  run ${String(i + 1).padStart(2, '0')}: wall=${wallMs.toFixed(2)}ms frame.latencyMs=${frame.latencyMs} objects=${frame.objects.length} ocr=${frame.text.length}`
  );
}

// Contract checks on last frame
const failures = [];
if (!lastFrame || typeof lastFrame !== 'object') {
  failures.push('No frame returned');
} else {
  if (!Array.isArray(lastFrame.objects)) failures.push('objects not array');
  if (!Array.isArray(lastFrame.text)) failures.push('text not array');
  if (typeof lastFrame.floorDetected !== 'boolean') {
    failures.push('floorDetected not boolean');
  }
  if (typeof lastFrame.immediateHazard !== 'boolean') {
    failures.push('immediateHazard not boolean');
  }
  for (const obj of lastFrame.objects || []) {
    if (!ALLOWED_LABELS.includes(obj.label)) {
      failures.push(`bad label: ${obj.label}`);
    }
    if (!ALLOWED_CLOCK.includes(obj.clockPosition)) {
      failures.push(`bad clock: ${obj.clockPosition}`);
    }
    const { x, y, width, height } = obj.box || {};
    for (const [k, v] of Object.entries({ x, y, width, height })) {
      if (typeof v !== 'number' || v < 0 || v > 1) {
        failures.push(`box.${k} out of range`);
      }
    }
  }
  const hasDoor = lastFrame.objects.some((o) => o.label === 'door');
  const hasRoomText = lastFrame.text.some((t) => t.text.includes('204'));
  const doorClock = lastFrame.objects.find((o) => o.label === 'door')
    ?.clockPosition;
  if (!hasDoor) failures.push('expected door object in hallway mock');
  if (!hasRoomText) failures.push('expected ROOM 204 OCR');
  if (doorClock !== "11 o'clock") {
    failures.push(`door clock expected 11 o'clock, got ${doorClock}`);
  }
}

const passed = failures.length === 0;
const timingSummary = summarize(latencies);

const snapshot = {
  step: 2,
  name: 'perception_frame_verification',
  status: passed ? 'mock_verified' : 'mock_failed',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  mode: 'mock',
  geminiCalled: false,
  scenario: SCENARIO,
  reviewCheckpoint:
    "Inspect PerceptionFrame output. Does clock direction match target position?",
  reviewAnswers: {
    doorAt11OClock: lastFrame?.objects?.find((o) => o.label === 'door')
      ?.clockPosition === "11 o'clock",
    room204Detected: lastFrame?.text?.some((t) => t.text.includes('204')),
    schemaValid: passed,
    usedGemini: false,
  },
  timing: {
    runs: RUNS,
    wallClock: timingSummary,
    samples,
  },
  failures,
  frame: lastFrame,
  promptMeta: {
    temperature: 0.1,
    maxOutputTokens: 256,
    abortTimeoutMs: 3500,
    promptCharLength: SYSTEM_PROMPT.length,
  },
};

const outPath = path.join(root, 'snapshots', 'snapshot_step2_perception_frame.json');
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(snapshot, null, 2));

console.log('\n----------------------------------------------------');
console.log(passed ? '✅ Contract checks PASSED' : '❌ Contract checks FAILED');
if (failures.length) failures.forEach((f) => console.log(`   • ${f}`));
console.log(
  `⏱️  wall avg=${timingSummary.avgMs}ms p90=${timingSummary.p90Ms}ms max=${timingSummary.maxMs}ms`
);
console.log(`📁 Snapshot: ${outPath}`);
console.log('====================================================');

if (!passed) process.exitCode = 1;
