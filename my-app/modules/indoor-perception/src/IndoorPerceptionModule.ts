import { NativeModule, requireNativeModule } from 'expo-modules-core';

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

const IndoorPerceptionModule =
  requireNativeModule<IndoorPerceptionModuleNative>('IndoorPerception');

export default IndoorPerceptionModule;
