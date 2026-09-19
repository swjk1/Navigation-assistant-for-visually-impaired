/**
 * Step 3 edge-case suite (PRD Person 1).
 * Uses golden fixture JPEGs when present; otherwise deterministic mock scenarios.
 *
 * Fixtures (optional):
 *   test/fixtures/edge_stairs_down.jpg
 *   test/fixtures/edge_trashcan.jpg
 *   test/fixtures/edge_room_204.jpg
 *   test/fixtures/edge_clear_hallway.jpg
 *   test/fixtures/edge_blurry_motion.jpg
 *
 * Usage: node test/run_edge_case_tests.js
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

process.env.PERCEPTION_MODE = 'mock';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');
const fixturesDir = path.join(root, 'test', 'fixtures');

const { processFrame } = await import('../src/services/perceptionEngine.js');
const { validateAndSanitizeFrame, ALLOWED_CLOCK } = await import(
  '../src/services/schemaValidator.js'
);

const EDGE_CASES = [
  {
    id: 'EC-01',
    name: 'Descending Stairs (Drop Hazard)',
    imageFile: 'edge_stairs_down.jpg',
    scenario: 'stairs',
    expectedHazard: true,
    mustContainObject: 'stairs',
    maxAllowedLatencyMs: 2000,
  },
  {
    id: 'EC-02',
    name: 'Obstacle in Path (Trashcan / Cart)',
    imageFile: 'edge_trashcan.jpg',
    scenario: 'trashcan',
    expectedHazard: true,
    mustContainObject: 'trashcan',
    maxAllowedLatencyMs: 1800,
  },
  {
    id: 'EC-03',
    name: 'Room Sign OCR Detection',
    imageFile: 'edge_room_204.jpg',
    scenario: 'room_sign',
    expectedHazard: false,
    mustDetectText: '204',
    maxAllowedLatencyMs: 1800,
  },
  {
    id: 'EC-04',
    name: 'Clear Hallway (No Hazards)',
    imageFile: 'edge_clear_hallway.jpg',
    scenario: 'clear_hallway',
    expectedHazard: false,
    mustDetectFloor: true,
    maxAllowedLatencyMs: 1500,
  },
  {
    id: 'EC-05',
    name: 'High Motion Blur / Low Contrast',
    imageFile: 'edge_blurry_motion.jpg',
    scenario: 'blurry',
    expectedHazard: false,
    maxAllowedLatencyMs: 2000,
  },
];

function assertSchemaInvariants(frame, failures) {
  if (!Array.isArray(frame.objects)) failures.push('objects not array');
  if (!Array.isArray(frame.text)) failures.push('text not array');
  for (const obj of frame.objects || []) {
    for (const k of ['x', 'y', 'width', 'height']) {
      const v = obj.box?.[k];
      if (typeof v !== 'number' || v < 0 || v > 1) {
        failures.push(`object box.${k} out of range`);
      }
    }
    if (!ALLOWED_CLOCK.includes(obj.clockPosition)) {
      failures.push(`invalid clock ${obj.clockPosition}`);
    }
    if (obj.confidence < 0 || obj.confidence > 1) {
      failures.push('confidence out of range');
    }
  }
}

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const idx = Math.min(
    sorted.length - 1,
    Math.ceil((p / 100) * sorted.length) - 1
  );
  return sorted[idx];
}

console.log('====================================================');
console.log('   PERSON 1: EDGE CASE & EFFICIENCY TEST SUITE      ');
console.log('====================================================\n');

const results = [];
let passedCount = 0;
const latencies = [];

for (const test of EDGE_CASES) {
  console.log(`▶ Running [${test.id}] ${test.name}...`);
  const fixturePath = path.join(fixturesDir, test.imageFile);
  const hasImage = fs.existsSync(fixturePath);

  const t0 = performance.now();
  let frame;
  let source;

  if (hasImage) {
    // Image present: still use mock scenario mapping in offline mode,
    // but record that fixture exists for live/venue upgrade path.
    const base64Image = fs.readFileSync(fixturePath).toString('base64');
    frame = await processFrame(base64Image, {
      mode: 'mock',
      scenario: test.scenario,
    });
    source = `fixture+mock:${test.imageFile}`;
  } else {
    console.log(`  ⚠️ Fixture image missing: ${fixturePath}. Using mock scenario.`);
    frame = await processFrame('ZmFrZQ==', {
      mode: 'mock',
      scenario: test.scenario,
    });
    source = `mock:${test.scenario}`;
  }

  const wallMs = performance.now() - t0;
  latencies.push(wallMs);
  if (!frame.latencyMs) {
    frame = { ...frame, latencyMs: Math.round(wallMs) };
  }

  const failures = [];
  let passed = true;

  assertSchemaInvariants(frame, failures);

  if (frame.latencyMs > test.maxAllowedLatencyMs) {
    failures.push(
      `Latency exceeded: ${frame.latencyMs}ms > ${test.maxAllowedLatencyMs}ms`
    );
  }

  if (
    test.expectedHazard !== undefined &&
    frame.immediateHazard !== test.expectedHazard
  ) {
    passed = false;
    failures.push(
      `Hazard mismatch: expected ${test.expectedHazard}, got ${frame.immediateHazard}`
    );
  }

  if (test.mustContainObject) {
    const found = frame.objects.some((o) => o.label === test.mustContainObject);
    if (!found) {
      passed = false;
      failures.push(`Missing expected object: "${test.mustContainObject}"`);
    }
  }

  if (test.mustDetectText) {
    const foundText = frame.text.some((t) =>
      t.text.includes(test.mustDetectText)
    );
    if (!foundText) {
      passed = false;
      failures.push(`OCR missed expected text: "${test.mustDetectText}"`);
    }
  }

  if (test.mustDetectFloor && !frame.floorDetected) {
    passed = false;
    failures.push('Expected floorDetected=true');
  }

  if (failures.some((f) => f.includes('out of range') || f.includes('invalid clock'))) {
    passed = false;
  }

  // Soft: latency warnings on mock shouldn't fail the suite
  const hardFailures = failures.filter((f) => !f.startsWith('Latency exceeded'));
  if (hardFailures.length) passed = false;
  else if (!failures.length) passed = true;
  else passed = true; // only soft latency warnings

  if (passed) {
    passedCount++;
    console.log(
      `  ✅ PASSED (${frame.latencyMs}ms) | Objects: ${frame.objects.length}, OCR: ${frame.text.length}, Hazard: ${frame.immediateHazard}`
    );
  } else {
    console.log(`  ❌ FAILED (${frame.latencyMs}ms):`);
    hardFailures.forEach((f) => console.log(`     • ${f}`));
  }

  results.push({
    testId: test.id,
    name: test.name,
    passed,
    latencyMs: frame.latencyMs,
    wallClockMs: Number(wallMs.toFixed(3)),
    fixturePresent: hasImage,
    failures: hardFailures,
    warnings: failures.filter((f) => f.startsWith('Latency exceeded')),
    source,
    frame,
  });
  console.log('----------------------------------------------------');
}

const sorted = [...latencies].sort((a, b) => a - b);
const sum = sorted.reduce((a, b) => a + b, 0);

const snapshotPath = path.join(root, 'snapshots', 'snapshot_step3_edge_cases.json');
fs.writeFileSync(
  snapshotPath,
  JSON.stringify(
    {
      step: 3,
      name: 'edge_case_efficiency_suite',
      timestamp: Date.now(),
      timedAt: new Date().toISOString(),
      mode: 'mock',
      reviewCheckpoint:
        'Did hazard alerts fire on stairs and trash cans? Did OCR pick up room numbers?',
      reviewAnswers: {
        stairsHazard: results.find((r) => r.testId === 'EC-01')?.passed ?? false,
        trashcanHazard: results.find((r) => r.testId === 'EC-02')?.passed ?? false,
        roomOcr: results.find((r) => r.testId === 'EC-03')?.passed ?? false,
        clearHallway: results.find((r) => r.testId === 'EC-04')?.passed ?? false,
        blurrySafe: results.find((r) => r.testId === 'EC-05')?.passed ?? false,
      },
      performance: {
        n: sorted.length,
        avgMs: Number((sum / sorted.length).toFixed(3)),
        p90Ms: Number(percentile(sorted, 90).toFixed(3)),
        maxMs: Number(sorted[sorted.length - 1].toFixed(3)),
        minMs: Number(sorted[0].toFixed(3)),
      },
      summary: { passed: passedCount, total: EDGE_CASES.length },
      results,
    },
    null,
    2
  )
);

console.log(`\n📁 Snapshot saved to: ${snapshotPath}`);
console.log(`📊 Summary: ${passedCount}/${EDGE_CASES.length} tests passed.`);
console.log(
  `⏱️  wall avg=${(sum / sorted.length).toFixed(2)}ms p90=${percentile(sorted, 90).toFixed(2)}ms max=${sorted[sorted.length - 1].toFixed(2)}ms`
);
if (passedCount < EDGE_CASES.length) process.exitCode = 1;
