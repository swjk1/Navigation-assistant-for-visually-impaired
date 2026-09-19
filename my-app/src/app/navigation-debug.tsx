import { useEffect, useState } from 'react';
import { Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import NavigationNative, {
  NavigationArView,
  addNavigationStateListener,
  type NavigationSnapshot,
  type NavigationSupport,
} from '../../modules/navigation-native';

/**
 * Minimal diagnostics screen for the navigation engine.
 *
 * Deliberately unstyled and unpolished: the real accessible UI (speech, haptics) is a separate
 * task. This exists so the engine can be exercised on a physical ARCore device and so the debug
 * counters from §41 are visible without attaching a debugger.
 */
export default function NavigationDebugScreen() {
  const [support, setSupport] = useState<NavigationSupport | null>(null);
  const [snapshot, setSnapshot] = useState<NavigationSnapshot | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const available = Platform.OS === 'android';

  useEffect(() => {
    if (!available) return;
    try {
      // Engine-only screen: this module owns the ARCore session here. The flag is global, so it
      // must be reset in case /navigate handed ownership to the perception module earlier.
      NavigationNative.setExternalFrameSource(false);
      setSupport(NavigationNative.isSupported());
      NavigationNative.setDebugEnabled(true);
    } catch (e) {
      setError(String(e));
      return;
    }
    const subscription = addNavigationStateListener(setSnapshot);
    return () => subscription.remove();
  }, [available]);

  const run = async (action: () => Promise<void>, next?: boolean) => {
    try {
      await action();
      if (next !== undefined) setRunning(next);
    } catch (e) {
      setError(String(e));
    }
  };

  if (!available) {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>Navigation engine</Text>
        <Text style={styles.note}>
          Android only. The iOS/ARKit adapter is not implemented yet.
        </Text>
      </View>
    );
  }

  const debug = snapshot?.debug;

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.title}>Navigation engine</Text>

      {/* ARCore needs a live GL surface to produce frames, so this must stay mounted. */}
      <NavigationArView style={styles.arView} debug />

      <Section title="Support">
        <Row label="ARCore" value={String(support?.arCoreSupported ?? '-')} />
        <Row label="Depth" value={String(support?.depthSupported ?? '-')} />
        <Row label="Tracking" value={String(support?.trackingAvailable ?? '-')} />
        {support?.reason ? <Row label="Reason" value={support.reason} /> : null}
      </Section>

      <View style={styles.buttons}>
        <Button
          label={running ? 'Stop' : 'Start (explore)'}
          onPress={() =>
            running
              ? run(() => NavigationNative.stop(), false)
              : run(() => NavigationNative.start({ type: 'EXPLORE' }), true)
          }
        />
        <Button
          label="Find 314"
          onPress={() => run(() => NavigationNative.setTarget({ type: 'ROOM', value: '314' }))}
        />
        <Button
          label="Find exit"
          onPress={() => run(() => NavigationNative.setTarget({ type: 'EXIT' }))}
        />
        <Button label="Reset map" onPress={() => run(() => NavigationNative.resetMap())} />
      </View>

      <Section title="State">
        <Text style={styles.command}>{snapshot?.command ?? 'STOP'}</Text>
        <Row label="Status" value={snapshot?.status ?? 'IDLE'} />
        <Row label="Target" value={snapshot?.target ?? '-'} />
        <Row label="Heading error" value={fmt(snapshot?.headingErrorDegrees, '°')} />
        <Row label="To waypoint" value={fmt(snapshot?.distanceToWaypointMeters, ' m')} />
        <Row label="To target" value={fmt(snapshot?.distanceMeters, ' m')} />
        <Row label="Tracking conf." value={fmt(snapshot?.trackingConfidence)} />
        <Row label="Map conf." value={fmt(snapshot?.mapConfidence)} />
        <Row label="Frontier" value={snapshot?.selectedFrontierId ?? '-'} />
      </Section>

      {debug ? (
        <Section title="Debug">
          <Row label="Depth points" value={String(debug.depthPointsLastFrame)} />
          <Row label="Depth available" value={String(debug.depthAvailable)} />
          <Row label="Floor y / conf." value={`${fmt(debug.floorY, ' m')} / ${fmt(debug.floorConfidence)}`} />
          <Row label="Cells f/o/u" value={`${debug.freeCells} / ${debug.occupiedCells} / ${debug.unknownCells}`} />
          <Row label="Frontiers" value={String(debug.frontierCount)} />
          <Row label="Graph nodes" value={String(debug.topologicalNodeCount)} />
          <Row label="Path length" value={String(debug.pathLength)} />
          <Row label="Pose" value={`${fmt(debug.poseX)}, ${fmt(debug.poseY)}, ${fmt(debug.poseZ)}`} />
          <Row label="Yaw" value={fmt(debug.yawDegrees, '°')} />
          {debug.lastError ? <Row label="Last error" value={debug.lastError} /> : null}
        </Section>
      ) : null}

      {error ? <Text style={styles.error}>{error}</Text> : null}
    </ScrollView>
  );
}

function fmt(value: number | null | undefined, suffix = '') {
  return value === null || value === undefined ? '-' : `${value.toFixed(2)}${suffix}`;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

function Button({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: { padding: 16, gap: 12 },
  title: { fontSize: 22, fontWeight: '600' },
  note: { opacity: 0.7 },
  arView: { width: 1, height: 1, opacity: 0 },
  section: { gap: 4 },
  sectionTitle: { fontSize: 15, fontWeight: '600', marginTop: 8, opacity: 0.7 },
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: 12 },
  rowLabel: { opacity: 0.6, flexShrink: 1 },
  rowValue: { fontVariant: ['tabular-nums'], fontWeight: '500' },
  command: { fontSize: 32, fontWeight: '700', letterSpacing: 1 },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  button: { paddingVertical: 10, paddingHorizontal: 14, borderRadius: 8, backgroundColor: '#208AEF' },
  buttonPressed: { opacity: 0.7 },
  buttonText: { color: 'white', fontWeight: '600' },
  error: { color: '#c0392b' },
});
