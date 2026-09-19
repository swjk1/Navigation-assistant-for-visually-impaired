import { requireNativeView } from 'expo';
import * as React from 'react';
import type { StyleProp, ViewStyle } from 'react-native';

export type PerceptionArViewProps = {
  style?: StyleProp<ViewStyle>;
};

const NativeView: React.ComponentType<PerceptionArViewProps> =
  requireNativeView('IndoorPerception');

/**
 * Hosts the app's single ARCore session.
 *
 * This replaces `CameraView`. ARCore requires exclusive access to the camera, so opening a
 * `CameraView` alongside it will silently break one of the two. Mount this instead, for as long
 * as perception OR navigation is running - it renders nothing visible.
 *
 * While mounted, `IndoorPerception.captureFrame()` returns RGB frames and the navigation engine
 * receives pose and depth from the same frames.
 */
export default function PerceptionArView(props: PerceptionArViewProps) {
  return <NativeView {...props} />;
}
