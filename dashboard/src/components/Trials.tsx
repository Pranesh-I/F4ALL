import type { ReactNode } from "react";
import { bibNumber } from "../lib/format";

/** A thick and a thin rule: lane markings on the ground, used under headers. */
export function LaneLines({ className = "" }: { className?: string }) {
  return (
    <div aria-hidden="true" className={`space-y-[3px] ${className}`}>
      <div className="h-[3px] bg-current" />
      <div className="h-px bg-current" />
    </div>
  );
}

/**
 * The athlete's chest number, as the athlete app shows it: the short ID on a
 * pinned paper bib. Officials and athletes can name the same person.
 */
export function Bib({ athleteId, name, detail }: { athleteId: string; name?: ReactNode; detail?: ReactNode }) {
  const number = bibNumber(athleteId);
  return (
    <div
      aria-label={`Athlete number ${number}`}
      className="relative inline-flex min-w-48 flex-col rounded-xs border-2 border-ink bg-paper px-6 py-3"
    >
      {/* The four safety-pin holes. */}
      {["left-2 top-2", "right-2 top-2", "bottom-2 left-2", "bottom-2 right-2"].map((place) => (
        <span key={place} aria-hidden="true" className={`absolute h-1.5 w-1.5 rounded-full border border-ink bg-ground ${place}`} />
      ))}
      <span className="numerals text-4xl leading-none">{number}</span>
      {name && <span className="mt-1 font-semibold">{name}</span>}
      {detail && <span className="text-xs text-pencil">{detail}</span>}
    </div>
  );
}

/**
 * How final a score is, told by the tool it is written with:
 * the phone's count in pencil, SAI's measurement in pen, an approval stamped.
 */
export type Ink = "pencil" | "pen" | "official" | "struck";

export function ScoreMark({ value, ink, size = "lg" }: { value: string; ink: Ink; size?: "lg" | "sm" }) {
  const scale = size === "lg" ? "text-4xl" : "text-2xl";
  if (ink === "pencil") {
    return (
      <span className={`numerals inline-block rounded-xs border-2 border-dashed border-pencil px-2 leading-tight text-pencil ${scale}`}>
        {value}
      </span>
    );
  }
  return (
    <span
      className={`numerals inline-block leading-tight ${scale} ${
        ink === "struck" ? "text-pencil line-through decoration-redpen decoration-[3px]" : "text-ink"
      }`}
    >
      {value}
    </span>
  );
}

/** The violet rubber stamp. Only ever means "approved by an official". */
export function Stamp({
  children = "Official",
  caption,
  decorative = false,
}: {
  children?: ReactNode;
  caption?: ReactNode;
  /** True when the words beside it already say the same thing. */
  decorative?: boolean;
}) {
  return (
    <span
      aria-hidden={decorative || undefined}
      className="inline-flex -rotate-6 flex-col items-center rounded-[4px] border-[3px] border-stamp p-[3px] text-stamp opacity-95"
    >
      <span className="flex flex-col items-center rounded-[2px] border border-stamp px-3 py-0.5">
        <span className="label-caps text-base font-extrabold">{children}</span>
        {caption && <span className="text-[10px] font-semibold uppercase tracking-wider">{caption}</span>}
      </span>
    </span>
  );
}

/** A section heading as a scoresheet prints it: capitalised label over a rule. */
export function SectionLabel({ children, id, action }: { children: ReactNode; id?: string; action?: ReactNode }) {
  return (
    <div className="flex items-end justify-between gap-3 border-b-2 border-ink pb-1">
      <h2 id={id} className="label-caps text-sm">
        {children}
      </h2>
      {action}
    </div>
  );
}
