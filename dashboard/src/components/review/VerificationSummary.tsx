import type { VerificationStatus, VerificationVerdict } from "../../api/types";
import { flagReason } from "../../lib/format";
import { VerdictBadge } from "../Badges";

/**
 * What the automated pipeline did and concluded. This is the machine's
 * record: it stays as it was after an official decides.
 */
export function VerificationSummary({
  verification,
  verdict,
}: {
  verification: VerificationStatus;
  verdict: VerificationVerdict | null;
}) {
  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-slate-500">Automated verdict:</span>
        <VerdictBadge verdict={verdict} />
      </div>
      <dl className="grid gap-x-6 gap-y-2 sm:grid-cols-2">
        {verification.verification_reason && (
          <div>
            <dt className="text-slate-500">Deciding check</dt>
            <dd>{flagReason(verification.verification_reason)}</dd>
          </div>
        )}
        {verification.waiting_seconds !== null && (
          <div>
            <dt className="text-slate-500">Waiting</dt>
            <dd>
              {Math.round(verification.waiting_seconds / 60)} min
              {verification.overdue && <span className="ml-1 font-medium text-red-700">(over the target time)</span>}
            </dd>
          </div>
        )}
        {verification.processing_duration_ms !== null && (
          <div>
            <dt className="text-slate-500">Processing took</dt>
            <dd>{(verification.processing_duration_ms / 1000).toFixed(1)} s</dd>
          </div>
        )}
        {verification.verification_attempts !== null && (
          <div>
            <dt className="text-slate-500">Attempts</dt>
            <dd>{verification.verification_attempts}</dd>
          </div>
        )}
        {verification.pipeline_version && (
          <div>
            <dt className="text-slate-500">Pipeline version</dt>
            <dd className="font-mono text-xs">{verification.pipeline_version}</dd>
          </div>
        )}
      </dl>
      {verification.checks && verification.checks.length > 0 && (
        <ul className="space-y-1" aria-label="Verification checks">
          {verification.checks.map((check) => (
            <li key={check.name} className="flex flex-wrap gap-2">
              <span
                className={`rounded px-1.5 text-xs font-medium ${
                  check.outcome === "passed"
                    ? "bg-emerald-50 text-emerald-700"
                    : check.outcome === "failed"
                      ? "bg-red-50 text-red-700"
                      : "bg-slate-100 text-slate-600"
                }`}
              >
                {check.outcome}
              </span>
              <span className="capitalize">{check.name.replace(/_/g, " ")}</span>
              {check.code && (
                <span className="text-slate-500">
                  — {[...new Set(check.code.split(","))].map((code) => flagReason(code.trim())).join(", ")}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {verification.verification_error && (
        <p className="rounded bg-amber-50 p-2 text-xs text-amber-900">{verification.verification_error}</p>
      )}
    </div>
  );
}
