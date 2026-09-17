import type { PoseSequence } from "../api/types";

/** MediaPipe Pose landmark connections (33-point model), torso and limbs only. */
export const SKELETON_CONNECTIONS: ReadonlyArray<readonly [number, number]> = [
  [11, 12], [11, 23], [12, 24], [23, 24], // torso
  [11, 13], [13, 15], [12, 14], [14, 16], // arms
  [23, 25], [25, 27], [24, 26], [26, 28], // legs
  [27, 29], [29, 31], [28, 30], [30, 32], // feet
  [0, 11], [0, 12], // head to shoulders
];

/** Below this visibility a landmark is a guess, and drawing it would mislead. */
export const MIN_VISIBILITY = 0.5;

/**
 * Beyond this gap from the nearest earlier frame, draw nothing.
 *
 * The server drops frames it cannot track. Holding the last skeleton on screen
 * across that gap would show the reviewer a pose the measurement never saw.
 */
export const MAX_FRAME_GAP_MS = 150;

export interface Point {
  x: number;
  y: number;
  visible: boolean;
}

/** The latest frame at or before `timeMs`, or null. Binary search. */
export function frameAt(sequence: PoseSequence, timeMs: number): PoseSequence["frames"][number] | null {
  const frames = sequence.frames;
  let low = 0;
  let high = frames.length - 1;
  let found = -1;

  while (low <= high) {
    const mid = (low + high) >> 1;
    const frame = frames[mid]!;
    if (frame.t <= timeMs) {
      found = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }

  if (found < 0) return null;
  const frame = frames[found]!;
  return timeMs - frame.t <= MAX_FRAME_GAP_MS ? frame : null;
}

export function landmarks(frame: { p: number[] }, count: number): Point[] {
  const points: Point[] = [];
  for (let index = 0; index < count; index++) {
    const base = index * 3;
    const x = frame.p[base];
    const y = frame.p[base + 1];
    const visibility = frame.p[base + 2];
    if (x === undefined || y === undefined || visibility === undefined) break;
    points.push({ x, y, visible: visibility >= MIN_VISIBILITY });
  }
  return points;
}

export interface Rect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * Where the video's picture actually sits inside its element.
 *
 * The element letterboxes (object-fit: contain). Landmarks are normalised to
 * the picture, not the element, so drawing against the element's box would
 * put every joint in the wrong place on a portrait recording.
 */
export function contentRect(
  elementWidth: number,
  elementHeight: number,
  videoWidth: number,
  videoHeight: number,
): Rect {
  if (!videoWidth || !videoHeight || !elementWidth || !elementHeight) {
    return { left: 0, top: 0, width: elementWidth, height: elementHeight };
  }

  const scale = Math.min(elementWidth / videoWidth, elementHeight / videoHeight);
  const width = videoWidth * scale;
  const height = videoHeight * scale;

  return {
    left: (elementWidth - width) / 2,
    top: (elementHeight - height) / 2,
    width,
    height,
  };
}
