/**
 * Optional Step-1 harness screen.
 * Navigate to /perception-harness after starting Expo to verify capture budgets.
 * This is Person 1 tooling — not the production guidance UI (Person 3).
 *
 * Uses the public analyzeAndStore API so the harness matches teammate integration.
 */
import { useCameraPermissions } from 'expo-camera';
import { useState } from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { buildHardwareSnapshot } from '@/services/cameraService';
import IndoorPerception, { PerceptionArView } from 'indoor-perception';
import { hasValidGeminiApiKey } from '@/services/envCheck';
import { getPerceptionMode } from '@/services/perceptionEngine';

export default function PerceptionHarnessScreen() {
  // Camera permission is still requested through expo-camera, but the camera itself is opened
  // by ARCore inside <PerceptionArView />. Only one session may hold the device.
  const [permission, requestPermission] = useCameraPermissions();
  const [snapshot, setSnapshot] = useState<unknown>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const liveReady =
    getPerceptionMode() === 'live' && hasValidGeminiApiKey();

  async function onCapture() {
    setBusy(true);
    setError(null);
    try {
      // One frame off the live ARCore session, which navigation is reading pose and depth from.
      const started = Date.now();
      const captured = await IndoorPerception.captureFrame();
      const result = {
        ...captured,
        captureLatencyMs: Date.now() - started,
        estimatedBytes: Math.floor((captured.base64.length * 3) / 4),
      };
      const hardware = buildHardwareSnapshot(result, {
        permissionGranted: true,
        platform: Platform.OS,
      });

      let handoff = null;
      let debug = null;
      try {
        // Prefer public API, but also keep raw hybrid meta for phone debugging
        const { processHybridFrame } = await import('@/services/hybridPerception');
        const frame = await processHybridFrame(result.base64, {
          allowLiveVlm: liveReady,
          vlmOnTop: false,
          forceVlm: true, // Expo Go has no YOLO — always need Gemini
          useMockVlmOnGate: !liveReady,
        });
        debug = frame.meta || null;
        const { buildTeammateHandoff } = await import('@/services/teammateHandoff');
        handoff = buildTeammateHandoff(frame);
      } catch (hybridErr) {
        handoff = {
          error: hybridErr instanceof Error ? hybridErr.message : String(hybridErr),
          note: 'Native YOLO/OCR needs Android dev build; Gemini needs GEMINI_API_KEY in .env',
        };
      }

      const record = {
        mode: liveReady ? 'live' : 'mock-fallback',
        hardware,
        handoff,
        debug,
      };
      setSnapshot(record);
       
      console.log('[Hybrid Perception Snapshot]', JSON.stringify(record, null, 2));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  if (!permission) {
    return (
      <View style={styles.centered}>
        <Text>Checking camera permission…</Text>
      </View>
    );
  }

  if (!permission.granted) {
    return (
      <View style={styles.centered}>
        <Text style={styles.title}>Camera permission required</Text>
        <Text style={styles.body}>
          Person 1 needs rear-camera frames for the perception pipeline.
        </Text>
        <Pressable style={styles.button} onPress={requestPermission}>
          <Text style={styles.buttonText}>Grant permission</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={styles.root}>
      {/* Owns the ARCore session: RGB for perception here, pose + depth for navigation. */}
      <PerceptionArView style={styles.camera} />
      <View style={styles.panel}>
        <Pressable
          style={[styles.button, busy && styles.buttonDisabled]}
          onPress={onCapture}
          disabled={busy}
        >
          <Text style={styles.buttonText}>
            {busy ? 'Running…' : 'Capture + hybrid YOLO/OCR'}
          </Text>
        </Pressable>
        {error ? <Text style={styles.error}>{error}</Text> : null}
        <ScrollView style={styles.jsonBox}>
          <Text style={styles.json}>
            {snapshot
              ? JSON.stringify(snapshot, null, 2)
              : 'Snapshot will appear here. Copy into snapshots/snapshot_step1_hardware.json'}
          </Text>
        </ScrollView>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#111' },
  camera: { flex: 1.2 },
  panel: { flex: 1, padding: 12, gap: 8 },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    gap: 12,
  },
  title: { fontSize: 18, fontWeight: '600', color: '#fff' },
  body: { textAlign: 'center', color: '#ccc' },
  button: {
    backgroundColor: '#208AEF',
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 8,
    alignItems: 'center',
  },
  buttonDisabled: { opacity: 0.6 },
  buttonText: { color: '#fff', fontWeight: '600' },
  error: { color: '#ff6b6b' },
  jsonBox: {
    flex: 1,
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    padding: 8,
  },
  json: { color: '#9fefb0', fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace', fontSize: 11 },
});
