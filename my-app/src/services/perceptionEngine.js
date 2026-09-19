import { validateAndSanitizeFrame } from './schemaValidator.js';
import { hasValidGeminiApiKey, requireGeminiApiKey } from './envCheck.js';
import {
  MOCK_TIMEOUT_SAFE_FRAME,
  selectMockRawPayload,
} from '../constants/mockPerception.js';
import { NETWORK_TIMEOUT_FALLBACK_MS } from '../constants/cameraConfig.js';

function readTimeoutMs() {
  // Abort slightly above network fallback so Gemini can finish before clamp
  return (
    Number(process.env.GEMINI_TIMEOUT_MS) ||
    NETWORK_TIMEOUT_FALLBACK_MS + 500
  );
}

/** Primary + fallbacks when a model is retired or overloaded. */
export function getGeminiModelCandidates() {
  const primary = process.env.GEMINI_MODEL || 'gemini-3.6-flash';
  const extras = [
    'gemini-flash-latest',
    'gemini-flash-lite-latest',
    'gemini-3.8-flash',
    'gemini-3.5-flash',
  ];
  return [primary, ...extras.filter((m) => m !== primary)];
}

export const GEMINI_MODEL = getGeminiModelCandidates()[0];

const MAX_RETRIES = Number(process.env.GEMINI_MAX_RETRIES) || 3;
const BASE_BACKOFF_MS = Number(process.env.GEMINI_BACKOFF_MS) || 600;
/** Gap between Gemini calls — keep short so phone demos aren't blocked. */
const MIN_CALL_GAP_MS = Number(process.env.GEMINI_MIN_GAP_MS) || 800;

let lastGeminiCallAt = 0;

export const SYSTEM_PROMPT = `You are the real-time perception engine of an indoor assistive navigation guide for a visually impaired user.
Analyze the image strictly and output ONLY valid JSON matching this schema:
{
  "objects": [
    {
      "label": "door" | "person" | "stairs" | "elevator" | "chair" | "trashcan" | "wall" | "sign",
      "confidence": number (0.0 to 1.0),
      "box": { "x": number, "y": number, "width": number, "height": number },
      "clockPosition": "9 o'clock" | "10 o'clock" | "11 o'clock" | "12 o'clock" | "1 o'clock" | "2 o'clock" | "3 o'clock",
      "approxDistanceMeters": number
    }
  ],
  "text": [
    {
      "text": string (e.g. "ROOM 204", "EXIT", "RESTROOM"),
      "confidence": number,
      "box": { "x": number, "y": number, "width": number, "height": number }
    }
  ],
  "floorDetected": boolean,
  "immediateHazard": boolean,
  "hazardDescription": string or null
}

RULES:
1. immediateHazard MUST be true if stairs going down, wet floor signs, construction barriers, or ground obstacles are within 3 meters directly ahead.
2. Clock orientation: 12 o'clock is the center 20% vertical corridor; 10-11 is left; 1-2 is right; 9/3 are hard edges.
3. Be deterministic. Do not include markdown ticks or conversation.`;

export function getPerceptionMode() {
  const forced = (process.env.PERCEPTION_MODE || '').toLowerCase().trim();
  if (forced === 'mock' || forced === 'live') return forced;
  return hasValidGeminiApiKey() ? 'live' : 'mock';
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function respectMinCallGap() {
  const now = Date.now();
  const wait = MIN_CALL_GAP_MS - (now - lastGeminiCallAt);
  if (wait > 0) await sleep(wait);
}

function isRetryableStatus(status) {
  return status === 429 || status === 503 || status === 500 || status === 404;
}

/**
 * Offline path: prompt rules encoded as fixed fixtures → schema sanitize.
 */
export async function processFrameMock(options = {}) {
  const startTime = Date.now();
  const raw = selectMockRawPayload(options.scenario || 'hallway');
  const latencyMs = Date.now() - startTime;
  return validateAndSanitizeFrame(raw, latencyMs);
}

function extractJsonObject(rawText) {
  const cleaned = String(rawText || '')
    .replace(/```json/gi, '')
    .replace(/```/g, '')
    .trim();
  try {
    return JSON.parse(cleaned);
  } catch {
    const start = cleaned.indexOf('{');
    const end = cleaned.lastIndexOf('}');
    if (start >= 0 && end > start) {
      return JSON.parse(cleaned.slice(start, end + 1));
    }
    throw new Error('Unexpected end of JSON input');
  }
}

async function callGeminiOnce(base64Image, model, apiKey, signal) {
  // Prefer header auth — never put the key in the URL (logs / proxies).
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-goog-api-key': apiKey,
      },
      signal,
      body: JSON.stringify({
        contents: [
          {
            role: 'user',
            parts: [
              { text: SYSTEM_PROMPT },
              {
                inline_data: {
                  mime_type: 'image/jpeg',
                  data: base64Image,
                },
              },
            ],
          },
        ],
        generationConfig: {
          temperature: 0.1,
          maxOutputTokens: 1024,
          responseMimeType: 'application/json',
          // Gemini 3.x Flash defaults to "thinking" which blows the phone latency budget.
          thinkingConfig: { thinkingBudget: 0 },
        },
      }),
    }
  );

  if (!response.ok) {
    let detail = '';
    try {
      const errBody = await response.json();
      detail = errBody?.error?.message || JSON.stringify(errBody).slice(0, 180);
    } catch {
      detail = '';
    }
    const err = new Error(
      `API HTTP Error: ${response.status}${detail ? ` — ${detail}` : ''}`
    );
    err.status = response.status;
    err.retryable = isRetryableStatus(response.status);
    err.model = model;
    throw err;
  }

  const data = await response.json();
  const finish = data.candidates?.[0]?.finishReason;
  const rawText = data.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!rawText) {
    const err = new Error(
      `Empty candidate payload from Gemini (finishReason=${finish || 'unknown'})`
    );
    err.retryable = true;
    err.model = model;
    throw err;
  }

  try {
    return extractJsonObject(rawText);
  } catch (parseErr) {
    const err = new Error(`JSON parse failed: ${parseErr.message}`);
    err.retryable = true;
    err.model = model;
    throw err;
  }
}

