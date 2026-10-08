import type { ReviewDetail, VerificationStatus } from "../../api/types";
import { formatScore, scoreDifference } from "../../lib/format";
import { Stamp } from "../Trials";

interface Row {
  label: string;
  phone: string;
  server: string;
}

/** Numbers from a stored snapshot, only when they are numbers. */
function num(record: Record<string, unknown> | null | undefined, key: string): number | null {
  const value = record?.[key];
  return typeof value === "number" ? value : null;
}

/**
 * The phone's result beside the server's, row by row, for whichever exercise
 * this is: reps for rep tests, a measurement for the jump. Every value comes
 * from the stored snapshots; a value the server did not record shows "—".
 */
export function ResultComparison({
  result,
  verification,
}: {
  result: ReviewDetail;
  verification: VerificationStatus | undefined;
}) {
  const comparison = verification?.comparison ?? null;
  const server = verification?.server_result ?? null;
  const unit = result.unit;
  const difference = comparison?.difference ?? scoreDifference(result.provisional_score, result.server_score);
  const tolerance = comparison?.tolerance ?? null;
  const exceeded = difference !== null && tolerance !== null && Math.abs(difference) > tolerance;

  const rows: Row[] = [
    {
      label: "Score",
      phone: formatScore(result.provisional_score, unit),
      server: formatScore(result.server_score, unit),
    },
  ];
  if (comparison && (comparison.mobile_rep_count !== null || comparison.server_rep_count !== null)) {
    rows.push({
      label: "Reps counted",
      phone: formatScore(comparison.mobile_rep_count, ""),
      server: formatScore(comparison.server_rep_count, ""),
    });
  }
  if (comparison && (comparison.mobile_measurement !== null || comparison.server_measurement !== null)) {
    rows.push({
      label: "Measurement",
      phone: formatScore(comparison.mobile_measurement, unit),
      server: formatScore(comparison.server_measurement, unit),
    });
  }
  if (comparison && (comparison.mobile_form_score !== null || comparison.server_form_score !== null)) {
    rows.push({
      label: "Form score",
      phone: formatScore(comparison.mobile_form_score, ""),
      server: formatScore(comparison.server_form_score, ""),
    });
  }

  const confidence = num(server, "confidence");
  const attempts = (server?.rep_attempts ?? null) as Record<string, number> | null;
  const framesAnalyzed = num(server, "frames_analyzed");
  const framesRejected = num(server, "frames_rejected");
  const invalidReason = typeof server?.invalid_reason === "string" ? server.invalid_reason : null;

  return (
    <div className="space-y-3 text-sm">
      <table className="w-full">
        <caption className="sr-only">Phone and server results</caption>
        <thead className="border-b-2 border-ink text-left text-xs [&_th]:label-caps">
          <tr>
            <th className="py-1 font-normal" />
            <th scope="col" className="py-1 text-right font-normal">
              Phone (provisional)
            </th>
            <th scope="col" className="py-1 text-right font-normal">
              Server
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.label} className="border-t border-rule">
              <th scope="row" className="py-1.5 text-left font-normal">
                {row.label}
              </th>
              {/* The phone's number in pencil; the server's, which counts, in ink. */}
              <td className="numerals py-1.5 text-right text-lg text-pencil">{row.phone}</td>
              <td className="numerals py-1.5 text-right text-lg">{row.server}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {difference === null ? (
        <p className="text-pencil">No comparison — the server has not scored this recording.</p>
      ) : (
        <p className={exceeded ? "font-medium text-redpen" : "text-ink"}>
          Server minus phone: {difference > 0 ? "+" : ""}
          {formatScore(difference, unit)}
          {tolerance !== null && (
            <>
              {" "}
              · allowed difference {formatScore(tolerance, unit)} ·{" "}
              {exceeded ? "outside the allowed difference" : "within the allowed difference"}
            </>
          )}
        </p>
      )}

      {(confidence !== null || framesAnalyzed !== null || attempts || invalidReason) && (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-pencil">
          {confidence !== null && (
            <>
              <dt>Server tracking confidence</dt>
              <dd className="tabular-nums">{confidence.toFixed(2)}</dd>
            </>
          )}
          {framesAnalyzed !== null && (
            <>
              <dt>Frames analysed / rejected</dt>
              <dd className="tabular-nums">
                {framesAnalyzed} / {framesRejected ?? "—"}
              </dd>
            </>
          )}
          {attempts && (
            <>
              <dt>Reps counted by the server</dt>
              <dd className="tabular-nums">{attempts.counted ?? "—"}</dd>
              <dt>Reps not counted</dt>
              <dd>
                {[
                  ["partial", attempts.rejected_partial],
                  ["too fast", attempts.rejected_too_fast],
                  ["form", attempts.rejected_form],
                  ["wrong arm", attempts.rejected_wrong_arm],
                ]
                  .filter(([, count]) => typeof count === "number" && count > 0)
                  .map(([label, count]) => `${count} ${label}`)
                  .join(", ") || "none"}
              </dd>
            </>
          )}
          {invalidReason && (
            <>
              <dt>Why the server could not score</dt>
              <dd>{invalidReason.replace(/_/g, " ")}</dd>
            </>
          )}
        </dl>
      )}

      <p className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span>
          <span className="text-pencil">Official score: </span>
          <span className={result.final_score !== null ? "numerals text-2xl" : "font-medium"}>
            {result.final_score !== null ? formatScore(result.final_score, unit) : "Not decided"}
          </span>
        </span>
        {result.final_score !== null && <Stamp caption="SAI" decorative />}
      </p>
    </div>
  );
}
