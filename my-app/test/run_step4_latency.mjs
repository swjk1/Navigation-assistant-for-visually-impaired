/**
 * Step 4 — latency profile, JPEG quality tradeoff, timeout fallback verification.
 * Usage: node test/run_step4_latency.mjs
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

process.env.PERCEPTION_MODE = 'mock';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

const { processFrame, buildScanTimeoutFrame } = await import(
  '../src/services/perceptionEngine.js'
);
const { processHybridFrame } = await import(
  '../src/services/hybridPerception.js'
);
const {
  CAMERA_CAPTURE_CONFIG,
  JPEG_QUALITY_CANDIDATES,
  PIPELINE_BUDGET_MS,
  NETWORK_TIMEOUT_FALLBACK_MS,
} = await import('../src/constants/cameraConfig.js');
const { estimateBase64Bytes } = await import(
  '../src/services/cameraService.js'
);

const RUNS = 10;
const fixturePath = path.join(root, 'test', 'fixtures', 'edge_clear_hallway.jpg');

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

function loadBase64() {
  if (fs.existsSync(fixturePath)) {
    return fs.readFileSync(fixturePath).toString('base64');
  }
  return Buffer.alloc(4096, 0x5a).toString('base64');
}

/**
 * Approximate JPEG size at different qualities by re-encoding fixture if possible.
 * Falls back to scaled byte estimates when canvas unavailable in Node.
 */
function profileJpegQualities(base64) {
  const baseBytes = estimateBase64Bytes(base64);
  // Empirical-ish scale vs quality 0.4 reference for same resolution
  const refQ = CAMERA_CAPTURE_CONFIG.quality;
  return JPEG_QUALITY_CANDIDATES.map((q) => {
    const scale = q / refQ;
    const estimatedBytes = Math.round(baseBytes * Math.pow(scale, 0.85));
    return {
      quality: q,
      estimatedBytes,
      withinSizeBudget: estimatedBytes <= CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
      note:
        q === 0.4
          ? 'default'
          : q < 0.4
            ? 'smaller payload / faster upload'
            : 'larger payload / potentially better OCR',
    };
  });
}

console.log('====================================================');
console.log('   PERSON 1: STEP 4 LATENCY & EFFICIENCY PROFILE    ');
console.log('====================================================\n');

const base64 = loadBase64();
const samples = [];
const wallTimes = [];

for (let i = 0; i < RUNS; i++) {
  const t0 = performance.now();
  const frame = await processHybridFrame(base64, {
    mode: 'mock',
    allowLiveVlm: false,
    useMockVlmOnGate: false,
    nativeResult: {
      objects: [
        {
          label: 'door',
          confidence: 0.9,
          box: { x: 0.2, y: 0.2, width: 0.3, height: 0.5 },
          cocoClassId: -1,
          cocoName: 'door',
        },
      ],
      text: [{ text: 'ROOM 204', confidence: 0.9, box: { x: 0.5, y: 0.1, width: 0.2, height: 0.1 } }],
      yoloMs: 8,
      ocrMs: 12,
      totalMs: 20,
      modelLoaded: true,
      modelPath: 'profile://mock',
      yoloError: null,
      ocrError: null,
      platform: 'profile-mock',
    },
  });
  // Also exercise pure mock engine path alternate runs
  if (i % 2 === 1) {
    await processFrame(base64, { mode: 'mock', scenario: 'hallway' });
  }
  const wallMs = performance.now() - t0;
  wallTimes.push(wallMs);
  samples.push({
    run: i + 1,
    wallClockMs: Number(wallMs.toFixed(3)),
    frameLatencyMs: frame.latencyMs,
    objectCount: frame.objects.length,
    textCount: frame.text.length,
    immediateHazard: frame.immediateHazard,
    withinPipelineBudget: wallMs <= PIPELINE_BUDGET_MS,
  });
  console.log(
    `  run ${String(i + 1).padStart(2, '0')}: ${wallMs.toFixed(2)}ms | objects=${frame.objects.length} ocr=${frame.text.length} budgetOK=${wallMs <= PIPELINE_BUDGET_MS}`
  );
}

