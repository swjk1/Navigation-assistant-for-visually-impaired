import type { EventSubscription } from 'expo-modules-core';

import NavigationNative, {
  addNavigationStateListener,
  type NavigationSnapshot,
  type NavigationTarget,
} from '../../modules/navigation-native';
import type { NavigationAction, NavigationCommand } from '@/types/NavigationCommand';

/**
 * The seam between Person 2 (mapping + planning) and Person 3 (UX + guidance).
 *
 * The native engine emits a rich `NavigationSnapshot`; the guidance layer consumes the frozen
 * `NavigationCommand` contract in `@/types/NavigationCommand`. This module is the only place that
 * knows about both, so the contract stays frozen and the engine stays free to grow.
 *
 * Dependency direction is one-way on purpose: this file imports the contract, never HapticService
 * or any UI. Guidance consumes commands; it is not consumed by them.
 */

/**
 * `SCAN` has no representation in the frozen contract, so it is delivered as `STOP`.
 *
 * This is a real loss of meaning, not a tidy equivalence. `SCAN` means "stand still AND sweep the
 * phone so I can see more" - it is the engine asking the user for help, and it is how the engine
 * recovers from lost tracking, missing depth, or an incomplete map. A user who is told only
 * "stop" will stand still indefinitely waiting for an instruction that cannot arrive until they
 * move the phone.
 *
 * Until the three owners agree to add a `SCAN` action, callers that can speak should check
 * {@link isScanRequest} and say so. `stopReason` on the snapshot carries the detail.
 */
export const SCAN_IS_DELIVERED_AS_STOP = true;

/** Map confidence at which the engine's instructions are treated as fully trustworthy. */
const MAP_CONFIDENCE_FULL = 0.5;

const ACTION_BY_COMMAND: Record<NavigationSnapshot['command'], NavigationAction> = {
  STRAIGHT: 'STRAIGHT',
  TURN_LEFT: 'LEFT',
  TURN_RIGHT: 'RIGHT',
  STOP: 'STOP',
  // See SCAN_IS_DELIVERED_AS_STOP.
  SCAN: 'STOP',
  ARRIVED: 'ARRIVED',
};

const clamp01 = (value: number) => (value < 0 ? 0 : value > 1 ? 1 : value);

/**
 * How far to trust this instruction, 0..1.
 *
 * A halt is always fully trusted: the engine stops the user precisely when it is NOT confident,
 * so hedging a safety stop would invert the meaning. Everything else is scaled by how well the
 * engine can currently see.
 */
function confidenceOf(snapshot: NavigationSnapshot): number {
  if (snapshot.command === 'STOP' || snapshot.command === 'SCAN') return 1;
  const mapFactor = clamp01(snapshot.mapConfidence / MAP_CONFIDENCE_FULL);
  return clamp01(snapshot.trackingConfidence * mapFactor);
}

/**
 * True when the engine is asking the user to sweep the phone rather than simply to stand still.
 * Use this to add speech; the returned `NavigationCommand` cannot express it.
 */
export function isScanRequest(snapshot: NavigationSnapshot): boolean {
  return snapshot.command === 'SCAN';
}

/**
 * Translates an engine snapshot into the frozen guidance contract.
 *
 * `hazard.detected` is driven by the engine's own `stopReason`, never inferred from the command:
 * a `STOP` because something is in the way and a `STOP` because tracking died look identical from
 * the outside, and the haptic vocabulary gives HAZARD priority over direction. Guessing here would
 * mean either a hazard alert for a software fault or, worse, silence for a real obstacle.
 */
export function toNavigationCommand(snapshot: NavigationSnapshot): NavigationCommand {
  return {
    action: ACTION_BY_COMMAND[snapshot.command] ?? 'STOP',
    target: snapshot.target ?? undefined,
    hazard: { detected: snapshot.stopReason === 'OBSTACLE_AHEAD', type: snapshot.stopReason ?? undefined },
    confidence: confidenceOf(snapshot),
  };
}

/**
 * Subscribes to the engine and delivers contract commands.
 *
 * Emitted at most ~8 Hz, and immediately on any change of status or command, so a safety stop is
 * never delayed by throttling. The raw snapshot is passed alongside for callers that need detail
 * the frozen contract cannot carry (`SCAN`, `stopReason`, map confidence, debug counters).
 */
export function subscribeNavigationCommands(
  listener: (command: NavigationCommand, snapshot: NavigationSnapshot) => void
): EventSubscription {
  return addNavigationStateListener((snapshot) => {
    listener(toNavigationCommand(snapshot), snapshot);
  });
}

/** The engine's current state as a contract command, without waiting for the next event. */
export function currentNavigationCommand(): NavigationCommand {
  return toNavigationCommand(NavigationNative.getSnapshot());
}

/** Convenience re-exports so guidance code needs only this module. */
export async function startNavigation(target: NavigationTarget): Promise<void> {
  await NavigationNative.start(target);
}

export async function stopNavigation(): Promise<void> {
  await NavigationNative.stop();
}

export { NavigationNative };
export type { NavigationSnapshot, NavigationTarget };
