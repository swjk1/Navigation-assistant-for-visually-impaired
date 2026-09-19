/**
 * Teammate-facing helpers derived from PerceptionFrame.
 * No pathfinding. No TTS engines — only structured hints teammates can consume.
 */

import { NAV_ANCHOR_LABELS, OBSTACLE_LABELS } from '../constants/navLabels.js';

/**
 * Strip debug `meta` so Person 2/3 only see the PRD contract fields.
 * @param {object} frame
 * @returns {object}
 */
export function toPublicPerceptionFrame(frame) {
  if (!frame || typeof frame !== 'object') return null;
  return {
    timestamp: frame.timestamp,
    latencyMs: frame.latencyMs,
    objects: Array.isArray(frame.objects) ? frame.objects : [],
    text: Array.isArray(frame.text) ? frame.text : [],
    floorDetected: Boolean(frame.floorDetected),
    immediateHazard: Boolean(frame.immediateHazard),
    hazardDescription: frame.hazardDescription ?? null,
  };
}

/**
 * Navigation anchors for Person 2 (doors, elevators, stairs, signs).
 * Sorted nearest-first.
 * @param {object} frame
 */
export function listNavAnchors(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  if (!publicFrame) return [];
  return publicFrame.objects
    .filter((o) => NAV_ANCHOR_LABELS.includes(o.label))
    .slice()
    .sort(
      (a, b) =>
        (a.approxDistanceMeters || 99) - (b.approxDistanceMeters || 99)
    );
}

/**
 * Obstacles for Person 2 occupancy / avoidance.
 * @param {object} frame
 */
export function listObstacles(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  if (!publicFrame) return [];
  return publicFrame.objects.filter((o) => OBSTACLE_LABELS.includes(o.label));
}

/**
 * Room / sign strings for destination confirmation.
 * @param {object} frame
 * @returns {string[]}
 */
export function listReadableSigns(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  if (!publicFrame) return [];
  return publicFrame.text.map((t) => t.text).filter(Boolean);
}

/**
 * Short speakable lines for Person 3 TTS (ordered by priority).
 * Person 3 decides voice engine, rate, and when to interrupt.
 *
 * @param {object} frame
 * @returns {string[]}
 */
export function listSpeakableLines(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  if (!publicFrame) return [];

  const lines = [];

  if (publicFrame.immediateHazard) {
    lines.push(
      publicFrame.hazardDescription ||
        'Hazard ahead. Stop and hold position.'
    );
  }

  for (const anchor of listNavAnchors(publicFrame)) {
    const dist = Number(anchor.approxDistanceMeters).toFixed(1);
    if (anchor.label === 'door') {
      lines.push(`Door at ${anchor.clockPosition}, about ${dist} meters.`);
    } else if (anchor.label === 'elevator') {
      lines.push(`Elevator at ${anchor.clockPosition}, about ${dist} meters.`);
    } else if (anchor.label === 'stairs') {
      lines.push(`Stairs at ${anchor.clockPosition}, about ${dist} meters.`);
    } else if (anchor.label === 'sign') {
      lines.push(`Sign at ${anchor.clockPosition}, about ${dist} meters.`);
    }
  }

  for (const sign of listReadableSigns(publicFrame)) {
    lines.push(`Sign reads ${sign}.`);
  }

  for (const obs of listObstacles(publicFrame)) {
    const dist = Number(obs.approxDistanceMeters).toFixed(1);
    lines.push(
      `${capitalize(obs.label)} at ${obs.clockPosition}, about ${dist} meters.`
    );
  }

  // De-dupe while preserving order
  return [...new Set(lines)];
}

/**
 * Compact integration payload for teammates.
 * @param {object} frame
 */
export function buildTeammateHandoff(frame) {
  const publicFrame = toPublicPerceptionFrame(frame);
  return {
    frame: publicFrame,
    navAnchors: listNavAnchors(publicFrame),
    obstacles: listObstacles(publicFrame),
    signs: listReadableSigns(publicFrame),
    speakableLines: listSpeakableLines(publicFrame),
    /** Hint for Person 3: hazard lines should preempt navigation chatter */
    speechPriority: publicFrame?.immediateHazard ? 'hazard' : 'guidance',
  };
}

function capitalize(s) {
  const str = String(s || '');
  return str ? str.charAt(0).toUpperCase() + str.slice(1) : str;
}
