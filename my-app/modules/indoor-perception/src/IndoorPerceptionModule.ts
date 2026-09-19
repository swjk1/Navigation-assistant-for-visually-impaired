import { NativeModule, requireOptionalNativeModule } from 'expo-modules-core';

export type NativeDetectedObject = {
  label: string;
  confidence: number;
  box: { x: number; y: number; width: number; height: number };
  cocoClassId: number;
  cocoName: string;
};

export type NativeOcrItem = {
  text: string;
  confidence: number;
  box: { x: number; y: number; width: number; height: number };
};

export type NativePerceptionResult = {
  objects: NativeDetectedObject[];
  text: NativeOcrItem[];
  yoloMs: number;
  ocrMs: number;
  totalMs: number;
  modelLoaded: boolean;
  modelPath: string | null;
  yoloError: string | null;
  ocrError: string | null;
  platform: string;
};

/**
 * One RGB frame taken from the live ARCore session, replacing CameraView's takePictureAsync.
 *
 * `timestampNs` is ARCore's frame timestamp (nanoseconds since boot) - the value the navigation
 * engine's `SemanticObservation.timestampNs` expects. Do NOT substitute Date.now(): it is a
 * different clock domain, and the engine would silently reject every observation.
 */
export type CapturedArFrame = {
  base64: string;
  width: number;
  height: number;
  timestampNs: number;
  mimeType: 'image/jpeg';
};

export type ArCameraStatus = {
  sessionActive: boolean;
  depthSupported: boolean;
  lastError: string | null;
};

type IndoorPerceptionModuleNative = NativeModule & {
  /**
   * Captures one frame from the ARCore session this module owns.
   * Requires <PerceptionArView /> to be mounted.
   */
  captureFrame(): Promise<CapturedArFrame>;

  getCameraStatus(): ArCameraStatus;

  getStatus(): Promise<{
    platform: string;
    modelLoaded: boolean;
    modelAssetName: string;
  }>;
  ensureModelLoaded(): Promise<boolean>;
  analyzeFrame(
    base64Image: string,
    options?: {
      confThreshold?: number;
      iouThreshold?: number;
      maxDetections?: number;
      runYolo?: boolean;
      runOcr?: boolean;
    }
  ): Promise<NativePerceptionResult>;
};

/**
 * Optional: missing in Expo Go. Present only after `npx expo run:android`
 * (dev build with the indoor-perception native module).
 */
const NativeIndoorPerception =
  requireOptionalNativeModule<IndoorPerceptionModuleNative>('IndoorPerception');

export const isNativePerceptionAvailable = Boolean(NativeIndoorPerception);

const StubIndoorPerception: IndoorPerceptionModuleNative = {
  async getStatus() {
    return {
      platform: 'unavailable',
      modelLoaded: false,
      modelAssetName: 'yolo26n_int8.tflite',
    };
  },
  async ensureModelLoaded() {
    return false;
  },
  async captureFrame(): Promise<CapturedArFrame> {
    // Without the native module there is no ARCore session, so there are no frames to give.
    // Failing loudly beats returning an empty image that silently produces no detections.
    throw new Error(
      'IndoorPerception native module not in this build, so the camera is not running. ' +
        'Use npx expo run:android (Expo Go unsupported).'
    );
  },
  getCameraStatus(): ArCameraStatus {
    return {
      sessionActive: false,
      depthSupported: false,
      lastError: 'IndoorPerception native module not in this build',
    };
  },
  async analyzeFrame() {
    return {
      objects: [],
      text: [],
      yoloMs: 0,
      ocrMs: 0,
      totalMs: 0,
      modelLoaded: false,
      modelPath: null,
      yoloError:
        'IndoorPerception native module not in this build. Use npx expo run:android (Expo Go unsupported).',
      ocrError: null,
      platform: 'unavailable',
    };
  },
} as unknown as IndoorPerceptionModuleNative;

const IndoorPerceptionModule =
  NativeIndoorPerception ?? StubIndoorPerception;

export default IndoorPerceptionModule;
