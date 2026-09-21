import type { EventSubscription } from 'expo-modules-core';

import NavigationNativeModule from './src/NavigationNativeModule';
import type { NavigationSnapshot } from './src/NavigationNative.types';

/**
 * Named exports only, and exactly one name per thing.
 *
 * This module used to expose the engine three ways - a default export, a named `NavigationNative`
 * and (for the view) both `NavigationArView` and an `ArView` alias - which meant four call sites
 * imported it three different ways and none of them was obviously canonical. One name each makes
 * the import line say which module it came from, which matters here because `NavigationNative`
 * and `IndoorPerception` are easy to confuse at a glance.
 */
export { default as NavigationArView } from './src/NavigationArView';
export * from './src/NavigationNative.types';

export const NavigationNative = NavigationNativeModule;

/**
 * Subscribes to navigation state. Emitted at most ~8 Hz, and immediately whenever the status or
 * command changes so a safety STOP is never delayed by throttling.
 */
export function addNavigationStateListener(
  listener: (snapshot: NavigationSnapshot) => void
): EventSubscription {
  return NavigationNativeModule.addListener('onNavigationState', listener);
}
