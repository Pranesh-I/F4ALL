import type {
  AssessmentSession,
  DashboardStats,
  OfficialProfile,
  OfficialRole,
  ReviewDetail,
  SubmissionItem,
  TestInfo,
  VerificationStatus,
} from "../api/types";

/** What GET /api/dashboard/tests returns: the six tests the server can score. */
export const CATALOG: TestInfo[] = [
  { code: "SQUATS", name: "Squats", unit: "reps", higher_is_better: true },
  { code: "PUSH_UPS", name: "Push-ups", unit: "reps", higher_is_better: true },
  { code: "BICEP_CURLS", name: "Bicep Curls", unit: "reps", higher_is_better: true },
  { code: "LUNGES", name: "Lunges", unit: "reps", higher_is_better: true },
  { code: "VERTICAL_JUMP", name: "Vertical Jump", unit: "cm", higher_is_better: true },
  { code: "SIT_UPS", name: "Sit-ups", unit: "reps", higher_is_better: true },
];

export function official(role: OfficialRole): OfficialProfile {
  return {
    official_id: "o1",
    name: role === "sai_admin" ? "Asha Admin" : "Ravi Reviewer",
    email: "o@sai.example",
    role,
    region: role === "sai_admin" ? null : "Kerala",
  };
}

export function session(overrides: Partial<AssessmentSession> = {}): AssessmentSession {
  return {
    id: "s1",
    name: "District trials",
    description: null,
    rules: null,
    starts_at: "2026-10-01T09:00:00Z",
    ends_at: "2026-10-03T09:00:00Z",
    enabled: true,
    allowed_tests: ["SQUATS", "SIT_UPS"],
    region: null,
    status: "active",
    submission_count: 0,
    created_at: "2026-09-29T09:00:00Z",
    updated_at: "2026-09-29T09:00:00Z",
    ...overrides,
  };
}

export function submission(overrides: Partial<SubmissionItem> = {}): SubmissionItem {
  return {
    result_id: "0f3c2a1b-0000-4000-8000-000000000001",
    athlete_id: "a1",
    athlete_name: "Meera",
    region: "Kerala",
    test_type: "SIT_UPS",
    status: "flagged",
    provisional_score: 30,
    server_score: 22,
    final_score: null,
    flag_count: 1,
    created_at: "2026-10-01T10:00:00Z",
    verified_at: "2026-10-01T10:03:00Z",
    verification_reason: null,
    max_severity: "high",
    attempt_number: 1,
    unit: "reps",
    session_id: "s1",
    session_name: "District trials",
    verification_verdict: "flagged",
    review_status: "needs_review",
    open_flag_count: 1,
    ...overrides,
  };
}

export function reviewDetail(overrides: Partial<ReviewDetail> = {}): ReviewDetail {
  return {
    result_id: "0f3c2a1b-0000-4000-8000-000000000001",
    athlete_name: "Meera",
    region: "Kerala",
    test_type: "SIT_UPS",
    status: "flagged",
    provisional_score: 30,
    server_score: 22,
    final_score: null,
    unit: "reps",
    video_url: null,
    flags: [
      {
        reason: "score_discrepancy",
        detail: "Phone 30, server 22",
        severity: "medium",
        source: "auto",
        created_at: "2026-10-01T10:03:00Z",
        resolution: null,
        resolved_at: null,
        flag_id: "f1",
        status: "open",
        evidence: {
          signal: "mobile_server_comparison",
          mobile_value: 30,
          server_value: 22,
          difference: -8,
          tolerance: 2,
          unit: "reps",
        },
      },
    ],
    created_at: "2026-10-01T10:00:00Z",
    verified_at: "2026-10-01T10:03:00Z",
    attempt_number: 1,
    athlete_id: "a1",
    athlete_age_years: 15,
    athlete_gender: "female",
    athlete_height_cm: 160,
    video_duration_seconds: 60,
    reference_photo_url: null,
    face_verification: null,
    identity_check: "match",
    has_pose_sequence: false,
    benchmark: null,
    review_history: [],
    allowed_actions: ["approved", "rejected", "requested_resubmission", "flagged"],
    session_id: "s1",
    session_name: "District trials",
    review_status: "needs_review",
    verification_verdict: "flagged",
    verification_reason: "score_discrepancy",
    review_version: 0,
    ...overrides,
  };
}

export function verificationStatus(overrides: Partial<VerificationStatus> = {}): VerificationStatus {
  return {
    result_id: "0f3c2a1b-0000-4000-8000-000000000001",
    test_type: "SIT_UPS",
    unit: "reps",
    status: "flagged",
    provisional_score: 30,
    server_score: 22,
    final_score: null,
    submitted_at: "2026-10-01T10:00:00Z",
    verified_at: "2026-10-01T10:03:00Z",
    waiting_seconds: null,
    sla_seconds: 300,
    overdue: false,
    processing_started_at: "2026-10-01T10:01:00Z",
    processing_completed_at: "2026-10-01T10:03:00Z",
    processing_duration_ms: 4200,
    verification_reason: null,
    comparison: {
      mobile_rep_count: 30,
      server_rep_count: 22,
      mobile_measurement: null,
      server_measurement: null,
      mobile_form_score: null,
      server_form_score: null,
      difference: -8,
      tolerance: 2,
    },
    pipeline_version: "s12.1",
    verification_attempts: 1,
    checks: [
      { name: "validation", outcome: "passed", code: null, detail: null },
      { name: "comparison", outcome: "failed", code: "score_discrepancy", detail: "8 reps apart" },
    ],
    mobile_result: null,
    server_result: null,
    integrity: null,
    verification_error: null,
    flags: null,
    identity_check: "match",
    face_check: null,
    ...overrides,
  };
}

export function stats(overrides: Partial<DashboardStats["by_status"]> = {}): DashboardStats {
  return {
    by_status: {
      pending_sync: 1,
      uploaded: 2,
      processing: 3,
      verified: 4,
      flagged: 5,
      approved: 6,
      rejected: 7,
      ...overrides,
    },
    flagged_high_severity: 2,
    breaching_sla: 0,
  };
}
