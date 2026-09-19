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

const IndoorPerceptionModule =
  requireNativeModule<IndoorPerceptionModuleNative>('IndoorPerception');

export default IndoorPerceptionModule;
