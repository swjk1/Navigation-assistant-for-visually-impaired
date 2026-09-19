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
const img = fs
  .readFileSync(path.join(root, 'test/fixtures/edge_clear_hallway.jpg'))
  .toString('base64');

async function tryOnce(model, withThinkingOff) {
  const t0 = Date.now();
  const generationConfig = {
    temperature: 0.1,
    maxOutputTokens: 1024,
    responseMimeType: 'application/json',
  };
  if (withThinkingOff) {
    generationConfig.thinkingConfig = { thinkingBudget: 0 };
  }
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-goog-api-key': key,
      },
      body: JSON.stringify({
        contents: [
          {
            role: 'user',
            parts: [
              {
                text: 'Return ONLY JSON with keys objects,text,floorDetected,immediateHazard,hazardDescription. Keep objects empty if unsure.',
              },
              { inline_data: { mime_type: 'image/jpeg', data: img } },
            ],
          },
        ],
        generationConfig,
      }),
    }
  );
  const ms = Date.now() - t0;
  const body = await res.json();
  const text = body?.candidates?.[0]?.content?.parts?.[0]?.text || '';
  const finish = body?.candidates?.[0]?.finishReason;
  console.log(
    `\n[${model}] thinkingOff=${withThinkingOff} HTTP ${res.status} ${ms}ms finish=${finish}`
  );
  console.log('textPrefix:', String(text).slice(0, 120));
  if (body?.error) console.log('error:', body.error.message);
}

await tryOnce('gemini-3.6-flash', true);
await tryOnce('gemini-3.6-flash', false);
await tryOnce('gemini-flash-lite-latest', true);
await tryOnce('gemini-flash-latest', true);
