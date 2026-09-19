import { requireNativeView } from 'expo';
import * as React from 'react';

import type { NavigationArViewProps } from './NavigationNative.types';

const NativeView: React.ComponentType<NavigationArViewProps> =
  requireNativeView('NavigationNative');

/**
 * Hosts the ARCore session.
 *
 * ARCore only produces frames while a GL surface is alive, so this view must be mounted for the
 * whole time navigation is running. It renders nothing visible - a small or fully covered view is
 * fine, but do not unmount it while guiding a user.
 */
export default function NavigationArView(props: NavigationArViewProps) {
  return <NativeView {...props} />;
}
