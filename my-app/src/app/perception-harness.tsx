/**
 * Optional Step-1 harness screen.
 * Navigate to /perception-harness after starting Expo to verify capture budgets.
 * This is Person 1 tooling — not the production guidance UI (Person 3).
 */
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useRef, useState } from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import {
  buildHardwareSnapshot,
  captureFrame,
} from '@/services/cameraService';

export default function PerceptionHarnessScreen() {
  const cameraRef = useRef(null);
  const [permission, requestPermission] = useCameraPermissions();
  const [snapshot, setSnapshot] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function onCapture() {
    setBusy(true);
    setError(null);
    try {
      const result = await captureFrame(cameraRef.current);
      const record = buildHardwareSnapshot(result, {
        permissionGranted: true,
        platform: Platform.OS,
      });
      setSnapshot(record);
      // eslint-disable-next-line no-console
      console.log('[Step1 Hardware Snapshot]', JSON.stringify(record, null, 2));
    } catch (err) {
      setError(err?.message || String(err));
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
      <CameraView
        ref={cameraRef}
        style={styles.camera}
        facing="back"
        mode="picture"
      />
      <View style={styles.panel}>
        <Pressable
          style={[styles.button, busy && styles.buttonDisabled]}
          onPress={onCapture}
          disabled={busy}
        >
          <Text style={styles.buttonText}>
            {busy ? 'Capturing…' : 'Capture Step 1 frame'}
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
