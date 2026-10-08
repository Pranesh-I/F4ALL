import type { ReviewHistoryEntry } from "../../api/types";
import { STATUS_LABELS, actionLabel, formatDateTime, reviewReasonLabel } from "../../lib/format";

/** The audit trail: who did what, why, and the status either side, oldest first. */
export function ReviewHistory({ history }: { history: ReviewHistoryEntry[] }) {
  if (history.length === 0) {
    return <p className="text-sm text-pencil">No official has acted on this submission yet.</p>;
  }
  return (
    <ol className="space-y-3 text-sm" aria-label="Review history">
      {history.map((entry, index) => (
        <li key={`${entry.created_at}-${index}`} className="border-l-2 border-ink pl-3">
          <div>
            <strong>{actionLabel(entry.action)}</strong> by {entry.official_name}
            <span className="text-pencil"> · {formatDateTime(entry.created_at)}</span>
          </div>
          <div className="text-xs text-pencil">
            {entry.reason && <>Reason: {reviewReasonLabel(entry.reason)}</>}
            {entry.reason && entry.previous_status && " · "}
            {entry.previous_status && entry.new_status && (
              <>
                {STATUS_LABELS[entry.previous_status]} → {STATUS_LABELS[entry.new_status]}
              </>
            )}
          </div>
          {entry.notes && <p className="mt-0.5 text-ink">“{entry.notes}”</p>}
        </li>
      ))}
    </ol>
  );
}
