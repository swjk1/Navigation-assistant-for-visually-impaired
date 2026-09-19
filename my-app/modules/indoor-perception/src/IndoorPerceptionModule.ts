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

type IndoorPerceptionModuleNative = NativeModule & {
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
} as IndoorPerceptionModuleNative;

const IndoorPerceptionModule =
  NativeIndoorPerception ?? StubIndoorPerception;

export default IndoorPerceptionModule;
