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
 * Haptic vocabulary for HapticNav.
 *
 * Direction patterns intentionally use different:
 * - pulse counts
 * - intensities
 * - spacing
 *
 * so LEFT and RIGHT remain distinguishable without looking
 * at the screen.
 */
export const HapticTuning = {
  /**
   * Large gap between direction pulses.
   * 250ms makes 2 vs 3 pulses much easier to count.
   */
  directionGap: 250,

  /**
   * STOP is faster and more urgent than normal directions.
   */
  urgentGap: 120,

  /**
   * Gap used for the ARRIVED swell.
   */
  swellGap: 45,

  /**
   * More taps = stronger feeling of a sustained vibration.
   */
  swellCount: 12,

  /**
   * Gap after the initial hazard notification.
   */
  leadGap: 180,

  /**
   * Gap between hazard impacts.
   */
  hazardGap: 100,
};

interface Pulse {
  style: Haptics.ImpactFeedbackStyle;
  gapAfter: number;
}

interface HapticPattern {
  description: string;
  lead?: Haptics.NotificationFeedbackType;
  pulses: Pulse[];
}

function counted(
  count: number,
  style: Haptics.ImpactFeedbackStyle,
  gap: number,
): Pulse[] {
  return Array.from({ length: count }, (_, i) => ({
    style,
    gapAfter: i === count - 1 ? 0 : gap,
  }));
}

const {
  directionGap,
  urgentGap,
  swellGap,
  swellCount,
  leadGap,
  hazardGap,
} = HapticTuning;

export const HapticPatterns: Record<
  HapticPatternName,
  HapticPattern
> = {
  /**
   * STRAIGHT
   *
   * One strong, unmistakable pulse.
   */
  STRAIGHT: {
    description: 'Straight, one strong pulse',
    pulses: counted(
      1,
      Haptics.ImpactFeedbackStyle.Heavy,
      directionGap,
    ),
  },

  /**
   * RIGHT
   *
   * Two heavy pulses with a large gap.
   */
  RIGHT: {
    description: 'Right, two heavy pulses',
    pulses: counted(
      2,
      Haptics.ImpactFeedbackStyle.Heavy,
      directionGap,
    ),
  },

  /**
   * LEFT
   *
   * Three rigid pulses.
   *
   * Rigid feels sharper/crisper than Heavy, making
   * LEFT feel different from RIGHT even before counting.
   */
  LEFT: {
    description: 'Left, three sharp pulses',
    pulses: counted(
      3,
      Haptics.ImpactFeedbackStyle.Rigid,
      directionGap,
    ),
  },

  /**
   * STOP
   *
   * Four rapid heavy pulses.
   *
   * The faster rhythm distinguishes STOP from the
   * slower direction patterns.
   */
  STOP: {
    description: 'STOP, four rapid heavy pulses',
    pulses: counted(
      4,
      Haptics.ImpactFeedbackStyle.Heavy,
      urgentGap,
    ),
  },

  /**
   * ARRIVED
   *
   * Repeated light taps create a sustained sensation,
   * followed by one heavy confirmation pulse.
   */
  ARRIVED: {
    description: 'Arrived, sustained swell + confirmation',

    pulses: [
      ...counted(
        swellCount,
        Haptics.ImpactFeedbackStyle.Light,
        swellGap,
      ).map((pulse) => ({
        ...pulse,
        gapAfter: swellGap,
      })),

      {
        style: Haptics.ImpactFeedbackStyle.Heavy,
        gapAfter: 0,
      },
    ],
  },

  /**
   * HAZARD
   *
   * Deliberately irregular and strong.
   * This should never sound like normal navigation.
   */
  HAZARD: {
    description: 'Hazard, emergency warning',

    lead: Haptics.NotificationFeedbackType.Error,

    pulses: [
      {
        style: Haptics.ImpactFeedbackStyle.Heavy,
        gapAfter: leadGap,
      },
      {
        style: Haptics.ImpactFeedbackStyle.Rigid,
        gapAfter: hazardGap,
      },
      {
        style: Haptics.ImpactFeedbackStyle.Heavy,
        gapAfter: hazardGap,
      },
      {
        style: Haptics.ImpactFeedbackStyle.Rigid,
        gapAfter: 0,
      },
    ],
  },
};

const sleep = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

let generation = 0;

let queue: Promise<void> = Promise.resolve();

async function run(
  pattern: HapticPattern,
  myGeneration: number,
): Promise<void> {
  if (myGeneration !== generation) return;

  if (pattern.lead !== undefined) {
    await Haptics.notificationAsync(pattern.lead);
    await sleep(leadGap);
  }

  for (const pulse of pattern.pulses) {
    if (myGeneration !== generation) return;

    await Haptics.impactAsync(pulse.style);

    if (pulse.gapAfter > 0) {
      await sleep(pulse.gapAfter);
    }
  }
}

/**
 * Plays a pattern to completion.
 * A newer call supersedes any pattern still playing.
 */
export function playPattern(
  name: HapticPatternName,
): Promise<void> {
  const myGeneration = ++generation;

  queue = queue
    .then(() => run(HapticPatterns[name], myGeneration))
    .catch(() => {});

  return queue;
}

/**
 * Abandons whatever is playing at the next pulse boundary.
 */
export function stopHaptics(): void {
  generation++;
}

/**
 * Hazard warnings outrank navigation.
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
  return playPattern(patternForCommand(command));
}

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