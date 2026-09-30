import { useQuery } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router-dom";
import type { SubmissionFilters as ApiFilters } from "../api/client";
import { useTestCatalog } from "../api/hooks";
import { useAuth } from "../auth/AuthContext";
import { REGIONS, SUBMISSION_STATUS_FILTERS } from "../lib/format";

/** URL parameter for each filter, so a filtered view can be bookmarked or shared. */
const KEYS = ["status", "review_status", "test_type", "session_id", "region", "athlete", "flags", "from", "to"] as const;
type Key = (typeof KEYS)[number];

const FLAG_CHOICES = [
  { value: "", label: "Any integrity state" },
  { value: "open", label: "Has open flags" },
  { value: "high", label: "High-severity flags" },
  { value: "medium", label: "Medium or higher" },
  { value: "none", label: "No open flags" },
] as const;

/** Local midnight of a `YYYY-MM-DD` day, as the instant the API compares against. */
function dayStart(day: string, offsetDays = 0): string {
  const date = new Date(`${day}T00:00`);
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString();
}

/** Reads the filters from the URL and turns them into what the API takes. */
export function useListFilters() {
  const [params, setParams] = useSearchParams();
  const values = Object.fromEntries(KEYS.map((key) => [key, params.get(key) ?? ""])) as Record<Key, string>;
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);

  const update = (changes: Partial<Record<Key | "page", string>>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    // Any change of filter starts again at the first page.
    if (!("page" in changes)) next.delete("page");
    setParams(next);
  };

  const clear = (keep: Key[] = []) => {
    const next = new URLSearchParams();
    for (const key of keep) if (values[key]) next.set(key, values[key]);
    setParams(next);
  };

  const api: ApiFilters = {
    status: values.status || undefined,
    reviewStatus: values.review_status || undefined,
    testType: values.test_type || undefined,
    sessionId: values.session_id || undefined,
    region: values.region || undefined,
    athlete: values.athlete || undefined,
    flags: values.flags || undefined,
    // "To" is inclusive for the person choosing it: up to the end of that day.
    submittedFrom: values.from ? dayStart(values.from) : undefined,
    submittedTo: values.to ? dayStart(values.to, 1) : undefined,
  };

  const active = (["status", "test_type", "session_id", "region", "athlete", "flags", "from", "to"] as Key[]).some(
    (key) => values[key],
  );

  return { values, page, update, clear, api, active };
}

/**
 * The filter bar shared by the review queue and the submission list. Every
 * filter is applied by the server; nothing is filtered in the browser.
 */
export function SubmissionFilters({
  filters,
  showStatus = false,
  keepOnClear = [],
}: {
  filters: ReturnType<typeof useListFilters>;
  showStatus?: boolean;
  keepOnClear?: Key[];
}) {
  const { api, official } = useAuth();
  const catalog = useTestCatalog();
  const sessions = useQuery({ queryKey: ["sessions", ""], queryFn: () => api.sessions() });
  const { values, update, clear, active } = filters;
  const [athlete, setAthlete] = useState(values.athlete);
  useEffect(() => setAthlete(values.athlete), [values.athlete]);

  const select = "rounded border border-slate-300 bg-white px-3 py-2 text-sm";

  function searchAthlete(event?: FormEvent) {
    event?.preventDefault();
    if (athlete.trim() !== values.athlete) update({ athlete: athlete.trim() });
  }

  return (
    <div className="flex flex-wrap items-end gap-3">
      {showStatus && (
        <select aria-label="Status" value={values.status} onChange={(e) => update({ status: e.target.value })} className={select}>
          {SUBMISSION_STATUS_FILTERS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      )}

      <select aria-label="Test" value={values.test_type} onChange={(e) => update({ test_type: e.target.value })} className={select}>
        <option value="">All tests</option>
        {catalog.tests?.map((test) => (
          <option key={test.code} value={test.code}>
            {catalog.nameOf(test.code)}
          </option>
        ))}
      </select>

      <select aria-label="Session" value={values.session_id} onChange={(e) => update({ session_id: e.target.value })} className={select}>
        <option value="">All sessions</option>
        {sessions.data?.map((session) => (
          <option key={session.id} value={session.id}>
            {session.name}
          </option>
        ))}
      </select>

      <select aria-label="Integrity" value={values.flags} onChange={(e) => update({ flags: e.target.value })} className={select}>
        {FLAG_CHOICES.map((choice) => (
          <option key={choice.value} value={choice.value}>
            {choice.label}
          </option>
        ))}
      </select>

      {official?.role === "sai_admin" && (
        <select aria-label="Region" value={values.region} onChange={(e) => update({ region: e.target.value })} className={select}>
          <option value="">All regions</option>
          {REGIONS.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      )}

      <form onSubmit={searchAthlete} role="search" className="flex">
        <input
          type="search"
          aria-label="Athlete"
          placeholder="Athlete name or ID"
          value={athlete}
          onChange={(e) => setAthlete(e.target.value)}
          onBlur={() => searchAthlete()}
          maxLength={150}
          className="w-48 rounded border border-slate-300 bg-white px-3 py-2 text-sm"
        />
      </form>

      <label className="text-xs text-slate-600">
        Submitted from
        <input
          type="date"
          value={values.from}
          onChange={(e) => update({ from: e.target.value })}
          className="mt-0.5 block rounded border border-slate-300 bg-white px-2 py-1.5 text-sm"
        />
      </label>
      <label className="text-xs text-slate-600">
        Submitted to
        <input
          type="date"
          value={values.to}
          onChange={(e) => update({ to: e.target.value })}
          className="mt-0.5 block rounded border border-slate-300 bg-white px-2 py-1.5 text-sm"
        />
      </label>

      {active && (
        <button type="button" onClick={() => clear(keepOnClear)} className="rounded px-3 py-2 text-sm text-blue-700 hover:underline">
          Clear filters
        </button>
      )}
    </div>
  );
}
