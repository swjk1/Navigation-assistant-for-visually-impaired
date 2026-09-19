/**
 * Snapshot: teammate handoff contract (Person 2 + Person 3 TTS).
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

const {
  getMockTeammateHandoff,
  getMockPerceptionFrame,
  listSpeakableLines,
} = await import('../src/index.js');

const hallway = getMockTeammateHandoff('hallway');
const stairs = getMockTeammateHandoff('stairs');

const failures = [];
if (!hallway.frame?.objects?.some((o) => o.label === 'door')) {
  failures.push('hallway missing door anchor');
}
if (!hallway.speakableLines?.some((l) => /door/i.test(l))) {
  failures.push('hallway missing speakable door line');
}
if (!hallway.signs?.some((s) => s.includes('204'))) {
  failures.push('hallway missing ROOM 204 for TTS confirm');
}
if (stairs.speechPriority !== 'hazard') {
  failures.push('stairs should be speechPriority=hazard');
}
if (!stairs.speakableLines?.[0]?.toLowerCase().includes('stop') &&
    !stairs.frame.immediateHazard) {
  failures.push('stairs hazard handoff incomplete');
}

const passed = failures.length === 0;
const snapshot = {
  step: 'handoff',
  name: 'teammate_integration_contract',
  status: passed ? 'verified' : 'failed',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  note: 'Person 1 vision → Person 2 nav anchors + Person 3 speakable lines (no TTS engine here).',
  reviewAnswers: {
    doorReachableViaClockLanguage: hallway.speakableLines.some((l) =>
      /11 o'clock/i.test(l)
    ),
    ocrAvailableForDestinationConfirm: hallway.signs.some((s) =>
      s.includes('204')
    ),
    hazardPreemptsGuidance: stairs.speechPriority === 'hazard',
  },
  failures,
  examples: {
    hallway,
    stairsSpeakable: listSpeakableLines(getMockPerceptionFrame('stairs')),
  },
};

const out = path.join(root, 'snapshots', 'snapshot_teammate_handoff.json');
fs.writeFileSync(out, JSON.stringify(snapshot, null, 2));
console.log(passed ? '✅ Teammate handoff verified' : '❌ Handoff failed');
failures.forEach((f) => console.log(' •', f));
console.log('📁', out);
console.log('Speakable (hallway):');
hallway.speakableLines.forEach((l) => console.log('  -', l));
if (!passed) process.exitCode = 1;
