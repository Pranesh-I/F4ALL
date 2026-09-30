import { Link } from "react-router-dom";
import type { SubmissionItem } from "../api/types";
import { formatDateTime, formatScore, shortId } from "../lib/format";
import { paths } from "../routes";
import { ReviewStatusBadge, SeverityBadge, StatusBadge, VerdictBadge } from "./Badges";

/**
 * Submissions as the server listed them. Used by the submission list, the
 * home page, a session, and (as `variant="review"`) the review queue, which
 * shows the machine verdict and review state instead of the raw status.
 */
export function SubmissionTable({
  items,
  nameOf,
  showSession = true,
  variant = "submissions",
  linkTo = paths.submission,
}: {
  items: SubmissionItem[];
  nameOf: (code: string) => string;
  showSession?: boolean;
  variant?: "submissions" | "review";
  linkTo?: (id: string) => string;
}) {
  const review = variant === "review";
  return (
    <div className="overflow-x-auto rounded border border-slate-200 bg-white">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-50 text-left text-slate-600">
          <tr>
            <th className="px-3 py-2">Submission</th>
            <th className="px-3 py-2">Athlete</th>
            <th className="px-3 py-2">Test</th>
            {showSession && <th className="px-3 py-2">Session</th>}
            {review ? (
              <>
                <th className="px-3 py-2">Verification</th>
                <th className="px-3 py-2">Integrity</th>
                <th className="px-3 py-2">Review</th>
              </>
            ) : (
              <>
                <th className="px-3 py-2">Status</th>
                <th className="px-3 py-2">Flags</th>
              </>
            )}
            <th className="px-3 py-2 text-right">Phone</th>
            <th className="px-3 py-2 text-right">Server</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.result_id} className="border-t border-slate-100 align-top hover:bg-slate-50">
              <td className="px-3 py-2">
                <Link
                  to={linkTo(item.result_id)}
                  className="font-mono text-xs text-blue-700 hover:underline"
                  aria-label={`Submission ${shortId(item.result_id)} by ${item.athlete_name}`}
                >
                  #{shortId(item.result_id)}
                </Link>
                <div className="whitespace-nowrap text-xs text-slate-500">{formatDateTime(item.created_at)}</div>
              </td>
              <td className="px-3 py-2">
                <div className="font-medium">{item.athlete_name}</div>
                <div className="text-xs text-slate-500">{item.region}</div>
              </td>
              <td className="px-3 py-2">
                {nameOf(item.test_type)}
                <span className="text-xs text-slate-500"> · attempt {item.attempt_number}</span>
              </td>
              {showSession && (
                <td className="px-3 py-2">
                  {item.session_id ? (
                    <Link to={paths.session(item.session_id)} className="text-blue-700 hover:underline">
                      {item.session_name}
                    </Link>
                  ) : (
                    <span className="text-slate-400">No session</span>
                  )}
                </td>
              )}
              {review ? (
                <>
                  <td className="px-3 py-2">
                    <VerdictBadge verdict={item.verification_verdict} />
                  </td>
                  <td className="whitespace-nowrap px-3 py-2">
                    <SeverityBadge severity={item.max_severity} />
                    {item.open_flag_count > 0 && (
                      <span className="ml-1 text-xs text-slate-500">({item.open_flag_count} open)</span>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <ReviewStatusBadge status={item.review_status} />
                  </td>
                </>
              ) : (
                <>
                  <td className="px-3 py-2">
                    <StatusBadge status={item.status} />
                  </td>
                  <td className="px-3 py-2">
                    <SeverityBadge severity={item.max_severity} />
                    {item.flag_count > 0 && <span className="ml-1 text-xs text-slate-500">({item.flag_count})</span>}
                  </td>
                </>
              )}
              <td className="px-3 py-2 text-right tabular-nums">{formatScore(item.provisional_score, item.unit)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{formatScore(item.server_score, item.unit)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
