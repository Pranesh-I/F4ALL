import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type ReviewActionResult, type ReviewDecision } from "../../api/client";
import type { ReviewDetail } from "../../api/types";
import { reviewDetail } from "../../test/fixtures";
import { makeApi, renderWithProviders } from "../../test/render";
import { DecisionPanel } from "./DecisionPanel";

const done: ReviewActionResult = {
  result_id: "r1",
  action: "approved",
  status: "approved",
  message: "Submission approved. Its score is now official.",
  review_status: "approved",
  review_version: 1,
};

function renderPanel(
  result: ReviewDetail = reviewDetail(),
  act: (id: string, decision: ReviewDecision) => Promise<ReviewActionResult> = async () => done,
) {
  const spy = vi.fn(act);
  const onDecided = vi.fn();
  const api = makeApi("regional_reviewer", { act: spy });
  const view = renderWithProviders(<DecisionPanel result={result} nameOf={() => "Sit-ups"} onDecided={onDecided} />, {
    api,
    route: "/admin/reviews/r1",
    path: "/admin/reviews/:resultId",
  });
  return { act: spy, onDecided, ...view };
}

const choose = (name: string) => fireEvent.click(screen.getByRole("radio", { name }));
const proceed = (name: RegExp) => fireEvent.click(screen.getByRole("button", { name }));

