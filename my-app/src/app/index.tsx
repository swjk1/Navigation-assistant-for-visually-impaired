import { AudioModule, RecordingPresets, setAudioModeAsync, useAudioRecorder } from 'expo-audio';
import * as Speech from 'expo-speech';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  hapticArrived,
  hapticHazard,
  hapticLeft,
  hapticRight,
  hapticStop,
  hapticStraight,
  stopHaptics,
} from '@/guidance/HapticService';

type UiState = 'IDLE' | 'LISTENING' | 'PROCESSING' | 'GUIDING' | 'ERROR';
type SimpleCommand = 'RIGHT' | 'LEFT' | 'STRAIGHT' | 'STOP' | 'ARRIVED' | 'HAZARD';

const LISTEN_MS = 3500;

export default function HomeScreen() {
  const recorder = useAudioRecorder(RecordingPresets.HIGH_QUALITY);
  const [uiState, setUiState] = useState<UiState>('IDLE');
  const [statusText, setStatusText] = useState('Ready');
  const [lastTranscriptHint, setLastTranscriptHint] = useState<string | null>(null);
  const [commandIndex, setCommandIndex] = useState(0);

  const commandFlow: SimpleCommand[] = ['RIGHT', 'LEFT', 'STRAIGHT', 'STOP', 'ARRIVED', 'HAZARD'];

  useEffect(() => {
    void (async () => {
      await setAudioModeAsync({
        playsInSilentMode: true,
        allowsRecording: true,
      });
    })();

    return () => {
      Speech.stop();
      stopHaptics();
    };
  }, []);

  const requestMicPermission = useCallback(async () => {
    const permission = await AudioModule.requestRecordingPermissionsAsync();
    return permission.granted;
  }, []);

  const runSimpleGuidance = useCallback(async (command: SimpleCommand) => {
    const sentence = command.toLowerCase();
    setLastTranscriptHint(`Demo command: ${command}`);
    Speech.speak(sentence, { rate: 1.0 });

    switch (command) {
      case 'RIGHT':
        await hapticRight();
        break;
      case 'LEFT':
        await hapticLeft();
        break;
      case 'STRAIGHT':
        await hapticStraight();
        break;
      case 'STOP':
        await hapticStop();
        break;
      case 'ARRIVED':
        await hapticArrived();
        break;
      case 'HAZARD':
        await hapticHazard();
        break;
    }
  }, []);

  const handleTap = useCallback(async () => {
    if (uiState === 'LISTENING' || uiState === 'PROCESSING' || uiState === 'GUIDING') return;

    setUiState('LISTENING');
    setStatusText('Listening');
    setLastTranscriptHint(null);
    Speech.stop();
    stopHaptics();

    const allowed = await requestMicPermission();
    if (!allowed) {
      setUiState('ERROR');
      setStatusText('Microphone permission denied');
      Speech.speak('Microphone permission is required.');
      return;
    }

    try {
      await recorder.prepareToRecordAsync();
      recorder.record();
      Speech.speak('Listening');

      await new Promise<void>((resolve) => setTimeout(resolve, LISTEN_MS));

      await recorder.stop();
      setUiState('PROCESSING');
      setStatusText('Processing request');

      await new Promise<void>((resolve) => setTimeout(resolve, 800));

      setUiState('GUIDING');
      setStatusText('Giving guidance');
      const command = commandFlow[commandIndex];
      await runSimpleGuidance(command);
      setCommandIndex((prev) => (prev + 1) % commandFlow.length);

      setUiState('IDLE');
      setStatusText('Ready');
    } catch {
      setUiState('ERROR');
      setStatusText('Could not capture audio');
      Speech.speak('Something went wrong. Please tap again.');
    }
  }, [commandFlow, commandIndex, recorder, requestMicPermission, runSimpleGuidance, uiState]);

  return (
    <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
      <Pressable
        onPress={() => void handleTap()}
        accessibilityRole="button"
        accessibilityLabel="Tap anywhere to speak"
        style={({ pressed }) => [styles.touchArea, pressed && styles.touchAreaPressed]}>
        <View style={styles.centerContent}>
          <Text style={styles.title}>HAPTICNAV</Text>
          <Text style={styles.subtitle}>TAP ANYWHERE TO{'\n'}SPEAK</Text>

          <View style={styles.statusRow}>
            <View
              style={[
                styles.dot,
                uiState === 'ERROR' ? styles.dotError : uiState === 'IDLE' ? styles.dotIdle : styles.dotActive,
              ]}
            />
            <Text style={styles.statusText}>{statusText}</Text>
          </View>

          {lastTranscriptHint ? <Text style={styles.helperText}>{lastTranscriptHint}</Text> : null}
        </View>
      </Pressable>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#0B0B0F',
  },
  touchArea: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  touchAreaPressed: {
    opacity: 0.92,
  },
  centerContent: {
    alignItems: 'center',
    gap: 26,
  },
  title: {
    color: '#FFFFFF',
    fontSize: 40,
    fontWeight: '700',
    letterSpacing: 1,
  },
  subtitle: {
    color: '#FFFFFF',
    fontSize: 28,
    lineHeight: 36,
    fontWeight: '600',
    textAlign: 'center',
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginTop: 6,
  },
  dot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  dotIdle: {
    backgroundColor: '#8C8C9F',
  },
  dotActive: {
    backgroundColor: '#16A34A',
  },
  dotError: {
    backgroundColor: '#DC2626',
  },
  statusText: {
    color: '#E6E6F0',
    fontSize: 20,
    fontWeight: '500',
  },
  helperText: {
    color: '#A9A9BC',
    fontSize: 15,
    textAlign: 'center',
    maxWidth: 320,
  },
});
