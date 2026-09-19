import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
for (const line of fs.readFileSync(path.join(root, '.env'), 'utf8').split(/\r?\n/)) {
  const t = line.trim();
  if (!t || t.startsWith('#')) continue;
  const i = t.indexOf('=');
  if (i < 0) continue;
  const k = t.slice(0, i).trim();
  const v = t.slice(i + 1).trim();
  if (!process.env[k]) process.env[k] = v;
}

const key = process.env.GEMINI_API_KEY;
const res = await fetch(
  `https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(key)}`
);
const data = await res.json();
const names = (data.models || [])
  .map((m) => m.name?.replace('models/', ''))
  .filter((n) => n && /flash/i.test(n))
  .slice(0, 40);
console.log('HTTP', res.status);
console.log(names.join('\n'));
