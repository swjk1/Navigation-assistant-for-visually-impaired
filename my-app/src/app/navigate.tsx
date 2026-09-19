import { useCameraPermissions } from 'expo-camera';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { executeCommand } from '@/guidance/NavigationController';
import {
  isScanRequest,
  startNavigation,
  stopNavigation,
  subscribeNavigationCommands,
} from '@/guidance/navigationCommandSource';
import { perceptionHazard, toSemanticObservations } from '@/perception/perceptionToSemantic';
import type { NavigationCommand } from '@/types/NavigationCommand';
import type { PerceptionFrame } from '@/types/perception';

import { analyzeAndStore, getLatestPerceptionFrame } from '@/index.js';
import IndoorPerception, { PerceptionArView } from '../../modules/indoor-perception';
import NavigationNative, {
  type NavigationSnapshot,
  type NavigationTarget,
} from '../../modules/navigation-native';

/**
 * The three parts running together.
 *
 *   ARCore ─► navigation engine ─► NavigationCommand ─► speech + haptics   (Person 2 → 3)
 *                    ▲
 *   camera ─► perception ─► SemanticObservation[] ─┘                       (Person 1 → 2)
 *
 * Deliberately plain: the accessible UI is Person 3's to design. This exists so the whole chain
 * can be exercised on a device, and so the wiring lives somewhere reviewable rather than inside
 * someone's screen.
 */

/**
 * How often to run the perception pipeline. Much slower than the navigation loop on purpose:
 * YOLO + OCR + a Gemini call take a while, and signs do not move.
 */
const PERCEPTION_INTERVAL_MS = 2500;

/** Map refresh. Fast enough to watch the scan fill in, slow enough not to compete with mapping. */
const MAP_INTERVAL_MS = 700;

/** Don't repeat an identical instruction; it makes the guidance unlistenable. */
function isSameInstruction(a: NavigationCommand | null, b: NavigationCommand): boolean {
  if (!a) return false;
  return (
    a.action === b.action &&
    (a.hazard?.detected ?? false) === (b.hazard?.detected ?? false) &&
    a.target === b.target
  );
}

