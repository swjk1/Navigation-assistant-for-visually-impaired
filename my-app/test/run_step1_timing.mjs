/**
 * Step 1 timed snapshot runner (offline / no phone).
 * Measures Person 1 capture-utility path and writes snapshots/snapshot_step1_hardware.json
 *
 * Usage: node test/run_step1_timing.mjs
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

const cameraService = await import('../src/services/cameraService.js');
const { CAMERA_CAPTURE_CONFIG } = await import('../src/constants/cameraConfig.js');
const envCheck = await import('../src/services/envCheck.js');

const {
  estimateBase64Bytes,
  stripDataUriPrefix,
  buildHardwareSnapshot,
} = cameraService;

const RUNS = 10;

/**
 * Build a synthetic JPEG-sized Base64 blob approximating 640x480 @ q≈0.4 (~40–80KB).
 * Not a valid JPEG decode — used only for size/latency of our JS utilities.
 */
function makeSyntheticFrameBase64(targetBytes = 72000) {
  // Base64 expands ~4/3; generate raw then encode
  const rawLen = Math.floor(targetBytes * 0.75);
  const buf = Buffer.alloc(rawLen, 0x5a);
  // JPEG SOI-ish prefix so it "looks" like image bytes in logs
  buf[0] = 0xff;
  buf[1] = 0xd8;
  return buf.toString('base64');
}

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[idx];
}

function timeSync(fn) {
  const t0 = performance.now();
  const result = fn();
  const ms = performance.now() - t0;
  return { result, ms };
}

console.log('====================================================');
console.log('   PERSON 1: STEP 1 TIMED SNAPSHOT RUN (OFFLINE)    ');
console.log('====================================================\n');

const timings = {
  envSoftCheckMs: [],
  stripPrefixMs: [],
  estimateBytesMs: [],
  buildSnapshotMs: [],
  fullUtilityPipelineMs: [],
};

let envOk = false;
try {
  const { ms } = timeSync(() => envCheck.hasValidGeminiApiKey());
  timings.envSoftCheckMs.push(ms);
  envOk = envCheck.hasValidGeminiApiKey();
} catch {
  envOk = false;
}

const syntheticBase64 = makeSyntheticFrameBase64(72000);
const estimatedBytes = estimateBase64Bytes(syntheticBase64);

const captureSamples = [];

for (let i = 0; i < RUNS; i++) {
  const pipeStart = performance.now();

  const strip = timeSync(() => stripDataUriPrefix(`data:image/jpeg;base64,${syntheticBase64}`));
  timings.stripPrefixMs.push(strip.ms);

  const est = timeSync(() => estimateBase64Bytes(strip.result));
  timings.estimateBytesMs.push(est.ms);

  // Simulate a capture result shape (no CameraView available offline)
  const fakeCapture = {
    base64: strip.result,
    width: CAMERA_CAPTURE_CONFIG.width,
    height: CAMERA_CAPTURE_CONFIG.height,
    captureLatencyMs: 0, // unknown until phone
    estimatedBytes: est.result,
    mimeType: 'image/jpeg',
    withinLatencyBudget: null,
    withinSizeBudget: est.result <= CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
  };

  const snap = timeSync(() =>
    buildHardwareSnapshot(fakeCapture, {
      permissionGranted: false,
      platform: 'offline-node',
    })
  );
  timings.buildSnapshotMs.push(snap.ms);

  const pipeMs = performance.now() - pipeStart;
  timings.fullUtilityPipelineMs.push(pipeMs);

  captureSamples.push({
    run: i + 1,
    stripPrefixMs: Number(strip.ms.toFixed(3)),
    estimateBytesMs: Number(est.ms.toFixed(3)),
    buildSnapshotMs: Number(snap.ms.toFixed(3)),
    utilityPipelineMs: Number(pipeMs.toFixed(3)),
    estimatedBytes: est.result,
  });

  console.log(
    `  run ${String(i + 1).padStart(2, '0')}: utility ${pipeMs.toFixed(2)}ms | bytes=${est.result}`
  );
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

const latencyProfile = {
  envSoftCheck: summarize(timings.envSoftCheckMs.length ? timings.envSoftCheckMs : [0]),
  stripPrefix: summarize(timings.stripPrefixMs),
  estimateBytes: summarize(timings.estimateBytesMs),
  buildSnapshot: summarize(timings.buildSnapshotMs),
  fullUtilityPipeline: summarize(timings.fullUtilityPipelineMs),
};

const snapshot = {
  step: 1,
  name: 'hardware_camera_verification',
  status: 'offline_timed_run_phone_pending',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  note:
    'Timed offline utility path only. Live CameraView captureLatencyMs still requires phone (/perception-harness).',
  permissionGranted: false,
  platform: 'offline-node',
  geminiKeyConfigured: envOk,
  config: { ...CAMERA_CAPTURE_CONFIG },
  capture: {
    width: CAMERA_CAPTURE_CONFIG.width,
    height: CAMERA_CAPTURE_CONFIG.height,
    captureLatencyMs: null,
    estimatedBytes,
    mimeType: 'image/jpeg',
    base64Length: syntheticBase64.length,
    base64Prefix: syntheticBase64.slice(0, 32),
    looksLikeBase64: /^[A-Za-z0-9+/]+=*$/.test(syntheticBase64.slice(0, 64)),
    synthetic: true,
  },
  budgets: {
    maxCaptureLatencyMs: CAMERA_CAPTURE_CONFIG.maxCaptureLatencyMs,
    maxBase64Bytes: CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
    withinLatencyBudget: null,
    withinSizeBudget: estimatedBytes <= CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
  },
  timing: {
    runs: RUNS,
    samples: captureSamples,
    summary: latencyProfile,
  },
  reviewCheckpoint:
    'Did camera capture a sample image with Base64 size < 150KB and latency < 150ms?',
  reviewAnswers: {
    sizeBudgetMetOffline: estimatedBytes <= CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
    liveCaptureLatencyMet: null,
    phoneRunCompleted: false,
  },
  artifactsReady: {
    gitignore: true,
    envExample: true,
    envCheck: true,
    cameraService: true,
    perceptionTypes: true,
  },
};

const outPath = path.join(root, 'snapshots', 'snapshot_step1_hardware.json');
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(snapshot, null, 2));

// Also drop a dedicated timing companion snapshot
const timingPath = path.join(root, 'snapshots', 'snapshot_step1_timing_profile.json');
fs.writeFileSync(
  timingPath,
  JSON.stringify(
    {
      step: 1,
      name: 'step1_utility_timing_profile',
      timestamp: Date.now(),
      timedAt: new Date().toISOString(),
      runs: RUNS,
      summary: latencyProfile,
      samples: captureSamples,
      budgets: snapshot.budgets,
    },
    null,
    2
  )
);

console.log('\n----------------------------------------------------');
console.log(`📁 Snapshot: ${outPath}`);
console.log(`📁 Timing:   ${timingPath}`);
console.log(
  `⏱️  Utility pipeline avg=${latencyProfile.fullUtilityPipeline.avgMs}ms p90=${latencyProfile.fullUtilityPipeline.p90Ms}ms max=${latencyProfile.fullUtilityPipeline.maxMs}ms`
);
console.log(
  `📦 Synthetic frame ~${estimatedBytes} bytes (budget ${CAMERA_CAPTURE_CONFIG.maxBase64Bytes}) → withinSize=${snapshot.budgets.withinSizeBudget}`
);
console.log('📱 Phone capture latency: PENDING (not measured this run)');
console.log('====================================================');
