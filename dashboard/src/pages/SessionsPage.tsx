import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { useTestCatalog } from "../api/hooks";
import type { AssessmentSession, SessionStatus } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { SessionStatusBadge } from "../components/Badges";
import { PageHeader } from "../components/PageHeader";
import { EmptyState, ErrorState, LoadingState } from "../components/StateViews";
import { formatDateTime } from "../lib/format";
import { paths } from "../routes";

const FILTERS: { key: "" | SessionStatus; label: string }[] = [
  { key: "", label: "All" },
  { key: "active", label: "Active" },
  { key: "scheduled", label: "Scheduled" },
  { key: "disabled", label: "Disabled" },
  { key: "ended", label: "Ended" },
];

/**
 * Assessment sessions: the windows in which athletes may submit official
 * tests. Any official can see them; only an SAI admin can create or change
 * one, since a session decides who may submit official results. Each status
 * shown is the server's, computed against its own clock.
 */
export function SessionsPage() {
  const { api, official } = useAuth();
  const isAdmin = official?.role === "sai_admin";
  const queryClient = useQueryClient();
  const { nameOf } = useTestCatalog();
  const [params, setParams] = useSearchParams();
  const status = (params.get("status") ?? "") as "" | SessionStatus;

  const sessions = useQuery({
    queryKey: ["sessions", status],
    queryFn: () => api.sessions(status ? { status: [status] } : {}),
    // A scheduled session becomes active on the server's clock, not ours.
    refetchInterval: 60_000,
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["sessions"] });

  const toggle = useMutation({
    mutationFn: (session: AssessmentSession) =>
      api.updateSession(session.id, { enabled: !session.enabled }),
    onSuccess: refresh,
  });

  const remove = useMutation({
    mutationFn: (session: AssessmentSession) => api.deleteSession(session.id),
    onSuccess: refresh,
  });

  return (
    <div className="space-y-5">
      <PageHeader
        title="Assessment sessions"
        description="Athletes see a session only while it is enabled and open, and may submit each test in it once."
        actions={
          isAdmin && (
            <Link
              to={paths.newSession}
              className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700"
            >
              New session
            </Link>
          )
        }
      />

      <div className="flex flex-wrap gap-2 border-b border-slate-200" role="tablist" aria-label="Session status">
        {FILTERS.map((filter) => (
          <button
            key={filter.key}
            type="button"
            role="tab"
            aria-selected={status === filter.key}
            onClick={() => setParams(filter.key ? { status: filter.key } : {})}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
              status === filter.key
                ? "border-slate-900 text-slate-900"
                : "border-transparent text-slate-500 hover:text-slate-800"
            }`}
          >
            {filter.label}
          </button>
        ))}
      </div>

      {(toggle.isError || remove.isError) && (
        <ErrorState title="That change was not saved" error={toggle.error ?? remove.error} />
      )}

      {sessions.isLoading && <LoadingState label="Loading sessions…" />}
      {sessions.isError && (
        <ErrorState title="Could not load sessions" error={sessions.error} onRetry={() => sessions.refetch()} />
      )}
      {sessions.data?.length === 0 && (
        <EmptyState
          title={status ? `No ${status} sessions.` : "No sessions yet."}
          action={
            isAdmin &&
            !status && (
              <Link to={paths.newSession} className="text-blue-700 hover:underline">
                Create the first session
              </Link>
            )
          }
        >
          {status
            ? "Sessions move between states on the server's clock; check back or clear the filter."
            : "A session opens a window in which athletes can submit official tests."}
        </EmptyState>
      )}

      {sessions.data && sessions.data.length > 0 && (
        <div className="overflow-x-auto rounded border border-slate-200 bg-white">
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-left text-slate-600">
              <tr>
                <th className="px-3 py-2">Session</th>
                <th className="px-3 py-2">Window</th>
                <th className="px-3 py-2">Tests</th>
                <th className="px-3 py-2">Region</th>
                <th className="px-3 py-2">Status</th>
                <th className="px-3 py-2 text-right">Submissions</th>
                {isAdmin && <th className="px-3 py-2" />}
              </tr>
            </thead>
            <tbody>
              {sessions.data.map((session) => (
                <tr key={session.id} className="border-t border-slate-100 align-top">
                  <td className="px-3 py-2">
                    <Link to={paths.session(session.id)} className="font-medium text-blue-700 hover:underline">
                      {session.name}
                    </Link>
                    {session.description && <div className="text-slate-600">{session.description}</div>}
                  </td>
                  <td className="px-3 py-2 text-slate-700">
                    {formatDateTime(session.starts_at)}
                    <br />
                    to {formatDateTime(session.ends_at)}
                  </td>
                  <td className="px-3 py-2">{session.allowed_tests.map(nameOf).join(", ")}</td>
                  <td className="px-3 py-2">{session.region ?? "All regions"}</td>
                  <td className="px-3 py-2">
                    <SessionStatusBadge status={session.status} />
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">{session.submission_count}</td>
                  {isAdmin && (
                    <td className="space-x-2 whitespace-nowrap px-3 py-2 text-right">
                      <button
                        type="button"
                        onClick={() => toggle.mutate(session)}
                        disabled={toggle.isPending}
                        className="rounded border border-slate-300 px-2 py-1 hover:bg-slate-100"
                      >
                        {session.enabled ? "Disable" : "Enable"}
                      </button>
                      {/* A session with submissions is part of the official record. */}
                      {session.submission_count === 0 && (
                        <button
                          type="button"
                          onClick={() => {
                            if (window.confirm(`Delete "${session.name}"?`)) remove.mutate(session);
                          }}
                          disabled={remove.isPending}
                          className="rounded border border-red-300 px-2 py-1 text-red-700 hover:bg-red-50"
                        >
                          Delete
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
