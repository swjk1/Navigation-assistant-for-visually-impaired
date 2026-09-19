import type { EventSubscription } from 'expo-modules-core';

import NavigationArView from './src/NavigationArView';
import NavigationNative from './src/NavigationNativeModule';
import type { NavigationSnapshot } from './src/NavigationNative.types';

export { default as NavigationArView } from './src/NavigationArView';
export { default as NavigationNative } from './src/NavigationNativeModule';
export * from './src/NavigationNative.types';

/**
 * Subscribes to navigation state. Emitted at most ~8 Hz, and immediately whenever the status or
 * command changes so a safety STOP is never delayed by throttling.
 */
export function addNavigationStateListener(
  listener: (snapshot: NavigationSnapshot) => void
): EventSubscription {
  return NavigationNative.addListener('onNavigationState', listener);
}

export default NavigationNative;
export { NavigationArView as ArView };
