import type { ResultStatus, ReviewStatus, SessionStatus, Severity, VerificationVerdict } from "../api/types";
import { REVIEW_STATUS_LABELS, SESSION_STATUS_LABELS, STATUS_LABELS, VERDICT_LABELS } from "../lib/format";

/*
 * Every state borrows a material from the trials palette, the same way the
 * athlete app writes a score:
 *   pencil (dashed)  waiting on the machine
 *   pen (ink)        checked, waiting on a person
 *   red pen          an integrity problem or a refusal
 *   marigold         open now, or the athlete must act
 *   stamp (violet)   approved by an official — never used for anything else
 * The words always carry the meaning too, so colour is never the only signal.
 */
const TAG = "inline-flex items-center rounded-xs px-2 py-0.5 text-xs font-semibold";

const PENCIL = "border border-dashed border-pencil text-pencil";
const PEN = "border border-ink text-ink";
const RED_PEN = "border border-redpen bg-redpen-tint text-redpen";
const REFUSED = "border border-redpen bg-paper text-redpen";
const MARIGOLD = "border border-marigold bg-marigold-tint text-ink";
const STAMP = "border-2 border-stamp text-stamp uppercase tracking-wide";

const SEVERITY_STYLES: Record<Severity, string> = {
  high: "bg-redpen text-paper",
  medium: "border border-redpen text-redpen",
  low: "border border-line text-pencil",
};

export function SeverityBadge({ severity }: { severity: Severity | null }) {
  if (!severity) return <span className="text-xs text-pencil">No open flags</span>;
  return <span className={`${TAG} label-caps ${SEVERITY_STYLES[severity]}`}>{severity}</span>;
}

const STATUS_STYLES: Record<ResultStatus, string> = {
  flagged: RED_PEN,
  uploaded: PENCIL,
  processing: PENCIL,
  verified: PEN,
  approved: STAMP,
  rejected: REFUSED,
  pending_sync: MARIGOLD,
};

export function StatusBadge({ status }: { status: ResultStatus }) {
  return <span className={`${TAG} ${STATUS_STYLES[status]}`}>{STATUS_LABELS[status]}</span>;
}

const SESSION_STATUS_STYLES: Record<SessionStatus, string> = {
  active: "bg-marigold text-ink",
  scheduled: PEN,
  ended: "bg-paper-shade text-pencil",
  disabled: PENCIL,
};

/** A session's state exactly as the server reported it. */
export function SessionStatusBadge({ status }: { status: SessionStatus }) {
  return <span className={`${TAG} ${SESSION_STATUS_STYLES[status]}`}>{SESSION_STATUS_LABELS[status]}</span>;
}

const REVIEW_STATUS_STYLES: Record<ReviewStatus, string> = {
  awaiting_upload: PENCIL,
  awaiting_verification: PENCIL,
  needs_review: RED_PEN,
  awaiting_approval: PEN,
  invalid: MARIGOLD,
  approved: STAMP,
  rejected: REFUSED,
  resubmission_requested: MARIGOLD,
};

/** The review state the server computed: what, if anything, a human must do. */
export function ReviewStatusBadge({ status }: { status: ReviewStatus }) {
  return <span className={`${TAG} ${REVIEW_STATUS_STYLES[status]}`}>{REVIEW_STATUS_LABELS[status]}</span>;
}

const VERDICT_STYLES: Record<VerificationVerdict, string> = {
  verified: PEN,
  flagged: RED_PEN,
  rejected: MARIGOLD,
};

/** The automated verdict, which stays what it was after a human decides. */
export function VerdictBadge({ verdict }: { verdict: VerificationVerdict | null }) {
  if (!verdict) return <span className="text-xs text-pencil">No machine verdict yet</span>;
  return <span className={`${TAG} ${VERDICT_STYLES[verdict]}`}>{VERDICT_LABELS[verdict]}</span>;
}
