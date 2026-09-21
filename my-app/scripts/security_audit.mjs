/**
 * Security audit — fail if secrets look committed or present in snapshots/docs.
 * Does NOT print secret values.
 *
 * Usage: node scripts/security_audit.mjs
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');
const repoRoot = path.join(root, '..');

const findings = [];

function add(ok, id, detail) {
  findings.push({ ok, id, detail });
  console.log(`${ok ? '✅' : '❌'} [${id}] ${detail}`);
}

// 1) .env must exist locally optionally, but must be gitignored
try {
  const ignored = execSync('git check-ignore -v my-app/.env', {
    cwd: repoRoot,
    encoding: 'utf8',
  }).trim();
  add(Boolean(ignored), 'gitignore-env', ignored || '.env not ignored');
} catch {
  try {
    const ignored = execSync('git check-ignore -v --no-index my-app/.env', {
      cwd: repoRoot,
      encoding: 'utf8',
    }).trim();
    add(Boolean(ignored), 'gitignore-env', ignored);
  } catch {
    add(false, 'gitignore-env', 'git check-ignore failed for my-app/.env');
  }
}

// 2) .env must not be tracked
try {
  const tracked = execSync('git ls-files my-app/.env .env', {
    cwd: repoRoot,
    encoding: 'utf8',
  }).trim();
  add(!tracked, 'env-not-tracked', tracked ? `TRACKED: ${tracked}` : 'not tracked');
} catch {
  add(false, 'env-not-tracked', 'git ls-files failed');
}

// 3) .env.example must only contain placeholders
const examplePath = path.join(root, '.env.example');
if (fs.existsSync(examplePath)) {
  const text = fs.readFileSync(examplePath, 'utf8');
  const hasPlaceholder = text.includes('your_gemini_api_key_here');
  const hasRealLooking =
    /API_KEY=(?!your_gemini_api_key_here)[A-Za-z0-9._\-]{20,}/m.test(text);
  add(
    hasPlaceholder && !hasRealLooking,
    'env-example-placeholder',
    hasRealLooking
      ? 'possible real key in .env.example'
      : 'placeholder only'
  );
  const privateLine = text
    .split(/\r?\n/)
    .find((l) => /^\s*GEMINI_API_KEY=/.test(l));
  const publicLine = text
    .split(/\r?\n/)
    .find((l) => /^\s*EXPO_PUBLIC_GEMINI_API_KEY=/.test(l));
  add(
    Boolean(privateLine) && !publicLine,
    'env-example-prefers-private',
    privateLine && !publicLine
      ? 'GEMINI_API_KEY documented; EXPO_PUBLIC_ not required'
      : 'prefer uncommented GEMINI_API_KEY over EXPO_PUBLIC_'
  );
} else {
  add(false, 'env-example-placeholder', '.env.example missing');
}

// 4) Scan snapshots + docs + src for key-like material (patterns only)
const secretPatterns = [
  { name: 'google-ai-aq', re: /\bAQ\.[A-Za-z0-9_\-]{30,}\b/ },
  { name: 'google-aiza', re: /\bAIza[0-9A-Za-z_\-]{30,}\b/ },
  {
    name: 'env-assignment',
    re: /(?:EXPO_PUBLIC_)?GEMINI_API_KEY\s*=\s*(?!your_gemini_api_key_here)(?!AIzaSy\.\.\.)([A-Za-z0-9._\-]{20,})/,
  },
];

const scanRoots = [
  path.join(root, 'snapshots'),
  path.join(root, 'docs'),
  path.join(root, 'src'),
  path.join(root, 'test'),
  path.join(root, 'README.md'),
];

function walk(filePath, files = []) {
  if (!fs.existsSync(filePath)) return files;
  const st = fs.statSync(filePath);
  if (st.isDirectory()) {
    for (const name of fs.readdirSync(filePath)) {
      if (name === 'node_modules' || name === '.git') continue;
      walk(path.join(filePath, name), files);
    }
  } else if (/\.(js|mjs|ts|tsx|md|json|txt)$/i.test(filePath)) {
    files.push(filePath);
  }
  return files;
}

const files = scanRoots.flatMap((p) => walk(p));
let leakHits = 0;
for (const file of files) {
  if (file.endsWith(`${path.sep}.env`) || file.endsWith('/.env')) continue;
  const text = fs.readFileSync(file, 'utf8');
  for (const { name, re } of secretPatterns) {
    if (re.test(text)) {
      leakHits += 1;
      add(
        false,
        `secret-scan-${name}`,
        `pattern matched in ${path.relative(root, file)}`
      );
    }
  }
}
if (leakHits === 0) {
  add(true, 'secret-scan', `scanned ${files.length} files — no key patterns`);
}

// 5) Live Gemini must use header auth (no ?key= in URL)
const engine = fs.readFileSync(
  path.join(root, 'src', 'services', 'perceptionEngine.js'),
  'utf8'
);
add(
  engine.includes('x-goog-api-key') &&
    !engine.includes('generateContent?key='),
  'header-api-key',
  'Gemini calls use x-goog-api-key header (not query string)'
);
add(
  engine.includes('Perception temporarily unavailable') ||
    engine.includes('Hold position'),
  'safe-error-copy',
  'connection errors use TTS-safe hazard copy (no raw API details)'
);

// 6) envCheck states the on-device exposure rather than implying the key is protected.
//
// The previous version of this check asserted that envCheck PREFERRED GEMINI_API_KEY over
// EXPO_PUBLIC_GEMINI_API_KEY and reported that as a security property. It is not one: Expo only
// inlines EXPO_PUBLIC_ names into the client bundle, so on a phone the private name is always
// undefined and the bundled one is the only key that can be found. Ordering them changed
// nothing about what ships in the APK, so the audit was passing on a fact with no bearing on
// the exposure. What is worth asserting is that the code says so plainly.
const envCheck = fs.readFileSync(
  path.join(root, 'src', 'services', 'envCheck.js'),
  'utf8'
);
add(
  /EXPO_PUBLIC_/.test(envCheck) && /extract|bundle/i.test(envCheck),
  'documents-key-exposure',
  'envCheck documents that the EXPO_PUBLIC_ key ships inside the app bundle'
);

const passed = findings.every((f) => f.ok);
const out = {
  step: 'security',
  name: 'security_audit',
  timestamp: Date.now(),
  timedAt: new Date().toISOString(),
  status: passed ? 'pass' : 'fail',
  findings,
};

fs.mkdirSync(path.join(root, 'snapshots'), { recursive: true });
fs.writeFileSync(
  path.join(root, 'snapshots', 'snapshot_security_audit.json'),
  JSON.stringify(out, null, 2)
);

console.log(`\n📊 Security audit: ${passed ? 'PASS' : 'FAIL'}`);
console.log(`📁 snapshots/snapshot_security_audit.json`);
if (!passed) process.exitCode = 1;
