import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { ApiError } from "../api/client";
import type { AssessmentSession, OfficialRole } from "../api/types";
import { session } from "../test/fixtures";
import { makeApi, renderWithProviders } from "../test/render";
import { SessionsPage } from "./SessionsPage";

function renderPage(role: OfficialRole, sessions: () => Promise<AssessmentSession[]>) {
  const api = makeApi(role, {
    sessions: vi.fn(sessions),
    updateSession: vi.fn(async () => session({ enabled: false, status: "disabled" })),
    deleteSession: vi.fn(async () => ({ message: "Session deleted" })),
  });
  renderWithProviders(<SessionsPage />, { api, route: "/admin/sessions", path: "/admin/sessions" });
  return api;
}

describe("SessionsPage", () => {
  it("lists sessions with their tests, the server's status and submissions", async () => {
    renderPage("regional_reviewer", async () => [session({ submission_count: 3 })]);

    const link = await screen.findByRole("link", { name: "District trials" });
    expect(link).toHaveAttribute("href", "/admin/sessions/s1");
    const table = within(screen.getByRole("table"));
    expect(table.getByText("Squats, Sit-ups")).toBeInTheDocument();
    expect(table.getByText("Active")).toBeInTheDocument();
    expect(table.getByText("3")).toBeInTheDocument();
  });

  it("gives a reviewer no way to change anything", async () => {
    renderPage("regional_reviewer", async () => [session()]);

    await screen.findByText("District trials");
    expect(screen.queryByRole("link", { name: "New session" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Disable" })).not.toBeInTheDocument();
  });

  it("lets an admin close a session, and delete only one nobody submitted to", async () => {
    const api = renderPage("sai_admin", async () => [
      session(),
      session({ id: "s2", name: "State trials", submission_count: 5 }),
    ]);

    await screen.findByText("State trials");
    expect(screen.getByRole("link", { name: "New session" })).toHaveAttribute("href", "/admin/sessions/create");
    expect(screen.getAllByRole("button", { name: "Delete" })).toHaveLength(1);

    const [firstDisable] = screen.getAllByRole("button", { name: "Disable" });
    fireEvent.click(firstDisable!);
    await waitFor(() => expect(api.updateSession).toHaveBeenCalledWith("s1", { enabled: false }));
  });

  it("asks the server for sessions in a status, rather than filtering here", async () => {
    const api = renderPage("sai_admin", async () => [session()]);
    await screen.findByText("District trials");

    fireEvent.click(screen.getByRole("tab", { name: "Scheduled" }));

    await waitFor(() => expect(api.sessions).toHaveBeenLastCalledWith({ status: ["scheduled"] }));
  });

  it("shows loading, then an empty state that says what to do", async () => {
    let resolve: (value: AssessmentSession[]) => void = () => {};
    renderPage("sai_admin", () => new Promise((r) => (resolve = r)));

    expect(await screen.findByText("Loading sessions…")).toBeInTheDocument();
    resolve([]);
    expect(await screen.findByText("No sessions yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create the first session" })).toBeInTheDocument();
  });

  it("explains a failure and retries", async () => {
    let calls = 0;
    const api = renderPage("sai_admin", async () => {
      calls += 1;
      if (calls === 1) throw new ApiError(500, "boom");
      return [session()];
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load sessions");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("District trials")).toBeInTheDocument();
    expect(api.sessions).toHaveBeenCalledTimes(2);
  });
});
