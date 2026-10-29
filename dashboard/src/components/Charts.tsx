import type { ReactNode } from "react";

/**
 * Two chart forms, drawn in plain HTML so they need no chart library and stay
 * legible on a slow connection:
 *
 * - `BarList` — horizontal bars for categories (states, tests, statuses), the
 *   label beside each bar and the value at its tip.
 * - `ColumnChart` — columns along an ordered axis (days, score bands).
 *
 * Every value comes from the server as-is; nothing is computed here beyond the
 * bar's length. Each chart carries a "Show the numbers" table, so no reading
 * depends on judging a bar's length or its colour, and each mark has a hover
 * title with its exact value.
 */

export interface Bar {
  key: string;
  label: string;
  value: number;
  /** Extra context for the hover title and the table, e.g. "12 athletes". */
  detail?: string;
  /** A token class for the bar, e.g. "bg-stamp". Ink unless a meaning calls for another. */
  tone?: string;
}

const TRACK_MAX = 100;

export function BarList({ bars, caption, valueLabel }: { bars: Bar[]; caption: string; valueLabel: string }) {
  const max = Math.max(1, ...bars.map((bar) => bar.value));
  return (
    <figure className="space-y-2">
      <ul aria-label={caption} className="space-y-1.5">
        {bars.map((bar) => {
          const title = `${bar.label}: ${bar.value} ${valueLabel}${bar.detail ? ` · ${bar.detail}` : ""}`;
          return (
            <li key={bar.key} title={title} className="group grid grid-cols-[minmax(7rem,11rem)_1fr] items-center gap-3 text-sm">
              <span className="truncate text-ink" title={bar.label}>
                {bar.label}
              </span>
              <span className="flex items-center gap-2">
                <span
                  aria-hidden="true"
                  className={`h-4 rounded-r-[4px] group-hover:opacity-80 ${bar.tone ?? "bg-ink"}`}
                  style={{ width: `${(bar.value / max) * TRACK_MAX}%`, minWidth: bar.value > 0 ? 2 : 0 }}
                />
                <span className="tabular-nums text-ink">{bar.value}</span>
              </span>
            </li>
          );
        })}
      </ul>
      <NumbersTable caption={caption} head={["", valueLabel, ""]}>
        {bars.map((bar) => (
          <tr key={bar.key} className="border-t border-rule">
            <th scope="row" className="px-2 py-1 text-left font-normal">
              {bar.label}
            </th>
            <td className="px-2 py-1 text-right tabular-nums">{bar.value}</td>
            <td className="px-2 py-1 text-pencil">{bar.detail}</td>
          </tr>
        ))}
      </NumbersTable>
    </figure>
  );
}

export interface Column {
  key: string;
  /** Axis label; shown for the first, middle and last columns only. */
  label: string;
  value: number;
  detail?: string;
}

const PLOT_HEIGHT = 160;

export function ColumnChart({
  columns,
  caption,
  valueLabel,
}: {
  columns: Column[];
  caption: string;
  valueLabel: string;
}) {
  const max = Math.max(1, ...columns.map((column) => column.value));
  const labelled = new Set([0, Math.floor((columns.length - 1) / 2), columns.length - 1]);
  return (
    <figure className="space-y-2">
      <div className="flex gap-2">
        {/* One recessive scale mark: the top of the plot. */}
        <div aria-hidden="true" className="flex w-8 flex-col justify-between text-right text-xs tabular-nums text-pencil" style={{ height: PLOT_HEIGHT }}>
          <span>{max}</span>
          <span>0</span>
        </div>
        <div className="min-w-0 flex-1">
          <ol
            aria-label={caption}
            className="flex items-end gap-[2px] border-b border-line"
            style={{ height: PLOT_HEIGHT }}
          >
            {columns.map((column) => (
              <li
                key={column.key}
                title={`${column.label}: ${column.value} ${valueLabel}${column.detail ? ` · ${column.detail}` : ""}`}
                className="group flex h-full flex-1 items-end justify-center"
              >
                <span
                  aria-hidden="true"
                  className="w-full max-w-6 rounded-t-[4px] bg-ink group-hover:opacity-80"
                  style={{ height: `${(column.value / max) * 100}%`, minHeight: column.value > 0 ? 2 : 0 }}
                />
                <span className="sr-only">
                  {column.label}: {column.value}
                </span>
              </li>
            ))}
          </ol>
          <div aria-hidden="true" className="mt-1 flex text-xs text-pencil">
            {columns.map((column, index) => (
              <span key={column.key} className="flex-1 truncate text-center">
                {labelled.has(index) ? column.label : ""}
              </span>
            ))}
          </div>
        </div>
      </div>
      <NumbersTable caption={caption} head={["", valueLabel, ""]}>
        {columns.map((column) => (
          <tr key={column.key} className="border-t border-rule">
            <th scope="row" className="px-2 py-1 text-left font-normal">
              {column.label}
            </th>
            <td className="px-2 py-1 text-right tabular-nums">{column.value}</td>
            <td className="px-2 py-1 text-pencil">{column.detail}</td>
          </tr>
        ))}
      </NumbersTable>
    </figure>
  );
}

function NumbersTable({ caption, head, children }: { caption: string; head: string[]; children: ReactNode }) {
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-pencil hover:text-ink">Show the numbers</summary>
      <table className="mt-2 min-w-full">
        <caption className="sr-only">{caption}</caption>
        <thead className="text-left text-xs text-pencil">
          <tr>
            {head.map((cell, index) => (
              <th key={index} scope="col" className={`px-2 py-1 font-normal ${index === 1 ? "text-right" : ""}`}>
                {cell}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </details>
  );
}
