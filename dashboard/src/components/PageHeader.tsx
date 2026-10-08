import type { ReactNode } from "react";

/** The page's title in the condensed chest-number face, with its context and actions. */
export function PageHeader({
  title,
  description,
  actions,
  back,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  back?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        {back && <div className="mb-1 text-sm">{back}</div>}
        <h1 className="text-3xl font-bold leading-tight [font-stretch:75%]">{title}</h1>
        {description && <div className="mt-1 text-sm text-pencil">{description}</div>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}
