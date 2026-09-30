import type { ReactNode } from "react";

/** A titled white card: the unit every review and detail page is built from. */
export function Panel({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section aria-label={title} className="rounded border border-slate-200 bg-white p-4">
      <div className="mb-3 flex items-start justify-between gap-2">
        <h2 className="font-semibold">{title}</h2>
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
      <dt className="text-slate-500">{label}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  );
}
