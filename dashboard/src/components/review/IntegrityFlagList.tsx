import type { Flag } from "../../api/types";
import { LIMIT_KEYS, evidenceLabel, evidenceValue, flagLocationMs, flagReason } from "../../lib/format";
import { SeverityBadge } from "../Badges";

/** Evidence keys that only restate the flag or are shown elsewhere. */
const HIDDEN_KEYS = new Set(["signal", "raised_by", "review_reason", "unit"]);

/**
 * Every integrity flag at once, each with its reason, severity, state, the
 * measurements behind it and the limit they crossed, and — where the check
 * knows it — a button to jump the video to the moment in question.
 */
export function IntegrityFlagList({ flags, onSeek }: { flags: Flag[]; onSeek?: (ms: number) => void }) {
  if (flags.length === 0) {
    return <p className="text-sm text-slate-600">No integrity concerns were raised.</p>;
  }
  const open = flags.filter((flag) => !flag.resolved_at).length;

  return (
    <div className="space-y-3 text-sm">
      <p className="text-slate-600">
        {flags.length} flag(s), {open} still open.
      </p>
      <ul className="space-y-3" aria-label="Integrity flags">
        {flags.map((flag, index) => {
          const at = flagLocationMs(flag);
          const evidence = Object.entries(flag.evidence ?? {}).filter(
            // A check that has no moment to point at records at_ms as null; say nothing then.
            ([key, value]) => !HIDDEN_KEYS.has(key) && !(key === "at_ms" && value == null),
          );
          const state = flag.status ?? flag.resolution ?? "open";
          return (
            <li key={flag.flag_id ?? `${flag.reason}-${index}`} className="rounded border border-slate-100 p-3">
              <div className="flex flex-wrap items-center gap-2">
                <SeverityBadge severity={flag.severity} />
                <span className="font-medium">{flagReason(flag.reason)}</span>
                <span className="text-xs text-slate-500">
                  {flag.source === "manual" ? "raised by a reviewer" : "automatic"} · {state}
                </span>
              </div>
              {flag.detail && <p className="mt-1 text-slate-700">{flag.detail}</p>}
              {evidence.length > 0 && (
                <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs">
                  {evidence.map(([key, value]) => (
                    <div key={key} className="contents">
                      <dt className={LIMIT_KEYS.has(key) ? "font-medium text-slate-700" : "text-slate-500"}>
                        {evidenceLabel(key)}
                      </dt>
                      <dd className={`tabular-nums ${LIMIT_KEYS.has(key) ? "font-medium" : ""}`}>
                        {evidenceValue(key, value)}
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
              {at !== null && onSeek && (
                <button
                  type="button"
                  onClick={() => onSeek(at)}
                  className="mt-2 text-xs font-medium text-blue-700 hover:underline"
                >
                  Jump to {(at / 1000).toFixed(1)}s
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
