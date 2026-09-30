import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useTestCatalog } from "../api/hooks";
import { useAuth } from "../auth/AuthContext";
import { ReviewStatusBadge, StatusBadge } from "../components/Badges";
import { PageHeader } from "../components/PageHeader";
import { Panel } from "../components/Panel";
import { IntegrityFlagList } from "../components/review/IntegrityFlagList";
import { ResultComparison } from "../components/review/ResultComparison";
import { ReviewHistory } from "../components/review/ReviewHistory";
import { SubmissionFacts } from "../components/review/SubmissionFacts";
import { VerificationSummary } from "../components/review/VerificationSummary";
import { ErrorState, LoadingState } from "../components/StateViews";
import { formatDateTime, identityCheckLabel, shortId } from "../lib/format";
import { paths } from "../routes";

/**
 * One submission, read-only: who, which session and test, the phone's result
 * beside the server's, where verification got to, what the integrity checks
 * raised, and what officials have done. Deciding happens in the review view,
 * built from the same components.
 */
export function SubmissionDetailPage() {
  const { resultId = "" } = useParams();
  const { api } = useAuth();
  const { nameOf } = useTestCatalog();

  const detail = useQuery({
    queryKey: ["review", resultId],
    queryFn: () => api.review(resultId),
    enabled: Boolean(resultId),
  });

  const verification = useQuery({
    queryKey: ["verification", resultId],
    queryFn: () => api.verification(resultId),
    enabled: Boolean(resultId),
  });

  const back = (
    <Link to={paths.submissions} className="text-blue-700 hover:underline">
      ← Submissions
    </Link>
  );

  if (detail.isLoading) return <LoadingState label="Loading the submission…" />;

  if (detail.isError || !detail.data) {
    return (
      <div className="space-y-3">
        {back}
        <ErrorState title="Could not load this submission" error={detail.error} onRetry={() => detail.refetch()} />
      </div>
    );
  }

  const result = detail.data;
  const decidable = result.allowed_actions.length > 0;

  return (
    <div className="space-y-5">
      <PageHeader
        back={back}
        title={`${result.athlete_name} — ${nameOf(result.test_type)}`}
        description={`Submission #${shortId(result.result_id)} · attempt ${result.attempt_number} · submitted ${formatDateTime(result.created_at)}`}
        actions={
          <Link
            to={paths.review(result.result_id)}
            className={`rounded px-4 py-2 text-sm font-medium ${
              decidable
                ? "bg-slate-900 text-white hover:bg-slate-700"
                : "border border-slate-300 bg-white hover:bg-slate-100"
            }`}
          >
            {decidable ? "Review and decide" : "Open review view"}
          </Link>
        }
      />

      <div className="flex flex-wrap items-center gap-2 text-sm">
        <StatusBadge status={result.status} />
        <ReviewStatusBadge status={result.review_status} />
        {result.status === "flagged" && (
          <span className="text-slate-600">Needs an official&apos;s decision before it counts.</span>
        )}
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <Panel title="Submission">
          <SubmissionFacts result={result} nameOf={nameOf} />
        </Panel>
        <Panel title="Phone and server results">
          <ResultComparison result={result} verification={verification.data} />
        </Panel>
        <Panel title="Verification">
          {verification.isLoading && <LoadingState label="Loading verification…" />}
          {verification.isError && (
            <ErrorState
              title="Could not load verification details"
              error={verification.error}
              onRetry={() => verification.refetch()}
            />
          )}
          {verification.data && (
            <VerificationSummary verification={verification.data} verdict={result.verification_verdict} />
          )}
        </Panel>
        <Panel title="Integrity">
          <IntegrityFlagList flags={result.flags} />
          <p className="mt-3 text-sm">
            <span className="text-slate-500">Photo check before the test: </span>
            {identityCheckLabel(result.identity_check)}
          </p>
          {verification.data?.face_check && (
            <p className="text-sm">
              <span className="text-slate-500">Face check on the recording: </span>
              {verification.data.face_check.replace(/_/g, " ")}
            </p>
          )}
        </Panel>
      </div>

      <Panel title="Review history">
        <ReviewHistory history={result.review_history} />
      </Panel>
    </div>
  );
}
