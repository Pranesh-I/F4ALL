import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DashboardApi, type TokenStore } from "../api/client";
import type { AssessmentSession, OfficialProfile } from "../api/types";
import { AuthProvider } from "../auth/AuthContext";
import { SessionsPage, toIso } from "./SessionsPage";

const signedIn: TokenStore = { get: () => ({ access: "a", refresh: "r" }), set: () => {}, clear: () => {} };

function official(role: OfficialProfile["role"]): OfficialProfile {
  return { official_id: "o1", name: "Official", email: "o@sai.example", role, region: role === "sai_admin" ? null : "Kerala" };
}

function session(overrides: Partial<AssessmentSession> = {}): AssessmentSession {
  return {
    id: "s1",
    name: "District trials",
    description: null,
    rules: null,
    starts_at: "2026-10-01T09:00:00Z",
    ends_at: "2026-10-03T09:00:00Z",
    enabled: true,
    allowed_tests: ["SQUATS", "SIT_UPS"],
    region: null,
    status: "active",
    submission_count: 0,
    created_at: "2026-09-29T09:00:00Z",
    updated_at: "2026-09-29T09:00:00Z",
    ...overrides,
  };
}

function renderPage(role: OfficialProfile["role"], sessions: AssessmentSession[]) {
  const api = new DashboardApi("", signedIn);
  api.me = vi.fn(async () => official(role));
  api.sessions = vi.fn(async () => sessions);
  api.createSession = vi.fn(async () => session());
  api.updateSession = vi.fn(async () => session({ enabled: false, status: "disabled" }));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider api={api}>
        <SessionsPage />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return api;
}

describe("SessionsPage", () => {
  it("lists sessions with their tests, status and submissions", async () => {
    renderPage("regional_reviewer", [session({ submission_count: 3 })]);

    expect(await screen.findByText("District trials")).toBeInTheDocument();
    expect(screen.getByText("Squats, Sit-ups")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("gives a reviewer no way to change anything", async () => {
    renderPage("regional_reviewer", [session()]);

    await screen.findByText("District trials");
    expect(screen.queryByRole("button", { name: "New session" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Disable" })).not.toBeInTheDocument();
  });

  it("lets an admin close a session, and delete only one nobody submitted to", async () => {
    const api = renderPage("sai_admin", [
      session(),
      session({ id: "s2", name: "State trials", submission_count: 5 }),
    ]);

    await screen.findByText("State trials");
    expect(screen.getAllByRole("button", { name: "Delete" })).toHaveLength(1);

    const [firstDisable] = screen.getAllByRole("button", { name: "Disable" });
    fireEvent.click(firstDisable!);
    await waitFor(() => expect(api.updateSession).toHaveBeenCalledWith("s1", { enabled: false }));
  });

  it("creates a session from the form, in UTC", async () => {
    const api = renderPage("sai_admin", []);

    fireEvent.click(await screen.findByRole("button", { name: "New session" }));
    expect(screen.getByRole("form", { name: "New session" })).toBeInTheDocument();

    // Nothing is complained about before the admin tries.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Create session" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Give the session a name.");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Block trials" } });
    fireEvent.change(screen.getByLabelText("Opens"), { target: { value: "2026-10-01T09:00" } });
    fireEvent.change(screen.getByLabelText("Closes"), { target: { value: "2026-10-02T09:00" } });
    fireEvent.click(screen.getByLabelText("Lunges"));
    fireEvent.click(screen.getByRole("button", { name: "Create session" }));

    await waitFor(() =>
      expect(api.createSession).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Block trials",
          allowed_tests: ["LUNGES"],
          starts_at: toIso("2026-10-01T09:00"),
          enabled: false,
          region: null,
        }),
      ),
    );
  });

  it("refuses a session that closes before it opens", async () => {
    const api = renderPage("sai_admin", []);

    fireEvent.click(await screen.findByRole("button", { name: "New session" }));
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Backwards" } });
    fireEvent.change(screen.getByLabelText("Opens"), { target: { value: "2026-10-02T09:00" } });
    fireEvent.change(screen.getByLabelText("Closes"), { target: { value: "2026-10-01T09:00" } });
    fireEvent.click(screen.getByLabelText("Squats"));
    fireEvent.click(screen.getByRole("button", { name: "Create session" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("must close after it opens");
    expect(api.createSession).not.toHaveBeenCalled();
  });
});
