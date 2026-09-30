import { Link } from "react-router-dom";
import type { ReviewDetail } from "../../api/types";
import { STATUS_LABELS, formatDateTime } from "../../lib/format";
import { paths } from "../../routes";
import { Fact } from "../Panel";

/** Who, which session and test, when, and the lifecycle status. */
export function SubmissionFacts({ result, nameOf }: { result: ReviewDetail; nameOf: (code: string) => string }) {
  return (
    <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
      <Fact label="Submission ID">
        <span className="break-all font-mono text-xs">{result.result_id}</span>
      </Fact>
      <Fact label="Athlete">
        {result.athlete_name}
        <div className="text-xs text-slate-500">
          {[
            result.region,
            result.athlete_age_years !== null ? `${result.athlete_age_years} years` : null,
            result.athlete_gender,
            result.athlete_height_cm !== null ? `${result.athlete_height_cm} cm` : null,
          ]
            .filter(Boolean)
            .join(" · ")}
        </div>
        {result.athlete_id && (
          <div className="break-all font-mono text-[11px] text-slate-400">ID {result.athlete_id}</div>
        )}
      </Fact>
      <Fact label="Session">
        {result.session_id ? (
          <Link to={paths.session(result.session_id)} className="text-blue-700 hover:underline">
            {result.session_name}
          </Link>
        ) : (
          "Not part of a session"
        )}
      </Fact>
      <Fact label="Test">
        {nameOf(result.test_type)} · attempt {result.attempt_number}
      </Fact>
      <Fact label="Submitted">{formatDateTime(result.created_at)}</Fact>
      <Fact label="Server check finished">{result.verified_at ? formatDateTime(result.verified_at) : "Not yet"}</Fact>
      <Fact label="Lifecycle status">{STATUS_LABELS[result.status]}</Fact>
      {result.video_duration_seconds !== null && (
        <Fact label="Recording length">{result.video_duration_seconds.toFixed(1)} s</Fact>
      )}
    </dl>
  );
}
