import type { ResultStatus, Severity } from "../api/types";
import { STATUS_LABELS } from "../lib/format";

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
