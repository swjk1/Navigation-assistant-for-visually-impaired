import { validateAndSanitizeFrame } from './schemaValidator.js';
import { hasValidGeminiApiKey, requireGeminiApiKey } from './envCheck.js';
import {
  MOCK_TIMEOUT_SAFE_FRAME,
  selectMockRawPayload,
} from '../constants/mockPerception.js';
import { NETWORK_TIMEOUT_FALLBACK_MS } from '../constants/cameraConfig.js';

/** @typedef {import('../types/perception').PerceptionFrame} PerceptionFrame */
/** @typedef {import('../constants/mockPerception.js').MockScenario} MockScenario */
/** @typedef {import('./hybridPerception.js').RawFrame} RawFrame */

/**
 * A Gemini call that failed in a way the retry loop needs to reason about.
 *
 * This used to be a plain `Error` with `status` / `retryable` / `model` bolted on at each throw
 * site, which meant the retry loop read properties the type system knew nothing about - and a
 * typo in any of them would silently disable retrying rather than fail loudly.
 */
export class GeminiCallError extends Error {
  /**
   * @param {string} message
   * @param {{ status?: number, retryable?: boolean, model?: string }} [info]
   */
  constructor(message, info = {}) {
    super(message);
    this.name = 'GeminiCallError';
    /** HTTP status, when the failure came back from the API at all. */
    this.status = info.status;
    /** Whether trying again (usually against the next model) could plausibly work. */
    this.retryable = info.retryable ?? false;
    /** Which model candidate produced this. */
    this.model = info.model;
  }
}

/**
 * Narrow an unknown thrown value to just the fields the retry loop reads.
 *
 * @param {unknown} err
 * @returns {{ name?: string, status?: number, retryable?: boolean }}
 */
function errorInfo(err) {
  if (err instanceof GeminiCallError) {
    return { name: err.name, status: err.status, retryable: err.retryable };
  }
  if (err instanceof Error) return { name: err.name };
  return {};
}

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

/**
 * @param {number} ms
 * @returns {Promise<void>}
 */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function respectMinCallGap() {
  const now = Date.now();
  const wait = MIN_CALL_GAP_MS - (now - lastGeminiCallAt);
  if (wait > 0) await sleep(wait);
}

/**
 * @param {number} status
 * @returns {boolean}
 */
function isRetryableStatus(status) {
  return status === 429 || status === 503 || status === 500 || status === 404;
}

/**
 * Offline path: the prompt rules encoded as fixed fixtures, then run through the same validator
 * the live path uses - so a fixture cannot pass a check a real frame would fail.
 *
 * @param {{ scenario?: MockScenario }} [options]
 * @returns {Promise<PerceptionFrame>}
 */
export async function processFrameMock(options = {}) {
  const startTime = Date.now();
  const raw = selectMockRawPayload(options.scenario || 'hallway');
  const latencyMs = Date.now() - startTime;
  return validateAndSanitizeFrame(raw, latencyMs);
}

/**
 * Pull the JSON object out of a model response that may be wrapped in markdown fences.
 *
 * @param {unknown} rawText
 * @returns {RawFrame}
 * @throws {Error} If there is no parseable object anywhere in the text.
 */
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

/**
 * @param {string} base64Image
 * @param {string} model
 * @param {string} apiKey
 * @param {AbortSignal} signal
 * @returns {Promise<RawFrame>}
 * @throws {GeminiCallError}
 */
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
    throw new GeminiCallError(
      `API HTTP Error: ${response.status}${detail ? ` — ${detail}` : ''}`,
      {
        status: response.status,
        retryable: isRetryableStatus(response.status),
        model,
      }
    );
  }

  const data = await response.json();
  const finish = data.candidates?.[0]?.finishReason;
  const rawText = data.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!rawText) {
    throw new GeminiCallError(
      `Empty candidate payload from Gemini (finishReason=${finish || 'unknown'})`,
      { retryable: true, model }
    );
  }

  try {
    return extractJsonObject(rawText);
  } catch (parseErr) {
    const detail = parseErr instanceof Error ? parseErr.message : String(parseErr);
    throw new GeminiCallError(`JSON parse failed: ${detail}`, {
      retryable: true,
      model,
    });
  }
}

/**
 * The deterministic frame returned when perception ran out of time.
 *
 * Safe to speak verbatim, and deliberately NOT an empty frame: "nothing detected" reads to the
 * guidance layer as "clear ahead", which is the opposite of what a timeout means.
 *
 * @param {number} latencyMs
 * @returns {PerceptionFrame}
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
 * Live Gemini Flash with retries, backoff and model fallbacks.
 *
 * Never throws on a network failure: past `NETWORK_TIMEOUT_FALLBACK_MS` it returns the
 * deterministic timeout frame instead, because a walking user needs an answer more than they
 * need an accurate one.
 *
 * @param {string} base64Image
 * @returns {Promise<PerceptionFrame & { meta?: Record<string, any> }>}
 */
export async function processFrameLive(base64Image) {
  const API_KEY = requireGeminiApiKey();
  const timeoutMs = readTimeoutMs();
  const models = getGeminiModelCandidates();
  /** @type {unknown} */
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
      const info = errorInfo(err);

      if (info.name === 'AbortError') {
        break;
      }

      const retryable =
        info.retryable ||
        info.status === 503 ||
        info.status === 429 ||
        info.status === 404;
      if (!retryable || attempt === MAX_RETRIES - 1) {
        break;
      }

      // On 404/429/503, flip to next model immediately (don't burn budget on same model)
      if (info.status === 404 || info.status === 503 || info.status === 429) {
        modelIndex = Math.min(modelIndex + 1, models.length - 1);
      }

      const room = NETWORK_TIMEOUT_FALLBACK_MS - (Date.now() - startTime);
      if (room < 800) break;

      // Short pause only — long exponential backoff caused phone "vision slow" timeouts
      const pause = info.status === 429 ? 400 : Math.min(BASE_BACKOFF_MS, room - 500);
      await sleep(Math.max(200, pause));
    }
  }

  const latencyMs = Date.now() - startTime;
  const lastInfo = errorInfo(lastError);
  const timedOut =
    lastInfo.name === 'AbortError' ||
    latencyMs > NETWORK_TIMEOUT_FALLBACK_MS ||
    latencyMs >= timeoutMs - 50;

  if (timedOut) {
    return buildScanTimeoutFrame(latencyMs);
  }

  const quotaHit = lastInfo.status === 429;
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
 * Main entry: camera Base64 to a verified PerceptionFrame.
 *
 * @param {string | null | undefined} base64Image
 * @param {{ mode?: 'mock' | 'live', scenario?: MockScenario }} [options]
 * @returns {Promise<PerceptionFrame & { meta?: Record<string, any> }>}
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
