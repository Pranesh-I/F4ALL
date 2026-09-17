import type { PoseSequence } from "../api/types";
import { MAX_FRAME_GAP_MS, contentRect, frameAt, landmarks } from "./pose";

const sequence: PoseSequence = {
  version: 1,
  landmarks: 2,
  frames: [
    { t: 0, p: [0.1, 0.2, 0.9, 0.3, 0.4, 0.2] },
    { t: 33, p: [0.11, 0.21, 0.9, 0.31, 0.41, 0.9] },
    { t: 66, p: [0.12, 0.22, 0.9, 0.32, 0.42, 0.9] },
    // A tracking gap: the server dropped frames it could not see.
    { t: 1000, p: [0.5, 0.5, 0.9, 0.5, 0.5, 0.9] },
  ],
};

describe("frameAt", () => {
  it("returns the latest frame at or before the time", () => {
    expect(frameAt(sequence, 0)?.t).toBe(0);
    expect(frameAt(sequence, 40)?.t).toBe(33);
    expect(frameAt(sequence, 66)?.t).toBe(66);
  });

  it("returns nothing before the first frame", () => {
    expect(frameAt({ ...sequence, frames: [{ t: 100, p: [] }] }, 50)).toBeNull();
  });

  it("draws nothing across a tracking gap rather than freezing the last pose", () => {
    expect(frameAt(sequence, 66 + MAX_FRAME_GAP_MS + 1)).toBeNull();
    expect(frameAt(sequence, 1010)?.t).toBe(1000);
  });

  it("handles an empty sequence", () => {
    expect(frameAt({ ...sequence, frames: [] }, 10)).toBeNull();
  });
});

describe("landmarks", () => {
  it("unpacks flat triples and hides low-visibility joints", () => {
    const points = landmarks(sequence.frames[0]!, 2);
    expect(points).toEqual([
      { x: 0.1, y: 0.2, visible: true },
      { x: 0.3, y: 0.4, visible: false },
    ]);
  });

  it("stops at a truncated frame instead of reading undefined", () => {
    expect(landmarks({ p: [0.1, 0.2, 0.9, 0.3] }, 2)).toHaveLength(1);
  });
});

describe("contentRect", () => {
  it("letterboxes a portrait video inside a landscape element", () => {
    // 480x854 video in a 1000x500 box: height-limited.
    const rect = contentRect(1000, 500, 480, 854);
    expect(rect.height).toBeCloseTo(500);
    expect(rect.width).toBeCloseTo(480 * (500 / 854));
    expect(rect.left).toBeCloseTo((1000 - rect.width) / 2);
    expect(rect.top).toBeCloseTo(0);
  });

  it("falls back to the element before metadata has loaded", () => {
    expect(contentRect(640, 360, 0, 0)).toEqual({ left: 0, top: 0, width: 640, height: 360 });
  });
});
