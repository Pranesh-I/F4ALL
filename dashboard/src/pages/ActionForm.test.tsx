import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DashboardApi, type TokenStore } from "../api/client";
import type { ReviewDetail } from "../api/types";
import { AuthProvider } from "../auth/AuthContext";
import { ActionForm } from "./ReviewPage";

const store: TokenStore = { get: () => null, set: () => {}, clear: () => {} };

function detail(overrides: Partial<ReviewDetail> = {}): ReviewDetail {
  return {
    result_id: "r1",
    athlete_name: "Asha",
    region: "Kerala",
    test_type: "SIT_UPS",
    status: "flagged",
    provisional_score: 40,
    server_score: 22,
    final_score: null,
    unit: "reps",
    video_url: null,
    flags: [],
    created_at: "2026-09-01T10:00:00Z",
    verified_at: null,
    attempt_number: 1,
    athlete_id: "a1",
    athlete_age_years: 15,
    athlete_gender: "female",
    athlete_height_cm: 160,
    video_duration_seconds: 60,
    reference_photo_url: null,
    face_verification: null,
    has_pose_sequence: false,
    benchmark: null,
    review_history: [],
    allowed_actions: ["approved", "rejected", "requested_resubmission"],
    ...overrides,
  };
}

function renderForm(result: ReviewDetail, act = vi.fn(async () => ({ status: "x", message: "ok" }))) {
  const api = new DashboardApi("", store);
  api.act = act;
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider api={api}>
        <ActionForm result={result} />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return act;
}

describe("ActionForm", () => {
  it("requires a reason before rejecting", async () => {
    const act = renderForm(detail());

    fireEvent.click(screen.getByRole("radio", { name: "Reject" }));
    fireEvent.click(screen.getByRole("button", { name: "Record decision" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("athlete will see");
    expect(act).not.toHaveBeenCalled();
  });

  it("approves a server-scored result without asking for a score", async () => {
    const act = renderForm(detail());

    fireEvent.click(screen.getByRole("radio", { name: "Approve" }));
    fireEvent.click(screen.getByRole("button", { name: "Record decision" }));

    await waitFor(() => expect(act).toHaveBeenCalledWith("r1", "approved", "", undefined));
  });

  it("asks for a hand-entered score when the server could not measure", async () => {
    const act = renderForm(detail({ server_score: null }));

    fireEvent.click(screen.getByRole("radio", { name: "Approve" }));
    fireEvent.click(screen.getByRole("button", { name: "Record decision" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter the score");

    fireEvent.change(screen.getByLabelText(/Score from the recording/), { target: { value: "31" } });
    fireEvent.change(screen.getByLabelText(/Notes/), { target: { value: "Counted in the video" } });
    fireEvent.click(screen.getByRole("button", { name: "Record decision" }));

    await waitFor(() =>
      expect(act).toHaveBeenCalledWith("r1", "approved", "Counted in the video", 31),
    );
  });

  it("offers only the actions the server allows", () => {
    renderForm(detail({ allowed_actions: ["rejected"] }));

    expect(screen.queryByRole("radio", { name: "Approve" })).toBeNull();
    expect(screen.getByRole("radio", { name: "Reject" })).toBeInTheDocument();
  });
});
