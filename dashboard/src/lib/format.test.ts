import {
  flagReason,
  flagTimestampMs,
  formatScore,
  identityCheckLabel,
  scoreDifference,
  testName,
} from "./format";

describe("format", () => {
  it("formats scores without spurious decimals", () => {
    expect(formatScore(24, "reps")).toBe("24 reps");
    expect(formatScore(41.25, "cm")).toBe("41.3 cm");
    expect(formatScore(null, "cm")).toBe("—");
  });

  it("computes server minus phone", () => {
    expect(scoreDifference(30, 22)).toBe(-8);
    expect(scoreDifference(null, 22)).toBeNull();
  });

  it("names every reason the backend raises", () => {
    for (const reason of [
      "looped_frames",
      "abrupt_cut",
      "static_video",
      "multiple_people",
      "no_subject",
      "subject_swapped",
      "duration_implausible",
      "resolution_unexpected",
      "framerate_implausible",
      "face_mismatch",
      "face_not_found",
      "identity_unconfirmed",
      "score_discrepancy",
      "server_could_not_score",
      "low_tracking_quality",
      "device_score_missing",
    ]) {
      expect(flagReason(reason)).not.toContain("_");
    }
  });

  it("reads where to look from a flag's detail", () => {
    expect(flagTimestampMs("20 frames from 1.0s repeat at 4.0s (at 4.5s)")).toBe(4500);
    expect(flagTimestampMs("No timestamp here")).toBeNull();
    expect(flagTimestampMs(null)).toBeNull();
  });

  it("names tests", () => {
    expect(testName("SIT_UPS")).toBe("Sit-ups");
    expect(testName("SHUTTLE_RUN")).toBe("shuttle run");
  });
  it("never presents a missing photo check as a pass", () => {
    expect(identityCheckLabel("match")).toBe("matched the registration photo");
    expect(identityCheckLabel(null)).toBe("not taken");
    expect(identityCheckLabel(undefined)).toBe("not taken");
    expect(identityCheckLabel("no_match")).toContain("did not clearly match");
  });
});
