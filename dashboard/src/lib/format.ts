import type { IdentityCheckOutcome, ResultStatus, Severity } from "../api/types";

export const TEST_NAMES: Record<string, string> = {
  SQUATS: "Squats",
  PUSH_UPS: "Push-ups",
  BICEP_CURLS: "Bicep curls",
  LUNGES: "Lunges",
  VERTICAL_JUMP: "Vertical jump",
  SIT_UPS: "Sit-ups",
};

export function testName(code: string): string {
  return TEST_NAMES[code] ?? code.replace(/_/g, " ").toLowerCase();
}

export const STATUS_LABELS: Record<ResultStatus, string> = {
  processing: "Processing",
  verified: "Verified — awaiting approval",
  flagged: "Flagged",
  approved: "Approved",
  rejected: "Rejected",
  pending_sync: "Resubmission requested",
};

/** Plain-language names for the reasons in `flags.reason`. */
const FLAG_REASONS: Record<string, string> = {
  looped_frames: "Repeated footage",
  abrupt_cut: "Abrupt cut",
  static_video: "Nothing moving",
  multiple_people: "More than one person",
  no_subject: "No athlete visible",
  subject_swapped: "Athlete may have changed",
  duration_implausible: "Implausible duration",
  resolution_unexpected: "Unexpected resolution",
  framerate_implausible: "Implausible frame rate",
  face_mismatch: "Face does not clearly match",
  face_not_found: "No face found",
  identity_unconfirmed: "Identity not confirmed before the test",
  score_discrepancy: "Phone and server scores disagree",
  server_could_not_score: "Server could not score",
  low_tracking_quality: "Poor tracking quality",
  device_score_missing: "No score from the phone",
};

/** What the athlete's photo check before the test concluded, for a reviewer. */
export function identityCheckLabel(outcome: IdentityCheckOutcome | null | undefined): string {
  switch (outcome) {
    case "match":
      return "matched the registration photo";
    case "no_match":
      return "did not clearly match — the athlete continued after retrying";
    case "no_face":
      return "showed no clear face — the athlete continued after retrying";
    case "unavailable":
      return "could not run";
    default:
      return "not taken";
  }
}

export function flagReason(reason: string): string {
  return FLAG_REASONS[reason] ?? reason.replace(/_/g, " ");
}

export const SEVERITY_ORDER: Record<Severity, number> = { high: 3, medium: 2, low: 1 };

export function formatScore(value: number | null | undefined, unit: string): string {
  if (value === null || value === undefined) return "—";
  const rounded = Number.isInteger(value) ? String(value) : value.toFixed(1);
  return unit ? `${rounded} ${unit}` : rounded;
}

/** Signed difference between server and phone, for the comparison view. */
export function scoreDifference(
  provisional: number | null,
  server: number | null,
): number | null {
  if (provisional === null || server === null) return null;
  return Math.round((server - provisional) * 100) / 100;
}

export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function actionLabel(action: string): string {
  switch (action) {
    case "approved":
      return "Approved";
    case "rejected":
      return "Rejected";
    case "requested_resubmission":
      return "Requested resubmission";
    default:
      return action;
  }
}

export const REGIONS = [
  "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa",
  "Gujarat", "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala",
  "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland",
  "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura",
  "Uttar Pradesh", "Uttarakhand", "West Bengal", "Andaman and Nicobar Islands",
  "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu", "Delhi",
  "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry",
] as const;

/**
 * Integrity flags carry "(at 4.0s)" in their detail — where in the recording
 * to look. Returned in milliseconds so the reviewer can jump straight there.
 */
export function flagTimestampMs(detail: string | null): number | null {
  if (!detail) return null;
  const match = /\(at (\d+(?:\.\d+)?)s\)/.exec(detail);
  return match ? Math.round(Number(match[1]) * 1000) : null;
}
