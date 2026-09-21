/**
 * Consumer-facing helpers derived from a {@link PerceptionFrame}.
 *
 * No pathfinding and no TTS engine here - only structured hints. The navigation layer decides
 * routes; the guidance layer decides voice, rate and when to interrupt. This module's whole job
 * is to turn one verified frame into the few short facts those two layers actually consume.
 */

import {
  MAX_SPEAKABLE_LINES,
  NAV_ANCHOR_LABELS,
  OBSTACLE_LABELS,
} from '../constants/perceptionCatalog.js';

/** @typedef {import('../types/perception').PerceptionFrame} PerceptionFrame */
/** @typedef {import('../types/perception').DetectedObject} DetectedObject */

/**
 * @typedef {object} TeammateHandoff
 * @property {PerceptionFrame | null} frame
 * @property {DetectedObject[]} navAnchors
 * @property {DetectedObject[]} obstacles
 * @property {string[]} signs
 * @property {string[]} speakableLines
 * @property {'hazard' | 'guidance'} speechPriority Hazard lines preempt navigation chatter.
 */

/**
 * Strip any debug `meta` so consumers only ever see the frozen contract fields.
 *
 * @param {unknown} frame
 * @returns {PerceptionFrame | null}
 */
export function toPublicPerceptionFrame(frame) {
  if (!frame || typeof frame !== 'object') return null;
  const raw = /** @type {Record<string, any>} */ (frame);
  return {
    timestamp: raw.timestamp,
    latencyMs: raw.latencyMs,
    objects: Array.isArray(raw.objects) ? raw.objects : [],
    text: Array.isArray(raw.text) ? raw.text : [],
    floorDetected: Boolean(raw.floorDetected),
    immediateHazard: Boolean(raw.immediateHazard),
    hazardDescription: raw.hazardDescription ?? null,
  };
}

/**
 * Navigation anchors (doors, elevators, stairs, signs), nearest first.
 *
 * @param {unknown} frame
 * @returns {DetectedObject[]}
 */
export function listNavAnchors(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  if (!publicFrame) return [];
  return publicFrame.objects
    .filter((o) => /** @type {readonly string[]} */ (NAV_ANCHOR_LABELS).includes(o.label))
    .slice()
    .sort(
      (a, b) =>
        (a.approxDistanceMeters || 99) - (b.approxDistanceMeters || 99)
    );
}

/**
 * Obstacles for occupancy / avoidance.
 *
 * @param {unknown} frame
 * @returns {DetectedObject[]}
 */
export function listObstacles(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  if (!publicFrame) return [];
  return publicFrame.objects.filter((o) =>
    /** @type {readonly string[]} */ (OBSTACLE_LABELS).includes(o.label)
  );
}

/**
 * Room / sign strings for destination confirmation.
 *
 * @param {unknown} frame
 * @returns {string[]}
 */
export function listReadableSigns(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  if (!publicFrame) return [];
  return publicFrame.text.map((t) => t.text).filter(Boolean);
}

/**
 * How each anchor label is spoken. Anchors are announced as "<noun> at <clock>, about <n> meters".
 *
 * Every label in NAV_ANCHOR_LABELS must appear here. An anchor the perception layer detects but
 * this map has no phrase for is silently never spoken - which is the same failure as not
 * detecting it at all, one layer further along. The Record type makes that a compile error
 * rather than a silent gap.
 *
 * @type {Record<(typeof NAV_ANCHOR_LABELS)[number], string>}
 */
const ANCHOR_SPOKEN_NOUN = {
  door: 'Door',
  elevator: 'Elevator',
  stairs: 'Stairs',
  sign: 'Sign',
  'exit sign': 'Exit sign',
  washroom: 'Washroom',
};

/**
 * Short speakable lines, ordered by priority and capped so the TTS queue stays actionable.
 *
 * @param {unknown} frame
 * @returns {string[]}
 */
export function listSpeakableLines(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  if (!publicFrame) return [];

  /** @type {string[]} */
  const lines = [];

  if (publicFrame.immediateHazard) {
    lines.push(
      publicFrame.hazardDescription ||
        'Hazard ahead. Stop and hold position.'
    );
  }

  for (const anchor of listNavAnchors(publicFrame)) {
    if (lines.length >= MAX_SPEAKABLE_LINES) break;
    const noun = ANCHOR_SPOKEN_NOUN[
      /** @type {(typeof NAV_ANCHOR_LABELS)[number]} */ (anchor.label)
    ];
    if (!noun) continue;
    const dist = Number(anchor.approxDistanceMeters).toFixed(1);
    lines.push(`${noun} at ${anchor.clockPosition}, about ${dist} meters.`);
  }

  for (const sign of listReadableSigns(publicFrame)) {
    if (lines.length >= MAX_SPEAKABLE_LINES) break;
    lines.push(`Sign reads ${sign}.`);
  }

  for (const obs of listObstacles(publicFrame)) {
    if (lines.length >= MAX_SPEAKABLE_LINES) break;
    const dist = Number(obs.approxDistanceMeters).toFixed(1);
    lines.push(
      `${capitalize(obs.label)} at ${obs.clockPosition}, about ${dist} meters.`
    );
  }

  // De-dupe while preserving order, then hard-cap for TTS.
  return [...new Set(lines)].slice(0, MAX_SPEAKABLE_LINES);
}

/**
 * Compact integration payload.
 *
 * @param {unknown} frame
 * @returns {TeammateHandoff}
 */
export function buildTeammateHandoff(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  return {
    frame: publicFrame,
    navAnchors: listNavAnchors(publicFrame),
    obstacles: listObstacles(publicFrame),
    signs: listReadableSigns(publicFrame),
    speakableLines: listSpeakableLines(publicFrame),
    speechPriority: publicFrame?.immediateHazard ? 'hazard' : 'guidance',
  };
}

/**
 * @param {unknown} s
 * @returns {string}
 */
function capitalize(s) {
  const str = String(s || '');
  return str ? str.charAt(0).toUpperCase() + str.slice(1) : str;
}
