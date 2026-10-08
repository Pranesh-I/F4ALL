/**
 * Class names for the controls every page shares, so a button or field looks
 * the same wherever it appears. Paper corners (2px), ink rules, no shadows.
 */
const button =
  "inline-flex items-center justify-center gap-2 rounded-xs px-4 py-2 text-sm font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50";

export const ui = {
  /** A firm, ordinary action. */
  buttonInk: `${button} bg-ink text-paper hover:bg-ink-raised`,
  /** The second choice beside an ink button. */
  buttonLine: `${button} border border-ink bg-paper text-ink hover:bg-paper-shade`,
  /** The one action that matters on the page. Marigold, used once. */
  buttonMarigold: `${button} bg-marigold text-ink hover:brightness-95`,
  /** Destructive: reject, remove. */
  buttonRedPen: `${button} bg-redpen text-paper hover:bg-redpen-dark`,
  /** An approval: the stamp's own colour. */
  buttonStamp: `${button} bg-stamp text-paper hover:brightness-110`,
  /** A quiet action in running text. */
  buttonPlain: "rounded-xs px-2 py-1 text-sm font-semibold text-ink underline decoration-line underline-offset-4 hover:decoration-ink",

  link: "font-medium text-ink underline decoration-line underline-offset-4 hover:decoration-ink",

  input: "w-full rounded-xs border border-line bg-paper px-3 py-2 text-sm text-ink placeholder:text-pencil focus-visible:border-ink",
  select: "rounded-xs border border-line bg-paper px-3 py-2 text-sm text-ink focus-visible:border-ink",

  /** A sheet of paper: the unit every page is built from. */
  sheet: "rounded-xs border border-ink bg-paper",
} as const;
