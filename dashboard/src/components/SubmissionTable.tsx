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
    <div className="overflow-x-auto rounded-xs border border-ink bg-paper">
      <table className="min-w-full text-sm">
        <thead className="border-b-2 border-ink text-left text-xs [&_th]:label-caps [&_th]:font-semibold">
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
            <tr key={item.result_id} className="border-t border-rule align-top hover:bg-ground">
              <td className="px-3 py-2">
                <Link
                  to={linkTo(item.result_id)}
                  className="numerals text-base tracking-wide text-ink underline decoration-line underline-offset-4 hover:decoration-ink"
                  aria-label={`Submission ${shortId(item.result_id)} by ${item.athlete_name}`}
                >
                  #{shortId(item.result_id)}
                </Link>
                <div className="whitespace-nowrap text-xs text-pencil">{formatDateTime(item.created_at)}</div>
              </td>
              <td className="px-3 py-2">
                <div className="font-medium">{item.athlete_name}</div>
                <div className="text-xs text-pencil">{item.region}</div>
              </td>
              <td className="px-3 py-2">
                {nameOf(item.test_type)}
                <span className="text-xs text-pencil"> · attempt {item.attempt_number}</span>
              </td>
              {showSession && (
                <td className="px-3 py-2">
                  {item.session_id ? (
                    <Link to={paths.session(item.session_id)} className="font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink">
                      {item.session_name}
                    </Link>
                  ) : (
                    <span className="text-pencil">No session</span>
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
                      <span className="ml-1 text-xs text-pencil">({item.open_flag_count} open)</span>
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
                    {item.flag_count > 0 && <span className="ml-1 text-xs text-pencil">({item.flag_count})</span>}
                  </td>
                </>
              )}
              <td className="numerals px-3 py-2 text-right text-base text-pencil">{formatScore(item.provisional_score, item.unit)}</td>
              <td className="numerals px-3 py-2 text-right text-base">{formatScore(item.server_score, item.unit)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
