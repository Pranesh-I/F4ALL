import { fireEvent, render, screen } from "@testing-library/react";
import { VideoWithSkeleton } from "./VideoWithSkeleton";

function failWith(code: number) {
  const { container } = render(<VideoWithSkeleton src="https://media.example/signed" pose={null} />);
  const video = container.querySelector("video")!;
  Object.defineProperty(video, "error", { value: { code } });
  fireEvent.error(video);
}

describe("VideoWithSkeleton", () => {
  it("says when the browser cannot play the format, and offers the recording itself", () => {
    failWith(4);
    expect(screen.getByText(/cannot play the recording's video format/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Open the recording/ })).toHaveAttribute("href", "https://media.example/signed");
  });

  it("suggests reloading when the recording could not be fetched", () => {
    failWith(2);
    expect(screen.getByText(/link may have expired/)).toBeInTheDocument();
  });
});
