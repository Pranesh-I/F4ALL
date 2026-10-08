import type { ReactNode } from "react";

/** A sheet of the scoresheet: the unit every review and detail page is built from. */
export function Panel({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-xs border border-ink bg-paper p-4">
      <div className="mb-3 flex items-end justify-between gap-2 border-b-2 border-ink pb-1">
        <h2 className="label-caps text-sm">{title}</h2>
        {actions}
      </div>
      {children}
    </section>
  );
}

/** A label and its value, for the `dl` grids inside panels. */
export function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="label-caps text-xs text-pencil">{label}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  );
}
