import * as Speech from 'expo-speech';

import type { NavigationAction, NavigationCommand } from '@/types/NavigationCommand';
import {
  hapticArrived,
  hapticHazard,
  hapticLeft,
  hapticRight,
  hapticScan,
  hapticStop,
  hapticStraight,
  stopHaptics,
} from '@/guidance/HapticService';

export type NavigationCommandInput = NavigationCommand | string;
const HAPTIC_DELAY_MS = 1000;

const ACTION_LABEL: Record<NavigationAction, string> = {
  LEFT: 'Left',
  RIGHT: 'Right',
  STRAIGHT: 'Straight',
  STOP: 'Stop',
  SCAN: 'Look around slowly',
  ARRIVED: 'Arrived',
};

/**
 * Maps plain speech text into a NavigationCommand for haptic testing.
 * Examples:
 * - "right" -> RIGHT
 * - "go straight" -> STRAIGHT
 * - "stop, hazard" -> STOP + hazard
 */
export function commandFromTranscript(transcript: string): NavigationCommand | null {
  const text = transcript.toLowerCase();
  const confidence = 0.85;

  if (/\b(hazard|danger|warning)\b/.test(text)) {
    return {
      action: 'STOP',
      hazard: { detected: true, type: 'Hazard' },
      confidence,
    };
  }

  if (/\b(stop|halt|wait)\b/.test(text)) return { action: 'STOP', confidence };
  if (/\b(arrived|arrival|here)\b/.test(text)) return { action: 'ARRIVED', confidence };
  if (/\b(straight|forward|ahead)\b/.test(text)) return { action: 'STRAIGHT', confidence };
  if (/\b(left)\b/.test(text)) return { action: 'LEFT', confidence };
  if (/\b(right)\b/.test(text)) return { action: 'RIGHT', confidence };

  return null;
}

export function parseNavigationCommand(input: NavigationCommandInput): NavigationCommand {
  if (typeof input !== 'string') return input;
  return JSON.parse(input) as NavigationCommand;
}

export function toSpeechText(command: NavigationCommand): string {
  if (command.hazard?.detected) {
    const hazardType = command.hazard.type?.trim();
    return hazardType ? `Stop. ${hazardType} ahead.` : 'Stop. Hazard ahead.';
  }

  // SCAN needs more than its label: it is a request for the user to help the engine see.
  if (command.action === 'SCAN') {
    return 'Stop. Look around slowly.';
  }

  if (command.action === 'ARRIVED') {
    return command.target ? `${command.target}. Arrived.` : 'Arrived.';
  }

  if (command.target) {
    return `${command.target}. ${ACTION_LABEL[command.action]}.`;
  }

  return `${ACTION_LABEL[command.action]}.`;
}

async function runHaptic(command: NavigationCommand): Promise<void> {
  if (command.hazard?.detected) {
    await hapticHazard();
    return;
  }

  switch (command.action) {
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
    case 'SCAN':
      await hapticScan();
      break;
    case 'ARRIVED':
      await hapticArrived();
      break;
  }
}

export async function executeCommand(command: NavigationCommand): Promise<void> {
  Speech.stop();
  stopHaptics();
  Speech.speak(toSpeechText(command), { rate: 1.0 });
  await new Promise<void>((resolve) => setTimeout(resolve, HAPTIC_DELAY_MS));
  await runHaptic(command);
}

export async function executeCommandInput(input: NavigationCommandInput): Promise<NavigationCommand> {
  const command = parseNavigationCommand(input);
  await executeCommand(command);
  return command;
}
