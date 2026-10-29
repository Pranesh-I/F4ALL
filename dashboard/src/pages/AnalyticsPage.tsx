import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import type { AnalyticsFilters } from "../api/client";
import { useTestCatalog } from "../api/hooks";
import type { Analytics, ResultStatus } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { BarList, ColumnChart } from "../components/Charts";
import { PageHeader } from "../components/PageHeader";
import { Fact, Panel } from "../components/Panel";
import { EmptyState, ErrorState, LoadingState } from "../components/StateViews";
import { REGIONS, STATUS_LABELS, formatDateTime, formatScore } from "../lib/format";

/** Time windows offered; the server buckets the chart by day, week or month to suit. */
export const PERIODS = [
  { key: "7", label: "Last 7 days", days: 7 },
  { key: "30", label: "Last 30 days", days: 30 },
  { key: "90", label: "Last 90 days", days: 90 },
  { key: "365", label: "Last 12 months", days: 365 },
  { key: "all", label: "All time", days: null },
] as const;

/** Statuses in lifecycle order, each in the material it is written in elsewhere. */
const STATUS_TONES: [ResultStatus, string][] = [
  ["uploaded", "bg-line"],
  ["processing", "bg-line"],
  ["verified", "bg-ink"],
  ["flagged", "bg-redpen"],
  ["approved", "bg-stamp"],
  ["rejected", "bg-redpen-dark"],
  ["pending_sync", "bg-marigold"],
];

/** Midnight, `days` ago: a stable start so the query is not re-sent every render. */
export function periodStart(days: number | null, now: Date = new Date()): string | undefined {
  if (days === null) return undefined;
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days + 1);
  return start.toISOString();
}

export function formatSeconds(seconds: number | null): string {
  if (seconds === null) return "—";
  if (seconds < 60) return `${Math.round(seconds)} s`;
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  return rest ? `${minutes} min ${rest} s` : `${minutes} min`;
}

export function bucketLabel(start: string, bucket: Analytics["bucket"]): string {
  const day = new Date(`${start}T00:00:00`);
  if (bucket === "month") return day.toLocaleDateString("en-IN", { month: "short", year: "numeric" });
  const text = day.toLocaleDateString("en-IN", { day: "numeric", month: "short" });
  return bucket === "week" ? `w/c ${text}` : text;
}

