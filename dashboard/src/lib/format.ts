import type {
  AssessmentSession,
  IdentityCheckOutcome,
  OfficialProfile,
  ResultStatus,
  ReviewReason,
  ReviewStatus,
  SessionStatus,
  Severity,
  VerificationVerdict,
} from "../api/types";

export const TEST_NAMES: Record<string, string> = {
  SQUATS: "Squats",
  PUSH_UPS: "Push-ups",
  BICEP_CURLS: "Bicep curls",
  LUNGES: "Lunges",
  VERTICAL_JUMP: "Vertical jump",
  SIT_UPS: "Sit-ups",
};

/**
 * `serverName` is the backend catalog's name (GET /api/dashboard/tests), so a
 * test added on the server is named properly here without a dashboard change.
 */
export function testName(code: string, serverName?: string): string {
  return TEST_NAMES[code] ?? serverName ?? code.replace(/_/g, " ").toLowerCase();
}

export const SESSION_STATUS_LABELS: Record<SessionStatus, string> = {
  active: "Active",
  scheduled: "Scheduled",
  ended: "Ended",
  disabled: "Disabled",
};

/** What a session's state means for athletes, in one sentence. */
export function sessionStatusExplanation(session: AssessmentSession): string {
  const where = session.region ? `in ${session.region}` : "in every region";
  switch (session.status) {
    case "active":
      return `Open now. Athletes ${where} can see it and submit until ${formatDateTime(session.ends_at)}.`;
    case "scheduled":
      return `Enabled, and opens ${formatDateTime(session.starts_at)}. Athletes cannot see it until then.`;
    case "ended":
      return `Closed ${formatDateTime(session.ends_at)}. Recordings made before then may still arrive from phones that were offline.`;
    case "disabled":
      return "Switched off. Athletes cannot see it or submit to it, whatever its window says.";
  }
}

export function roleLabel(official: OfficialProfile): string {
  return official.role === "sai_admin"
    ? "SAI admin · All regions"
    : `Regional reviewer · ${official.region ?? "No region assigned"}`;
}

/** Status filters for the submission list; values are the API's `status` param. */
export const SUBMISSION_STATUS_FILTERS = [
  { value: "", label: "All statuses" },
  { value: "uploaded,processing", label: "Awaiting verification" },
  { value: "verified", label: "Verified — awaiting approval" },
  { value: "flagged", label: "Flagged" },
  { value: "approved", label: "Approved" },
  { value: "rejected", label: "Rejected" },
  { value: "pending_sync", label: "Resubmission requested" },
] as const;

/** A `datetime-local` value, in the browser's time zone, sent as UTC. */
export function toIso(local: string): string {
  return new Date(local).toISOString();
}

/** The reverse, for editing: a UTC instant as a `datetime-local` value. */
export function toLocalInput(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

export const STATUS_LABELS: Record<ResultStatus, string> = {
  uploaded: "Queued for verification",
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
  // Sprint 11 validation: why the server stopped before scoring.
  video_not_submitted: "No video submitted",
  video_missing_from_storage: "Video missing from storage",
  video_empty: "Empty video file",
  video_too_large: "Video too large",
  unsupported_format: "Not an MP4 video",
  video_unreadable: "Video could not be read",
  no_decodable_frames: "No frames could be decoded",
  test_type_not_verifiable: "No automatic scorer for this test",
  athlete_height_unknown: "Athlete height unknown",
  // Sprint 12 integrity checks.
  duplicate_frames: "Repeated frames while moving",
  timestamp_anomaly: "Gaps in the recording's timing",
  playback_speed_suspicious: "Playback speed looks wrong",
  impossible_movement: "Movement too fast to be real",
  duplicate_submission: "Same video submitted before",
  integrity_check_failed: "An integrity check could not run",
  processing_error: "Verification hit an error",
  // Reasons a reviewer can give when flagging (Sprint 14).
  identity_mismatch: "Identity does not match",
  invalid_video: "Video not valid for assessment",
  technical_issue: "Technical problem",
  form_issue: "Form not acceptable",
  other: "Other concern",
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
    case "flagged":
      return "Flagged for further review";
    default:
      return action;
  }
}

