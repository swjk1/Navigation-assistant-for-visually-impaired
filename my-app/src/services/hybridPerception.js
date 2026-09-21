/**
 * Hybrid perception: YOLO26n + ML Kit OCR, Gemini only when needed.
 *
 * Gate policy (sparse — empty YOLO/OCR alone does NOT force Gemini):
 *  - real native errors (YOLO/OCR failure)
 *  - OCR returned text but confidence is low
 *  - caution keywords in OCR
 *  - periodic everyNth / forceVlm / vlmOnTop
 */

import { validateAndSanitizeFrame, ALLOWED_CLOCK } from './schemaValidator.js';
import { processFrameLive, getPerceptionMode } from './perceptionEngine.js';
import { selectMockRawPayload } from '../constants/mockPerception.js';
import { hasValidGeminiApiKey } from './envCheck.js';
import { CAMERA_CAPTURE_CONFIG } from '../constants/cameraConfig.js';

/** @typedef {import('../types/perception').PerceptionFrame} PerceptionFrame */
/** @typedef {import('../types/perception').BoundingBox} BoundingBox */
/** @typedef {import('../types/perception').ClockDirection} ClockDirection */

/**
 * What the native module hands back, plus the one field it does not set.
 *
 * `floorDetected` is only ever known by the VLM - YOLO and ML Kit have no opinion on it - so it
 * is optional here and defaults to false rather than being assumed true. Assuming a floor is
 * present when nothing looked for one is the single most dangerous default in this file.
 *
 * @typedef {Partial<import('indoor-perception').NativePerceptionResult> & {
 *   floorDetected?: boolean,
 * }} NativeLike
 */

/**
 * A raw, UNVALIDATED frame payload: what Gemini returns, and what [mergeNativeToRaw] produces.
 * It becomes a `PerceptionFrame` only by passing through `validateAndSanitizeFrame`.
 *
 * @typedef {{
 *   objects?: Array<Record<string, any>>,
 *   text?: Array<Record<string, any>>,
 *   floorDetected?: boolean,
 *   immediateHazard?: boolean,
 *   hazardDescription?: string | null,
 * }} RawFrame
 */

/**
 * @typedef {object} VlmGatePolicy
 * @property {boolean} [forceVlm] Always call Gemini.
 * @property {boolean} [vlmOnTop] Call Gemini even when the native result looks fine.
 * @property {number} [everyNth] Call Gemini on every Nth frame. 0 disables.
 * @property {number} [frameIndex] Required for `everyNth` to mean anything.
 */

/**
 * @typedef {VlmGatePolicy & {
 *   nativeResult?: NativeLike | null,
 *   confThreshold?: number,
 *   runYolo?: boolean,
 *   runOcr?: boolean,
 *   allowLiveVlm?: boolean,
 *   vlmEveryNth?: number,
 *   useMockVlmOnGate?: boolean,
 *   mockVlmScenario?: import('../constants/mockPerception.js').MockScenario,
 * }} HybridOptions
 */

const VLM_FILL_LABELS = new Set([
  'door',
  'stairs',
  'elevator',
  'trashcan',
  'wall',
  'sign',
]);

const MLKIT_CONF_UNSURE = 0.55;

/**
 * @param {Partial<BoundingBox> | null | undefined} box
 * @returns {ClockDirection}
 */
export function clockFromBox(box) {
  const cx = (Number(box?.x) || 0) + (Number(box?.width) || 0) / 2;
  if (cx < 0.12) return "9 o'clock";
  if (cx < 0.28) return "10 o'clock";
  if (cx < 0.4) return "11 o'clock";
  if (cx < 0.6) return "12 o'clock";
  if (cx < 0.72) return "1 o'clock";
  if (cx < 0.88) return "2 o'clock";
  return "3 o'clock";
}

/**
 * @param {Partial<BoundingBox> | null | undefined} box
 * @returns {number} Meters, clamped to the catalog's sane range.
 */
export function distanceFromBox(box) {
  const h = Math.max(0.01, Number(box?.height) || 0.1);
  const meters = 0.4 / h;
  return Math.max(0.5, Math.min(12, Number(meters.toFixed(1))));
}

/**
 * @param {Array<{ text?: unknown }> | null | undefined} [textItems]
 * @returns {boolean}
 */
function hazardHintsFromOcr(textItems = []) {
  const joined = (textItems ?? [])
    .map((t) => String(t.text || '').toUpperCase())
    .join(' ');
  const keywords = [
    'CAUTION',
    'WET',
    'DANGER',
    'STAIR',
    'EXIT ONLY',
    'CONSTRUCTION',
    'KEEP OUT',
  ];
  return keywords.some((k) => joined.includes(k));
}

