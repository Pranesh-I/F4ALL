import { fireEvent, screen, waitFor } from "@testing-library/react";
import { ApiError } from "../api/client";
import type { AssessmentSession, OfficialRole } from "../api/types";
import { session, submission } from "../test/fixtures";
import { makeApi, renderWithProviders } from "../test/render";
import { SessionDetailPage } from "./SessionDetailPage";

function renderPage(
  role: OfficialRole,
  current: AssessmentSession | (() => Promise<AssessmentSession>),
  stubs: Record<string, unknown> = {},
) {
  const api = makeApi(role, {
    session: vi.fn(typeof current === "function" ? current : async () => current),
    submissions: vi.fn(async () => ({ items: [], total: 0 })),
    updateSession: vi.fn(async (_id: string, changes: Partial<AssessmentSession>) =>
      session({ ...(typeof current === "function" ? {} : current), ...changes }),
    ),
    endSession: vi.fn(async () => session({ status: "ended", ends_at: "2026-10-01T12:00:00Z" })),
    ...stubs,
  });
  renderWithProviders(<SessionDetailPage />, {
    api,
    route: "/admin/sessions/s1",
    path: "/admin/sessions/:sessionId",
  });
  return api;
}

describe("SessionDetailPage", () => {
  it("shows the session and what its server-computed state means", async () => {
    renderPage("regional_reviewer", session({ status: "scheduled", region: "Kerala", rules: "Film side-on." }));

    expect(await screen.findByRole("heading", { name: /District trials/ })).toBeInTheDocument();
    expect(screen.getByText("Scheduled")).toBeInTheDocument();
    expect(screen.getByText(/Athletes cannot see it until then/)).toBeInTheDocument();
    expect(screen.getByText("Kerala")).toBeInTheDocument();
    expect(screen.getByText("Film side-on.")).toBeInTheDocument();
    expect(screen.getByText("Squats")).toBeInTheDocument();
    // A reviewer may look, not change.
    expect(screen.queryByRole("button", { name: "Disable" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
  });

  it("lets an admin disable a session and reports it", async () => {
    const api = renderPage("sai_admin", session());

    fireEvent.click(await screen.findByRole("button", { name: "Disable" }));

    await waitFor(() => expect(api.updateSession).toHaveBeenCalledWith("s1", { enabled: false }));
    expect(await screen.findByText("Session disabled.")).toBeInTheDocument();
  });

  it("ends an active session through the server, after confirming", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const api = renderPage("sai_admin", session({ status: "active" }));

    fireEvent.click(await screen.findByRole("button", { name: "End now" }));

    await waitFor(() => expect(api.endSession).toHaveBeenCalledWith("s1"));
    expect(await screen.findByText(/Session ended/)).toBeInTheDocument();
    expect(screen.getByText("Ended")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "End now" })).not.toBeInTheDocument();
  });

  it("only offers to end a session that is active", async () => {
    renderPage("sai_admin", session({ status: "scheduled" }));
    await screen.findByRole("button", { name: "Disable" });
    expect(screen.queryByRole("button", { name: "End now" })).not.toBeInTheDocument();
  });

  it("edits a session, clearing its region explicitly", async () => {
    const api = renderPage("sai_admin", session({ region: "Kerala", status: "scheduled" }));

    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    await screen.findByLabelText(/Squats/);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "District trials, week 2" } });
    fireEvent.change(screen.getByLabelText("Region"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(api.updateSession).toHaveBeenCalledWith(
        "s1",
        expect.objectContaining({
          name: "District trials, week 2",
          allowed_tests: ["SQUATS", "SIT_UPS"],
          clear_region: true,
          description: "",
        }),
      ),
    );
    expect(await screen.findByText("Changes saved.")).toBeInTheDocument();
    // The window was not touched, so it is not resent (and not shifted).
    const changes = vi.mocked(api.updateSession).mock.calls[0]![1];
    expect(changes).not.toHaveProperty("starts_at");
    expect(changes).not.toHaveProperty("ends_at");
  });

  it("sends a moved window", async () => {
    const api = renderPage("sai_admin", session({ status: "scheduled" }));

    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    await screen.findByLabelText(/Squats/);
    fireEvent.change(screen.getByLabelText("Closes"), { target: { value: "2026-10-05T18:30" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(api.updateSession).toHaveBeenCalledWith(
        "s1",
        expect.objectContaining({ ends_at: new Date("2026-10-05T18:30").toISOString() }),
      ),
    );
    expect(vi.mocked(api.updateSession).mock.calls[0]![1]).not.toHaveProperty("starts_at");
  });

  it("lists the submissions made in it", async () => {
    const api = renderPage("sai_admin", session({ submission_count: 1 }), {
      submissions: vi.fn(async () => ({ items: [submission()], total: 1 })),
    });

    expect(await screen.findByText("Meera")).toBeInTheDocument();
    expect(api.submissions).toHaveBeenCalledWith({ sessionId: "s1", limit: 10 });
  });

  it("says when nothing has been submitted yet", async () => {
    renderPage("sai_admin", session());
    expect(await screen.findByText("No submissions yet.")).toBeInTheDocument();
  });

  it("treats another region's session as not found, with no retry", async () => {
    renderPage("regional_reviewer", async () => {
      throw new ApiError(404, "Session not found");
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("outside your region");
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });
});