/** Where a submission stands for a reviewer, as the server reports it. */
export const REVIEW_STATUS_LABELS: Record<ReviewStatus, string> = {
  awaiting_upload: "Awaiting upload",
  awaiting_verification: "Awaiting verification",
  needs_review: "Needs review",
  awaiting_approval: "Awaiting approval",
  invalid: "Could not be verified",
  approved: "Approved",
  rejected: "Rejected",
  resubmission_requested: "Resubmission requested",
};

/** What the automated check concluded — kept, whatever a reviewer decides. */
export const VERDICT_LABELS: Record<VerificationVerdict, string> = {
  verified: "Server agreed with the phone",
  flagged: "Server flagged it for a human",
  rejected: "Server could not verify it",
};

/** Reasons a reviewer chooses from, in the order offered. */
export const REVIEW_REASONS: { value: ReviewReason; label: string }[] = [
  { value: "identity_mismatch", label: "Identity mismatch" },
  { value: "multiple_people", label: "More than one person" },
  { value: "invalid_video", label: "Invalid or unusable video" },
  { value: "score_discrepancy", label: "Score does not match the video" },
  { value: "form_issue", label: "Form not acceptable" },
  { value: "duplicate_submission", label: "Duplicate submission" },
  { value: "technical_issue", label: "Technical issue" },
  { value: "other", label: "Other" },
];

export function reviewReasonLabel(reason: string | null | undefined): string {
  return REVIEW_REASONS.find((item) => item.value === reason)?.label ?? (reason ? reason.replace(/_/g, " ") : "—");
}

/** Evidence keys that are the limit a measurement was judged against. */
export const LIMIT_KEYS = new Set([
  "threshold",
  "threshold_fraction",
  "limit",
  "minimum",
  "maximum",
  "tolerance",
  "min_rep_duration_ms",
]);

/** A readable name for a key in a flag's evidence. */
export function evidenceLabel(key: string): string {
  const named: Record<string, string> = {
    mobile_value: "Phone",
    server_value: "Server",
    difference: "Difference",
    tolerance: "Allowed difference",
    threshold: "Limit",
    threshold_fraction: "Limit (fraction)",
    limit: "Limit",
    minimum: "Minimum allowed",
    maximum: "Maximum allowed",
    at_ms: "At",
    server_confidence: "Server tracking confidence",
  };
  return named[key] ?? key.replace(/_/g, " ").replace(/^./, (first) => first.toUpperCase());
}

/** A flag's evidence value, compactly; nested ranges read as "a–b". */
export function evidenceValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (key === "at_ms" && typeof value === "number") return `${(value / 1000).toFixed(1)} s`;
  if (typeof value === "number") return Number.isInteger(value) ? String(value) : value.toFixed(3).replace(/0+$/, "");
  if (typeof value === "boolean") return value ? "yes" : "no";
  if (Array.isArray(value)) {
    return value.length === 2 && value.every((part) => typeof part === "number")
      ? `${value[0]}–${value[1]}`
      : value.map((part) => evidenceValue("", part)).join(", ");
  }
  if (typeof value === "object") {
    return Object.entries(value as Record<string, unknown>)
      .map(([name, part]) => `${name.replace(/_/g, " ")} ${evidenceValue(name, part)}`)
      .join("; ");
  }
  return String(value);
}

/** Where in the recording a flag points, from its evidence or its text. */
export function flagLocationMs(flag: { detail: string | null; evidence?: Record<string, unknown> | null }): number | null {
  const at = flag.evidence?.at_ms;
  if (typeof at === "number") return at;
  return flagTimestampMs(flag.detail);
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