/**
 * ML Kit unsure = OCR errored, OR text present but low confidence.
 * Empty OCR is normal in blank hallways — does not force Gemini.
 */
/**
 * @param {NativeLike | null | undefined} native
 * @returns {boolean}
 */
export function isMlKitUnsure(native) {
  if (native?.ocrError) return true;
  const texts = native?.text || [];
  if (!texts.length) return false;
  const best = Math.max(...texts.map((t) => Number(t.confidence) || 0));
  return best < MLKIT_CONF_UNSURE;
}

/**
 * YOLO unsure = real failure, not merely empty COCO detections.
 * Empty objects are expected when only doors/stairs are visible (not in COCO map).
 */
/**
 * @param {NativeLike | null | undefined} native
 * @returns {boolean}
 */
export function isYoloUnsure(native) {
  if (native?.yoloError) return true;
  if (native?.platform === 'unavailable') return true;
  if (native?.modelLoaded === false && native?.platform === 'android') {
    return true;
  }
  return false;
}

/**
 * Decide whether to call Gemini (sparse by default).
 */
/**
 * @param {NativeLike | null | undefined} native
 * @param {VlmGatePolicy} [policy]
 * @returns {boolean}
 */
export function shouldInvokeVlm(native, policy = {}) {
  if (policy.forceVlm === true) return true;
  if (policy.vlmOnTop === true) return true;

  if (isMlKitUnsure(native)) return true;
  if (isYoloUnsure(native)) return true;
  if (hazardHintsFromOcr(native?.text)) return true;

  const everyNth = policy.everyNth ?? 0;
  if (everyNth > 0 && typeof policy.frameIndex === 'number') {
    return policy.frameIndex % everyNth === 0;
  }
  return false;
}

/**
 * The human-readable reason [shouldInvokeVlm] decided as it did. Kept in lockstep with it -
 * a gate you cannot explain is a gate you cannot tune.
 *
 * @param {NativeLike | null | undefined} native
 * @param {VlmGatePolicy} [policy]
 * @returns {string}
 */
export function explainVlmGate(native, policy = {}) {
  if (policy.forceVlm) return 'forced';
  if (policy.vlmOnTop) return 'vlm_on_top';
  if (native?.ocrError) return 'mlkit_error';
  if (isMlKitUnsure(native)) return 'mlkit_low_confidence';
  if (native?.yoloError) return 'yolo_error';
  if (native?.platform === 'unavailable') return 'native_unavailable';
  if (isYoloUnsure(native)) return 'yolo_model_missing';
  if (hazardHintsFromOcr(native?.text)) return 'ocr_caution_keywords';
  if ((policy.everyNth ?? 0) > 0) return 'every_nth';
  return 'skipped';
}

/**
 * Fold an optional Gemini result into the native one.
 *
 * Native detections win: they are measured on-device against the real frame. Gemini only fills
 * gaps - labels the native model has no class for, and the floor/hazard judgement it cannot
 * make at all.
 *
 * @param {NativeLike | null | undefined} native
 * @param {RawFrame | null} [vlmPartial]
 * @returns {RawFrame}
 */
export function mergeNativeToRaw(native, vlmPartial = null) {
  /** @type {Array<Record<string, any>>} */
  const objects = (native?.objects || []).map((obj) => ({
    label: obj.label,
    confidence: obj.confidence,
    box: obj.box,
    clockPosition: clockFromBox(obj.box),
    approxDistanceMeters: distanceFromBox(obj.box),
  }));

  /** @type {Array<Record<string, any>>} */
  const text = (native?.text || []).map((item) => ({
    text: item.text,
    confidence: item.confidence,
    box: item.box,
  }));

  let immediateHazard = Boolean(vlmPartial?.immediateHazard);
  let hazardDescription =
    vlmPartial?.hazardDescription != null
      ? vlmPartial.hazardDescription
      : null;

  // Do not assume floor is present when VLM was skipped
  let floorDetected =
    typeof vlmPartial?.floorDetected === 'boolean'
      ? vlmPartial.floorDetected
      : Boolean(native?.floorDetected);

  if (!immediateHazard && hazardHintsFromOcr(text)) {
    immediateHazard = true;
    hazardDescription =
      hazardDescription || 'Caution text detected ahead. Proceed carefully.';
  }

  if (Array.isArray(vlmPartial?.objects)) {
    for (const obj of vlmPartial.objects) {
      if (!obj?.label) continue;
      const isFill = VLM_FILL_LABELS.has(obj.label);
      const missing = !objects.some((o) => o.label === obj.label);
      if ((isFill || !objects.length) && missing) {
        objects.push({
          ...obj,
          clockPosition: obj.clockPosition || clockFromBox(obj.box || {}),
          approxDistanceMeters:
            obj.approxDistanceMeters || distanceFromBox(obj.box || {}),
        });
      }
    }
  }

  if (!text.length && Array.isArray(vlmPartial?.text)) {
    for (const item of vlmPartial.text) {
      text.push(item);
    }
  }

  return {
    objects,
    text,
    floorDetected,
    immediateHazard,
    hazardDescription,
  };
}

