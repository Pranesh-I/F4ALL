import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useBlocker } from "react-router-dom";
import { ApiError, type ReviewActionResult } from "../../api/client";
import type { ReviewActionName, ReviewDetail, ReviewReason } from "../../api/types";
import { useAuth } from "../../auth/AuthContext";
import { REVIEW_REASONS, formatScore, reviewReasonLabel, shortId } from "../../lib/format";
import { ConfirmDialog } from "../ConfirmDialog";
import { describeError } from "../StateViews";

interface ActionSpec {
  value: ReviewActionName;
  /** The button's own words — also the confirm button's. */
  label: string;
  outcome: string;
  tone: "neutral" | "danger";
  /** Settles the submission; `flagged` does not. */
  final: boolean;
}

const ACTIONS: ActionSpec[] = [
  {
    value: "approved",
    label: "Approve result",
    outcome: "The score becomes the athlete's official result.",
    tone: "neutral",
    final: true,
  },
  {
    value: "requested_resubmission",
    label: "Request resubmission",
    outcome: "The athlete is asked to record this test again. This recording and its evidence are kept.",
    tone: "neutral",
    final: true,
  },
  {
    value: "rejected",
    label: "Reject submission",
    outcome: "The submission is rejected and will not count.",
    tone: "danger",
    final: true,
  },
  {
    value: "flagged",
    label: "Flag for further review",
    outcome: "Nothing is decided yet. The submission stays open, with your concern attached for the next reviewer.",
    tone: "neutral",
    final: false,
  },
];

type Severity = "low" | "medium" | "high";

/**
 * The reviewer's decision. The server decides which actions are possible
 * (`allowed_actions`) and whether this one still is: the panel sends the
 * `review_version` it was showing, and a submission that moved on in the
 * meantime comes back as a conflict rather than being overwritten.
 */
