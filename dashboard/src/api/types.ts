// Mirrors backend/app/schemas.py. Field names are the wire contract.

export type OfficialRole = "sai_admin" | "regional_reviewer";

export type ResultStatus =
  | "pending_sync"
  | "uploaded"
  | "processing"
  | "verified"
  | "flagged"
  | "approved"
  | "rejected";

export type Severity = "low" | "medium" | "high";

export type ReviewActionName = "approved" | "rejected" | "requested_resubmission" | "flagged";

/** Why a reviewer rejected, sent back or flagged (backend ReviewReason). */
export type ReviewReason =
  | "identity_mismatch"
  | "multiple_people"
  | "invalid_video"
  | "score_discrepancy"
  | "technical_issue"
  | "form_issue"
  | "duplicate_submission"
  | "other";

/** Where a submission stands for a reviewer. Computed by the server, never here. */
export type ReviewStatus =
  | "awaiting_upload"
  | "awaiting_verification"
  | "needs_review"
  | "awaiting_approval"
  | "invalid"
  | "approved"
  | "rejected"
  | "resubmission_requested";

/** What the automated check concluded, kept after a reviewer decides. */
export type VerificationVerdict = "verified" | "flagged" | "rejected";

export interface ReviewHistoryEntry {
  action: ReviewActionName;
  notes: string | null;
  official_name: string;
  created_at: string;
  /** Sprint 14 audit fields; null on actions recorded before them. */
  official_id?: string | null;
  reason?: ReviewReason | null;
  previous_status?: ResultStatus | null;
  new_status?: ResultStatus | null;
}

export interface OfficialProfile {
  official_id: string;
  name: string;
  email: string;
  role: OfficialRole;
  region: string | null;
}

export interface OfficialTokens {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  official: OfficialProfile;
}

export interface ReviewItem {
  result_id: string;
  athlete_name: string;
  region: string;
  test_type: string;
  status: ResultStatus;
  provisional_score: number | null;
  server_score: number | null;
  flag_count: number;
  created_at: string;
  max_severity: Severity | null;
  attempt_number: number;
  unit: string;
}

export interface Flag {
  reason: string;
  detail: string | null;
  severity: Severity;
  source: "auto" | "manual";
  created_at: string;
  resolution: "confirmed" | "dismissed" | null;
  resolved_at: string | null;
  /** Sprint 12; present in official views only. */
  flag_id?: string | null;
  status?: "open" | "confirmed" | "dismissed" | null;
  /** The measurements behind the flag and the limit they crossed. */
  evidence?: Record<string, unknown> | null;
  reviewed_by?: string | null;
}

export interface Benchmark {
  band: string;
  label: string;
  percentile: number | null;
  percentile_50: number;
  percentile_75: number;
  percentile_90: number;
  next_target: number | null;
  cohort: string;
  unit: string;
  source: string;
  provisional: boolean;
}

export interface ReviewDetail {
  result_id: string;
  athlete_name: string;
  region: string;
  test_type: string;
  status: ResultStatus;
  provisional_score: number | null;
  server_score: number | null;
  final_score: number | null;
  unit: string;
  video_url: string | null;
  flags: Flag[];
  created_at: string;
  verified_at: string | null;
  attempt_number: number;
  athlete_id: string | null;
  athlete_age_years: number | null;
  athlete_gender: string | null;
  athlete_height_cm: number | null;
  video_duration_seconds: number | null;
  reference_photo_url: string | null;
  face_verification: {
    status: "pass" | "fail" | "manual_review";
    similarity_score: number | null;
    verified_at: string | null;
  } | null;
  /** The photo check the athlete took before recording; null when none was taken. */
  identity_check?: IdentityCheckOutcome | null;
  has_pose_sequence: boolean;
  benchmark: Benchmark | null;
  review_history: ReviewHistoryEntry[];
  allowed_actions: ReviewActionName[];
  /** The assessment session it was submitted to (Sprint 13); null outside one. */
  session_id?: string | null;
  session_name?: string | null;
  /** Sprint 14. A decision quotes `review_version` back as `expected_version`. */
  review_status: ReviewStatus;
  verification_verdict: VerificationVerdict | null;
  verification_reason: string | null;
  review_version: number;
}

