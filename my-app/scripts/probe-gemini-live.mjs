/**
 * Live Gemini 2.0 Flash smoke test (real network).
 * Requires a real key in .env — never commit that file.
 *
 * Usage: node test/run_step2_live.mjs
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

function loadEnvFile() {
  const envPath = path.join(root, '.env');
  if (!fs.existsSync(envPath)) return;
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq < 0) continue;
    const key = trimmed.slice(0, eq).trim();
    let val = trimmed.slice(eq + 1).trim();
    if (
      (val.startsWith('"') && val.endsWith('"')) ||
      (val.startsWith("'") && val.endsWith("'"))
    ) {
      val = val.slice(1, -1);
    }
    if (!process.env[key]) process.env[key] = val;
  }
}

loadEnvFile();
process.env.PERCEPTION_MODE = 'live';
// Smoke: more headroom + retries for demand spikes
if (!process.env.GEMINI_TIMEOUT_MS) process.env.GEMINI_TIMEOUT_MS = '20000';
if (!process.env.GEMINI_MAX_RETRIES) process.env.GEMINI_MAX_RETRIES = '4';
if (!process.env.GEMINI_BACKOFF_MS) process.env.GEMINI_BACKOFF_MS = '1200';
if (!process.env.GEMINI_MIN_GAP_MS) process.env.GEMINI_MIN_GAP_MS = '1000';

const { processFrame, getPerceptionMode, GEMINI_MODEL } = await import(
  '../src/services/perceptionEngine.js'
);
const { hasValidGeminiApiKey } = await import('../src/services/envCheck.js');

console.log('====================================================');
console.log('   PERSON 1: STEP 2 LIVE GEMINI FLASH               ');
console.log('====================================================\n');

if (!hasValidGeminiApiKey()) {
  console.error(
    '❌ No real Gemini key in my-app/.env yet.\n' +
      '   1. Open https://aistudio.google.com/apikey\n' +
      '   2. Create a key (free tier is fine)\n' +
      '   3. Paste into my-app/.env replacing your_gemini_api_key_here\n' +
      '   4. Keep PERCEPTION_MODE=live\n' +
      '   5. Re-run: npm run test:step2:live'
  );
  process.exit(1);
}

console.log(`Mode: ${getPerceptionMode()} | model: ${GEMINI_MODEL} (Gemini key detected)\n`);

// Prefer a real JPEG fixture; fall back to embedded bytes
const fixturePath = path.join(root, 'test', 'fixtures', 'live_smoke.jpg');
let tinyJpegBase64;
if (fs.existsSync(fixturePath)) {
  tinyJpegBase64 = fs.readFileSync(fixturePath).toString('base64');
  console.log(`Using fixture: ${fixturePath} (${tinyJpegBase64.length} b64 chars)`);
} else {
  tinyJpegBase64 =
    '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAn/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBEQCEAwAAwAH/2Q==';
}

const t0 = performance.now();
const frame = await processFrame(tinyJpegBase64, { mode: 'live' });
const wallMs = performance.now() - t0;

const snapshot = {
  step: 2,
  name: 'perception_frame_live_gemini',
  status: frame.hazardDescription?.includes('connection error')
    ? 'live_error_fallback'
    : frame.hazardDescription?.includes('timed out')
      ? 'live_timeout_fallback'
      : 'live_ok',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  mode: 'live',
  geminiCalled: true,
  model: GEMINI_MODEL,
  wallClockMs: Number(wallMs.toFixed(1)),
  frame,
};

const outPath = path.join(root, 'snapshots', 'snapshot_step2_perception_frame_live.json');
fs.writeFileSync(outPath, JSON.stringify(snapshot, null, 2));

console.log(`⏱️  ${wallMs.toFixed(0)}ms (frame.latencyMs=${frame.latencyMs})`);
console.log(`Hazard: ${frame.immediateHazard} | ${frame.hazardDescription}`);
console.log(`Objects: ${frame.objects.length} | OCR: ${frame.text.length}`);
console.log(`📁 ${outPath}`);
console.log('====================================================');
