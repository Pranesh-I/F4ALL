import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type ReviewActionResult } from "../api/client";
import type { ReviewDetail, VerificationStatus } from "../api/types";
import { reviewDetail, verificationStatus } from "../test/fixtures";
import { makeApi, renderWithProviders } from "../test/render";
import { ReviewPage } from "./ReviewPage";

function renderPage(
  review: () => Promise<ReviewDetail> = async () => reviewDetail(),
  verification: () => Promise<VerificationStatus> = async () => verificationStatus(),
  stubs: Record<string, unknown> = {},
) {
  const api = makeApi("regional_reviewer", {
    review: vi.fn(review),
    verification: vi.fn(verification),
    ...stubs,
  });
  renderWithProviders(<ReviewPage />, { api, route: "/admin/reviews/r1", path: "/admin/reviews/:resultId" });
  return api;
}

const twoFlags = reviewDetail({
  flags: [
    {
      reason: "looped_frames",
      detail: "90 frames from 1.7s repeat at 7.1s (at 7.1s)",
      severity: "high",
      source: "auto",
      created_at: "2026-10-01T10:03:00Z",
      resolution: null,
      resolved_at: null,
      flag_id: "f1",
      status: "open",
      evidence: { run_length_frames: 90, mean_difference: 0.4, threshold: 2.0, at_ms: 7100 },
    },
    {
      reason: "score_discrepancy",
      detail: "Phone 30, server 22",
      severity: "medium",
      source: "auto",
      created_at: "2026-10-01T10:03:00Z",
      resolution: null,
      resolved_at: null,
      flag_id: "f2",
      status: "open",
      evidence: { mobile_value: 30, server_value: 22, difference: -8, tolerance: 2 },
    },
  ],
});