export default function NavigateScreen() {
  const [running, setRunning] = useState(false);
  const [snapshot, setSnapshot] = useState<NavigationSnapshot | null>(null);
  const [spoken, setSpoken] = useState<string>('—');
  const [error, setError] = useState<string | null>(null);
  const [perceptionNote, setPerceptionNote] = useState('idle');
  const [mapUri, setMapUri] = useState<string | null>(null);
  const [permission, requestPermission] = useCameraPermissions();
  const lastCommand = useRef<NavigationCommand | null>(null);
  const perceptionBusy = useRef(false);

  const supported = Platform.OS === 'android';

  useEffect(() => {
    if (!supported) return;
    NavigationNative.setDebugEnabled(true);
    // The perception module owns the ARCore session (it mounts PerceptionArView below), so the
    // navigation module must not open one of its own. ARCore allows exactly one.
    NavigationNative.setExternalFrameSource(true);

    const subscription = subscribeNavigationCommands((command, next) => {
      setSnapshot(next);

      // Guidance layer: speech + haptics. Skip repeats of an identical instruction.
      if (!isSameInstruction(lastCommand.current, command)) {
        lastCommand.current = command;
        void executeCommand(command);
        setSpoken(command.hazard?.detected ? 'Hazard' : command.action);
      }

      if (isScanRequest(next)) setSpoken('SCAN');
    });

    return () => {
      subscription.remove();
      void stopNavigation();
    };
  }, [supported]);

  const start = useCallback(
    async (target: NavigationTarget) => {
      setError(null);
      try {
        if (!permission?.granted) {
          const granted = await requestPermission();
          if (!granted.granted) throw new Error('Camera permission is required');
        }
        const support = NavigationNative.isSupported();
        if (!support.arCoreSupported) throw new Error(support.reason ?? 'ARCore not supported');
        if (!support.depthSupported) throw new Error('This device has no ARCore Depth support');
        await startNavigation(target);
        setRunning(true);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [permission?.granted, requestPermission]
  );

  const stop = useCallback(async () => {
    await stopNavigation();
    setRunning(false);
    setMapUri(null);
  }, []);

  const onPerceptionFrame = useCallback((frame: PerceptionFrame) => {
    const observations = toSemanticObservations(frame);
    if (observations.length > 0) {
      void NavigationNative.submitSemanticObservations(observations);
    }
    const hazard = perceptionHazard(frame);
    if (hazard) {
      // Perception hazards outrank navigation guidance and bypass the repeat filter.
      lastCommand.current = null;
      void executeCommand({
        action: 'STOP',
        hazard: { detected: true, type: hazard },
        confidence: 0.9,
      });
    }
  }, []);

  /**
   * Polls the rendered occupancy map. Pulled rather than pushed: an image is far larger than the
   * state the guidance layer needs, so it must not ride along with every snapshot.
   */
  useEffect(() => {
    if (!supported || !running) return;
    let cancelled = false;
    let busy = false;

    const timer = setInterval(() => {
      if (busy) return;
      busy = true;
      void NavigationNative.getMapImage()
        .then((map) => {
          if (!cancelled) setMapUri(`data:image/png;base64,${map.base64}`);
        })
        .catch(() => {
          /* transient - the engine may be mid-reset */
        })
        .finally(() => {
          busy = false;
        });
    }, MAP_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [supported, running]);

  /**
   * Drives the perception pipeline off the SAME ARCore session navigation is using.
   *
   *   captureFrame() -> analyzeAndStore() -> PerceptionFrame -> SemanticObservation[] -> engine
   *
   * Guarded by `perceptionBusy` because a Gemini round trip can outlast the interval; queuing
   * them would pile up stale frames behind a walking user.
   */
  useEffect(() => {
    if (!supported || !running || !permission?.granted) return;

    let cancelled = false;
    const timer = setInterval(() => {
      if (perceptionBusy.current) return;
      perceptionBusy.current = true;
      void (async () => {
        try {
          const captured = await IndoorPerception.captureFrame();
          await analyzeAndStore(captured.base64);
          const frame = getLatestPerceptionFrame() as PerceptionFrame | null;
          if (frame && !cancelled) {
            onPerceptionFrame(frame);
            setPerceptionNote(
              `${frame.objects.length} objects, ${frame.text.length} signs`
            );
          }
        } catch (e) {
          if (!cancelled) {
            setPerceptionNote(e instanceof Error ? e.message : String(e));
          }
        } finally {
          perceptionBusy.current = false;
        }
      })();
    }, PERCEPTION_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [supported, running, permission?.granted, onPerceptionFrame]);

  if (!supported) {
    return (
      <SafeAreaView style={styles.safeArea}>
        <Text style={styles.title}>Navigation</Text>
        <Text style={styles.muted}>Android only — the iOS/ARKit adapter is not built yet.</Text>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
      {/* The app's ONE ARCore session. Navigation gets pose + depth from it, perception gets
          the RGB image. Do not unmount while guiding. */}
      <PerceptionArView style={styles.arView} />

      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>Navigation</Text>

        <Text style={styles.command}>{snapshot?.command ?? 'STOP'}</Text>
        <Text style={styles.muted}>
          {snapshot?.status ?? 'IDLE'}
          {snapshot?.stopReason ? ` · ${snapshot.stopReason}` : ''}
        </Text>
        <Text style={styles.muted}>spoken: {spoken}</Text>
        {snapshot?.debug && snapshot.debug.scanProgress < 1 ? (
          <Text style={styles.muted}>
            scanning {Math.round(snapshot.debug.scanProgress * 100)}% — sweep the phone slowly
          </Text>
        ) : null}

        {mapUri ? (
          <View style={styles.mapBlock}>
            <Image
              source={{ uri: mapUri }}
              style={styles.map}
              accessibilityLabel="Live occupancy map: green is clear space, red is an obstacle, and white marks your position."
              // Nearest-neighbour-ish: the map is deliberately blocky, one square per 10 cm cell.
              resizeMode="contain"
              fadeDuration={0}
            />
            <View style={styles.legend}>
              <Legend color="#408460" label="free" />
              <Legend color="#D65A4A" label="obstacle" />
              <Legend color="#18181E" label="unknown" />
              <Legend color="#F0C85A" label="frontier" />
              <Legend color="#5AA0F0" label="path" />
            </View>
            <Text style={styles.mutedSmall}>
              {snapshot?.debug
                ? `${(snapshot.debug.freeCells * 0.01).toFixed(1)} m² mapped · 12 m window`
                : ''}
            </Text>
          </View>
        ) : null}
        <Text style={styles.mutedSmall}>perception: {perceptionNote}</Text>

        {permission?.granted ? null : (
          <Button label="Grant camera permission" onPress={() => void requestPermission()} />
        )}

        <View style={styles.buttons}>
          {running ? (
            <Button label="Stop" onPress={() => void stop()} />
          ) : (
            <Button label="Explore" onPress={() => void start({ type: 'EXPLORE' })} />
          )}
          <Button label="Find 314" onPress={() => void start({ type: 'ROOM', value: '314' })} />
          <Button label="Find exit" onPress={() => void start({ type: 'EXIT' })} />
          <Button label="Find door" onPress={() => void start({ type: 'DOOR' })} />
          <Button label="Reset map" onPress={() => void NavigationNative.resetMap()} />
        </View>

        {snapshot?.debug ? (
          <View style={styles.debug}>
            <Text style={styles.mutedSmall}>
              depth {snapshot.debug.depthPointsLastFrame} · floor{' '}
              {snapshot.debug.floorConfidence.toFixed(2)} · cells {snapshot.debug.freeCells}/
              {snapshot.debug.occupiedCells}/{snapshot.debug.unknownCells}
            </Text>
            <Text style={styles.mutedSmall}>
              frontiers {snapshot.debug.frontierCount} · nodes{' '}
              {snapshot.debug.topologicalNodeCount} · path {snapshot.debug.pathLength}
            </Text>
          </View>
        ) : null}

        {error ? <Text style={styles.error}>{error}</Text> : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <View style={styles.legendItem}>
      <View style={[styles.legendSwatch, { backgroundColor: color }]} />
      <Text style={styles.legendLabel}>{label}</Text>
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
  safeArea: { flex: 1, backgroundColor: '#0B0B0F' },
  arView: { width: 1, height: 1, opacity: 0 },
  content: { padding: 20, gap: 10 },
  title: { color: '#FFFFFF', fontSize: 24, fontWeight: '700' },
  command: { color: '#FFFFFF', fontSize: 40, fontWeight: '800', letterSpacing: 1 },
  muted: { color: '#A9A9BC', fontSize: 15 },
  mutedSmall: { color: '#8A8A99', fontSize: 12, fontVariant: ['tabular-nums'] },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  button: {
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 8,
    backgroundColor: '#208AEF',
  },
  buttonPressed: { opacity: 0.7 },
  buttonText: { color: '#FFFFFF', fontWeight: '600' },
  mapBlock: { marginTop: 12, gap: 6 },
  map: {
    width: '100%',
    aspectRatio: 1,
    borderRadius: 10,
    backgroundColor: '#18181E',
  },
  legend: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  legendSwatch: { width: 10, height: 10, borderRadius: 2 },
  legendLabel: { color: '#8A8A99', fontSize: 11 },
  debug: { marginTop: 12, gap: 2 },
  error: { color: '#DC2626', marginTop: 12 },
});
