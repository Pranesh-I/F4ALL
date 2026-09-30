import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useTestCatalog } from "../api/hooks";
import { useAuth } from "../auth/AuthContext";
import { PageHeader } from "../components/PageHeader";
import { EmptyState, ErrorState, LoadingState } from "../components/StateViews";
import { SubmissionFilters, useListFilters } from "../components/SubmissionFilters";
import { SubmissionTable } from "../components/SubmissionTable";

const PAGE_SIZE = 25;

/**
 * Every submission this official may see, newest first. Filters live in the
 * URL so a filtered view can be shared or bookmarked. Deciding on a
 * submission is the review queue's job; this is where to find one.
 */
export function SubmissionsPage() {
  const { api } = useAuth();
  const catalog = useTestCatalog();
  const filters = useListFilters();
  const { page } = filters;

  const list = useQuery({
    queryKey: ["submissions", filters.api, page],
    queryFn: () => api.submissions({ ...filters.api, limit: PAGE_SIZE, offset: page * PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  const total = list.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-5">
      <PageHeader
        title="Submissions"
        description="Every official test submitted, newest first, with its verification state. Decisions are made in the review queue."
      />

      <SubmissionFilters filters={filters} showStatus />

      {list.isLoading && <LoadingState label="Loading submissions…" />}
      {list.isError && (
        <ErrorState title="Could not load submissions" error={list.error} onRetry={() => list.refetch()} />
      )}
      {list.data?.items.length === 0 &&
        (filters.active ? (
          <EmptyState title="No submissions match these filters.">Try a different status, test or session.</EmptyState>
        ) : (
          <EmptyState title="No submissions yet.">
            Official tests appear here as soon as an athlete&apos;s phone submits them.
          </EmptyState>
        ))}

      {list.data && list.data.items.length > 0 && (
        <>
          <SubmissionTable items={list.data.items} nameOf={catalog.nameOf} />
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
