/**
 * The contract between Person 2 (Mapping + Planning) and Person 3 (UX + Guidance).
 * Frozen: changing anything here requires agreement from all three owners.
 */

export type NavigationAction = 'LEFT' | 'RIGHT' | 'STRAIGHT' | 'STOP' | 'ARRIVED';

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
