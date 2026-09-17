import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { SeverityBadge, StatusBadge } from "../components/Badges";
import { useAuth } from "../auth/AuthContext";
import { REGIONS, TEST_NAMES, formatDateTime, formatScore, testName } from "../lib/format";

const PAGE_SIZE = 25;

const TABS = [
  { key: "", label: "Needs review", hint: "Flagged and still processing" },
  { key: "verified", label: "Awaiting approval", hint: "Server agreed with the phone" },
  { key: "approved,rejected,pending_sync", label: "Decided", hint: "Already acted on" },
] as const;

export function QueuePage() {
  const { api, official } = useAuth();
  const [params, setParams] = useSearchParams();

  const status = params.get("status") ?? "";
  const testType = params.get("test_type") ?? "";
  const region = params.get("region") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);

  const update = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    if (!("page" in changes)) next.delete("page");
    setParams(next);
  };

  const queue = useQuery({
    queryKey: ["reviews", status, testType, region, page],
    queryFn: () =>
      api.reviewQueue({
        status: status || undefined,
        testType: testType || undefined,
        region: region || undefined,
        limit: PAGE_SIZE,
        offset: page * PAGE_SIZE,
      }),
    placeholderData: keepPreviousData,
  });

  const stats = useQuery({ queryKey: ["stats"], queryFn: () => api.stats() });

  const total = queue.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold">Review queue</h1>
        {stats.data && (
          <div className="flex flex-wrap gap-2 text-sm">
            <Stat label="Flagged" value={stats.data.by_status.flagged} />
            <Stat label="High severity" value={stats.data.flagged_high_severity} tone="red" />
            <Stat label="Awaiting approval" value={stats.data.by_status.verified} />
            <Stat
              label="Over SLA"
              value={stats.data.breaching_sla}
              tone={stats.data.breaching_sla > 0 ? "red" : undefined}
            />
          </div>
        )}
      </div>

      <div className="flex flex-wrap gap-2 border-b border-slate-200">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            title={tab.hint}
            onClick={() => update({ status: tab.key })}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
              status === tab.key
                ? "border-slate-900 text-slate-900"
                : "border-transparent text-slate-500 hover:text-slate-800"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="flex flex-wrap gap-3">
        <select
          aria-label="Test"
          value={testType}
          onChange={(event) => update({ test_type: event.target.value })}
          className="rounded border border-slate-300 bg-white px-3 py-2 text-sm"
        >
          <option value="">All tests</option>
          {Object.entries(TEST_NAMES).map(([code, name]) => (
            <option key={code} value={code}>
              {name}
            </option>
          ))}
        </select>

        {official?.role === "sai_admin" && (
          <select
            aria-label="Region"
            value={region}
            onChange={(event) => update({ region: event.target.value })}
            className="rounded border border-slate-300 bg-white px-3 py-2 text-sm"
          >
            <option value="">All regions</option>
            {REGIONS.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        )}
      </div>

      {queue.isError && (
        <p role="alert" className="text-red-700">
          Could not load the queue: {(queue.error as Error).message}
        </p>
      )}

      <div className="overflow-x-auto rounded border border-slate-200 bg-white">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-left text-slate-600">
            <tr>
              <th className="px-3 py-2">Severity</th>
              <th className="px-3 py-2">Athlete</th>
              <th className="px-3 py-2">Test</th>
              <th className="px-3 py-2 text-right">Phone</th>
              <th className="px-3 py-2 text-right">Server</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2">Submitted</th>
            </tr>
          </thead>
          <tbody>
            {queue.isLoading && (
              <tr>
                <td colSpan={7} className="px-3 py-6 text-center text-slate-500">
                  Loading…
                </td>
              </tr>
            )}
            {queue.data?.items.length === 0 && (
              <tr>
                <td colSpan={7} className="px-3 py-6 text-center text-slate-500">
                  Nothing here.
                </td>
              </tr>
            )}
            {queue.data?.items.map((item) => (
              <tr key={item.result_id} className="border-t border-slate-100 hover:bg-slate-50">
                <td className="px-3 py-2">
                  <SeverityBadge severity={item.max_severity} />
                </td>
                <td className="px-3 py-2">
                  <Link to={`/reviews/${item.result_id}`} className="font-medium text-blue-700 hover:underline">
                    {item.athlete_name}
                  </Link>
                  <div className="text-xs text-slate-500">{item.region}</div>
                </td>
                <td className="px-3 py-2">
                  {testName(item.test_type)}
                  <span className="text-xs text-slate-500"> · attempt {item.attempt_number}</span>
                </td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {formatScore(item.provisional_score, item.unit)}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {formatScore(item.server_score, item.unit)}
                </td>
                <td className="px-3 py-2">
                  <StatusBadge status={item.status} />
                </td>
                <td className="px-3 py-2 text-slate-600">{formatDateTime(item.created_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between text-sm text-slate-600">
        <span>{total} result(s)</span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => update({ page: String(page - 1) })}
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
            onClick={() => update({ page: String(page + 1) })}
            className="rounded border border-slate-300 px-3 py-1 disabled:opacity-40"
          >
            Next
          </button>
        </div>
      </div>
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
