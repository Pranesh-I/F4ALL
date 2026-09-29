// Mirrors backend/app/schemas.py. Field names are the wire contract.

export type OfficialRole = "sai_admin" | "regional_reviewer";

export type ResultStatus =
  | "pending_sync"
  | "processing"
  | "verified"
  | "flagged"
  | "approved"
  | "rejected";

export type Severity = "low" | "medium" | "high";

export type ReviewActionName = "approved" | "rejected" | "requested_resubmission";

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
  review_history: {
    action: ReviewActionName;
    notes: string | null;
    official_name: string;
    created_at: string;
  }[];
  allowed_actions: ReviewActionName[];
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

export interface ReviewQueuePage {
  items: ReviewItem[];
  total: number;
}

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

export type IdentityCheckOutcome = "match" | "no_match" | "no_face" | "unavailable";
