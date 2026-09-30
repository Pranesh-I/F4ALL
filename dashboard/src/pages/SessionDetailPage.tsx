import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { useTestCatalog } from "../api/hooks";
import type { AssessmentSession, AssessmentSessionChanges, NewAssessmentSession } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { SessionStatusBadge } from "../components/Badges";
import { PageHeader } from "../components/PageHeader";
import { SessionForm } from "../components/SessionForm";
import { EmptyState, ErrorState, LoadingState, SuccessNotice } from "../components/StateViews";
import { SubmissionTable } from "../components/SubmissionTable";
import { formatDateTime, sessionStatusExplanation, toLocalInput } from "../lib/format";
import { paths } from "../routes";

const RECENT = 10;

export function SessionDetailPage() {
  const { sessionId = "" } = useParams();
  const { api, official } = useAuth();
  const isAdmin = official?.role === "sai_admin";
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const catalog = useTestCatalog();
  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState<string | null>(
    (location.state as { notice?: string } | null)?.notice ?? null,
  );

  // Shown once: a reload of this page should not announce the creation again.
  useEffect(() => {
    if (location.state) navigate(location.pathname, { replace: true, state: null });
  }, [location.state, location.pathname, navigate]);

  const session = useQuery({
    queryKey: ["session", sessionId],
    queryFn: () => api.session(sessionId),
    enabled: Boolean(sessionId),
    // Status follows the server's clock; keep it current while the page is open.
    refetchInterval: 60_000,
  });

  const submissions = useQuery({
    queryKey: ["submissions", { sessionId, limit: RECENT }],
    queryFn: () => api.submissions({ sessionId, limit: RECENT }),
    enabled: Boolean(sessionId),
  });

  function saved(updated: AssessmentSession, message: string) {
    queryClient.setQueryData(["session", updated.id], updated);
    void queryClient.invalidateQueries({ queryKey: ["sessions"] });
    setNotice(message);
  }

  const update = useMutation({
    mutationFn: ({ changes }: { changes: AssessmentSessionChanges; message: string }) =>
      api.updateSession(sessionId, changes),
    onSuccess: (updated, { message }) => {
      setEditing(false);
      saved(updated, message);
    },
  });

  const end = useMutation({
    mutationFn: () => api.endSession(sessionId),
    onSuccess: (updated) => saved(updated, "Session ended. The server closed it just now."),
  });

  const remove = useMutation({
    mutationFn: () => api.deleteSession(sessionId),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ["session", sessionId] });
      void queryClient.invalidateQueries({ queryKey: ["sessions"] });
      navigate(paths.sessions, { replace: true });
    },
  });

  const back = (
    <Link to={paths.sessions} className="text-blue-700 hover:underline">
      ← Sessions
    </Link>
  );

  if (session.isLoading) return <LoadingState label="Loading the session…" />;

  if (session.isError || !session.data) {
    return (
      <div className="space-y-3">
        {back}
        <ErrorState title="Could not load this session" error={session.error} onRetry={() => session.refetch()} />
      </div>
    );
  }

  const current = session.data;
  const actionError = update.error ?? end.error ?? remove.error;
  const busy = update.isPending || end.isPending || remove.isPending;

  function applyEdit(values: NewAssessmentSession) {
    // The form works in whole minutes; resending an untouched time would
    // quietly move the window by the seconds it drops.
    const moved = (before: string, after: string) => toLocalInput(before) !== toLocalInput(after);
    update.mutate({
      changes: {
        name: values.name,
        // Empty strings, not null: in a patch null means "leave as it is".
        description: values.description ?? "",
        rules: values.rules ?? "",
        ...(moved(current.starts_at, values.starts_at) ? { starts_at: values.starts_at } : {}),
        ...(moved(current.ends_at, values.ends_at) ? { ends_at: values.ends_at } : {}),
        allowed_tests: values.allowed_tests,
        ...(values.region ? { region: values.region } : { clear_region: true }),
      },
      message: "Changes saved.",
    });
  }

  return (
    <div className="space-y-5">
      <PageHeader
        back={back}
        title={
          <span className="flex flex-wrap items-center gap-3">
            {current.name}
            <SessionStatusBadge status={current.status} />
          </span>
        }
        description={sessionStatusExplanation(current)}
        actions={
          isAdmin &&
          !editing && (
            <>
              <button
                type="button"
                disabled={busy}
                onClick={() =>
                  update.mutate({
                    changes: { enabled: !current.enabled },
                    message: current.enabled ? "Session disabled." : "Session enabled.",
                  })
                }
                className="rounded border border-slate-300 bg-white px-3 py-2 text-sm hover:bg-slate-100 disabled:opacity-50"
              >
                {current.enabled ? "Disable" : "Enable"}
              </button>
              {current.status === "active" && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    if (window.confirm(`End "${current.name}" now? Athletes can no longer record for it.`)) {
                      end.mutate();
                    }
                  }}
                  className="rounded border border-slate-300 bg-white px-3 py-2 text-sm hover:bg-slate-100 disabled:opacity-50"
                >
                  End now
                </button>
              )}
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  setNotice(null);
                  setEditing(true);
                }}
                className="rounded border border-slate-300 bg-white px-3 py-2 text-sm hover:bg-slate-100 disabled:opacity-50"
              >
                Edit
              </button>
              {/* A session with submissions is part of the official record. */}
              {current.submission_count === 0 && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    if (window.confirm(`Delete "${current.name}"?`)) remove.mutate();
                  }}
                  className="rounded border border-red-300 bg-white px-3 py-2 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50"
                >
                  Delete
                </button>
              )}
            </>
          )
        }
      />

      {notice && <SuccessNotice>{notice}</SuccessNotice>}
      {actionError && !editing && <ErrorState title="That change was not saved" error={actionError} />}

      {editing ? (
        <SessionForm
          initial={current}
          label="Edit session"
          submitLabel="Save changes"
          pending={update.isPending}
          error={update.error}
          onSubmit={applyEdit}
          onCancel={() => {
            update.reset();
            setEditing(false);
          }}
        />
      ) : (
        <dl className="grid gap-x-6 gap-y-3 rounded border border-slate-200 bg-white p-4 text-sm sm:grid-cols-2">
          <Detail label="Opens">{formatDateTime(current.starts_at)}</Detail>
          <Detail label="Closes">{formatDateTime(current.ends_at)}</Detail>
          <Detail label="Tests">
            <ul className="space-y-0.5">
              {current.allowed_tests.map((code) => (
                <li key={code}>{catalog.nameOf(code)}</li>
              ))}
            </ul>
          </Detail>
          <Detail label="Region">{current.region ?? "All regions"}</Detail>
          <Detail label="Switched on">{current.enabled ? "Yes" : "No"}</Detail>
          <Detail label="Submissions">{current.submission_count}</Detail>
          <Detail label="Description">{current.description ?? "—"}</Detail>
          <Detail label="Rules shown to athletes">
            <span className="whitespace-pre-line">{current.rules ?? "—"}</span>
          </Detail>
          <Detail label="Created">{formatDateTime(current.created_at)}</Detail>
          <Detail label="Last changed">{formatDateTime(current.updated_at)}</Detail>
        </dl>
      )}

      <section className="space-y-3">
        <div className="flex items-end justify-between gap-3">
          <h2 className="text-lg font-semibold">Submissions in this session</h2>
          {(submissions.data?.total ?? 0) > RECENT && (
            <Link to={`${paths.submissions}?session_id=${current.id}`} className="text-sm text-blue-700 hover:underline">
              View all {submissions.data?.total}
            </Link>
          )}
        </div>
        {submissions.isLoading && <LoadingState label="Loading submissions…" />}
        {submissions.isError && (
          <ErrorState
            title="Could not load this session's submissions"
            error={submissions.error}
            onRetry={() => submissions.refetch()}
          />
        )}
        {submissions.data?.items.length === 0 && (
          <EmptyState title="No submissions yet.">
            Athletes&apos; official tests for this session appear here as they arrive.
          </EmptyState>
        )}
        {submissions.data && submissions.data.items.length > 0 && (
          <SubmissionTable items={submissions.data.items} nameOf={catalog.nameOf} showSession={false} />
        )}
      </section>
    </div>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-slate-500">{label}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  );
}
