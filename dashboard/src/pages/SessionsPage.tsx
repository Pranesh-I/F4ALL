import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { useAuth } from "../auth/AuthContext";
import type { AssessmentSession, NewAssessmentSession, SessionStatus } from "../api/types";
import { REGIONS, TEST_NAMES, formatDateTime } from "../lib/format";

const STATUS_STYLE: Record<SessionStatus, string> = {
  active: "bg-green-100 text-green-800",
  scheduled: "bg-blue-100 text-blue-800",
  ended: "bg-slate-200 text-slate-700",
  disabled: "bg-amber-100 text-amber-800",
};

const STATUS_LABEL: Record<SessionStatus, string> = {
  active: "Active",
  scheduled: "Scheduled",
  ended: "Ended",
  disabled: "Disabled",
};

/** A `datetime-local` value, in the browser's time zone, sent as UTC. */
export function toIso(local: string): string {
  return new Date(local).toISOString();
}

/**
 * Assessment sessions: the windows in which athletes may submit official
 * tests. Any official can see them; only an SAI admin can create or change
 * one, since a session decides who may submit official results.
 */
export function SessionsPage() {
  const { api, official } = useAuth();
  const isAdmin = official?.role === "sai_admin";
  const queryClient = useQueryClient();

  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => api.sessions() });
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

  const [creating, setCreating] = useState(false);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Assessment sessions</h1>
          <p className="text-sm text-slate-600">
            Athletes see a session only while it is enabled and open, and may submit each test in it
            once.
          </p>
        </div>
        {isAdmin && !creating && (
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700"
          >
            New session
          </button>
        )}
      </div>

      {creating && (
        <SessionForm
          onCancel={() => setCreating(false)}
          onCreated={() => {
            setCreating(false);
            refresh();
          }}
        />
      )}

      {sessions.isError && (
        <p role="alert" className="text-red-700">
          Could not load sessions: {(sessions.error as Error).message}
        </p>
      )}
      {(toggle.isError || remove.isError) && (
        <p role="alert" className="text-red-700">
          {((toggle.error ?? remove.error) as Error).message}
        </p>
      )}

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
            {sessions.isLoading && (
              <tr>
                <td colSpan={7} className="px-3 py-6 text-center text-slate-500">
                  Loading…
                </td>
              </tr>
            )}
            {sessions.data?.length === 0 && (
              <tr>
                <td colSpan={7} className="px-3 py-6 text-center text-slate-500">
                  No sessions yet.
                </td>
              </tr>
            )}
            {sessions.data?.map((session) => (
              <tr key={session.id} className="border-t border-slate-100 align-top">
                <td className="px-3 py-2">
                  <div className="font-medium">{session.name}</div>
                  {session.description && (
                    <div className="text-slate-600">{session.description}</div>
                  )}
                </td>
                <td className="px-3 py-2 text-slate-700">
                  {formatDateTime(session.starts_at)}
                  <br />
                  to {formatDateTime(session.ends_at)}
                </td>
                <td className="px-3 py-2">
                  {session.allowed_tests.map((code) => TEST_NAMES[code] ?? code).join(", ")}
                </td>
                <td className="px-3 py-2">{session.region ?? "All regions"}</td>
                <td className="px-3 py-2">
                  <span className={`rounded px-2 py-0.5 text-xs font-medium ${STATUS_STYLE[session.status]}`}>
                    {STATUS_LABEL[session.status]}
                  </span>
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
    </div>
  );
}

function SessionForm({ onCancel, onCreated }: { onCancel: () => void; onCreated: () => void }) {
  const { api } = useAuth();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [rules, setRules] = useState("");
  const [startsAt, setStartsAt] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [tests, setTests] = useState<string[]>([]);
  const [region, setRegion] = useState("");
  const [enabled, setEnabled] = useState(false);
  // Complaints wait until someone tries to create; a blank form is not an error.
  const [attempted, setAttempted] = useState(false);

  const create = useMutation({
    mutationFn: (session: NewAssessmentSession) => api.createSession(session),
    onSuccess: onCreated,
  });

  const problem =
    !name.trim()
      ? "Give the session a name."
      : !startsAt || !endsAt
        ? "Set when the session opens and closes."
        : new Date(endsAt) <= new Date(startsAt)
          ? "The session must close after it opens."
          : tests.length === 0
            ? "Choose at least one test."
            : null;

  function submit(event: FormEvent) {
    event.preventDefault();
    setAttempted(true);
    if (problem) return;
    create.mutate({
      name: name.trim(),
      description: description.trim() || null,
      rules: rules.trim() || null,
      starts_at: toIso(startsAt),
      ends_at: toIso(endsAt),
      enabled,
      allowed_tests: tests,
      region: region || null,
    });
  }

  const input = "w-full rounded border border-slate-300 px-3 py-2 text-sm";

  return (
    <form onSubmit={submit} className="space-y-4 rounded border border-slate-200 bg-white p-4" aria-label="New session">
      <div className="grid gap-4 md:grid-cols-2">
        <label className="block text-sm">
          Name
          <input className={input} value={name} onChange={(e) => setName(e.target.value)} maxLength={150} />
        </label>
        <label className="block text-sm">
          Region
          <select className={input} value={region} onChange={(e) => setRegion(e.target.value)}>
            <option value="">All regions</option>
            {REGIONS.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
        <label className="block text-sm">
          Opens
          <input type="datetime-local" className={input} value={startsAt} onChange={(e) => setStartsAt(e.target.value)} />
        </label>
        <label className="block text-sm">
          Closes
          <input type="datetime-local" className={input} value={endsAt} onChange={(e) => setEndsAt(e.target.value)} />
        </label>
      </div>

      <label className="block text-sm">
        Description
        <input className={input} value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <label className="block text-sm">
        Rules shown to athletes
        <textarea className={input} rows={3} value={rules} onChange={(e) => setRules(e.target.value)} />
      </label>

      <fieldset>
        <legend className="text-sm">Tests</legend>
        <div className="mt-1 flex flex-wrap gap-3">
          {Object.entries(TEST_NAMES).map(([code, label]) => (
            <label key={code} className="flex items-center gap-1 text-sm">
              <input
                type="checkbox"
                checked={tests.includes(code)}
                onChange={(e) =>
                  setTests((current) =>
                    e.target.checked ? [...current, code] : current.filter((item) => item !== code),
                  )
                }
              />
              {label}
            </label>
          ))}
        </div>
      </fieldset>

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        Enable now (athletes see it once it opens)
      </label>

      {((attempted && problem) || create.isError) && (
        <p role="alert" className="text-sm text-red-700">
          {create.isError ? (create.error as Error).message : problem}
        </p>
      )}

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={create.isPending}
          className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          Create session
        </button>
        <button type="button" onClick={onCancel} className="rounded border border-slate-300 px-4 py-2 text-sm">
          Cancel
        </button>
      </div>
    </form>
  );
}
