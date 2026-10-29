import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type AnalyticsFilters } from "../api/client";
import type { Analytics } from "../api/types";
import { session } from "../test/fixtures";
import { makeApi, renderWithProviders } from "../test/render";
import { AnalyticsPage, bucketLabel, formatSeconds, periodStart } from "./AnalyticsPage";

function analytics(overrides: Partial<Analytics> = {}): Analytics {
  return {
    filters: { submitted_from: null, submitted_to: null, region: null, test_type: null, session_id: null },
    generated_at: "2026-10-13T06:00:00Z",
    totals: {
      athletes: 12,
      active_athletes: 9,
      submissions: 20,
      verified: 4,
      approved: 7,
      flagged: 3,
      rejected: 2,
      awaiting_verification: 4,
      resubmission_requested: 0,
    },
    by_status: { pending_sync: 0, uploaded: 1, processing: 3, verified: 4, flagged: 3, approved: 7, rejected: 2 },
    tests: [
      { test_type: "SIT_UPS", name: "Sit-ups", unit: "reps", higher_is_better: true, submissions: 14, athletes: 8, counted: 8, average_score: 31.5 },
      { test_type: "VERTICAL_JUMP", name: "Vertical Jump", unit: "cm", higher_is_better: true, submissions: 6, athletes: 5, counted: 3, average_score: 41.2 },
    ],
    regions: [
      { region: "Kerala", athletes: 6, submissions: 15, counted: 9, flagged: 2, rejected: 1 },
      { region: "Karnataka", athletes: 3, submissions: 5, counted: 2, flagged: 1, rejected: 1 },
    ],
    sessions: [{ session_id: "s1", name: "District trials", submissions: 11, athletes: 7, counted: 6 }],
    outside_sessions: 9,
    uploads: { started: 25, completed: 20, in_progress: 1, abandoned: 4, rejected_by_verification: 2 },
    verification_time: {
      measured: 15,
      turnaround_p50_seconds: 95,
      turnaround_p95_seconds: 260,
      processing_p50_seconds: 40,
      processing_p95_seconds: 70,
      sla_seconds: 300,
      within_sla: 15,
    },
    bucket: "day",
    participation: [
      { start: "2026-10-11", submissions: 5, counted: 2 },
      { start: "2026-10-12", submissions: 0, counted: 0 },
      { start: "2026-10-13", submissions: 15, counted: 9 },
    ],
    score_distribution: null,
    ...overrides,
  };
}

function renderPage(
  call: (filters: AnalyticsFilters) => Promise<Analytics> = async () => analytics(),
  role: "sai_admin" | "regional_reviewer" = "sai_admin",
) {
  const api = makeApi(role, {
    analytics: vi.fn(call),
    sessions: vi.fn(async () => [session()]),
  });
  renderWithProviders(<AnalyticsPage />, { api, route: "/admin/analytics", path: "/admin/analytics" });
  return api;
}

function lastFilters(api: ReturnType<typeof makeApi>): AnalyticsFilters {
  const calls = (api.analytics as ReturnType<typeof vi.fn>).mock.calls;
  return calls[calls.length - 1]![0];
}

