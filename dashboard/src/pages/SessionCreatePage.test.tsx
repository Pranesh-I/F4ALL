import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { ApiError } from "../api/client";
import { toIso } from "../lib/format";
import { session } from "../test/fixtures";
import { CurrentPath, makeApi, renderWithProviders } from "../test/render";
import { SessionCreatePage } from "./SessionCreatePage";

function renderPage(createSession = vi.fn(async () => session({ id: "new1", name: "Block trials" }))) {
  const api = makeApi("sai_admin", { createSession });
  renderWithProviders(
    <Routes>
      <Route path="/admin/sessions/create" element={<SessionCreatePage />} />
      <Route path="*" element={<CurrentPath />} />
    </Routes>,
    { api, route: "/admin/sessions/create" },
  );
  return api;
}

function fillIn({ name = "Block trials", opens = "2026-10-01T09:00", closes = "2026-10-02T09:00" } = {}) {
  fireEvent.change(screen.getByLabelText("Name"), { target: { value: name } });
  fireEvent.change(screen.getByLabelText("Opens"), { target: { value: opens } });
  fireEvent.change(screen.getByLabelText("Closes"), { target: { value: closes } });
}

describe("SessionCreatePage", () => {
  it("offers exactly the tests the server can assess", async () => {
    const api = renderPage();

    const tests = await screen.findByRole("group", { name: "Tests" });
    await within(tests).findByLabelText(/Squats/);
    expect(within(tests).getAllByRole("checkbox")).toHaveLength(6);
    for (const name of [/Squats/, /Push-ups/, /Bicep curls/, /Lunges/, /Vertical jump/, /Sit-ups/]) {
      expect(within(tests).getByLabelText(name)).toBeInTheDocument();
    }
    expect(api.tests).toHaveBeenCalled();
  });

  it("creates a session in UTC, then opens it with a confirmation", async () => {
    const api = renderPage();
    await screen.findByLabelText(/Lunges/);

    // Nothing is complained about before the admin tries.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Create session" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Give the session a name.");

    fillIn();
    // Ticked out of order; sent in the catalog's order.
    fireEvent.click(screen.getByLabelText(/Sit-ups/));
    fireEvent.click(screen.getByLabelText(/Lunges/));
    fireEvent.click(screen.getByRole("button", { name: "Create session" }));

    await waitFor(() =>
      expect(api.createSession).toHaveBeenCalledWith({
        name: "Block trials",
        description: null,
        rules: null,
        starts_at: toIso("2026-10-01T09:00"),
        ends_at: toIso("2026-10-02T09:00"),
        enabled: false,
        allowed_tests: ["LUNGES", "SIT_UPS"],
        region: null,
      }),
    );
    expect(await screen.findByTestId("path")).toHaveTextContent("/admin/sessions/new1");
  });

  it("refuses a session that closes before it opens", async () => {
    const api = renderPage();
    await screen.findByLabelText(/Squats/);

    fillIn({ opens: "2026-10-02T09:00", closes: "2026-10-01T09:00" });
    fireEvent.click(screen.getByLabelText(/Squats/));
    fireEvent.click(screen.getByRole("button", { name: "Create session" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("must close after it opens");
    expect(api.createSession).not.toHaveBeenCalled();
  });

  it("refuses a session with no tests", async () => {
    const api = renderPage();
    await screen.findByLabelText(/Squats/);

    fillIn();
    fireEvent.click(screen.getByRole("button", { name: "Create session" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Choose at least one test.");
    expect(api.createSession).not.toHaveBeenCalled();
  });

  it("shows the server's refusal and stays on the form", async () => {
    renderPage(
      vi.fn(async () => {
        throw new ApiError(422, "Region must be an Indian state or union territory");
      }),
    );
    await screen.findByLabelText(/Squats/);

    fillIn();
    fireEvent.click(screen.getByLabelText(/Squats/));
    fireEvent.click(screen.getByRole("button", { name: "Create session" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Region must be an Indian state");
    expect(screen.getByRole("form", { name: "New session" })).toBeInTheDocument();
  });
});
