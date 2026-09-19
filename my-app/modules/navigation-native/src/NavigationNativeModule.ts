import { NativeModule, requireNativeModule } from 'expo';

import type {
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
}

export default requireNativeModule<NavigationNativeModuleType>('NavigationNative');