/** GET /api/verification/{result_id}, official view (Sprint 11). */
export interface VerificationComparison {
  mobile_rep_count: number | null;
  server_rep_count: number | null;
  mobile_measurement: number | null;
  server_measurement: number | null;
  mobile_form_score: number | null;
  server_form_score: number | null;
  difference: number | null;
  tolerance: number | null;
}

export interface VerificationCheck {
  name: "validation" | "processing" | "server_scoring" | "comparison" | "integrity";
  outcome: "passed" | "failed" | "skipped";
  code: string | null;
  detail: string | null;
}

export interface VerificationStatus {
  result_id: string;
  test_type: string;
  unit: string;
  status: ResultStatus;
  provisional_score: number | null;
  server_score: number | null;
  final_score: number | null;
  submitted_at: string;
  verified_at: string | null;
  waiting_seconds: number | null;
  sla_seconds: number;
  overdue: boolean;
  processing_started_at: string | null;
  processing_completed_at: string | null;
  processing_duration_ms: number | null;
  verification_reason: string | null;
  comparison: VerificationComparison | null;
  pipeline_version: string | null;
  verification_attempts: number | null;
  checks: VerificationCheck[] | null;
  mobile_result: Record<string, unknown> | null;
  server_result: Record<string, unknown> | null;
  /** Every integrity check's measurements, flagged or not (Sprint 12). */
  integrity: Record<string, unknown> | null;
  verification_error: string | null;
  flags: Flag[] | null;
  identity_check: IdentityCheckOutcome | null;
  face_check: "pass" | "fail" | "manual_review" | null;
}

export interface PoseSequence {
  version: number;
  landmarks: number;
  /** t: milliseconds from the start of the video; p: flat [x, y, visibility] per landmark. */
  frames: { t: number; p: number[] }[];
}

export interface LeaderboardEntry {
  rank: number;
  athlete_id: string;
  athlete_name: string;
  region: string;
  gender: string;
  age_years: number;
  score: number;
  unit: string;
  achieved_at: string;
}

export interface Leaderboard {
  test_type: string;
  unit: string;
  higher_is_better: boolean;
  entries: LeaderboardEntry[];
}

export interface DashboardStats {
  by_status: Record<ResultStatus, number>;
  flagged_high_severity: number;
  breaching_sla: number;
}


/** GET /api/dashboard/submissions (Sprint 13): every submission, newest first. */
export interface SubmissionItem extends ReviewItem {
  athlete_id: string;
  final_score: number | null;
  verified_at: string | null;
  /** Why the server settled it without scoring, when it did. */
  verification_reason: string | null;
  session_id: string | null;
  session_name: string | null;
  verification_verdict: VerificationVerdict | null;
  review_status: ReviewStatus;
  open_flag_count: number;
}

export interface SubmissionPage {
  items: SubmissionItem[];
  total: number;
}

/** GET /api/dashboard/tests: a test the backend can assess. */
export interface TestInfo {
  code: string;
  name: string;
  unit: string;
  higher_is_better: boolean;
}

/** Computed by the server from `enabled` and the window against its clock. */
export type SessionStatus = "disabled" | "scheduled" | "active" | "ended";

export interface AssessmentSession {
  id: string;
  name: string;
  description: string | null;
  rules: string | null;
  starts_at: string;
  ends_at: string;
  enabled: boolean;
  allowed_tests: string[];
  region: string | null;
  status: SessionStatus;
  submission_count: number;
  created_at: string;
  updated_at: string;
}

export interface NewAssessmentSession {
  name: string;
  description?: string | null;
  rules?: string | null;
  starts_at: string;
  ends_at: string;
  enabled: boolean;
  allowed_tests: string[];
  region?: string | null;
}

/** PATCH body. `clear_region` because `region: null` reads as "not provided". */
export type AssessmentSessionChanges = Partial<NewAssessmentSession> & { clear_region?: boolean };

export type IdentityCheckOutcome = "match" | "no_match" | "no_face" | "unavailable";
