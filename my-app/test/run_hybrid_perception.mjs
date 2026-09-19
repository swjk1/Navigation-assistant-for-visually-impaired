/**
 * Offline hybrid pipeline snapshot — gated Gemini (not every frame).
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

process.env.PERCEPTION_MODE = 'mock';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

const {
  processHybridFrame,
  clockFromBox,
  shouldInvokeVlm,
  isMlKitUnsure,
} = await import('../src/services/hybridPerception.js');

const confidentNative = {
  objects: [
    {
      label: 'person',
      confidence: 0.91,
      box: { x: 0.42, y: 0.3, width: 0.2, height: 0.5 },
      cocoClassId: 0,
      cocoName: 'person',
    },
    {
      label: 'chair',
      confidence: 0.77,
      box: { x: 0.05, y: 0.55, width: 0.18, height: 0.3 },
      cocoClassId: 56,
      cocoName: 'chair',
    },
  ],
  text: [
    {
      text: 'ROOM 204',
      confidence: 0.88,
      box: { x: 0.6, y: 0.15, width: 0.25, height: 0.1 },
    },
  ],
  yoloMs: 42,
  ocrMs: 55,
  totalMs: 110,
  modelLoaded: true,
  modelPath: 'mock://yolo26n_int8.tflite',
  yoloError: null,
  ocrError: null,
  platform: 'android-mock',
};

const unsureNative = {
  ...confidentNative,
  objects: [],
  text: [],
  modelLoaded: true,
  yoloError: null,
};

console.log('====================================================');
console.log('   PERSON 1: HYBRID GATED GEMINI SNAPSHOT           ');
console.log('====================================================\n');

const t0 = performance.now();
const frame = await processHybridFrame('ZmFrZQ==', {
  nativeResult: confidentNative,
  allowLiveVlm: false,
  useMockVlmOnGate: true,
  vlmOnTop: false,
});
const wallMs = performance.now() - t0;

const failures = [];
const person = frame.objects.find((o) => o.label === 'person');
if (!person) failures.push('missing person from YOLO mock');
if (person && person.clockPosition !== "12 o'clock") {
  failures.push(`person clock expected 12 o'clock got ${person.clockPosition}`);
}
if (!frame.text.some((t) => t.text.includes('204'))) {
  failures.push('OCR ROOM 204 missing');
}
if (frame.meta?.ocrSource !== 'mlkit') {
  failures.push(`ocrSource expected mlkit, got ${frame.meta?.ocrSource}`);
}
if (shouldInvokeVlm(confidentNative, { vlmOnTop: false })) {
  failures.push('confident ML Kit+YOLO should NOT invoke Gemini');
}
if (!shouldInvokeVlm(unsureNative, { vlmOnTop: false })) {
  failures.push('empty ML Kit+YOLO SHOULD invoke Gemini');
}
if (!isMlKitUnsure(unsureNative)) {
  failures.push('empty OCR should be mlkit unsure');
}
if (frame.meta?.vlmInvoked) {
  failures.push('confident path should skip mock VLM gate');
}

// Unsure path should invoke mock VLM
const unsureFrame = await processHybridFrame('ZmFrZQ==', {
  nativeResult: unsureNative,
  allowLiveVlm: false,
  useMockVlmOnGate: true,
  vlmOnTop: false,
  mockVlmScenario: 'hallway',
});
if (!unsureFrame.meta?.vlmInvoked) {
  failures.push('unsure path should invoke mock VLM');
}
if (unsureFrame.meta?.vlmGateReason !== 'mlkit_empty' &&
    unsureFrame.meta?.vlmGateReason !== 'yolo_empty') {
  // either reason is fine; prefer recording actual
  if (!unsureFrame.meta?.vlmGateReason) {
    failures.push('unsure path missing vlmGateReason');
  }
}

const passed = failures.length === 0;
const snapshot = {
  step: 'hybrid',
  name: 'yolo26_mlkit_gemini_gated',
  status: passed ? 'mock_verified' : 'mock_failed',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  geminiCalled: false,
  wallClockMs: Number(wallMs.toFixed(3)),
  reviewAnswers: {
    yoloObjectsMerged: frame.objects.length >= 2,
    mlkitOcrPrimary: frame.meta?.ocrSource === 'mlkit',
    skipGeminiWhenConfident: !shouldInvokeVlm(confidentNative),
    callGeminiWhenUnsure: shouldInvokeVlm(unsureNative),
    clockFromBoxWorks:
      clockFromBox({ x: 0.42, width: 0.2 }) === "12 o'clock",
  },
  failures,
  frameConfident: frame,
  frameUnsure: {
    vlmInvoked: unsureFrame.meta?.vlmInvoked,
    vlmGateReason: unsureFrame.meta?.vlmGateReason,
    objectCount: unsureFrame.objects.length,
  },
};

const outPath = path.join(root, 'snapshots', 'snapshot_hybrid_yolo_ocr_vlm.json');
fs.writeFileSync(outPath, JSON.stringify(snapshot, null, 2));

console.log(passed ? '✅ PASSED' : '❌ FAILED');
failures.forEach((f) => console.log(`   • ${f}`));
console.log(`⏱️  ${wallMs.toFixed(2)}ms`);
console.log(`📁 ${outPath}`);
console.log(
  `Confident gate: skip | Unsure gate: ${unsureFrame.meta?.vlmGateReason}`
);

if (!passed) process.exitCode = 1;
