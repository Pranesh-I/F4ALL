import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { SubmissionFilters } from "../api/client";
import type { SubmissionPage } from "../api/types";
import { session, submission } from "../test/fixtures";
import { makeApi, renderWithProviders } from "../test/render";
import { SubmissionsPage } from "./SubmissionsPage";

function renderPage(list: (filters: SubmissionFilters) => Promise<SubmissionPage>, route = "/admin/submissions") {
  const api = makeApi("sai_admin", {
    submissions: vi.fn(list),
    sessions: vi.fn(async () => [session()]),
  });
  renderWithProviders(<SubmissionsPage />, { api, route, path: "/admin/submissions" });
  return api;
}

describe("SubmissionsPage", () => {
  it("lists real submissions with athlete, test, session, status and flags", async () => {
    renderPage(async () => ({ items: [submission()], total: 1 }));

    expect(await screen.findByText("Meera")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Submission 0f3c2a1b by Meera/ })).toHaveAttribute(
      "href",
      "/admin/submissions/0f3c2a1b-0000-4000-8000-000000000001",
    );
    expect(screen.getByRole("link", { name: "District trials" })).toHaveAttribute("href", "/admin/sessions/s1");
    const table = within(screen.getByRole("table"));
    expect(table.getByText("Sit-ups")).toBeInTheDocument();
    expect(table.getByText("Flagged")).toBeInTheDocument();
    expect(table.getByText("high")).toBeInTheDocument();
    expect(screen.getByText("1 submission(s)")).toBeInTheDocument();
  });

  it("shows loading, then says when there is nothing yet", async () => {
    let resolve: (value: SubmissionPage) => void = () => {};
    renderPage(() => new Promise((r) => (resolve = r)));

    expect(await screen.findByText("Loading submissions…")).toBeInTheDocument();
    resolve({ items: [], total: 0 });
    expect(await screen.findByText("No submissions yet.")).toBeInTheDocument();
  });

  it("distinguishes no results for a filter from no submissions at all", async () => {
    renderPage(async () => ({ items: [], total: 0 }), "/admin/submissions?status=approved");
    expect(await screen.findByText("No submissions match these filters.")).toBeInTheDocument();
  });

  it("explains a failure and retries", async () => {
    let calls = 0;
    renderPage(async () => {
      calls += 1;
      if (calls === 1) throw new TypeError("Failed to fetch");
      return { items: [submission()], total: 1 };
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not reach the server");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Meera")).toBeInTheDocument();
  });

  it("filters on the server by status, test and session", async () => {
    const api = renderPage(async () => ({ items: [submission()], total: 1 }));
    await screen.findByText("Meera");

    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "flagged" } });
    await waitFor(() => expect(api.submissions).toHaveBeenLastCalledWith(expect.objectContaining({ status: "flagged" })));

    await screen.findByRole("option", { name: "Push-ups" });
    fireEvent.change(screen.getByLabelText("Test"), { target: { value: "PUSH_UPS" } });
    await waitFor(() =>
      expect(api.submissions).toHaveBeenLastCalledWith(expect.objectContaining({ status: "flagged", testType: "PUSH_UPS" })),
    );

    await screen.findByRole("option", { name: "District trials" });
    fireEvent.change(screen.getByLabelText("Session"), { target: { value: "s1" } });
    await waitFor(() => expect(api.submissions).toHaveBeenLastCalledWith(expect.objectContaining({ sessionId: "s1" })));
  });

  it("pages through the server's total", async () => {
    const api = renderPage(async (filters) => ({
      items: [submission({ result_id: `r-${filters.offset}` })],
      total: 60,
    }));
    await screen.findByText("Page 1 of 3");

    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    await waitFor(() => expect(api.submissions).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 25, limit: 25 })));
    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
  });
});
