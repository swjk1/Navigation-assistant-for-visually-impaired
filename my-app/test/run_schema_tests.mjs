/**
 * Step 3 — schema regression tests (bounds, clocks, labels, truncation).
 * Usage: node test/run_schema_tests.mjs
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import {
  validateAndSanitizeFrame,
  ALLOWED_CLOCK,
  ALLOWED_LABELS,
} from '../src/services/schemaValidator.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

const cases = [];

function run(name, fn) {
  const t0 = performance.now();
  try {
    fn();
    cases.push({ name, passed: true, ms: performance.now() - t0 });
    console.log(`  ✅ ${name}`);
  } catch (err) {
    cases.push({
      name,
      passed: false,
      ms: performance.now() - t0,
      error: err.message,
    });
    console.log(`  ❌ ${name}: ${err.message}`);
  }
}

console.log('====================================================');
console.log('   PERSON 1: SCHEMA VALIDATOR REGRESSIONS           ');
console.log('====================================================\n');

run('rejects non-object root', () => {
  let threw = false;
  try {
    validateAndSanitizeFrame(null, 1);
  } catch {
    threw = true;
  }
  assert(threw, 'expected throw');
});

run('clamps out-of-range box coords to 0..1', () => {
  const frame = validateAndSanitizeFrame(
    {
      objects: [
        {
          label: 'door',
          confidence: 2.5,
          box: { x: -1, y: 5, width: 3, height: -0.2 },
          clockPosition: "12 o'clock",
          approxDistanceMeters: 0.1,
        },
      ],
      text: [],
      floorDetected: true,
      immediateHazard: false,
      hazardDescription: null,
    },
    12
  );
  const b = frame.objects[0].box;
  assert(b.x === 0 && b.y === 1, 'x/y clamp');
  assert(b.width === 1 && b.height === 0, 'w/h clamp');
  assert(frame.objects[0].confidence === 1, 'confidence clamp');
  assert(frame.objects[0].approxDistanceMeters >= 0.5, 'distance floor');
});

run('drops unknown labels', () => {
  const frame = validateAndSanitizeFrame(
    {
      objects: [
        { label: 'dragon', confidence: 1, box: {}, clockPosition: "12 o'clock" },
        { label: 'person', confidence: 0.9, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.4 }, clockPosition: "11 o'clock" },
      ],
      text: [],
      floorDetected: false,
      immediateHazard: false,
    },
    5
  );
  assert(frame.objects.length === 1, 'only person kept');
  assert(frame.objects[0].label === 'person', 'label person');
});

run('invalid clock falls back to 12 o\'clock', () => {
  const frame = validateAndSanitizeFrame(
    {
      objects: [
        {
          label: 'chair',
          confidence: 0.7,
          box: { x: 0.2, y: 0.2, width: 0.1, height: 0.1 },
          clockPosition: 'north',
        },
      ],
      text: [],
      floorDetected: true,
      immediateHazard: false,
    },
    3
  );
  assert(frame.objects[0].clockPosition === "12 o'clock", 'clock fallback');
});

run('truncates hazardDescription and OCR text', () => {
  const long = 'H'.repeat(200);
  const frame = validateAndSanitizeFrame(
    {
      objects: [],
      text: [{ text: `ROOM ${'9'.repeat(80)}`, confidence: 0.9, box: {} }],
      floorDetected: false,
      immediateHazard: true,
      hazardDescription: long,
    },
    2
  );
  assert(frame.hazardDescription.length <= 100, 'hazard truncate');
  assert(frame.text[0].text.length <= 50, 'ocr truncate');
});

run('non-array objects/text become empty arrays', () => {
  const frame = validateAndSanitizeFrame(
    {
      objects: 'nope',
      text: 42,
      floorDetected: 1,
      immediateHazard: 0,
    },
    1
  );
  assert(Array.isArray(frame.objects) && frame.objects.length === 0, 'objects');
  assert(Array.isArray(frame.text) && frame.text.length === 0, 'text');
  assert(frame.floorDetected === true, 'bool coerce floor');
  assert(frame.immediateHazard === false, 'bool coerce hazard');
});

run('allowed label/clock catalogs match PRD', () => {
  assert(ALLOWED_LABELS.includes('trashcan'), 'trashcan label');
  assert(ALLOWED_LABELS.includes('stairs'), 'stairs label');
  assert(ALLOWED_CLOCK.includes("9 o'clock"), '9 oclock');
  assert(ALLOWED_CLOCK.includes("3 o'clock"), '3 oclock');
  assert(ALLOWED_CLOCK.length === 7, '7 clock positions');
});

const passed = cases.filter((c) => c.passed).length;
const snapshot = {
  step: 3,
  name: 'schema_validator_regressions',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  summary: { passed, total: cases.length },
  cases,
};

const out = path.join(root, 'snapshots', 'snapshot_step3_schema.json');
fs.writeFileSync(out, JSON.stringify(snapshot, null, 2));
console.log(`\n📊 ${passed}/${cases.length} schema tests passed`);
console.log(`📁 ${out}`);
if (passed < cases.length) process.exitCode = 1;