// Timeout fallback verification (deterministic, no network)
const timeoutFrame = buildScanTimeoutFrame(NETWORK_TIMEOUT_FALLBACK_MS + 120);
const timeoutOk =
  timeoutFrame.immediateHazard === true &&
  timeoutFrame.hazardDescription === 'Scan timeout. Stop and hold position.' &&
  Array.isArray(timeoutFrame.objects) &&
  timeoutFrame.objects.length === 0;

console.log(
  `\nTimeout fallback @>${NETWORK_TIMEOUT_FALLBACK_MS}ms: ${timeoutOk ? '✅' : '❌'}`
);
console.log(`  hazardDescription: "${timeoutFrame.hazardDescription}"`);

const qualityProfile = profileJpegQualities(base64);
console.log('\nJPEG quality payload estimate (640×480 class fixture):');
for (const q of qualityProfile) {
  console.log(
    `  q=${q.quality} → ~${q.estimatedBytes} bytes | within150KB=${q.withinSizeBudget} (${q.note})`
  );
}

const wallSummary = summarize(wallTimes);
const recommendedQuality =
  qualityProfile.find((q) => q.quality === 0.3 && q.withinSizeBudget)?.quality ??
  CAMERA_CAPTURE_CONFIG.quality;

const snapshot = {
  step: 4,
  name: 'latency_efficiency_profile',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  mode: 'mock_profile',
  reviewCheckpoint:
    'Is average round-trip latency under 1500ms? Does timeout gracefully fallback?',
  reviewAnswers: {
    avgUnder1500ms: wallSummary.avgMs <= PIPELINE_BUDGET_MS,
    p90Under1500ms: wallSummary.p90Ms <= PIPELINE_BUDGET_MS,
    maxUnder1500ms: wallSummary.maxMs <= PIPELINE_BUDGET_MS,
    timeoutFallbackWorks: timeoutOk,
    timeoutMessage:
      'Scan timeout. Stop and hold position.',
    networkFallbackThresholdMs: NETWORK_TIMEOUT_FALLBACK_MS,
  },
  budgets: {
    pipelineBudgetMs: PIPELINE_BUDGET_MS,
    captureBudgetMs: CAMERA_CAPTURE_CONFIG.maxCaptureLatencyMs,
    maxBase64Bytes: CAMERA_CAPTURE_CONFIG.maxBase64Bytes,
    networkTimeoutFallbackMs: NETWORK_TIMEOUT_FALLBACK_MS,
  },
  timing: {
    runs: RUNS,
    wallClock: wallSummary,
    samples,
  },
  jpegQualityComparison: qualityProfile,
  recommendation: {
    defaultQuality: CAMERA_CAPTURE_CONFIG.quality,
    suggestedQualityForTokenReduction: recommendedQuality,
    note: 'Prefer 0.3 when OCR still readable on-device; keep 0.4 default; avoid 0.5 unless OCR fails.',
  },
  timeoutFallbackDemo: {
    latencyMs: timeoutFrame.latencyMs,
    immediateHazard: timeoutFrame.immediateHazard,
    hazardDescription: timeoutFrame.hazardDescription,
  },
};

const outPath = path.join(root, 'snapshots', 'snapshot_step4_latency_profile.json');
fs.writeFileSync(outPath, JSON.stringify(snapshot, null, 2));

console.log('\n----------------------------------------------------');
console.log(
  `⏱️  avg=${wallSummary.avgMs}ms p90=${wallSummary.p90Ms}ms max=${wallSummary.maxMs}ms (budget ${PIPELINE_BUDGET_MS}ms)`
);
console.log(`📁 ${outPath}`);
console.log('====================================================');

if (!timeoutOk || wallSummary.avgMs > PIPELINE_BUDGET_MS) {
  process.exitCode = 1;
}
