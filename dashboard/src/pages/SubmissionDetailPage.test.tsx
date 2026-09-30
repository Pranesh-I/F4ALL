import { screen } from "@testing-library/react";
import { ApiError } from "../api/client";
import type { ReviewDetail, VerificationStatus } from "../api/types";
import { reviewDetail, verificationStatus } from "../test/fixtures";
import { makeApi, renderWithProviders } from "../test/render";
import { SubmissionDetailPage } from "./SubmissionDetailPage";

function renderPage(
  review: () => Promise<ReviewDetail> = async () => reviewDetail(),
  verification: () => Promise<VerificationStatus> = async () => verificationStatus(),
) {
  const api = makeApi("regional_reviewer", { review: vi.fn(review), verification: vi.fn(verification) });
  renderWithProviders(<SubmissionDetailPage />, {
    api,
    route: "/admin/submissions/r1",
    path: "/admin/submissions/:resultId",
  });
  return api;
}

describe("SubmissionDetailPage", () => {
  it("shows who, which session, and the phone's result beside the server's", async () => {
    const api = renderPage();

    expect(await screen.findByRole("heading", { name: "Meera — Sit-ups" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "District trials" })).toHaveAttribute("href", "/admin/sessions/s1");
    expect(screen.getByText("0f3c2a1b-0000-4000-8000-000000000001")).toBeInTheDocument();

    expect(screen.getByText("30 reps")).toBeInTheDocument();
    expect(screen.getByText("22 reps")).toBeInTheDocument();
    expect(await screen.findByText("Reps counted")).toBeInTheDocument();
    expect(
      screen.getByText(/Server minus phone: -8 reps · allowed difference 2 reps · outside the allowed difference/),
    ).toBeInTheDocument();
    expect(screen.getByText("Not decided")).toBeInTheDocument();

    expect(api.review).toHaveBeenCalledWith("r1");
    expect(api.verification).toHaveBeenCalledWith("r1");
  });

  it("summarises verification and integrity without deciding anything", async () => {
    renderPage();

    expect(await screen.findByText("Processing took")).toBeInTheDocument();
    expect(screen.getByText("4.2 s")).toBeInTheDocument();
    expect(screen.getAllByText("Phone and server scores disagree").length).toBeGreaterThan(0);
    expect(screen.getByText("1 flag(s), 1 still open.")).toBeInTheDocument();
    expect(screen.getByText("matched the registration photo")).toBeInTheDocument();
    // Decisions happen in the review view, not here.
    expect(screen.getByRole("link", { name: "Review and decide" })).toHaveAttribute(
      "href",
      "/admin/reviews/0f3c2a1b-0000-4000-8000-000000000001",
    );
    expect(screen.queryByRole("button", { name: /approve/i })).not.toBeInTheDocument();
  });

  it("says when a submission was not part of a session", async () => {
    renderPage(async () => reviewDetail({ session_id: null, session_name: null, allowed_actions: [] }));

    expect(await screen.findByText("Not part of a session")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open review view" })).toBeInTheDocument();
  });

  it("treats another region's submission as not found", async () => {
    renderPage(async () => {
      throw new ApiError(404, "Result not found");
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("outside your region");
  });

  it("still shows the submission when only the verification details fail", async () => {
    renderPage(undefined, async () => {
      throw new ApiError(500, "boom");
    });

    expect(await screen.findByRole("heading", { name: "Meera — Sit-ups" })).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load verification details");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