describe("DecisionPanel", () => {
  it("offers only the actions the server allows", () => {
    renderPanel(reviewDetail({ allowed_actions: ["rejected", "requested_resubmission"] }));

    expect(screen.queryByRole("radio", { name: "Approve result" })).toBeNull();
    expect(screen.queryByRole("radio", { name: "Flag for further review" })).toBeNull();
    expect(screen.getByRole("radio", { name: "Reject submission" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Request resubmission" })).toBeInTheDocument();
  });

  it("names the action on its button, never a bare submit", () => {
    renderPanel();
    expect(screen.getByRole("button", { name: "Choose a decision" })).toBeDisabled();
    choose("Reject submission");
    expect(screen.getByRole("button", { name: "Reject submission…" })).toBeEnabled();
  });

  it("requires a reason, then a note, before rejecting", async () => {
    const { act } = renderPanel();

    choose("Reject submission");
    proceed(/Reject submission…/);
    expect(await screen.findByRole("alert")).toHaveTextContent("Choose a reason.");

    fireEvent.change(screen.getByLabelText("Reason (required)"), { target: { value: "identity_mismatch" } });
    proceed(/Reject submission…/);
    expect(await screen.findByRole("alert")).toHaveTextContent("athlete will see");
    expect(act).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("confirms a rejection with its outcome before sending it, and can be cancelled", async () => {
    const { act, onDecided } = renderPanel();

    choose("Reject submission");
    fireEvent.change(screen.getByLabelText("Reason (required)"), { target: { value: "identity_mismatch" } });
    fireEvent.change(screen.getByLabelText(/Note to the athlete/), { target: { value: "Not the registered athlete." } });
    proceed(/Reject submission…/);

    const dialog = await screen.findByRole("dialog", { name: "Reject submission?" });
    expect(dialog).toHaveTextContent("Reason: Identity mismatch");
    expect(dialog).toHaveTextContent("Not the registered athlete.");
    expect(dialog).toHaveTextContent("will not count");
    expect(dialog).toHaveTextContent("cannot be undone");

    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(act).not.toHaveBeenCalled();

    proceed(/Reject submission…/);
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Reject submission" }));

    await waitFor(() =>
      expect(act).toHaveBeenCalledWith("0f3c2a1b-0000-4000-8000-000000000001", {
        action: "rejected",
        reason: "identity_mismatch",
        notes: "Not the registered athlete.",
        finalScore: undefined,
        severity: undefined,
        expectedVersion: 0,
      }),
    );
    await waitFor(() => expect(onDecided).toHaveBeenCalledWith(done));
  });

  it("approves a server-scored result without asking for a score or reason", async () => {
    const { act } = renderPanel(reviewDetail({ review_version: 2 }));

    choose("Approve result");
    expect(screen.queryByLabelText("Reason (required)")).not.toBeInTheDocument();
    proceed(/Approve result…/);
    const dialog = await screen.findByRole("dialog", { name: "Approve result?" });
    expect(dialog).toHaveTextContent("Official score: 22 reps");
    fireEvent.click(within(dialog).getByRole("button", { name: "Approve result" }));

    await waitFor(() =>
      expect(act).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({ action: "approved", expectedVersion: 2 })),
    );
  });

  it("asks for a hand-entered score when the server could not measure", async () => {
    const { act } = renderPanel(reviewDetail({ server_score: null }));

    choose("Approve result");
    proceed(/Approve result…/);
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter the score");

    fireEvent.change(screen.getByLabelText(/Score from the recording/), { target: { value: "31" } });
    proceed(/Approve result…/);
    expect(await screen.findByRole("alert")).toHaveTextContent("how you determined");

    fireEvent.change(screen.getByLabelText(/How you determined the score/), { target: { value: "Counted in the video" } });
    proceed(/Approve result…/);
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("Official score: 31 reps (entered by you)");
    fireEvent.click(within(dialog).getByRole("button", { name: "Approve result" }));

    await waitFor(() =>
      expect(act).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({ action: "approved", finalScore: 31, notes: "Counted in the video" }),
      ),
    );
  });

  it("flags with a severity and an internal note, and says it decides nothing", async () => {
    const { act } = renderPanel();

    choose("Flag for further review");
    expect(screen.getByText(/Nothing is decided yet/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Reason (required)"), { target: { value: "form_issue" } });
    fireEvent.change(screen.getByLabelText("How serious"), { target: { value: "high" } });
    fireEvent.change(screen.getByLabelText(/not shown to the athlete/), { target: { value: "Hips drop on each rep." } });
    proceed(/Flag for further review…/);
    const dialog = await screen.findByRole("dialog");
    expect(dialog).not.toHaveTextContent("cannot be undone");
    fireEvent.click(within(dialog).getByRole("button", { name: "Flag for further review" }));

    await waitFor(() =>
      expect(act).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({ action: "flagged", reason: "form_issue", severity: "high" }),
      ),
    );
  });

  it("shows that another official decided first, and reloads the submission", async () => {
    const { queryClient } = renderPanel(reviewDetail(), async () => {
      throw new ApiError(
        409,
        "This submission changed while you had it open: Asha recorded 'approved' at 2026-10-01 10:05 UTC, and it is now approved. Reload it before deciding.",
      );
    });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");

    choose("Approve result");
    proceed(/Approve result…/);
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve result" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Another official acted on this submission first");
    expect(alert).toHaveTextContent("Asha recorded 'approved'");
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["review"] });
  });

  it("explains a refusal for lack of permission", async () => {
    renderPanel(reviewDetail(), async () => {
      throw new ApiError(404, "Result not found");
    });

    choose("Approve result");
    proceed(/Approve result…/);
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve result" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("outside your region");
  });

  it("warns before leaving with an unsaved decision", async () => {
    const { router } = renderPanel();

    choose("Reject submission");
    fireEvent.change(screen.getByLabelText(/Note to the athlete/), { target: { value: "half-written" } });

    await router.navigate("/admin/reviews");
    const dialog = await screen.findByRole("dialog", { name: "Leave without recording your decision?" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Stay on this page" }));
    expect(router.state.location.pathname).toBe("/admin/reviews/r1");
    expect(screen.getByLabelText(/Note to the athlete/)).toHaveValue("half-written");

    void router.navigate("/admin/reviews");
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Leave page" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/admin/reviews"));
  });

  it("lets a reviewer move on freely when nothing has been entered", async () => {
    const { router } = renderPanel();
    await router.navigate("/admin/reviews");
    expect(router.state.location.pathname).toBe("/admin/reviews");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
