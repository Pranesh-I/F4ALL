import { fireEvent, screen, within } from "@testing-library/react";
import { ApiError } from "../api/client";
import type { DashboardStats } from "../api/types";
import { session, stats, submission } from "../test/fixtures";
import { makeApi, renderWithProviders } from "../test/render";
import { DashboardPage } from "./DashboardPage";

function renderPage(statsCall: () => Promise<DashboardStats> = async () => stats(), sessions = [session()]) {
  const api = makeApi("sai_admin", {
    stats: vi.fn(statsCall),
    sessions: vi.fn(async () => sessions),
    submissions: vi.fn(async () => ({ items: [submission()], total: 1 })),
  });
  renderWithProviders(<DashboardPage />, { api, route: "/admin/dashboard", path: "/admin/dashboard" });
  return api;
}

describe("DashboardPage", () => {
  it("shows the server's counts, each opening the matching submissions", async () => {
    renderPage();

    const waiting = await screen.findByRole("link", { name: /Awaiting verification/ });
    // uploaded 2 + processing 3
    expect(waiting).toHaveTextContent("5");
    expect(waiting).toHaveAttribute("href", "/admin/submissions?status=uploaded,processing");
    expect(screen.getByRole("link", { name: /^5\s*Flagged/ })).toHaveAttribute("href", "/admin/submissions?status=flagged");
    expect(screen.getByRole("link", { name: /Approved/ })).toHaveTextContent("6");
    expect(screen.getByText(/2 flagged with a high-severity integrity issue/)).toBeInTheDocument();
  });

  it("asks the server which sessions are active", async () => {
    const api = renderPage();

    const section = within(screen.getByRole("region", { name: "Active sessions" }));
    expect(await section.findByRole("link", { name: "District trials" })).toHaveAttribute("href", "/admin/sessions/s1");
    expect(api.sessions).toHaveBeenCalledWith({ status: ["active"] });
  });

  it("shows recent submissions", async () => {
    const api = renderPage();
    expect(await screen.findByText("Meera")).toBeInTheDocument();
    expect(api.submissions).toHaveBeenCalledWith({ limit: 8 });
  });

  it("says when no session is active, and offers to create one", async () => {
    renderPage(undefined, []);
    expect(await screen.findByText("No session is active right now.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create a session" })).toHaveAttribute("href", "/admin/sessions/create");
  });

  it("explains when the counts cannot be loaded, and retries", async () => {
    let calls = 0;
    const api = renderPage(async () => {
      calls += 1;
      if (calls === 1) throw new ApiError(503, "unavailable");
      return stats();
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load the counts");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("link", { name: /Awaiting verification/ })).toBeInTheDocument();
    expect(api.stats).toHaveBeenCalledTimes(2);
  });
});
