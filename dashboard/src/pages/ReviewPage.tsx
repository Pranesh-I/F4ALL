import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { ReviewActionResult } from "../api/client";
import { useTestCatalog } from "../api/hooks";
import type { ReviewDetail } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { ReviewStatusBadge, VerdictBadge } from "../components/Badges";
import { PageHeader } from "../components/PageHeader";
import { Panel } from "../components/Panel";
import { DecisionPanel } from "../components/review/DecisionPanel";
import { BenchmarkPanel, IdentityPanel } from "../components/review/IdentityPanel";
import { IntegrityFlagList } from "../components/review/IntegrityFlagList";
import { ResultComparison } from "../components/review/ResultComparison";
import { ReviewHistory } from "../components/review/ReviewHistory";
import { SubmissionFacts } from "../components/review/SubmissionFacts";
import { VerificationSummary } from "../components/review/VerificationSummary";
import { ErrorState, LoadingState, SuccessNotice } from "../components/StateViews";
import { VideoWithSkeleton } from "../components/VideoWithSkeleton";
import { paths } from "../routes";
import { Bib } from "../components/Trials";
import { ui } from "../lib/ui";

/**
 * Everything the verification used, and the decision. What is shown comes
 * from the server: the detail, the Sprint 11 verification record, and the
 * pose data for the skeleton. Which decisions are possible does too.
 */
export function ReviewPage() {
  const { resultId = "" } = useParams();
  const { api } = useAuth();
  const { nameOf } = useTestCatalog();
  const [seekToMs, setSeekToMs] = useState<number | null>(null);
  const [outcome, setOutcome] = useState<ReviewActionResult | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);

  const detail = useQuery({
    queryKey: ["review", resultId],
    queryFn: () => api.review(resultId),
    enabled: Boolean(resultId),
    // Signed media URLs expire; do not hold a stale detail for long.
    staleTime: 60_000,
  });

  const verification = useQuery({
    queryKey: ["verification", resultId],
    queryFn: () => api.verification(resultId),
    enabled: Boolean(resultId),
  });

  const pose = useQuery({
    queryKey: ["pose", resultId],
    queryFn: () => api.poseSequence(resultId),
    enabled: Boolean(detail.data?.has_pose_sequence),
    staleTime: Infinity,
  });

  const back = (
    <Link to={paths.reviews} className="font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink">
      ← Review queue
    </Link>
  );

  if (detail.isLoading) return <LoadingState label="Loading the submission…" />;

  if (detail.isError || !detail.data) {
    return (
      <div className="space-y-3">
        {back}
        <ErrorState title="Could not open this submission" error={detail.error} onRetry={() => detail.refetch()} />
      </div>
    );
  }

  const result = detail.data;

  return (
    <div className="space-y-6">
      <PageHeader
        back={back}
        title={`${result.athlete_name} — ${nameOf(result.test_type)}`}
        description={
          <span className="flex flex-wrap items-center gap-2">
            <ReviewStatusBadge status={result.review_status} />
            <VerdictBadge verdict={result.verification_verdict} />
          </span>
        }
        actions={
          <div className="flex flex-wrap items-end gap-3">
            {/* The same chest number the athlete sees on their phone. */}
            {result.athlete_id && <Bib athleteId={result.athlete_id} detail={result.region} />}
            <Link to={paths.submission(result.result_id)} className={ui.buttonLine}>
              Submission summary
            </Link>
          </div>
        }
      />

      {outcome && <SuccessNotice>{outcome.message}</SuccessNotice>}
      {conflict && (
        <div role="alert" className="rounded-xs border border-marigold bg-marigold-tint px-4 py-3 text-sm text-ink">
          <p className="font-medium">Your decision was not recorded — another official acted on this submission first.</p>
          <p>{conflict}</p>
          <p>The page below shows its current state and who decided it.</p>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <div className="space-y-5">
          <Panel title="Recording">
            {result.video_url ? (
              <VideoWithSkeleton src={result.video_url} pose={pose.data ?? null} seekToMs={seekToMs} />
            ) : (
              <div className="rounded-xs bg-paper-shade p-6 text-sm text-pencil">
                No recording is attached to this submission.
              </div>
            )}
            {pose.isError && (
              <p className="mt-2 text-sm text-ink">The server&apos;s tracking data could not be loaded.</p>
            )}
            {result.video_url && (
              <p className="mt-2 text-xs text-pencil">
                Streamed through a short-lived signed link. The skeleton is the server&apos;s own tracking.
              </p>
            )}
          </Panel>
          <Panel title="Submission">
            <SubmissionFacts result={result} nameOf={nameOf} />
          </Panel>
        </div>

        <div className="space-y-5">
          <Panel title="Phone and server results">
            <ResultComparison result={result} verification={verification.data} />
          </Panel>
          <Panel title={`Integrity (${result.flags.length})`}>
            <IntegrityFlagList flags={result.flags} onSeek={setSeekToMs} />
          </Panel>
          <Panel title="Automated verification">
            {verification.isLoading && <LoadingState label="Loading verification…" />}
            {verification.isError && (
              <ErrorState
                title="Could not load the verification record"
                error={verification.error}
                onRetry={() => verification.refetch()}
              />
            )}
            {verification.data && (
              <VerificationSummary verification={verification.data} verdict={result.verification_verdict} />
            )}
          </Panel>
          <IdentityPanel result={result} />
          <BenchmarkPanel result={result} />
        </div>
      </div>

      <Panel title="Review history">
        <ReviewHistory history={result.review_history} />
      </Panel>

      {result.allowed_actions.length > 0 ? (
        <DecisionPanel
          result={result}
          nameOf={nameOf}
          onDecided={(decided) => {
            setConflict(null);
            setOutcome(decided);
          }}
          onConflict={setConflict}
        />
      ) : (
        <NoDecision result={result} />
      )}
    </div>
  );
}

/** Why there is nothing to decide, rather than an empty space. */
function NoDecision({ result }: { result: ReviewDetail }) {
  const text =
    result.review_status === "awaiting_verification"
      ? "The server is still verifying this recording. A decision can be made once its verdict is in."
      : result.review_status === "awaiting_upload"
        ? "The recording has not been uploaded yet."
        : "This submission has been decided. The review history above shows who decided it, when and why.";
  return (
    <div className="rounded-xs border border-ink bg-ground p-4 text-sm text-ink" role="status">
      {text}
    </div>
  );
}
