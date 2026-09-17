import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import type { ReviewActionName, ReviewDetail } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { SeverityBadge, StatusBadge } from "../components/Badges";
import { VideoWithSkeleton } from "../components/VideoWithSkeleton";
import {
  actionLabel,
  flagReason,
  flagTimestampMs,
  formatDateTime,
  formatScore,
  scoreDifference,
  testName,
} from "../lib/format";

export function ReviewPage() {
  const { resultId = "" } = useParams();
  const { api } = useAuth();
  const [seekToMs, setSeekToMs] = useState<number | null>(null);

  const detail = useQuery({
    queryKey: ["review", resultId],
    queryFn: () => api.review(resultId),
    enabled: Boolean(resultId),
    // Signed media URLs expire; do not hold a stale detail for long.
    staleTime: 60_000,
  });

  const pose = useQuery({
    queryKey: ["pose", resultId],
    queryFn: () => api.poseSequence(resultId),
    enabled: Boolean(detail.data?.has_pose_sequence),
    staleTime: Infinity,
  });

  if (detail.isLoading) return <p className="text-slate-500">Loading…</p>;

  if (detail.isError || !detail.data) {
    const notFound = detail.error instanceof ApiError && detail.error.status === 404;
    return (
      <div className="space-y-3">
        <p role="alert" className="text-red-700">
          {notFound ? "This result does not exist or is outside your region." : "Could not load this result."}
        </p>
        <Link to="/reviews" className="text-blue-700 hover:underline">
          Back to the queue
        </Link>
      </div>
    );
  }

  const result = detail.data;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <Link to="/reviews" className="text-sm text-blue-700 hover:underline">
            ← Review queue
          </Link>
          <h1 className="mt-1 text-2xl font-semibold">
            {result.athlete_name} — {testName(result.test_type)}
          </h1>
          <p className="text-sm text-slate-600">
            {[
              result.region,
              result.athlete_age_years !== null ? `${result.athlete_age_years} years` : null,
              result.athlete_gender,
              result.athlete_height_cm !== null ? `${result.athlete_height_cm} cm` : null,
              `attempt ${result.attempt_number}`,
              `submitted ${formatDateTime(result.created_at)}`,
            ]
              .filter(Boolean)
              .join(" · ")}
          </p>
        </div>
        <StatusBadge status={result.status} />
      </div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <section className="space-y-3">
          {result.video_url ? (
            <VideoWithSkeleton src={result.video_url} pose={pose.data ?? null} seekToMs={seekToMs} />
          ) : (
            <div className="rounded bg-slate-100 p-6 text-sm text-slate-600">
              No recording is attached to this submission.
            </div>
          )}
          {pose.isError && (
            <p className="text-sm text-amber-800">The server's tracking data could not be loaded.</p>
          )}
        </section>

        <section className="space-y-5">
          <ScoreComparison result={result} />
          <Flags result={result} onSeek={setSeekToMs} />
          <Identity result={result} />
          {result.benchmark && <BenchmarkPanel result={result} />}
        </section>
      </div>

      <History result={result} />

      {result.allowed_actions.length > 0 && <ActionForm result={result} />}
    </div>
  );
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded border border-slate-200 bg-white p-4">
      <h2 className="mb-3 font-semibold">{title}</h2>
      {children}
    </div>
  );
}

function ScoreComparison({ result }: { result: ReviewDetail }) {
  const difference = scoreDifference(result.provisional_score, result.server_score);

  return (
    <Panel title="Scores">
      <dl className="grid grid-cols-3 gap-3 text-center">
        <div className="rounded bg-slate-50 p-3">
          <dt className="text-xs text-slate-500">Athlete's phone (provisional)</dt>
          <dd className="text-xl font-semibold tabular-nums">
            {formatScore(result.provisional_score, result.unit)}
          </dd>
        </div>
        <div className="rounded bg-blue-50 p-3">
          <dt className="text-xs text-slate-500">Server measurement</dt>
          <dd className="text-xl font-semibold tabular-nums">
            {formatScore(result.server_score, result.unit)}
          </dd>
        </div>
        <div className="rounded bg-emerald-50 p-3">
          <dt className="text-xs text-slate-500">Official</dt>
          <dd className="text-xl font-semibold tabular-nums">{formatScore(result.final_score, result.unit)}</dd>
        </div>
      </dl>
      {difference !== null && difference !== 0 && (
        <p className="mt-3 text-sm text-slate-700">
          The server measured {difference > 0 ? "more" : "less"} than the phone by{" "}
          <strong>{formatScore(Math.abs(difference), result.unit)}</strong>.
        </p>
      )}
      {result.server_score === null && (
        <p className="mt-3 text-sm text-amber-800">
          The server could not score this recording. Approving it requires entering the score you
          determine from the video.
        </p>
      )}
    </Panel>
  );
}