export function DecisionPanel({
  result,
  nameOf,
  onDecided,
  onConflict,
}: {
  result: ReviewDetail;
  nameOf: (code: string) => string;
  onDecided: (outcome: ReviewActionResult) => void;
  /** The page keeps this notice even if the refreshed submission has nothing left to decide. */
  onConflict?: (message: string) => void;
}) {
  const { api } = useAuth();
  const queryClient = useQueryClient();
  const [action, setAction] = useState<ReviewActionName | null>(null);
  const [reason, setReason] = useState<ReviewReason | "">("");
  const [notes, setNotes] = useState("");
  const [manualScore, setManualScore] = useState("");
  const [severity, setSeverity] = useState<Severity>("medium");
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const spec = ACTIONS.find((candidate) => candidate.value === action) ?? null;
  const needsManualScore = action === "approved" && result.server_score === null;
  const needsReason = action !== null && action !== "approved";
  const dirty = action !== null && Boolean(notes.trim() || reason || manualScore.trim());

  function refresh() {
    // By prefix: whichever page is showing this submission re-reads it.
    for (const key of ["review", "verification", "reviews", "submissions", "stats"]) {
      void queryClient.invalidateQueries({ queryKey: [key] });
    }
  }

  function clear() {
    setAction(null);
    setReason("");
    setNotes("");
    setManualScore("");
    setSeverity("medium");
    setProblem(null);
  }

  const mutation = useMutation({
    mutationFn: () =>
      api.act(result.result_id, {
        action: action!,
        reason: reason || undefined,
        notes,
        finalScore: needsManualScore ? Number(manualScore) : undefined,
        severity: action === "flagged" ? severity : undefined,
        expectedVersion: result.review_version,
      }),
    onSuccess: (outcome) => {
      setConfirming(false);
      clear();
      refresh();
      onDecided(outcome);
    },
    onError: (error) => {
      setConfirming(false);
      // Someone else acted first: reload so the page shows what it is now.
      if (error instanceof ApiError && error.status === 409) {
        onConflict?.(error.message);
        refresh();
      }
    },
  });

  // Leaving with a half-written decision asks first — in the app...
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty && !mutation.isPending && currentLocation.pathname !== nextLocation.pathname,
  );
  // ...and when closing or reloading the tab.
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  function check(): string | null {
    if (!action) return "Choose a decision.";
    if (needsReason && !reason) return "Choose a reason.";
    if ((action === "rejected" || action === "requested_resubmission") && !notes.trim()) {
      return "Explain the decision — the athlete will see this note.";
    }
    if (action === "flagged" && !notes.trim()) return "Say what concerns you — the next reviewer will read this.";
    if (needsManualScore) {
      const score = Number(manualScore);
      if (!manualScore.trim() || !Number.isFinite(score) || score < 0) {
        return "Enter the score you determined from the recording.";
      }
      if (!notes.trim()) return "Say how you determined the score.";
    }
    return null;
  }

  function review(event: FormEvent) {
    event.preventDefault();
    mutation.reset();
    const found = check();
    setProblem(found);
    if (!found) setConfirming(true);
  }

  const allowed = ACTIONS.filter((candidate) => result.allowed_actions.includes(candidate.value));
  const conflict = mutation.error instanceof ApiError && mutation.error.status === 409;
  const notesLabel =
    action === "flagged"
      ? "Note for the next reviewer (required · not shown to the athlete)"
      : action === "rejected" || action === "requested_resubmission"
        ? "Note to the athlete (required · the athlete will see it)"
        : needsManualScore
          ? "How you determined the score (required)"
          : "Note (optional)";
  const officialScore = needsManualScore
    ? `${formatScore(Number(manualScore), result.unit)} (entered by you)`
    : formatScore(result.server_score, result.unit);

  return (
    <form onSubmit={review} aria-label="Decision" className="space-y-4 rounded border border-slate-300 bg-white p-4">
      <h2 className="font-semibold">Decision</h2>

      <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Decision">
        {allowed.map((candidate) => (
          <button
            key={candidate.value}
            type="button"
            role="radio"
            aria-checked={action === candidate.value}
            onClick={() => {
              setAction(candidate.value);
              setProblem(null);
            }}
            className={`rounded border-2 px-4 py-2 text-sm font-medium ${
              action === candidate.value
                ? candidate.tone === "danger"
                  ? "border-red-600 bg-red-50 text-red-800"
                  : "border-slate-900 bg-slate-50 text-slate-900"
                : "border-slate-200 text-slate-700 hover:border-slate-400"
            }`}
          >
            {candidate.label}
          </button>
        ))}
      </div>

      {spec && <p className="text-sm text-slate-600">{spec.outcome}</p>}

      {needsReason && (
        <label className="block text-sm">
          <span className="font-medium">Reason (required)</span>
          <select
            value={reason}
            onChange={(event) => setReason(event.target.value as ReviewReason | "")}
            className="mt-1 block w-full max-w-sm rounded border border-slate-300 bg-white px-3 py-2"
          >
            <option value="">Choose a reason…</option>
            {REVIEW_REASONS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        </label>
      )}

      {action === "flagged" && (
        <label className="block text-sm">
          <span className="font-medium">How serious</span>
          <select
            value={severity}
            onChange={(event) => setSeverity(event.target.value as Severity)}
            className="mt-1 block w-40 rounded border border-slate-300 bg-white px-3 py-2"
          >
            <option value="low">Low</option>
            <option value="medium">Medium</option>
            <option value="high">High</option>
          </select>
        </label>
      )}

      {needsManualScore && (
        <label className="block text-sm">
          <span className="font-medium">Score from the recording ({result.unit})</span>
          <input
            type="number"
            min={0}
            step="any"
            value={manualScore}
            onChange={(event) => setManualScore(event.target.value)}
            className="mt-1 block w-40 rounded border border-slate-300 px-3 py-2"
          />
        </label>
      )}

      {action && (
        <label className="block text-sm">
          <span className="font-medium">{notesLabel}</span>
          <textarea
            value={notes}
            maxLength={2000}
            rows={3}
            onChange={(event) => setNotes(event.target.value)}
            className="mt-1 w-full rounded border border-slate-300 px-3 py-2"
          />
        </label>
      )}

      {problem && (
        <p role="alert" className="text-sm text-red-700">
          {problem}
        </p>
      )}
      {mutation.isError && (
        <div role="alert" className="rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
          {conflict ? (
            <>
              <p className="font-medium">Another official acted on this submission first.</p>
              <p>{mutation.error.message}</p>
              <p>The page now shows its current state and history.</p>
            </>
          ) : (
            <p>{describeError(mutation.error)}</p>
          )}
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <button
          type="submit"
          disabled={!spec || mutation.isPending}
          className={`rounded px-4 py-2 text-sm font-medium text-white disabled:opacity-50 ${
            spec?.tone === "danger" ? "bg-red-700 hover:bg-red-800" : "bg-slate-900 hover:bg-slate-700"
          }`}
        >
          {spec ? `${spec.label}…` : "Choose a decision"}
        </button>
        {action && (
          <button type="button" onClick={clear} className="rounded border border-slate-300 px-4 py-2 text-sm">
            Clear
          </button>
        )}
      </div>

      {confirming && spec && (
        <ConfirmDialog
          title={`${spec.label}?`}
          confirmLabel={spec.label}
          tone={spec.tone}
          busy={mutation.isPending}
          onConfirm={() => mutation.mutate()}
          onCancel={() => setConfirming(false)}
        >
          <p>
            <strong>{result.athlete_name}</strong> — {nameOf(result.test_type)}, submission #{shortId(result.result_id)}
          </p>
          {reason && <p>Reason: {reviewReasonLabel(reason)}</p>}
          {action === "flagged" && <p>Severity: {severity}</p>}
          {action === "approved" && <p>Official score: {officialScore}</p>}
          {notes.trim() && (
            <p>
              {action === "flagged" ? "Note for reviewers" : "Note to the athlete"}: “{notes.trim()}”
            </p>
          )}
          <p>{spec.outcome}</p>
          {spec.final && <p className="font-medium">This cannot be undone from the dashboard.</p>}
        </ConfirmDialog>
      )}

      {blocker.state === "blocked" && (
        <ConfirmDialog
          title="Leave without recording your decision?"
          confirmLabel="Leave page"
          cancelLabel="Stay on this page"
          tone="danger"
          onConfirm={() => blocker.proceed()}
          onCancel={() => blocker.reset()}
        >
          <p>You have started a decision on this submission. What you have entered will be lost.</p>
        </ConfirmDialog>
      )}
    </form>
  );
}
