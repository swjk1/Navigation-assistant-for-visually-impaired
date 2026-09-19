import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const envPath = path.join(root, '.env');
for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
  const t = line.trim();
  if (!t || t.startsWith('#')) continue;
  const i = t.indexOf('=');
  if (i < 0) continue;
  const k = t.slice(0, i).trim();
  let v = t.slice(i + 1).trim();
  if (!process.env[k]) process.env[k] = v;
}

const key =
  process.env.GEMINI_API_KEY || process.env.EXPO_PUBLIC_GEMINI_API_KEY || '';
console.log(`keyPrefix=${key.slice(0, 7)}... len=${key.length}`);

const models = [
  'gemini-2.0-flash',
  'gemini-2.0-flash-001',
  'gemini-flash-latest',
  'gemini-1.5-flash',
];

for (const model of models) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
  const body = {
    contents: [{ role: 'user', parts: [{ text: 'Say hi in one word.' }] }],
    generationConfig: { temperature: 0.1, maxOutputTokens: 16 },
  };
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-goog-api-key': key,
      },
      body: JSON.stringify(body),
    });
    const text = await res.text();
    const redacted = text.replaceAll(key, '[REDACTED]').slice(0, 400);
    console.log(`\n[${model}] HTTP ${res.status}`);
    console.log(redacted);
    if (res.ok) break;
  } catch (err) {
    console.log(`\n[${model}] FETCH ERROR: ${err.message}`);
  }
}
