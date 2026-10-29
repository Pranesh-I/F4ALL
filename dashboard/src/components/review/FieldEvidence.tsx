/**
 * The evidence for a shuttle or endurance run (Sprint 18), where a video
 * would otherwise be: what the server measured from the phone's trace.
 *
 * Shuttle run: when each turn came and how long each length took. Endurance
 * run: the GPS track as recorded, the 100 m splits, the distance, the pace and
 * the step cadence that shows it was run on foot. Everything is the server's
 * own reading of the trace, not the phone's claim.
 */

type Event = [number, string, string];

export interface FieldServerResult {
  field: true;
  status: string;
  score: number | null;
  invalid_reason: string | null;
  metrics: {
    turns?: number;
    leg_ms?: number[];
    distance_m?: number;
    pace_s_per_km?: number;
    cadence_spm?: number;
    gps_jumps?: number;
    fixes_used?: number;
  };
  events: Event[];
  samples?: { motion: number; fixes: number };
  track?: [number, number][];
}

export function isFieldResult(value: unknown): value is FieldServerResult {
  return typeof value === "object" && value !== null && (value as { field?: unknown }).field === true;
}

const seconds = (ms: number) => `${(ms / 1000).toFixed(2)} s`;

export function formatPace(secondsPerKm: number): string {
  const whole = Math.round(secondsPerKm);
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, "0")} /km`;
}

export function FieldEvidence({ result, testType }: { result: FieldServerResult; testType: string }) {
  const { metrics } = result;
  const shuttle = testType === "SHUTTLE_RUN";
  const turns = result.events.filter(([, label]) => label === "turn");
  const splits = result.events.filter(([, label]) => label === "split");

  return (
    <div className="space-y-4 text-sm">
      <p className="text-pencil">
        No video: this test is timed by the phone&apos;s {shuttle ? "motion sensors" : "GPS and motion sensors"}. The
        figures below are the server&apos;s own reading of the trace the phone sent
        {result.samples ? ` (${result.samples.motion} motion samples${result.samples.fixes ? `, ${result.samples.fixes} GPS fixes` : ""})` : ""}.
      </p>

      {shuttle ? (
        <dl className="grid grid-cols-2 gap-3">
          <div>
            <dt className="label-caps text-xs text-pencil">Turns found</dt>
            <dd className="tabular-nums">{metrics.turns ?? 0} of 3</dd>
          </div>
          {turns.length > 0 && (
            <div>
              <dt className="label-caps text-xs text-pencil">Turns at</dt>
              <dd className="tabular-nums">{turns.map(([t]) => seconds(t)).join(", ")}</dd>
            </div>
          )}
          {metrics.leg_ms && (
            <div className="col-span-2">
              <dt className="label-caps text-xs text-pencil">Lengths</dt>
              <dd>
                <ol aria-label="Lengths" className="flex flex-wrap gap-2">
                  {metrics.leg_ms.map((leg, index) => (
                    <li key={index} className="rounded-xs border border-rule px-2 py-1 tabular-nums">
                      {index + 1}: {seconds(leg)}
                    </li>
                  ))}
                </ol>
              </dd>
            </div>
          )}
        </dl>
      ) : (
        <>
          <dl className="grid grid-cols-2 gap-3">
            <div>
              <dt className="label-caps text-xs text-pencil">GPS distance</dt>
              <dd className="tabular-nums">{metrics.distance_m ?? "—"} m</dd>
            </div>
            <div>
              <dt className="label-caps text-xs text-pencil">Pace</dt>
              <dd className="tabular-nums">{metrics.pace_s_per_km ? formatPace(metrics.pace_s_per_km) : "—"}</dd>
            </div>
            <div>
              <dt className="label-caps text-xs text-pencil">Step cadence</dt>
              <dd className="tabular-nums">{metrics.cadence_spm != null ? `${metrics.cadence_spm} steps/min` : "—"}</dd>
            </div>
            <div>
              <dt className="label-caps text-xs text-pencil">GPS jumps ignored</dt>
              <dd className="tabular-nums">{metrics.gps_jumps ?? 0}</dd>
            </div>
          </dl>
          {splits.length > 0 && (
            <p className="tabular-nums">
              <span className="label-caps text-xs text-pencil">Splits </span>
              {splits.map(([t, , metres]) => `${metres} m ${(t / 1000).toFixed(1)} s`).join(" · ")}
            </p>
          )}
          {result.track && result.track.length > 1 && <Track points={result.track} />}
        </>
      )}
    </div>
  );
}

const SIZE = 240;
const PAD = 12;

/** The GPS track, north up, scaled to fit; start in ink, finish in the stamp's violet. */
function Track({ points }: { points: [number, number][] }) {
  const midLat = points.reduce((sum, [lat]) => sum + lat, 0) / points.length;
  // Longitude degrees shrink towards the poles; scale them so the shape is true.
  const lonScale = Math.cos((midLat * Math.PI) / 180);
  const projected = points.map(([lat, lon]) => ({ x: lon * lonScale, y: lat }));
  const minX = Math.min(...projected.map((p) => p.x));
  const minY = Math.min(...projected.map((p) => p.y));
  const span = Math.max(
    Math.max(...projected.map((p) => p.x)) - minX,
    Math.max(...projected.map((p) => p.y)) - minY,
    1e-9,
  );
  const scaled = projected.map((p) => ({
    x: PAD + ((p.x - minX) / span) * (SIZE - 2 * PAD),
    y: SIZE - PAD - ((p.y - minY) / span) * (SIZE - 2 * PAD),
  }));
  const path = scaled.map((p, i) => `${i === 0 ? "M" : "L"}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ");
  const start = scaled[0];
  const finish = scaled[scaled.length - 1];

  return (
    <figure>
      <svg
        role="img"
        aria-label={`GPS track of ${points.length} points`}
        viewBox={`0 0 ${SIZE} ${SIZE}`}
        className="h-60 w-60 rounded-xs border border-rule bg-paper"
      >
        <path d={path} fill="none" stroke="currentColor" strokeWidth={2} strokeLinejoin="round" className="text-ink" />
        {start && <circle cx={start.x} cy={start.y} r={5} className="fill-ink" stroke="white" strokeWidth={2} />}
        {finish && <circle cx={finish.x} cy={finish.y} r={5} className="fill-stamp" stroke="white" strokeWidth={2} />}
      </svg>
      <figcaption className="mt-1 text-xs text-pencil">North up · start ● ink · finish ● violet</figcaption>
    </figure>
  );
}
