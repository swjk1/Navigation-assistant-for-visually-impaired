import * as Haptics from 'expo-haptics';

import type { NavigationCommand } from '@/types/NavigationCommand';

export type HapticPatternName =
  | 'STRAIGHT'
  | 'RIGHT'
  | 'LEFT'
  | 'STOP'
  | 'ARRIVED'
  | 'HAZARD';

/**
 * Requested haptic rhythm mapping.
 *
 * ._ = short pulse
 * __ = long pulse
 */
const RHYTHM: Record<HapticPatternName, string> = {
  LEFT: '._ ._',
  RIGHT: '._ ._ ._ ._',
  STRAIGHT: '._ ._ ._',
  STOP: '__ __',
  ARRIVED: '._ ._ ._ ._ ._',
  HAZARD: '__ ._ __ ._',
};

/**
 * Tune these values on the physical phone.
 */
export const HapticTuning = {
  // Length of a short pulse.
  shortDuration: 180,

  // Length of a long pulse.
  longDuration: 540,

  // Gap between pulses in the same command.
  pulseGap: 180,

  // Gap before starting the next command.
  commandGap: 700,
};

const sleep = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

let generation = 0;
let queue: Promise<void> = Promise.resolve();

/** Play one token from the rhythm map. */
async function playToken(
  token: '._' | '__',
  myGeneration: number,
): Promise<void> {
  if (myGeneration !== generation) return;

  const isLong = token === '__';
  const duration = isLong
    ? HapticTuning.longDuration
    : HapticTuning.shortDuration;

  /*
   * expo-haptics does not expose a true "vibrate for N milliseconds"
   * API on iOS.
   *
   * We therefore use a Heavy impact for both symbols and create
   * the perception of duration through repeated impacts.
   */
  if (!isLong) {
    await Haptics.impactAsync(
      Haptics.ImpactFeedbackStyle.Heavy,
    );

    await sleep(duration);
  } else {
    // Create a longer-feeling dash using repeated Heavy impacts.
    const dashImpacts = 3;

    for (let i = 0; i < dashImpacts; i++) {
      if (myGeneration !== generation) return;

      await Haptics.impactAsync(
        Haptics.ImpactFeedbackStyle.Heavy,
      );

      if (i < dashImpacts - 1) {
        await sleep(120);
      }
    }

    await sleep(180);
  }
}

/**
 * Play a mapped pulse command.
 */
async function runPattern(
  rhythm: string,
  myGeneration: number,
): Promise<void> {
  const tokens = rhythm
    .split(' ')
    .map((part) => part.trim())
    .filter((part): part is '._' | '__' => part === '._' || part === '__');

  for (let tokenIndex = 0; tokenIndex < tokens.length; tokenIndex++) {
    if (myGeneration !== generation) return;
    await playToken(tokens[tokenIndex], myGeneration);
    if (tokenIndex < tokens.length - 1) {
      await sleep(HapticTuning.pulseGap);
    }
  }
}

/**
 * Plays a navigation command using the requested pulse rhythm map.
 *
 * A newer command immediately supersedes the old one.
 */
export function playPattern(
  name: HapticPatternName,
): Promise<void> {
  const myGeneration = ++generation;

  queue = queue
    .then(() =>
      runPattern(RHYTHM[name], myGeneration),
    )
    .catch(() => {});

  return queue;
}

/**
 * Stop the current haptic sequence.
 */
export function stopHaptics(): void {
  generation++;
}

/**
 * Hazard always takes priority over normal navigation.
 */
export function patternForCommand(
  command: NavigationCommand,
): HapticPatternName {
  return command.hazard?.detected
    ? 'HAZARD'
    : command.action;
}

export function playForCommand(
  command: NavigationCommand,
): Promise<void> {
  return playPattern(
    patternForCommand(command),
  );
}

// Convenience functions

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