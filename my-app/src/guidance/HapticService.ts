import * as Haptics from 'expo-haptics';

import type { NavigationCommand } from '@/types/NavigationCommand';

/**
 * Haptic vocabulary for HapticNav, tuned for iOS.
 *
 * Three iOS constraints shape this implementation:
 *
 * 1. React Native's `Vibration` API maps to a fixed ~400ms system buzz on iOS and
 *    ignores requested durations, so counted pulses are composed from Taptic Engine
 *    impacts separated by explicit gaps.
 * 2. The Taptic Engine is silent while a camera session is active. Callers must set
 *    `active={false}` on `CameraView` before playing a pattern.
 * 3. The engine is also silent in Low Power Mode, or if the user disabled system
 *    haptics in Settings. Neither is detectable here; speech is the fallback channel.
 */

export type HapticPatternName = 'STRAIGHT' | 'RIGHT' | 'LEFT' | 'STOP' | 'ARRIVED' | 'HAZARD';

/** Gap lengths in milliseconds. Tune these on the physical phone, not in the simulator. */
export const HapticTuning = {
  /** Between counted direction pulses. Too short and 2 vs 3 becomes unreadable. */
  pulseGap: 110,
  /** Between STOP pulses. Tighter than pulseGap so it reads as urgent. */
  urgentGap: 85,
  /** Between the fast taps that fake a sustained buzz for ARRIVED. */
  swellGap: 38,
  /** How many fast taps make up the ARRIVED swell. */
  swellCount: 9,
  /** After the leading notification of a hazard pattern. */
  leadGap: 140,
};

interface Pulse {
  style: Haptics.ImpactFeedbackStyle;
  gapAfter: number;
}

interface HapticPattern {
  /** Spoken aloud by the test bench so patterns can be learned eyes-free. */
  description: string;
  lead?: Haptics.NotificationFeedbackType;
  pulses: Pulse[];
}

function counted(count: number, style: Haptics.ImpactFeedbackStyle, gap: number): Pulse[] {
  return Array.from({ length: count }, (_, i) => ({
    style,
    gapAfter: i === count - 1 ? 0 : gap,
  }));
}

const { pulseGap, urgentGap, swellGap, swellCount, leadGap } = HapticTuning;

export const HapticPatterns: Record<HapticPatternName, HapticPattern> = {
  STRAIGHT: {
    description: 'Straight, one pulse',
    pulses: counted(1, Haptics.ImpactFeedbackStyle.Medium, pulseGap),
  },
  RIGHT: {
    description: 'Right, two pulses',
    pulses: counted(2, Haptics.ImpactFeedbackStyle.Medium, pulseGap),
  },
  LEFT: {
    description: 'Left, three pulses',
    pulses: counted(3, Haptics.ImpactFeedbackStyle.Medium, pulseGap),
  },
  STOP: {
    description: 'Stop, four pulses',
    pulses: counted(4, Haptics.ImpactFeedbackStyle.Heavy, urgentGap),
  },
  ARRIVED: {
    // iOS cannot hold a long vibration, so a fast swell closed by one heavy tap
    // stands in for the single long pulse in the spec.
    description: 'Arrived, long pulse',
    pulses: [
      ...counted(swellCount, Haptics.ImpactFeedbackStyle.Light, swellGap).map((pulse) => ({
        ...pulse,
        gapAfter: swellGap,
      })),
      { style: Haptics.ImpactFeedbackStyle.Heavy, gapAfter: 0 },
    ],
  },
  HAZARD: {
    // Deliberately unlike the counted patterns: an alert burst, then uneven taps.
    description: 'Hazard warning',
    lead: Haptics.NotificationFeedbackType.Error,
    pulses: [
      { style: Haptics.ImpactFeedbackStyle.Heavy, gapAfter: 70 },
      { style: Haptics.ImpactFeedbackStyle.Rigid, gapAfter: 70 },
      { style: Haptics.ImpactFeedbackStyle.Heavy, gapAfter: 0 },
    ],
  },
};

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

let generation = 0;
let queue: Promise<void> = Promise.resolve();

async function run(pattern: HapticPattern, myGeneration: number): Promise<void> {
  if (myGeneration !== generation) return;

  if (pattern.lead !== undefined) {
    await Haptics.notificationAsync(pattern.lead);
    await sleep(leadGap);
  }

  for (const pulse of pattern.pulses) {
    if (myGeneration !== generation) return;
    await Haptics.impactAsync(pulse.style);
    if (pulse.gapAfter > 0) await sleep(pulse.gapAfter);
  }
}

/**
 * Plays a pattern to completion. A newer call supersedes any pattern still playing,
 * so a hazard can cut off a direction mid-count instead of garbling the pulse count.
 */
export function playPattern(name: HapticPatternName): Promise<void> {
  const myGeneration = ++generation;
  queue = queue.then(() => run(HapticPatterns[name], myGeneration)).catch(() => {});
  return queue;
}

/** Abandons whatever is playing at the next pulse boundary. */
export function stopHaptics(): void {
  generation++;
}

/** Hazard warnings outrank navigation, so this is where that priority is enforced. */
export function patternForCommand(command: NavigationCommand): HapticPatternName {
  return command.hazard?.detected ? 'HAZARD' : command.action;
}

export function playForCommand(command: NavigationCommand): Promise<void> {
  return playPattern(patternForCommand(command));
}

/** Simple direction-specific helpers used by UI/controller code. */
export function hapticStraight(): Promise<void> {
  return playPattern('STRAIGHT');
}

export function hapticRight(): Promise<void> {
  return playPattern('RIGHT');
}

export function hapticLeft(): Promise<void> {
  return playPattern('LEFT');
}

export function hapticStop(): Promise<void> {
  return playPattern('STOP');
}

export function hapticArrived(): Promise<void> {
  return playPattern('ARRIVED');
}

export function hapticHazard(): Promise<void> {
  return playPattern('HAZARD');
}
