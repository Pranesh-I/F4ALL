import { type FormEvent, useState } from "react";
import { useTestCatalog } from "../api/hooks";
import type { AssessmentSession, NewAssessmentSession } from "../api/types";
import { REGIONS, toIso, toLocalInput } from "../lib/format";
import { describeError, ErrorState, LoadingState } from "./StateViews";

/**
 * Create or edit a session. The checks here mirror the server's so an admin
 * hears about a mistake before a round trip; the server repeats every one.
 */
export function SessionForm({
  initial,
  label,
  submitLabel,
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  /** The session being edited; absent when creating. */
  initial?: AssessmentSession;
  label: string;
  submitLabel: string;
  pending: boolean;
  error: unknown;
  onSubmit: (values: NewAssessmentSession) => void;
  onCancel: () => void;
}) {
  const catalog = useTestCatalog();
  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [rules, setRules] = useState(initial?.rules ?? "");
  const [startsAt, setStartsAt] = useState(initial ? toLocalInput(initial.starts_at) : "");
  const [endsAt, setEndsAt] = useState(initial ? toLocalInput(initial.ends_at) : "");
  const [tests, setTests] = useState<string[]>(initial?.allowed_tests ?? []);
  const [region, setRegion] = useState(initial?.region ?? "");
  const [enabled, setEnabled] = useState(initial?.enabled ?? false);
  // Complaints wait until someone tries to save; a blank form is not an error.
  const [attempted, setAttempted] = useState(false);

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
  const message = attempted && problem ? problem : error ? describeError(error) : null;

  function submit(event: FormEvent) {
    event.preventDefault();
    setAttempted(true);
    if (problem) return;
    onSubmit({
      name: name.trim(),
      description: description.trim() || null,
      rules: rules.trim() || null,
      starts_at: toIso(startsAt),
      ends_at: toIso(endsAt),
      enabled,
      // In the catalog's order, whatever order they were ticked in.
      allowed_tests: (catalog.tests ?? []).map((test) => test.code).filter((code) => tests.includes(code)),
      region: region || null,
    });
  }

  const input = "mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm";

  return (
    <form onSubmit={submit} className="space-y-4 rounded border border-slate-200 bg-white p-4" aria-label={label}>
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
        <input className={input} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} />
      </label>
      <label className="block text-sm">
        Rules shown to athletes
        <textarea className={input} rows={3} value={rules} onChange={(e) => setRules(e.target.value)} maxLength={4000} />
      </label>

      <fieldset>
        <legend className="text-sm">Tests</legend>
        {catalog.isLoading && <LoadingState label="Loading the tests the server can assess…" />}
        {catalog.isError && (
          <ErrorState title="Could not load the list of tests" error={catalog.error} onRetry={() => catalog.refetch()} />
        )}
        {catalog.tests && (
          <div className="mt-1 flex flex-wrap gap-3">
            {catalog.tests.map((test) => (
              <label key={test.code} className="flex items-center gap-1 text-sm">
                <input
                  type="checkbox"
                  checked={tests.includes(test.code)}
                  onChange={(e) =>
                    setTests((current) =>
                      e.target.checked ? [...current, test.code] : current.filter((item) => item !== test.code),
                    )
                  }
                />
                {catalog.nameOf(test.code)}
                <span className="text-xs text-slate-500">({test.unit})</span>
              </label>
            ))}
          </div>
        )}
      </fieldset>

      {!initial && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          Enable now (athletes see it once it opens)
        </label>
      )}

      {message && (
        <p role="alert" className="text-sm text-red-700">
          {message}
        </p>
      )}

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={pending || !catalog.tests}
          className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          {pending ? "Saving…" : submitLabel}
        </button>
        <button type="button" onClick={onCancel} className="rounded border border-slate-300 px-4 py-2 text-sm">
          Cancel
        </button>
      </div>
    </form>
  );
}
