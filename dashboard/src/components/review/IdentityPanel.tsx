import type { ReviewDetail } from "../../api/types";
import { formatScore, identityCheckLabel } from "../../lib/format";
import { Panel } from "../Panel";

/** The registration photo beside what the face checks concluded. */
export function IdentityPanel({ result }: { result: ReviewDetail }) {
  const face = result.face_verification;
  return (
    <Panel title="Identity">
      <div className="flex gap-4">
        {result.reference_photo_url ? (
          <img src={result.reference_photo_url} alt="Registration photo" className="h-32 w-24 rounded object-cover" />
        ) : (
          <div className="flex h-32 w-24 items-center justify-center rounded bg-slate-100 p-2 text-center text-xs text-slate-500">
            No registration photo
          </div>
        )}
        <div className="space-y-1 text-sm">
          {face ? (
            <>
              <p>
                Automatic check:{" "}
                <strong>
                  {face.status === "pass" ? "matched" : face.status === "manual_review" ? "unclear" : "failed"}
                </strong>
                {face.similarity_score !== null && ` (similarity ${face.similarity_score.toFixed(2)})`}
              </p>
              <p className="text-slate-600">
                This check is approximate and unreliable in poor light. Compare the photo with the recording
                yourself.
              </p>
            </>
          ) : (
            <p className="text-slate-600">The identity check did not run for this submission — this is not a pass.</p>
          )}
          <p>
            Photo check before the test: <strong>{identityCheckLabel(result.identity_check)}</strong>
          </p>
        </div>
      </div>
    </Panel>
  );
}

/** Where the score sits in its age group, clearly marked when the norms are provisional. */
export function BenchmarkPanel({ result }: { result: ReviewDetail }) {
  const benchmark = result.benchmark;
  if (!benchmark) return null;
  return (
    <Panel title="Against age group">
      <p className="text-sm">
        <strong>{benchmark.label}</strong>
        {benchmark.percentile !== null && ` · around the ${benchmark.percentile}th percentile`}
      </p>
      <p className="mt-1 text-xs text-slate-600">
        {benchmark.cohort}: median {formatScore(benchmark.percentile_50, benchmark.unit)}, top 25%{" "}
        {formatScore(benchmark.percentile_75, benchmark.unit)}, top 10%{" "}
        {formatScore(benchmark.percentile_90, benchmark.unit)}
      </p>
      {benchmark.provisional && (
        <p className="mt-2 rounded bg-amber-50 p-2 text-xs text-amber-900">
          Provisional norms — not an official SAI standard. Do not use for selection.
        </p>
      )}
    </Panel>
  );
}
