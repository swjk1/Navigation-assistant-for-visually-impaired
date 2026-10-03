/**
 * Turns a NavigationCommand into what the user actually perceives: speech and haptics.
 *
 * NAMING. This was `NavigationController.ts`, which collided with
 * `navigation-core/planning/NavigationController.kt` - an unrelated class that does the
 * geometry, converting a planned path into one instruction. Two files with the same name at
 * opposite ends of the pipeline made stack traces and code review needlessly ambiguous. The
 * Kotlin one decides WHAT to say; this one decides HOW it reaches the user.
 */

import * as Speech from 'expo-speech';
import type { PhoneAim } from 'navigation-native';

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

/** Below this, an aim angle is not worth saying: "level", "ahead". */
const AIM_SPOKEN_MIN_DEGREES = 5;

const roundTo5 = (degrees: number) => Math.round(Math.abs(degrees) / 5) * 5;

/**
 * What to say when the engine asks for a different phone hold. Null or absent is the usual hold,
 * announced only as a return to it - so pass the PREVIOUS aim too, and nothing is said when there
 * was nothing to undo.
 */
export function toAimSpeechText(aim: PhoneAim | null | undefined, previous?: PhoneAim | null): string | null {
  if (!aim) return previous ? 'Hold the phone as usual.' : null;
  const parts: string[] = [];
  const pitch = aim.pitchDegrees;
  if (pitch != null) {
    if (Math.abs(pitch) < AIM_SPOKEN_MIN_DEGREES) parts.push('Hold the phone level');
    else parts.push(`Tilt the phone ${pitch > 0 ? 'up' : 'down'} ${roundTo5(pitch)} degrees`);
  }
  if (Math.abs(aim.yawDegrees) >= AIM_SPOKEN_MIN_DEGREES) {
    parts.push(`point it ${roundTo5(aim.yawDegrees)} degrees to your ${aim.yawDegrees > 0 ? 'right' : 'left'}`);
  }
  if (aim.sweepDegrees > 0) parts.push('sweep it slowly left and right as you walk');
  if (parts.length === 0) return 'Hold the phone as usual.';
  const sentence = parts.join(', and ');
  return `${sentence.charAt(0).toUpperCase()}${sentence.slice(1)}.`;
}

/** True when two aims would be spoken the same way. */
export function isSameAim(a: PhoneAim | null | undefined, b: PhoneAim | null | undefined): boolean {
  if (!a || !b) return !a && !b;
  return (
    (a.pitchDegrees ?? null) === (b.pitchDegrees ?? null) &&
    a.yawDegrees === b.yawDegrees &&
    a.sweepDegrees === b.sweepDegrees
  );
}

/**
 * Speaks a change of phone hold after whatever is being said, rather than cutting it off: the
 * walking instruction matters more, and the hold stands until the next change.
 */
export function speakAim(aim: PhoneAim | null | undefined, previous?: PhoneAim | null): void {
  const text = toAimSpeechText(aim, previous);
  if (text) speakAimText(text);
}

/** An aim announcement not yet heard to the end: a new instruction cuts it off, then repeats it. */
let pendingAimText: string | null = null;

function speakAimText(text: string): void {
  pendingAimText = text;
  Speech.speak(text, {
    rate: 1.0,
    onDone: () => {
      if (pendingAimText === text) pendingAimText = null;
    },
  });
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
  if (pendingAimText) speakAimText(pendingAimText);
  await new Promise<void>((resolve) => setTimeout(resolve, HAPTIC_DELAY_MS));
  await runHaptic(command);
}

export async function executeCommandInput(input: NavigationCommandInput): Promise<NavigationCommand> {
  const command = parseNavigationCommand(input);
  await executeCommand(command);
  return command;
}
