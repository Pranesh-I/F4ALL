import type { ResultStatus, ReviewStatus, SessionStatus, Severity, VerificationVerdict } from "../api/types";
import { REVIEW_STATUS_LABELS, SESSION_STATUS_LABELS, STATUS_LABELS, VERDICT_LABELS } from "../lib/format";

const SEVERITY_STYLES: Record<Severity, string> = {
  high: "bg-red-100 text-red-800 ring-red-200",
  medium: "bg-amber-100 text-amber-900 ring-amber-200",
  low: "bg-slate-100 text-slate-700 ring-slate-200",
};

export function SeverityBadge({ severity }: { severity: Severity | null }) {
  if (!severity) return <span className="text-xs text-slate-400">No open flags</span>;
  return (
    <span
      className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-semibold uppercase ring-1 ${SEVERITY_STYLES[severity]}`}
    >
      {severity}
    </span>
  );
}

const STATUS_STYLES: Record<ResultStatus, string> = {
  flagged: "bg-red-50 text-red-700",
  uploaded: "bg-slate-100 text-slate-600",
  processing: "bg-slate-100 text-slate-600",
  verified: "bg-blue-50 text-blue-700",
  approved: "bg-emerald-50 text-emerald-700",
  rejected: "bg-slate-200 text-slate-700",
  pending_sync: "bg-amber-50 text-amber-800",
};

export function StatusBadge({ status }: { status: ResultStatus }) {
  return (
    <span className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[status]}`}>
      {STATUS_LABELS[status]}
    </span>
  );
}

const SESSION_STATUS_STYLES: Record<SessionStatus, string> = {
  active: "bg-green-100 text-green-800",
  scheduled: "bg-blue-100 text-blue-800",
  ended: "bg-slate-200 text-slate-700",
  disabled: "bg-amber-100 text-amber-800",
};

/** A session's state exactly as the server reported it. */
export function SessionStatusBadge({ status }: { status: SessionStatus }) {
  return (
    <span className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${SESSION_STATUS_STYLES[status]}`}>
      {SESSION_STATUS_LABELS[status]}
    </span>
  );
}

const REVIEW_STATUS_STYLES: Record<ReviewStatus, string> = {
  awaiting_upload: "bg-slate-100 text-slate-600",
  awaiting_verification: "bg-slate-100 text-slate-600",
  needs_review: "bg-red-50 text-red-700",
  awaiting_approval: "bg-blue-50 text-blue-700",
  invalid: "bg-amber-50 text-amber-800",
  approved: "bg-emerald-50 text-emerald-700",
  rejected: "bg-slate-200 text-slate-700",
  resubmission_requested: "bg-amber-50 text-amber-800",
};

/** The review state the server computed: what, if anything, a human must do. */
export function ReviewStatusBadge({ status }: { status: ReviewStatus }) {
  return (
    <span className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${REVIEW_STATUS_STYLES[status]}`}>
      {REVIEW_STATUS_LABELS[status]}
    </span>
  );
}

const VERDICT_STYLES: Record<VerificationVerdict, string> = {
  verified: "border-blue-200 text-blue-700",
  flagged: "border-red-200 text-red-700",
  rejected: "border-amber-300 text-amber-800",
};

/** The automated verdict, which stays what it was after a human decides. */
export function VerdictBadge({ verdict }: { verdict: VerificationVerdict | null }) {
  if (!verdict) return <span className="text-xs text-slate-400">No machine verdict yet</span>;
  return (
    <span className={`inline-flex rounded border px-2 py-0.5 text-xs font-medium ${VERDICT_STYLES[verdict]}`}>
      {VERDICT_LABELS[verdict]}
    </span>
  );
}
