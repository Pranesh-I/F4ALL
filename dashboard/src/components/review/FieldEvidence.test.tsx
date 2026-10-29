import { render, screen, within } from "@testing-library/react";
import { FieldEvidence, type FieldServerResult, formatPace, isFieldResult } from "./FieldEvidence";

const shuttle: FieldServerResult = {
  field: true,
  status: "COMPLETE",
  score: 9.71,
  invalid_reason: null,
  metrics: { turns: 3, leg_ms: [2690, 2410, 2400, 2210] },
  events: [
    [0, "start", ""],
    [2690, "turn", "1"],
    [5100, "turn", "2"],
    [7500, "turn", "3"],
    [9710, "finish", ""],
  ],
  samples: { motion: 1250, fixes: 0 },
};

const endurance: FieldServerResult = {
  field: true,
  status: "COMPLETE",
  score: 132.7,
  invalid_reason: null,
  metrics: { distance_m: 600, pace_s_per_km: 221.1, cadence_spm: 170.1, gps_jumps: 2 },
  events: [
    [0, "start", ""],
    [22300, "split", "100"],
    [44500, "split", "200"],
    [132700, "finish", ""],
  ],
  samples: { motion: 3400, fixes: 140 },
  track: [
    [12.9716, 77.5946],
    [12.9726, 77.5946],
    [12.9736, 77.5947],
    [12.9770, 77.5947],
  ],
};

describe("FieldEvidence", () => {
  it("shows a shuttle run's turns and lengths as the server measured them", () => {
    render(<FieldEvidence result={shuttle} testType="SHUTTLE_RUN" />);

    expect(screen.getByText("3 of 3")).toBeInTheDocument();
    expect(screen.getByText("2.69 s, 5.10 s, 7.50 s")).toBeInTheDocument();
    const lengths = within(screen.getByRole("list", { name: "Lengths" }));
    expect(lengths.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "1: 2.69 s",
      "2: 2.41 s",
      "3: 2.40 s",
      "4: 2.21 s",
    ]);
    expect(screen.getByText(/1250 motion samples/)).toBeInTheDocument();
  });

  it("shows an endurance run's distance, pace, cadence, splits and track", () => {
    render(<FieldEvidence result={endurance} testType="ENDURANCE_RUN" />);

    expect(screen.getByText("600 m")).toBeInTheDocument();
    expect(screen.getByText("3:41 /km")).toBeInTheDocument();
    expect(screen.getByText("170.1 steps/min")).toBeInTheDocument();
    expect(screen.getByText(/100 m 22.3 s · 200 m 44.5 s/)).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "GPS track of 4 points" })).toBeInTheDocument();
  });

  it("recognises a field result and nothing else", () => {
    expect(isFieldResult(shuttle)).toBe(true);
    expect(isFieldResult({ rep_count: 12 })).toBe(false);
    expect(isFieldResult(null)).toBe(false);
  });

  it("formats pace as minutes and seconds per kilometre", () => {
    expect(formatPace(221.1)).toBe("3:41 /km");
    expect(formatPace(299.6)).toBe("5:00 /km");
  });
});