/**
 * PRD Step 4 deterministic timeout frame (safe for TTS).
 * @param {number} latencyMs
 */
export function buildScanTimeoutFrame(latencyMs) {
  return validateAndSanitizeFrame(
    {
      ...MOCK_TIMEOUT_SAFE_FRAME,
      hazardDescription:
        'Vision service slow. Hold still and try again.',
    },
    latencyMs
  );
}

/**
 * Live Gemini Flash with retries / backoff / model fallbacks.
 * If total network time exceeds NETWORK_TIMEOUT_FALLBACK_MS, returns timeout frame.
 */
export async function processFrameLive(base64Image) {
  const API_KEY = requireGeminiApiKey();
  const timeoutMs = readTimeoutMs();
  const models = getGeminiModelCandidates();
  let lastError = null;

  // Rate-limit wait must NOT count against the network budget (otherwise
  // back-to-back captures "timeout" even with a still photo).
  await respectMinCallGap();
  const startTime = Date.now();
  lastGeminiCallAt = startTime;

  let modelIndex = 0;
  for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
    // If already past network budget, stop and return safe frame
    if (Date.now() - startTime > NETWORK_TIMEOUT_FALLBACK_MS) {
      return buildScanTimeoutFrame(Date.now() - startTime);
    }

    const model = models[Math.min(modelIndex, models.length - 1)];
    const remaining = Math.min(
      timeoutMs - (Date.now() - startTime),
      NETWORK_TIMEOUT_FALLBACK_MS - (Date.now() - startTime) + 50
    );
    if (remaining < 400) break;

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), remaining);

    try {
      const parsed = await callGeminiOnce(
        base64Image,
        model,
        API_KEY,
        controller.signal
      );
      clearTimeout(timeoutId);
      const latencyMs = Date.now() - startTime;

      if (latencyMs > NETWORK_TIMEOUT_FALLBACK_MS) {
        return buildScanTimeoutFrame(latencyMs);
      }

      const frame = validateAndSanitizeFrame(parsed, latencyMs);
      return {
        ...frame,
        meta: {
          geminiModel: model,
          geminiAttempts: attempt + 1,
        },
      };
    } catch (err) {
      clearTimeout(timeoutId);
      lastError = err;

      if (err?.name === 'AbortError') {
        break;
      }

      const retryable =
        err?.retryable ||
        err?.status === 503 ||
        err?.status === 429 ||
        err?.status === 404;
      if (!retryable || attempt === MAX_RETRIES - 1) {
        break;
      }

      // On 404/429/503, flip to next model immediately (don't burn budget on same model)
      if (err?.status === 404 || err?.status === 503 || err?.status === 429) {
        modelIndex = Math.min(modelIndex + 1, models.length - 1);
      }

      const room = NETWORK_TIMEOUT_FALLBACK_MS - (Date.now() - startTime);
      if (room < 800) break;

      // Short pause only — long exponential backoff caused phone "vision slow" timeouts
      const pause = err?.status === 429 ? 400 : Math.min(BASE_BACKOFF_MS, room - 500);
      await sleep(Math.max(200, pause));
    }
  }

  const latencyMs = Date.now() - startTime;
  const timedOut =
    lastError?.name === 'AbortError' ||
    latencyMs > NETWORK_TIMEOUT_FALLBACK_MS ||
    latencyMs >= timeoutMs - 50;

  if (timedOut) {
    return buildScanTimeoutFrame(latencyMs);
  }

  const quotaHit = lastError?.status === 429;
  return validateAndSanitizeFrame(
    {
      objects: [],
      text: [],
      floorDetected: false,
      immediateHazard: true,
      hazardDescription: quotaHit
        ? 'Vision quota reached. Wait one minute then retry.'
        : 'Perception temporarily unavailable. Hold position.',
    },
    latencyMs
  );
}

/**
 * Main entry: camera Base64 → verified PerceptionFrame.
 */
export async function processFrame(base64Image, options = {}) {
  const mode = options.mode || getPerceptionMode();

  if (mode === 'mock') {
    return processFrameMock({ scenario: options.scenario });
  }

  if (!base64Image || typeof base64Image !== 'string') {
    throw new Error('processFrame(live): base64Image is required');
  }

  return processFrameLive(base64Image);
}
