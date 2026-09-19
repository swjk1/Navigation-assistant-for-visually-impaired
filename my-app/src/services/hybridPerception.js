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

const VLM_FILL_LABELS = new Set([
  'door',
  'stairs',
  'elevator',
  'trashcan',
  'wall',
  'sign',
]);

const MLKIT_CONF_UNSURE = 0.55;

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

export function distanceFromBox(box) {
  const h = Math.max(0.01, Number(box?.height) || 0.1);
  const meters = 0.4 / h;
  return Math.max(0.5, Math.min(12, Number(meters.toFixed(1))));
}

function hazardHintsFromOcr(textItems = []) {
  const joined = textItems
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

export function explainVlmGate(native, policy = {}) {
  if (policy.forceVlm) return 'forced';
  if (policy.vlmOnTop) return 'vlm_on_top';
  if (native?.ocrError) return 'mlkit_error';
  if (isMlKitUnsure(native)) return 'mlkit_low_confidence';
  if (native?.yoloError) return 'yolo_error';
  if (native?.platform === 'unavailable') return 'native_unavailable';
  if (isYoloUnsure(native)) return 'yolo_model_missing';
  if (hazardHintsFromOcr(native?.text)) return 'ocr_caution_keywords';
  if (policy.everyNth > 0) return 'every_nth';
  return 'skipped';
}

export function mergeNativeToRaw(native, vlmPartial = null) {
  const objects = (native?.objects || []).map((obj) => ({
    label: obj.label,
    confidence: obj.confidence,
    box: obj.box,
    clockPosition: clockFromBox(obj.box),
    approxDistanceMeters: distanceFromBox(obj.box),
  }));

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
 * Full hybrid pipeline.
 */
export async function processHybridFrame(base64Image, options = {}) {
  const start = Date.now();
  assertPayloadSize(base64Image);

  let native = options.nativeResult || null;

  if (!native) {
    try {
      const mod = await import('indoor-perception');
      const IndoorPerception = mod.default || mod;
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
        yoloError: err?.message || String(err),
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