describe("ReviewPage", () => {
  it("shows loading, then who, which session, and the review and machine states", async () => {
    let resolve: (value: ReviewDetail) => void = () => {};
    renderPage(() => new Promise((r) => (resolve = r)));
    expect(await screen.findByText("Loading the submission…")).toBeInTheDocument();

    resolve(reviewDetail());
    expect(await screen.findByRole("heading", { name: "Meera — Sit-ups" })).toBeInTheDocument();
    const header = screen.getByRole("heading", { name: "Meera — Sit-ups" }).parentElement!;
    expect(within(header).getByText("Needs review")).toBeInTheDocument();
    expect(within(header).getByText("Server flagged it for a human")).toBeInTheDocument();
    const facts = within(screen.getByRole("region", { name: "Submission" }));
    expect(facts.getByRole("link", { name: "District trials" })).toHaveAttribute("href", "/admin/sessions/s1");
    expect(facts.getByText("0f3c2a1b-0000-4000-8000-000000000001")).toBeInTheDocument();
    expect(facts.getByText("Flagged")).toBeInTheDocument();
  });

  it("compares the phone with the server and says the allowed difference was exceeded", async () => {
    renderPage();
    const panel = within(await screen.findByRole("region", { name: "Phone and server results" }));

    expect(await panel.findByRole("rowheader", { name: "Reps counted" })).toBeInTheDocument();
    expect(panel.getByText("30 reps")).toBeInTheDocument();
    expect(panel.getByText("22 reps")).toBeInTheDocument();
    expect(panel.getByText(/outside the allowed difference/)).toBeInTheDocument();
    expect(panel.getByText("Not decided")).toBeInTheDocument();
  });

  it("adapts to the exercise: a jump is a measurement, not reps", async () => {
    renderPage(
      async () => reviewDetail({ test_type: "VERTICAL_JUMP", unit: "cm", provisional_score: 41, server_score: 39.7 }),
      async () =>
        verificationStatus({
          test_type: "VERTICAL_JUMP",
          unit: "cm",
          comparison: {
            mobile_rep_count: null,
            server_rep_count: null,
            mobile_measurement: 41,
            server_measurement: 39.7,
            mobile_form_score: null,
            server_form_score: null,
            difference: -1.3,
            tolerance: 5,
          },
        }),
    );
    const panel = within(await screen.findByRole("region", { name: "Phone and server results" }));

    expect(await panel.findByRole("rowheader", { name: "Measurement" })).toBeInTheDocument();
    expect(panel.queryByRole("rowheader", { name: "Reps counted" })).not.toBeInTheDocument();
    expect(panel.getByText(/within the allowed difference/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Meera — Vertical jump" })).toBeInTheDocument();
  });

  it("lists every integrity flag with its evidence, its limit and where to look", async () => {
    renderPage(async () => twoFlags);
    const panel = within(await screen.findByRole("region", { name: "Integrity (2)" }));

    const flags = panel.getAllByRole("listitem");
    expect(flags).toHaveLength(2);
    expect(flags[0]).toHaveTextContent("Repeated footage");
    expect(flags[0]).toHaveTextContent("high");
    expect(flags[0]).toHaveTextContent("Run length frames90");
    expect(flags[0]).toHaveTextContent("Limit2");
    expect(within(flags[0]!).getByRole("button", { name: "Jump to 7.1s" })).toBeInTheDocument();
    expect(flags[1]).toHaveTextContent("Phone and server scores disagree");
    expect(flags[1]).toHaveTextContent("Allowed difference2");
    expect(panel.getByText("2 flag(s), 2 still open.")).toBeInTheDocument();
  });

  it("shows the automated verification record", async () => {
    renderPage();
    const panel = within(await screen.findByRole("region", { name: "Automated verification" }));

    expect(await panel.findByText("s12.1")).toBeInTheDocument();
    expect(panel.getByText("4.2 s")).toBeInTheDocument();
    const checks = within(panel.getByRole("list", { name: "Verification checks" })).getAllByRole("listitem");
    expect(checks.map((item) => item.textContent)).toEqual([
      "passedvalidation",
      "failedcomparison— Phone and server scores disagree",
    ]);
  });

  it("shows the audit trail with reasons and transitions", async () => {
    renderPage(async () =>
      reviewDetail({
        review_version: 1,
        review_history: [
          {
            action: "flagged",
            notes: "Hips drop on every rep.",
            official_name: "Ravi Reviewer",
            created_at: "2026-10-01T11:00:00Z",
            official_id: "o2",
            reason: "form_issue",
            previous_status: "verified",
            new_status: "flagged",
          },
        ],
      }),
    );
    const history = within(await screen.findByRole("list", { name: "Review history" }));
    const [entry] = history.getAllByRole("listitem");
    expect(entry).toHaveTextContent("Flagged for further review by Ravi Reviewer");
    expect(entry).toHaveTextContent("Reason: Form not acceptable");
    expect(entry).toHaveTextContent("Verified — awaiting approval → Flagged");
    expect(entry).toHaveTextContent("Hips drop on every rep.");
  });

  it("records a decision, confirms it, and shows the server's new state", async () => {
    let decided = false;
    const outcome: ReviewActionResult = {
      result_id: "r1",
      action: "approved",
      status: "approved",
      message: "Submission approved. Its score is now official.",
      review_status: "approved",
      review_version: 1,
    };
    const api = renderPage(
      async () =>
        decided
          ? reviewDetail({
              status: "approved",
              review_status: "approved",
              final_score: 22,
              allowed_actions: [],
              review_version: 1,
              review_history: [
                {
                  action: "approved",
                  notes: null,
                  official_name: "Ravi Reviewer",
                  created_at: "2026-10-01T11:00:00Z",
                  previous_status: "flagged",
                  new_status: "approved",
                },
              ],
            })
          : reviewDetail(),
      undefined,
      {
        act: vi.fn(async () => {
          decided = true;
          return outcome;
        }),
      },
    );

    fireEvent.click(await screen.findByRole("radio", { name: "Approve result" }));
    fireEvent.click(screen.getByRole("button", { name: "Approve result…" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve result" }));

    expect(await screen.findByText("Submission approved. Its score is now official.")).toBeInTheDocument();
    expect(await screen.findByText(/This submission has been decided/)).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Decision" })).not.toBeInTheDocument();
    const [entry] = within(screen.getByRole("list", { name: "Review history" })).getAllByRole("listitem");
    expect(entry).toHaveTextContent("Approved by Ravi Reviewer");
    expect(entry).toHaveTextContent("Flagged → Approved");
    expect(api.review).toHaveBeenCalledTimes(2);
  });

  it("keeps telling the reviewer their decision was not recorded after another official's refresh", async () => {
    let decidedElsewhere = false;
    renderPage(
      async () =>
        decidedElsewhere
          ? reviewDetail({
              status: "rejected",
              review_status: "rejected",
              allowed_actions: [],
              review_version: 1,
              review_history: [
                {
                  action: "rejected",
                  notes: "No athlete visible.",
                  official_name: "Asha Admin",
                  created_at: "2026-10-01T11:00:00Z",
                  reason: "invalid_video",
                  previous_status: "flagged",
                  new_status: "rejected",
                },
              ],
            })
          : reviewDetail(),
      undefined,
      {
        act: vi.fn(async () => {
          decidedElsewhere = true;
          throw new ApiError(
            409,
            "This submission changed while you had it open: Asha Admin recorded 'rejected' at 2026-10-01 11:00 UTC, and it is now rejected. Reload it before deciding.",
          );
        }),
      },
    );

    fireEvent.click(await screen.findByRole("radio", { name: "Approve result" }));
    fireEvent.click(screen.getByRole("button", { name: "Approve result…" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve result" }));

    // The panel goes (nothing is left to decide); the explanation stays.
    expect(await screen.findByText(/This submission has been decided/)).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Decision" })).not.toBeInTheDocument();
    const notice = screen.getByRole("alert");
    expect(notice).toHaveTextContent("Your decision was not recorded");
    expect(notice).toHaveTextContent("Asha Admin recorded 'rejected'");
    expect(screen.getByRole("list", { name: "Review history" })).toHaveTextContent("Rejected by Asha Admin");
  });

  it("offers no decision while the server is still verifying", async () => {
    renderPage(async () =>
      reviewDetail({ status: "processing", review_status: "awaiting_verification", allowed_actions: [], flags: [] }),
    );
    expect(await screen.findByText(/still verifying this recording/)).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Decision" })).not.toBeInTheDocument();
    expect(screen.getByText("No integrity concerns were raised.")).toBeInTheDocument();
  });

  it("treats another region's submission as not found, without a retry", async () => {
    renderPage(async () => {
      throw new ApiError(404, "Result not found");
    });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not open this submission");
    expect(alert).toHaveTextContent("outside your region");
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("explains a permission refusal", async () => {
    renderPage(async () => {
      throw new ApiError(403, "Dashboard access requires an official account");
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("does not have permission");
  });

  it("keeps the evidence on screen when only the verification record fails, and retries it", async () => {
    let calls = 0;
    const api = renderPage(undefined, async () => {
      calls += 1;
      if (calls === 1) throw new ApiError(500, "boom");
      return verificationStatus();
    });

    expect(await screen.findByText("Could not load the verification record")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Meera — Sit-ups" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(api.verification).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("s12.1")).toBeInTheDocument();
  });
});
