import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTestCatalog } from "../api/hooks";
import type { DashboardStats } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { SessionStatusBadge } from "../components/Badges";
import { PageHeader } from "../components/PageHeader";
import { EmptyState, ErrorState, LoadingState } from "../components/StateViews";
import { SubmissionTable } from "../components/SubmissionTable";
import { formatDateTime, roleLabel } from "../lib/format";
import { paths } from "../routes";

const RECENT = 8;

/** Each tile is a count the server returned, and opens the matching list. */
const TILES: {
  label: string;
  status: string;
  value: (stats: DashboardStats) => number;
  alert?: boolean;
  /** Approvals are written in the stamp's violet, as everywhere else. */
  stamp?: boolean;
}[] = [
  {
    label: "Awaiting verification",
    status: "uploaded,processing",
    value: (s) => s.by_status.uploaded + s.by_status.processing,
  },
  { label: "Verified — awaiting approval", status: "verified", value: (s) => s.by_status.verified },
  { label: "Flagged", status: "flagged", value: (s) => s.by_status.flagged, alert: true },
  { label: "Approved", status: "approved", value: (s) => s.by_status.approved, stamp: true },
  { label: "Rejected", status: "rejected", value: (s) => s.by_status.rejected },
  { label: "Resubmission requested", status: "pending_sync", value: (s) => s.by_status.pending_sync },
];

export function DashboardPage() {
  const { api, official } = useAuth();
  const isAdmin = official?.role === "sai_admin";
  const { nameOf } = useTestCatalog();

  const stats = useQuery({ queryKey: ["stats"], queryFn: () => api.stats(), refetchInterval: 60_000 });
  // "Active" is decided by the server against its clock, not worked out here.
  const active = useQuery({
    queryKey: ["sessions", "active"],
    queryFn: () => api.sessions({ status: ["active"] }),
    refetchInterval: 60_000,
  });
  const recent = useQuery({
    queryKey: ["submissions", { limit: RECENT }],
    queryFn: () => api.submissions({ limit: RECENT }),
    refetchInterval: 60_000,
  });

  return (
    <div className="space-y-8">
      <PageHeader title="Dashboard" description={official ? `Signed in as ${roleLabel(official)}.` : undefined} />

      <section aria-labelledby="counts" className="space-y-3">
        <h2 id="counts" className="label-caps border-b-2 border-ink pb-1 text-sm">
          Submissions by state
        </h2>
        {stats.isLoading && <LoadingState label="Loading counts…" />}
        {stats.isError && (
          <ErrorState title="Could not load the counts" error={stats.error} onRetry={() => stats.refetch()} />
        )}
        {stats.data && (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {TILES.map((tile) => {
                const value = tile.value(stats.data);
                return (
                  <Link
                    key={tile.label}
                    to={`${paths.submissions}?status=${tile.status}`}
                    className={`group rounded-xs border p-4 transition-colors hover:bg-ground ${
                      tile.alert && value > 0 ? "border-redpen bg-redpen-tint" : "border-ink bg-paper"
                    }`}
                  >
                    {/* A scoreboard figure: the count large, in the chest-number face. */}
                    <div
                      className={`numerals text-5xl leading-none ${
                        tile.alert && value > 0 ? "text-redpen" : tile.stamp ? "text-stamp" : "text-ink"
                      }`}
                    >
                      {value}
                    </div>
                    <div className="label-caps mt-2 text-xs text-pencil group-hover:text-ink">{tile.label}</div>
                  </Link>
                );
              })}
            </div>
            <p className="text-sm text-pencil">
              <span className={stats.data.flagged_high_severity > 0 ? "font-medium text-redpen" : undefined}>
                {stats.data.flagged_high_severity} flagged with a high-severity integrity issue
              </span>
              {" · "}
              <span className={stats.data.breaching_sla > 0 ? "font-medium text-redpen" : undefined}>
                {stats.data.breaching_sla} waiting longer than the verification target
              </span>
            </p>
          </>
        )}
      </section>

      <section aria-labelledby="active-sessions" className="space-y-3">
        <div className="flex items-end justify-between gap-3 border-b-2 border-ink pb-1">
          <h2 id="active-sessions" className="label-caps text-sm">
            Active sessions
          </h2>
          <Link to={paths.sessions} className="text-sm font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink">
            All sessions
          </Link>
        </div>
        {active.isLoading && <LoadingState label="Loading sessions…" />}
        {active.isError && (
          <ErrorState title="Could not load sessions" error={active.error} onRetry={() => active.refetch()} />
        )}
        {active.data?.length === 0 && (
          <EmptyState
            title="No session is active right now."
            action={
              isAdmin && (
                <Link to={paths.newSession} className="font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink">
                  Create a session
                </Link>
              )
            }
          >
            Athletes can only submit official tests while a session is active.
          </EmptyState>
        )}
        {active.data && active.data.length > 0 && (
          <ul className="grid gap-3 md:grid-cols-2">
            {active.data.map((session) => (
              <li key={session.id} className="rounded-xs border border-ink bg-paper p-4">
                <div className="flex items-start justify-between gap-2">
                  <Link to={paths.session(session.id)} className="font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink">
                    {session.name}
                  </Link>
                  <SessionStatusBadge status={session.status} />
                </div>
                <p className="mt-1 text-sm text-pencil">
                  {session.region ?? "All regions"} · closes {formatDateTime(session.ends_at)}
                </p>
                <p className="mt-1 text-sm text-pencil">{session.allowed_tests.map(nameOf).join(", ")}</p>
                <p className="mt-1 text-sm">
                  <span className="font-medium tabular-nums">{session.submission_count}</span> submission(s)
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="recent" className="space-y-3">
        <div className="flex items-end justify-between gap-3 border-b-2 border-ink pb-1">
          <h2 id="recent" className="label-caps text-sm">
            Recent submissions
          </h2>
          <Link to={paths.submissions} className="text-sm font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink">
            All submissions
          </Link>
        </div>
        {recent.isLoading && <LoadingState label="Loading submissions…" />}
        {recent.isError && (
          <ErrorState title="Could not load submissions" error={recent.error} onRetry={() => recent.refetch()} />
        )}
        {recent.data?.items.length === 0 && (
          <EmptyState title="No submissions yet.">Official tests appear here as athletes submit them.</EmptyState>
        )}
        {recent.data && recent.data.items.length > 0 && (
          <SubmissionTable items={recent.data.items} nameOf={nameOf} />
        )}
      </section>
    </div>
  );
}