export function AnalyticsPage() {
  const { api, official } = useAuth();
  const isAdmin = official?.role === "sai_admin";
  const { tests } = useTestCatalog();
  const sessions = useQuery({ queryKey: ["sessions", "all"], queryFn: () => api.sessions() });

  const [period, setPeriod] = useState<string>("30");
  const [testType, setTestType] = useState("");
  const [sessionId, setSessionId] = useState("");
  const [region, setRegion] = useState("");

  const filters: AnalyticsFilters = useMemo(
    () => ({
      submittedFrom: periodStart(PERIODS.find((p) => p.key === period)?.days ?? null),
      testType: testType || undefined,
      sessionId: sessionId || undefined,
      region: region || undefined,
    }),
    [period, testType, sessionId, region],
  );

  const analytics = useQuery({ queryKey: ["analytics", filters], queryFn: () => api.analytics(filters) });
  const data = analytics.data;
  const select = "rounded-xs border border-line bg-paper px-3 py-2 text-sm";

  return (
    <div className="space-y-6">
      <PageHeader
        title="Analytics"
        description={
          isAdmin
            ? "Every region. Counted means verified or approved; averages use only those results."
            : `${official?.region ?? "Your region"} only. Counted means verified or approved; averages use only those results.`
        }
      />

      <div className="flex flex-wrap gap-3" role="group" aria-label="Filters">
        <select aria-label="Period" value={period} onChange={(e) => setPeriod(e.target.value)} className={select}>
          {PERIODS.map((option) => (
            <option key={option.key} value={option.key}>
              {option.label}
            </option>
          ))}
        </select>
        <select aria-label="Test" value={testType} onChange={(e) => setTestType(e.target.value)} className={select}>
          <option value="">All tests</option>
          {(tests ?? []).map((test) => (
            <option key={test.code} value={test.code}>
              {test.name}
            </option>
          ))}
        </select>
        <select aria-label="Session" value={sessionId} onChange={(e) => setSessionId(e.target.value)} className={select}>
          <option value="">All sessions</option>
          {(sessions.data ?? []).map((session) => (
            <option key={session.id} value={session.id}>
              {session.name}
            </option>
          ))}
        </select>
        {isAdmin && (
          <select aria-label="Region" value={region} onChange={(e) => setRegion(e.target.value)} className={select}>
            <option value="">All regions</option>
            {REGIONS.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        )}
      </div>

      {analytics.isLoading && <LoadingState label="Counting…" />}
      {analytics.isError && (
        <ErrorState title="Could not load analytics" error={analytics.error} onRetry={() => analytics.refetch()} />
      )}
      {data && <AnalyticsBody data={data} />}
    </div>
  );
}

function AnalyticsBody({ data }: { data: Analytics }) {
  const { totals } = data;
  const tiles: { label: string; value: number; tone?: string }[] = [
    { label: "Registered athletes", value: totals.athletes },
    { label: "Athletes who submitted", value: totals.active_athletes },
    { label: "Submissions", value: totals.submissions },
    { label: "Verified — awaiting approval", value: totals.verified },
    { label: "Approved", value: totals.approved, tone: "text-stamp" },
    { label: "Flagged", value: totals.flagged, tone: totals.flagged ? "text-redpen" : undefined },
    { label: "Rejected", value: totals.rejected },
    { label: "Awaiting verification", value: totals.awaiting_verification },
  ];

  return (
    <div className="space-y-6">
      <section aria-label="Totals" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {tiles.map((tile) => (
          <div key={tile.label} className="rounded-xs border border-ink bg-paper p-4">
            <div className={`numerals text-4xl leading-none ${tile.tone ?? "text-ink"}`}>{tile.value}</div>
            <div className="label-caps mt-2 text-xs text-pencil">{tile.label}</div>
          </div>
        ))}
      </section>

      {totals.submissions === 0 ? (
        <EmptyState title="No submissions match these filters.">
          Widen the period or clear a filter. Registered athletes are counted whatever the period.
        </EmptyState>
      ) : (
        <>
          <Panel title="Participation over time">
            <p className="mb-3 text-sm text-pencil">Submissions per {data.bucket}, India time.</p>
            <ColumnChart
              caption="Submissions over time"
              valueLabel="submissions"
              columns={data.participation.map((point) => ({
                key: point.start,
                label: bucketLabel(point.start, data.bucket),
                value: point.submissions,
                detail: `${point.counted} counted`,
              }))}
            />
          </Panel>

          <div className="grid gap-6 lg:grid-cols-2">
            <Panel title="Verification status">
              <BarList
                caption="Submissions by status"
                valueLabel="submissions"
                bars={STATUS_TONES.map(([status, tone]) => ({
                  key: status,
                  label: STATUS_LABELS[status],
                  value: data.by_status[status] ?? 0,
                  tone,
                }))}
              />
            </Panel>

            <Panel title="Regional distribution">
              <BarList
                caption="Submissions by region"
                valueLabel="submissions"
                bars={data.regions.map((row) => ({
                  key: row.region,
                  label: row.region,
                  value: row.submissions,
                  detail: `${row.athletes} athletes · ${row.counted} counted · ${row.flagged} flagged · ${row.rejected} rejected`,
                }))}
              />
            </Panel>
          </div>

          <Panel title="Test performance">
            <BarList
              caption="Submissions by test"
              valueLabel="submissions"
              bars={data.tests.map((test) => ({
                key: test.test_type,
                label: test.name,
                value: test.submissions,
                detail: `${test.counted} counted`,
              }))}
            />
            <div className="mt-4 overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="bg-ground text-left text-pencil">
                  <tr>
                    <th className="px-3 py-2">Test</th>
                    <th className="px-3 py-2 text-right">Submissions</th>
                    <th className="px-3 py-2 text-right">Athletes</th>
                    <th className="px-3 py-2 text-right">Counted</th>
                    <th className="px-3 py-2 text-right">Average (counted)</th>
                  </tr>
                </thead>
                <tbody>
                  {data.tests.map((test) => (
                    <tr key={test.test_type} className="border-t border-rule">
                      <td className="px-3 py-2">{test.name}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{test.submissions}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{test.athletes}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{test.counted}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{formatScore(test.average_score, test.unit)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>

          <Panel title="Score distribution">
            {data.score_distribution === null ? (
              <p className="text-sm text-pencil">
                Choose a test to see how its counted scores are spread. Tests are measured in different units, so
                they cannot share one chart.
              </p>
            ) : data.score_distribution.length === 0 ? (
              <p className="text-sm text-pencil">No counted results for this test yet.</p>
            ) : (
              <ColumnChart
                caption="Counted results by score"
                valueLabel="results"
                columns={data.score_distribution.map((bin) => ({
                  key: `${bin.low}`,
                  label: bin.low === bin.high ? `${bin.low}` : `${bin.low}–${bin.high}`,
                  value: bin.count,
                }))}
              />
            )}
          </Panel>

          <div className="grid gap-6 lg:grid-cols-2">
            <Panel title="Session participation">
              {data.sessions.length === 0 ? (
                <p className="text-sm text-pencil">No submissions were made in an assessment session.</p>
              ) : (
                <BarList
                  caption="Submissions by session"
                  valueLabel="submissions"
                  bars={data.sessions.map((session) => ({
                    key: session.session_id,
                    label: session.name,
                    value: session.submissions,
                    detail: `${session.athletes} athletes · ${session.counted} counted`,
                  }))}
                />
              )}
              <p className="mt-2 text-sm text-pencil">{data.outside_sessions} submitted outside any session.</p>
            </Panel>

            <Panel title="Uploads and verification">
              <dl className="grid grid-cols-2 gap-3 text-sm">
                <Fact label="Uploads started">{data.uploads.started}</Fact>
                <Fact label="Completed">{data.uploads.completed}</Fact>
                <Fact label="Still uploading">{data.uploads.in_progress}</Fact>
                <Fact label="Abandoned">
                  <span className={data.uploads.abandoned ? "font-medium text-redpen" : undefined}>
                    {data.uploads.abandoned}
                  </span>
                </Fact>
                <Fact label="Refused as unscorable">{data.uploads.rejected_by_verification}</Fact>
                <Fact label="Verified in time">
                  {data.verification_time.within_sla} of {data.verification_time.measured}
                </Fact>
                <Fact label="Wait for a verdict (median / 95%)">
                  {formatSeconds(data.verification_time.turnaround_p50_seconds)} /{" "}
                  {formatSeconds(data.verification_time.turnaround_p95_seconds)}
                </Fact>
                <Fact label="Worker time (median / 95%)">
                  {formatSeconds(data.verification_time.processing_p50_seconds)} /{" "}
                  {formatSeconds(data.verification_time.processing_p95_seconds)}
                </Fact>
              </dl>
              <p className="mt-3 text-xs text-pencil">
                Upload counts ignore the session filter: an upload belongs to a session only once it is submitted.
                Target: {formatSeconds(data.verification_time.sla_seconds)}.
              </p>
            </Panel>
          </div>
        </>
      )}

      <p className="text-xs text-pencil">Counted by the server at {formatDateTime(data.generated_at)}.</p>
    </div>
  );
}
