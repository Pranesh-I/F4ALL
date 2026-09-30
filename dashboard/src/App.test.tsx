import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { App } from "./App";
import { official, session, stats, submission } from "./test/fixtures";
import { makeApi, renderWithProviders } from "./test/render";

/** Every page's own data calls, answered with something harmless. */
const pageData = {
  stats: vi.fn(async () => stats()),
  sessions: vi.fn(async () => [session()]),
  submissions: vi.fn(async () => ({ items: [submission()], total: 1 })),
  reviewQueue: vi.fn(async () => ({ items: [], total: 0 })),
  leaderboard: vi.fn(async () => ({ test_type: "SIT_UPS", unit: "reps", higher_is_better: true, entries: [] })),
};

describe("admin routes", () => {
  it.each([
    "/admin/dashboard",
    "/admin/sessions",
    "/admin/sessions/create",
    "/admin/sessions/s1",
    "/admin/submissions",
    "/admin/submissions/r1",
    "/admin/athletes",
    "/admin/athletes/a1",
    "/admin/leaderboards",
    "/admin/analytics",
    "/admin/reviews",
  ])("%s cannot be opened without signing in", async (route) => {
    renderWithProviders(<App />, { api: makeApi(null), route });

    expect(await screen.findByRole("heading", { name: "Official sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Admin" })).not.toBeInTheDocument();
  });

  it("signs in and returns to the page that was asked for", async () => {
    const api = makeApi(null, {
      ...pageData,
      login: vi.fn(async () => official("sai_admin")),
    });
    renderWithProviders(<App />, { api, route: "/admin/submissions" });

    fireEvent.change(await screen.findByLabelText("Email"), { target: { value: "a@sai.example" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("heading", { name: "Submissions" })).toBeInTheDocument();
    expect(api.login).toHaveBeenCalledWith("a@sai.example", "secret");
  });

  it("shows a refused sign-in instead of letting anyone in", async () => {
    const { ApiError } = await import("./api/client");
    const api = makeApi(null, {
      login: vi.fn(async () => {
        throw new ApiError(401, "Incorrect email or password");
      }),
    });
    renderWithProviders(<App />, { api, route: "/admin/dashboard" });

    fireEvent.change(await screen.findByLabelText("Email"), { target: { value: "x@sai.example" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "wrong" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Incorrect email or password");
    expect(screen.queryByRole("navigation", { name: "Admin" })).not.toBeInTheDocument();
  });

  it("drops a stored session the server no longer accepts", async () => {
    const api = makeApi(null);
    api.hasSession = () => true;
    api.me = vi.fn(async () => {
      throw new Error("expired");
    });
    api.clearSession = vi.fn();
    renderWithProviders(<App />, { api, route: "/admin/dashboard" });

    expect(await screen.findByRole("heading", { name: "Official sign in" })).toBeInTheDocument();
    expect(api.clearSession).toHaveBeenCalled();
  });

  it("gives the shell navigation to every section, and the signed-in official", async () => {
    renderWithProviders(<App />, { api: makeApi("regional_reviewer", pageData), route: "/admin" });

    const nav = await screen.findByRole("navigation", { name: "Admin" });
    expect(within(nav).getAllByRole("link").map((link) => link.textContent)).toEqual([
      "Dashboard",
      "Sessions",
      "Submissions",
      "Review queue",
      "Athletes",
      "Leaderboards",
      "Analytics",
    ]);
    const header = within(screen.getByRole("banner"));
    expect(header.getByText("Ravi Reviewer")).toBeInTheDocument();
    expect(header.getByText(/Regional reviewer · Kerala/)).toBeInTheDocument();
    // /admin opens the dashboard.
    expect(await screen.findByRole("heading", { name: "Dashboard" })).toBeInTheDocument();

    fireEvent.click(within(nav).getByRole("link", { name: "Sessions" }));
    expect(await screen.findByRole("heading", { name: "Assessment sessions" })).toBeInTheDocument();

    fireEvent.click(within(nav).getByRole("link", { name: "Analytics" }));
    expect(await screen.findByRole("heading", { name: "Analytics" })).toBeInTheDocument();
  });

  it("signs out through the server and back to the sign-in page", async () => {
    const api = makeApi("sai_admin", pageData);
    renderWithProviders(<App />, { api, route: "/admin/dashboard" });

    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("heading", { name: "Official sign in" })).toBeInTheDocument();
    expect(api.logout).toHaveBeenCalledOnce();
  });

  it("sends whoever signs in after a sign-out to the dashboard, not the last official's page", async () => {
    const api = makeApi("sai_admin", {
      ...pageData,
      login: vi.fn(async () => official("regional_reviewer")),
    });
    renderWithProviders(<App />, { api, route: "/admin/sessions" });

    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));
    fireEvent.change(await screen.findByLabelText("Email"), { target: { value: "next@sai.example" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("heading", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Assessment sessions" })).not.toBeInTheDocument();
  });

  it("keeps session creation to SAI admins", async () => {
    renderWithProviders(<App />, { api: makeApi("regional_reviewer", pageData), route: "/admin/sessions/create" });

    expect(await screen.findByRole("alert")).toHaveTextContent("You do not have permission");
    expect(screen.queryByRole("form", { name: "New session" })).not.toBeInTheDocument();
  });

  it("sends old addresses to their new places", async () => {
    renderWithProviders(<App />, { api: makeApi("sai_admin", pageData), route: "/reviews?status=verified" });
    expect(await screen.findByRole("heading", { name: "Review queue" })).toBeInTheDocument();
    await waitFor(() =>
      expect(pageData.reviewQueue).toHaveBeenLastCalledWith(expect.objectContaining({ status: "verified" })),
    );
  });

  it("says so when an admin address does not exist", async () => {
    renderWithProviders(<App />, { api: makeApi("sai_admin", pageData), route: "/admin/nowhere" });
    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
  });
});
