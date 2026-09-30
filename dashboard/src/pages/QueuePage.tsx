import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useTestCatalog } from "../api/hooks";
import { useAuth } from "../auth/AuthContext";
import { PageHeader } from "../components/PageHeader";
import { EmptyState, ErrorState, LoadingState } from "../components/StateViews";
import { SubmissionFilters, useListFilters } from "../components/SubmissionFilters";
import { SubmissionTable } from "../components/SubmissionTable";
import { paths } from "../routes";

const PAGE_SIZE = 25;

/** Tabs are review states the server computes; the empty key is its default. */
const TABS = [
  { key: "", label: "Needs a decision", hint: "Flagged, or the server could not verify it" },
  { key: "awaiting_approval", label: "Awaiting approval", hint: "The server agreed with the phone" },
  { key: "awaiting_verification", label: "Being verified", hint: "Not decidable until the server's verdict is in" },
  { key: "approved,rejected,resubmission_requested", label: "Decided", hint: "Already acted on" },
] as const;

const EMPTY: Record<string, string> = {
  "": "Nothing is waiting for a decision.",
  awaiting_approval: "Nothing is waiting for approval.",
  awaiting_verification: "Nothing is being verified right now.",
  "approved,rejected,resubmission_requested": "Nothing has been decided yet.",
};

/**
 * The reviewer's work queue: most severe unresolved flags first, then oldest.
 * Filtering, ordering and paging all happen on the server.
 */
export function QueuePage() {
  const { api } = useAuth();
  const { nameOf } = useTestCatalog();
  const filters = useListFilters();
  const tab = filters.values.review_status;
  const { page } = filters;

  const queue = useQuery({
    queryKey: ["reviews", filters.api, page],
    queryFn: () => api.reviewQueue({ ...filters.api, limit: PAGE_SIZE, offset: page * PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  const stats = useQuery({ queryKey: ["stats"], queryFn: () => api.stats() });

  const total = queue.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-5">
      <PageHeader
        title="Review queue"
        description="Most severe unresolved flags first, then oldest."
        actions={
          stats.data && (
            <div className="flex flex-wrap gap-2 text-sm">
              <Stat label="Flagged" value={stats.data.by_status.flagged} />
              <Stat label="High severity" value={stats.data.flagged_high_severity} tone="red" />
              <Stat label="Awaiting approval" value={stats.data.by_status.verified} />
              <Stat label="Over SLA" value={stats.data.breaching_sla} tone="red" />
            </div>
          )
        }
      />

      <div className="flex flex-wrap gap-2 border-b border-slate-200" role="tablist" aria-label="Review state">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            title={item.hint}
            aria-selected={tab === item.key}
            onClick={() => filters.update({ review_status: item.key })}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
              tab === item.key ? "border-slate-900 text-slate-900" : "border-transparent text-slate-500 hover:text-slate-800"
            }`}
          >
            {item.label}
          </button>
        ))}
      </div>

      <SubmissionFilters filters={filters} keepOnClear={["review_status"]} />

      {queue.isLoading && <LoadingState label="Loading the queue…" />}
      {queue.isError && (
        <ErrorState title="Could not load the queue" error={queue.error} onRetry={() => queue.refetch()} />
      )}
      {queue.data?.items.length === 0 && (
        <EmptyState title={filters.active ? "No submissions match these filters." : (EMPTY[tab] ?? "Nothing here.")}>
          {filters.active ? "Try a different test, session, athlete or date." : "New submissions appear here once the server has verified them."}
        </EmptyState>
      )}

      {queue.data && queue.data.items.length > 0 && (
        <>
          <SubmissionTable items={queue.data.items} nameOf={nameOf} variant="review" linkTo={paths.review} />
          <div className="flex items-center justify-between text-sm text-slate-600">
            <span>{total} submission(s)</span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => filters.update({ page: String(page - 1) })}
                className="rounded border border-slate-300 px-3 py-1 disabled:opacity-40"
              >
                Previous
              </button>
              <span>
                Page {page + 1} of {pages}
              </span>
              <button
                type="button"
                disabled={page + 1 >= pages}
                onClick={() => filters.update({ page: String(page + 1) })}
                className="rounded border border-slate-300 px-3 py-1 disabled:opacity-40"
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: "red" }) {
  return (
    <div
      className={`rounded border px-3 py-1.5 ${
        tone === "red" && value > 0 ? "border-red-200 bg-red-50 text-red-800" : "border-slate-200 bg-white"
      }`}
    >
      <span className="font-semibold tabular-nums">{value}</span> {label}
    </div>
  );
}