describe("AnalyticsPage", () => {
  it("shows the server's totals as they were sent", async () => {
    renderPage();

    const totals = within(await screen.findByRole("region", { name: "Totals" }));
    expect(totals.getByText("Registered athletes").previousSibling).toHaveTextContent("12");
    expect(totals.getByText("Submissions").previousSibling).toHaveTextContent("20");
    expect(totals.getByText("Approved").previousSibling).toHaveTextContent("7");
    expect(totals.getByText("Flagged").previousSibling).toHaveTextContent("3");
  });

  it("draws every chart from the API values, with the numbers behind them", async () => {
    renderPage();

    const regions = within(await screen.findByRole("list", { name: "Submissions by region" }));
    expect(regions.getByText("Kerala").closest("li")).toHaveTextContent("15");
    expect(regions.getByText("Karnataka").closest("li")).toHaveTextContent("5");

    const status = within(screen.getByRole("list", { name: "Submissions by status" }));
    expect(status.getByText("Flagged").closest("li")).toHaveAttribute("title", "Flagged: 3 submissions");

    const time = within(screen.getByRole("list", { name: "Submissions over time" }));
    expect(time.getAllByRole("listitem")).toHaveLength(3);
    expect(time.getAllByRole("listitem")[2]).toHaveAttribute("title", "13 Oct: 15 submissions · 9 counted");

    const sessions = within(screen.getByRole("list", { name: "Submissions by session" }));
    expect(sessions.getByText("District trials").closest("li")).toHaveTextContent("11");
    expect(screen.getByText("9 submitted outside any session.")).toBeInTheDocument();

    // The averages table, in each test's own unit.
    expect(screen.getByRole("cell", { name: "41.2 cm" })).toBeInTheDocument();
    // Verification time and upload failures.
    expect(screen.getByText("1 min 35 s", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("Abandoned").nextSibling).toHaveTextContent("4");
  });

  it("asks for a test before drawing a score distribution", async () => {
    const api = renderPage(async (filters) =>
      analytics(
        filters.testType
          ? { score_distribution: [{ low: 10, high: 14, count: 3 }, { low: 15, high: 19, count: 5 }] }
          : {},
      ),
    );

    expect(await screen.findByText(/Choose a test to see how its counted scores are spread/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Test"), { target: { value: "SIT_UPS" } });

    const bins = within(await screen.findByRole("list", { name: "Counted results by score" }));
    expect(bins.getAllByRole("listitem")[1]).toHaveAttribute("title", "15–19: 5 results");
    expect(lastFilters(api).testType).toBe("SIT_UPS");
  });

  it("sends every filter to the server", async () => {
    const api = renderPage();
    await screen.findByRole("region", { name: "Totals" });

    expect(lastFilters(api).submittedFrom).toBeDefined();
    fireEvent.change(screen.getByLabelText("Period"), { target: { value: "all" } });
    await screen.findByRole("option", { name: "District trials" });
    fireEvent.change(screen.getByLabelText("Session"), { target: { value: "s1" } });
    fireEvent.change(screen.getByLabelText("Region"), { target: { value: "Kerala" } });

    await waitFor(() =>
      expect(lastFilters(api)).toEqual({
        submittedFrom: undefined,
        testType: undefined,
        sessionId: "s1",
        region: "Kerala",
      }),
    );
  });

  it("offers a reviewer no region filter: the server scopes them", async () => {
    renderPage(undefined, "regional_reviewer");
    await screen.findByRole("region", { name: "Totals" });

    expect(screen.queryByLabelText("Region")).not.toBeInTheDocument();
    expect(screen.getByText(/Kerala only/)).toBeInTheDocument();
  });

  it("says so when nothing matches, instead of drawing empty charts", async () => {
    const empty = analytics({
      totals: { ...analytics().totals, submissions: 0, active_athletes: 0 },
      regions: [],
      tests: [],
      participation: [],
    });
    renderPage(async () => empty);

    expect(await screen.findByText("No submissions match these filters.")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Submissions by region" })).not.toBeInTheDocument();
  });

  it("explains a failure and retries", async () => {
    let calls = 0;
    const api = renderPage(async () => {
      calls += 1;
      if (calls === 1) throw new ApiError(503, "down");
      return analytics();
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load analytics");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("region", { name: "Totals" })).toBeInTheDocument();
    expect(api.analytics).toHaveBeenCalledTimes(2);
  });
});

describe("analytics helpers", () => {
  it("starts a period at local midnight so the query is stable", () => {
    const start = new Date(periodStart(7, new Date(2026, 9, 13, 15, 30))!);
    expect([start.getFullYear(), start.getMonth(), start.getDate(), start.getHours()]).toEqual([2026, 9, 7, 0]);
    expect(periodStart(null)).toBeUndefined();
  });

  it("formats durations and buckets", () => {
    expect(formatSeconds(null)).toBe("—");
    expect(formatSeconds(42)).toBe("42 s");
    expect(formatSeconds(120)).toBe("2 min");
    expect(bucketLabel("2026-10-06", "week")).toBe("w/c 6 Oct");
    expect(bucketLabel("2026-10-01", "month")).toBe("Oct 2026");
  });
});
