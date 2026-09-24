import { NativeModule, requireNativeModule } from 'expo';

import type {
  NavigationDepthImage,
  NavigationMapImage,
  NavigationNativeModuleEvents,
  NavigationSnapshot,
  NavigationSupport,
  NavigationTarget,
  SemanticObservation,
} from './NavigationNative.types';

declare class NavigationNativeModuleType extends NativeModule<NavigationNativeModuleEvents> {
  /** Cheap capability probe. Call before starting; never assume depth exists. */
  isSupported(): NavigationSupport;

  /** Starts a session. Requires <NavigationArView /> to be mounted for ARCore to get frames. */
  start(target?: NavigationTarget): Promise<void>;

  stop(): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;

  /** Forgets the learned map and graph but keeps the session and destination. */
  resetMap(): Promise<void>;

  setTarget(target: NavigationTarget): Promise<void>;

  /** Hand-off point for the perception layer. */
  submitSemanticObservations(observations: SemanticObservation[]): Promise<void>;

  /** The most recent snapshot, without waiting for the next event. */
  getSnapshot(): NavigationSnapshot;

  setDebugEnabled(enabled: boolean): void;

  /**
   * A rendered picture of the occupancy grid: free space, obstacles, frontiers, the planned path
   * and the user's pose. Pulled on demand for debug UI; the grid itself never crosses the bridge.
   */
  getMapImage(): Promise<NavigationMapImage>;

  /**
   * A rendered picture of the RAW depth returns of the latest frame, in the same window and scale
   * as `getMapImage`, plus per-class counts.
   *
   * This is the input to mapping rather than its output, so the two views answer different
   * questions: the map says what the engine believes, this says what the sensor reported.
   */
  getDepthImage(): Promise<NavigationDepthImage>;

  /**
   * Hands ARCore session ownership to another native module.
   *
   * ARCore needs exclusive access to the camera and this engine cannot run without ARCore, so
   * only one module may open it. Set this true when the perception module owns the session and
   * feeds frames through `NavigationSensorBridge`. Call before `start()`; while external, no
   * session is created here and `<NavigationArView />` is not needed.
   */
  setExternalFrameSource(external: boolean): void;

  isExternalFrameSource(): boolean;
}

export default requireNativeModule<NavigationNativeModuleType>('NavigationNative');