/**
 * @param {string | null | undefined} base64Image
 * @throws {Error} If the frame is far past the capture budget, which means the caller skipped
 *   the downscale step and is about to spend seconds uploading a full-sensor image.
 */
function assertPayloadSize(base64Image) {
  if (!base64Image || typeof base64Image !== 'string') return;
  // Rough decoded size from base64 length
  const approxBytes = Math.floor((base64Image.length * 3) / 4);
  if (approxBytes > CAMERA_CAPTURE_CONFIG.maxBase64Bytes * 2) {
    throw new Error(
      `Frame too large (~${approxBytes} bytes). Recapture at 640x480 q≤0.4.`
    );
  }
}

/**
 * Full hybrid pipeline: native YOLO + ML Kit OCR, with Gemini called only when the gate says so.
 *
 * @param {string} base64Image
 * @param {HybridOptions} [options]
 * @returns {Promise<PerceptionFrame & { meta: Record<string, any> }>}
 */
export async function processHybridFrame(base64Image, options = {}) {
  const start = Date.now();
  assertPayloadSize(base64Image);

  let native = options.nativeResult || null;

  if (!native) {
    try {
      const mod = await import('indoor-perception');
      const IndoorPerception = mod.default;
      native = await IndoorPerception.analyzeFrame(base64Image, {
        confThreshold: options.confThreshold ?? 0.35,
        runYolo: options.runYolo !== false,
        runOcr: options.runOcr !== false,
      });
    } catch (err) {
      native = {
        objects: [],
        text: [],
        yoloMs: 0,
        ocrMs: 0,
        totalMs: 0,
        modelLoaded: false,
        modelPath: null,
        yoloError: err instanceof Error ? err.message : String(err),
        ocrError: null,
        platform: 'unavailable',
      };
    }
  }

  const liveMode = getPerceptionMode() === 'live';
  const allowLiveVlm =
    options.allowLiveVlm ?? (liveMode && hasValidGeminiApiKey());

  const vlmOnTop = options.vlmOnTop === true;
  const gateReason = explainVlmGate(native, {
    forceVlm: options.forceVlm,
    vlmOnTop,
    everyNth: options.vlmEveryNth,
    frameIndex: options.frameIndex,
  });
  const invokeVlm = shouldInvokeVlm(native, {
    forceVlm: options.forceVlm,
    vlmOnTop,
    everyNth: options.vlmEveryNth,
    frameIndex: options.frameIndex,
  });

  let vlmPartial = null;
  let vlmMs = null;

  if (invokeVlm && allowLiveVlm && base64Image) {
    const v0 = Date.now();
    try {
      vlmPartial = await processFrameLive(base64Image);
    } catch {
      vlmPartial = null;
    }
    vlmMs = Date.now() - v0;
  } else if (invokeVlm && options.useMockVlmOnGate) {
    vlmPartial = selectMockRawPayload(options.mockVlmScenario || 'hallway');
    vlmMs = 0;
  }

  const raw = mergeNativeToRaw(native, vlmPartial);
  const latencyMs = Date.now() - start;
  const frame = validateAndSanitizeFrame(raw, latencyMs);

  return {
    ...frame,
    meta: {
      pipeline: 'yolo26n+mlkit+gemini-gated',
      roles: {
        yolo: 'objects (COCO-mapped)',
        mlkit: 'OCR primary',
        gemini: 'errors / low-conf OCR / caution / periodic only',
      },
      yoloMs: native?.yoloMs ?? null,
      ocrMs: native?.ocrMs ?? null,
      vlmMs,
      nativeTotalMs: native?.totalMs ?? null,
      modelLoaded: native?.modelLoaded ?? false,
      vlmInvoked: Boolean(vlmPartial),
      vlmGateReason: gateReason,
      vlmOnTop,
      vlmMode: allowLiveVlm
        ? invokeVlm
          ? 'live'
          : 'skipped_gate'
        : options.useMockVlmOnGate
          ? 'mock'
          : 'skipped',
      ocrSource: (native?.text || []).length
        ? 'mlkit'
        : vlmPartial?.text?.length
          ? 'gemini-fallback'
          : 'none',
      yoloError: native?.yoloError ?? null,
      ocrError: native?.ocrError ?? null,
      platform: native?.platform ?? 'unknown',
      clocksValid: frame.objects.every((o) =>
        ALLOWED_CLOCK.includes(o.clockPosition)
      ),
    },
  };
}
