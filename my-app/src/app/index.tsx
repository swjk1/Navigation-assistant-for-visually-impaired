import { AudioModule, RecordingPresets, setAudioModeAsync, useAudioRecorder } from 'expo-audio';
import * as Speech from 'expo-speech';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { transcribeAudioWithGemini } from '@/ai/GeminiSpeechService';
import { stopHaptics } from '@/guidance/HapticService';
import { commandFromTranscript, executeCommand } from '@/guidance/NavigationController';

type UiState = 'IDLE' | 'LISTENING' | 'PROCESSING' | 'GUIDING' | 'ERROR';

const LISTEN_MS = 3500;

export default function HomeScreen() {
  const recorder = useAudioRecorder(RecordingPresets.HIGH_QUALITY);
  const [uiState, setUiState] = useState<UiState>('IDLE');
  const [statusText, setStatusText] = useState('Ready');
  const [lastTranscriptHint, setLastTranscriptHint] = useState<string | null>(null);

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
      setStatusText('Transcribing speech');

      const audioUri = recorder.uri;
      if (!audioUri) throw new Error('No recorded audio URI');

      const transcript = await transcribeAudioWithGemini(audioUri);

      setUiState('GUIDING');
      setStatusText('Applying command');

      if (transcript.length > 0) {
        setLastTranscriptHint(`You said: "${transcript}"`);
        const command = commandFromTranscript(transcript);
        if (command) {
          await executeCommand(command);
        } else {
          Speech.speak(`Heard: ${transcript}`, { rate: 1.0 });
          Speech.speak('No navigation command detected. Say left, right, straight, stop, or arrived.', {
            rate: 1.0,
          });
        }
      } else {
        setLastTranscriptHint('No speech detected.');
        Speech.speak('I could not hear any speech. Please try again.');
      }

      setUiState('IDLE');
      setStatusText('Ready');
    } catch (error) {
      setUiState('ERROR');
      const message = error instanceof Error ? error.message : 'Unknown error';
      if (message.includes('EXPO_PUBLIC_GEMINI_API_KEY')) {
        setStatusText('Missing Gemini API key');
        setLastTranscriptHint('Add EXPO_PUBLIC_GEMINI_API_KEY to your environment and restart Expo.');
        Speech.speak('Gemini API key is missing.');
      } else {
        setStatusText('Could not process speech');
        setLastTranscriptHint(message);
        Speech.speak('Something went wrong. Please tap again.');
      }
    }
  }, [recorder, requestMicPermission, uiState]);

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