function Flags({ result, onSeek }: { result: ReviewDetail; onSeek: (ms: number) => void }) {
  return (
    <Panel title={`Flags (${result.flags.length})`}>
      {result.flags.length === 0 ? (
        <p className="text-sm text-slate-600">No automatic concerns were raised.</p>
      ) : (
        <ul className="space-y-3">
          {result.flags.map((flag, index) => {
            const at = flagTimestampMs(flag.detail);
            return (
              <li key={`${flag.reason}-${index}`} className="text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <SeverityBadge severity={flag.severity} />
                  <span className="font-medium">{flagReason(flag.reason)}</span>
                  {flag.resolution && (
                    <span className="text-xs text-slate-500">({flag.resolution})</span>
                  )}
                </div>
                {flag.detail && <p className="mt-1 text-slate-700">{flag.detail}</p>}
                {at !== null && (
                  <button
                    type="button"
                    onClick={() => onSeek(at)}
                    className="mt-1 text-xs font-medium text-blue-700 hover:underline"
                  >
                    Jump to {(at / 1000).toFixed(1)}s
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}

function Identity({ result }: { result: ReviewDetail }) {
  const face = result.face_verification;
  return (
    <Panel title="Identity">
      <div className="flex gap-4">
        {result.reference_photo_url ? (
          <img
            src={result.reference_photo_url}
            alt="Registration photo"
            className="h-32 w-24 rounded object-cover"
          />
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
                This check is approximate and unreliable in poor light. Compare the photo with the
                recording yourself.
              </p>
            </>
          ) : (
            <p className="text-slate-600">
              The identity check did not run for this submission — this is not a pass.
            </p>
          )}
        </div>
      </div>
    </Panel>
  );
}

function BenchmarkPanel({ result }: { result: ReviewDetail }) {
  const benchmark = result.benchmark!;
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

function History({ result }: { result: ReviewDetail }) {
  if (result.review_history.length === 0) return null;
  return (
    <Panel title="Review history">
      <ul className="space-y-2 text-sm">
        {result.review_history.map((entry, index) => (
          <li key={index}>
            <strong>{actionLabel(entry.action)}</strong> by {entry.official_name} ·{" "}
            <span className="text-slate-600">{formatDateTime(entry.created_at)}</span>
            {entry.notes && <p className="text-slate-700">“{entry.notes}”</p>}
          </li>
        ))}
      </ul>
    </Panel>
  );
}

export function ActionForm({ result }: { result: ReviewDetail }) {
  const { api } = useAuth();
  const queryClient = useQueryClient();
  const [action, setAction] = useState<ReviewActionName | null>(null);
  const [notes, setNotes] = useState("");
  const [manualScore, setManualScore] = useState("");
  const [validation, setValidation] = useState<string | null>(null);

  const needsManualScore = action === "approved" && result.server_score === null;

  const mutation = useMutation({
    mutationFn: () =>
      api.act(result.result_id, action!, notes, needsManualScore ? Number(manualScore) : undefined),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["review", result.result_id] });
      void queryClient.invalidateQueries({ queryKey: ["reviews"] });
      void queryClient.invalidateQueries({ queryKey: ["stats"] });
      setNotes("");
      setAction(null);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setValidation(null);

    if (!action) {
      setValidation("Choose a decision.");
      return;
    }
    if (action !== "approved" && !notes.trim()) {
      setValidation("Explain the decision — the athlete will see this note.");
      return;
    }
    if (needsManualScore) {
      const score = Number(manualScore);
      if (!manualScore.trim() || !Number.isFinite(score) || score < 0) {
        setValidation("Enter the score you determined from the recording.");
        return;
      }
      if (!notes.trim()) {
        setValidation("Say how you determined the score.");
        return;
      }
    }
    mutation.mutate();
  }

  const choices: { value: ReviewActionName; label: string; style: string }[] = [
    { value: "approved", label: "Approve", style: "border-emerald-600 text-emerald-800" },
    { value: "requested_resubmission", label: "Ask to record again", style: "border-amber-600 text-amber-900" },
    { value: "rejected", label: "Reject", style: "border-red-600 text-red-800" },
  ];

  return (
    <form onSubmit={submit} className="space-y-4 rounded border border-slate-300 bg-white p-4">
      <h2 className="font-semibold">Decision</h2>

      <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Decision">
        {choices
          .filter((choice) => result.allowed_actions.includes(choice.value))
          .map((choice) => (
            <button
              key={choice.value}
              type="button"
              role="radio"
              aria-checked={action === choice.value}
              onClick={() => setAction(choice.value)}
              className={`rounded border-2 px-4 py-2 text-sm font-medium ${
                action === choice.value ? `${choice.style} bg-slate-50` : "border-slate-200 text-slate-700"
              }`}
            >
              {choice.label}
            </button>
          ))}
      </div>

      {needsManualScore && (
        <label className="block text-sm">
          <span className="font-medium">Score from the recording ({result.unit})</span>
          <input
            type="number"
            min={0}
            step="any"
            value={manualScore}
            onChange={(event) => setManualScore(event.target.value)}
            className="mt-1 w-40 rounded border border-slate-300 px-3 py-2"
          />
        </label>
      )}

      <label className="block text-sm">
        <span className="font-medium">
          Notes {action === "approved" && !needsManualScore ? "(optional)" : "(shown to the athlete)"}
        </span>
        <textarea
          value={notes}
          maxLength={2000}
          rows={3}
          onChange={(event) => setNotes(event.target.value)}
          className="mt-1 w-full rounded border border-slate-300 px-3 py-2"
        />
      </label>

      {(validation || mutation.isError) && (
        <p role="alert" className="text-sm text-red-700">
          {validation ?? (mutation.error instanceof Error ? mutation.error.message : "Could not save.")}
        </p>
      )}

      <button
        type="submit"
        disabled={mutation.isPending}
        className="rounded bg-slate-900 px-4 py-2 font-medium text-white disabled:opacity-60"
      >
        {mutation.isPending ? "Saving…" : "Record decision"}
      </button>
    </form>
  );
}
