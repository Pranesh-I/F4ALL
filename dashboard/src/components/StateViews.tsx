import type { ReactNode } from "react";
import { ApiError } from "../api/client";

/** Shown while the server is being asked. Never a blank screen. */
export function LoadingState({ label = "Loading…" }: { label?: string }) {
  return (
    <div role="status" aria-live="polite" className="flex items-center gap-2 px-3 py-6 text-sm text-pencil">
      <span
        aria-hidden="true"
        className="h-4 w-4 animate-spin rounded-full border-2 border-line border-t-ink"
      />
      {label}
    </div>
  );
}

/** The server answered, and there is nothing yet. Says so, and what comes next. */
export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="rounded-xs border border-dashed border-line bg-paper px-4 py-8 text-center">
      <p className="font-medium text-ink">{title}</p>
      {children && <p className="mx-auto mt-1 max-w-prose text-sm text-pencil">{children}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

/** What went wrong, in terms an official can act on. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return "Your account does not have permission to see this.";
    if (error.status === 404) return "It does not exist, or it is outside your region.";
    if (error.status >= 500) return "The server had a problem. Try again in a moment.";
    return error.message;
  }
  // fetch rejects with a TypeError when the network is down.
  return "Could not reach the server. Check your connection.";
}

export function ErrorState({
  title = "Something went wrong",
  error,
  onRetry,
}: {
  title?: string;
  error: unknown;
  onRetry?: () => void;
}) {
  const retryable = !(error instanceof ApiError && [403, 404].includes(error.status));
  return (
    <div role="alert" className="rounded-xs border border-redpen bg-redpen-tint px-4 py-3 text-redpen">
      <p className="font-medium">{title}</p>
      <p className="text-sm">{describeError(error)}</p>
      {onRetry && retryable && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-2 rounded-xs border border-redpen bg-paper px-3 py-1 text-sm font-semibold hover:bg-redpen-tint"
        >
          Try again
        </button>
      )}
    </div>
  );
}

/** Signed in, but this page is for another role. The API refuses it regardless. */
export function NotPermitted({ children }: { children?: ReactNode }) {
  return (
    <div role="alert" className="rounded-xs border border-marigold bg-marigold-tint px-4 py-3 text-ink">
      <p className="font-medium">You do not have permission to open this page.</p>
      {children && <p className="text-sm">{children}</p>}
    </div>
  );
}

/** Confirmation after something worked. */
export function SuccessNotice({ children }: { children: ReactNode }) {
  return (
    <div role="status" className="rounded-xs border-2 border-ink bg-paper px-4 py-3 text-sm font-medium text-ink">
      {children}
    </div>
  );
}
