/**
 * The contract between Person 2 (Mapping + Planning) and Person 3 (UX + Guidance).
 * Frozen: changing anything here requires agreement from all three owners.
 */

/**
 * SCAN means "stand still AND sweep the phone slowly".
 *
 * It is not a variant of STOP. STOP means "wait, I will tell you when it is safe"; SCAN means
 * "I cannot tell you anything until you move the phone". Delivered as STOP, a user obediently
 * stands still while the engine waits for camera motion that never comes - and this is the FIRST
 * thing the engine says on every session, while it builds its initial map.
 */
export type NavigationAction =
  | 'LEFT'
  | 'RIGHT'
  | 'STRAIGHT'
  | 'STOP'
  | 'SCAN'
  | 'ARRIVED';

export interface NavigationHazard {
  detected: boolean;
  type?: string;
}

export interface NavigationCommand {
  action: NavigationAction;
  target?: string;
  hazard?: NavigationHazard;
  /** 0..1 */
  confidence: number;
}
