import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type QueueFilters } from "../api/client";
import type { SubmissionPage } from "../api/types";
import { session, stats, submission } from "../test/fixtures";
import { makeApi, renderWithProviders } from "../test/render";
import { QueuePage } from "./QueuePage";

function renderPage(queue: (filters: QueueFilters) => Promise<SubmissionPage>, route = "/admin/reviews") {
  const api = makeApi("sai_admin", {
    reviewQueue: vi.fn(queue),
    stats: vi.fn(async () => stats()),
    sessions: vi.fn(async () => [session()]),
  });
  renderWithProviders(<QueuePage />, { api, route, path: "/admin/reviews" });
  return api;
}

const lastFilters = (api: ReturnType<typeof renderPage>) =>
  vi.mocked(api.reviewQueue).mock.calls.at(-1)![0] as QueueFilters;

describe("QueuePage", () => {
  it("shows the server's queue with verification, integrity and review state", async () => {
    renderPage(async () => ({ items: [submission({ open_flag_count: 3 })], total: 1 }));

    const row = within(await screen.findByRole("table")).getAllByRole("row")[1]!;
    expect(row).toHaveTextContent("Meera");
    expect(row).toHaveTextContent("Server flagged it for a human");
    expect(row).toHaveTextContent("high");
    expect(row).toHaveTextContent("(3 open)");
    expect(row).toHaveTextContent("Needs review");
    expect(row).toHaveTextContent("District trials");
    expect(within(row).getByRole("link", { name: /Submission 0f3c2a1b/ })).toHaveAttribute(
      "href",
      "/admin/reviews/0f3c2a1b-0000-4000-8000-000000000001",
    );
  });

  it("asks the server for work awaiting a decision by default, and other states by tab", async () => {
    const api = renderPage(async () => ({ items: [], total: 0 }));

    expect(await screen.findByText("Nothing is waiting for a decision.")).toBeInTheDocument();
    expect(lastFilters(api).reviewStatus).toBeUndefined();

    fireEvent.click(screen.getByRole("tab", { name: "Awaiting approval" }));
    await waitFor(() => expect(lastFilters(api).reviewStatus).toBe("awaiting_approval"));
    expect(await screen.findByText("Nothing is waiting for approval.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Decided" }));
    await waitFor(() => expect(lastFilters(api).reviewStatus).toBe("approved,rejected,resubmission_requested"));
  });

  it("filters on the server by session, test, integrity, athlete and date", async () => {
    const api = renderPage(async () => ({ items: [submission()], total: 1 }));
    await screen.findByText("Meera");

    await screen.findByRole("option", { name: "District trials" });
    fireEvent.change(screen.getByLabelText("Session"), { target: { value: "s1" } });
    await waitFor(() => expect(lastFilters(api).sessionId).toBe("s1"));

    await screen.findByRole("option", { name: "Push-ups" });
    fireEvent.change(screen.getByLabelText("Test"), { target: { value: "PUSH_UPS" } });
    fireEvent.change(screen.getByLabelText("Integrity"), { target: { value: "high" } });
    await waitFor(() => expect(lastFilters(api)).toMatchObject({ testType: "PUSH_UPS", flags: "high", sessionId: "s1" }));

    const search = screen.getByRole("searchbox", { name: "Athlete" });
    fireEvent.change(search, { target: { value: "meera" } });
    fireEvent.submit(search);
    await waitFor(() => expect(lastFilters(api).athlete).toBe("meera"));

    fireEvent.change(screen.getByLabelText("Submitted from"), { target: { value: "2026-10-01" } });
    fireEvent.change(screen.getByLabelText("Submitted to"), { target: { value: "2026-10-02" } });
    await waitFor(() =>
      expect(lastFilters(api)).toMatchObject({
        submittedFrom: new Date("2026-10-01T00:00").toISOString(),
        // Through the end of the chosen day.
        submittedTo: new Date("2026-10-03T00:00").toISOString(),
      }),
    );

    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(lastFilters(api)).toMatchObject({ sessionId: undefined, athlete: undefined }));
  });

  it("says when no submission matches the filters", async () => {
    renderPage(async () => ({ items: [], total: 0 }), "/admin/reviews?flags=high");
    expect(await screen.findByText("No submissions match these filters.")).toBeInTheDocument();
  });

  it("shows loading, then explains a failure and retries", async () => {
    let calls = 0;
    const api = renderPage(async () => {
      calls += 1;
      if (calls === 1) throw new ApiError(503, "down");
      return { items: [submission()], total: 1 };
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load the queue");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Meera")).toBeInTheDocument();
    expect(api.reviewQueue).toHaveBeenCalledTimes(2);
  });

  it("explains a permission refusal", async () => {
    renderPage(async () => {
      throw new ApiError(403, "Dashboard access requires an official account");
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("does not have permission");
  });

  it("pages through the server's total", async () => {
    const api = renderPage(async (filters) => ({
      items: [submission({ result_id: `r-${filters.offset}` })],
      total: 60,
    }));
    await screen.findByText("Page 1 of 3");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(lastFilters(api)).toMatchObject({ offset: 25, limit: 25 }));
    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
  });
});
