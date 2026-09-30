import type { ReactNode } from "react";
import { ApiError } from "../api/client";

/** Shown while the server is being asked. Never a blank screen. */
export function LoadingState({ label = "Loading…" }: { label?: string }) {
  return (
    <div role="status" aria-live="polite" className="flex items-center gap-2 px-3 py-6 text-sm text-slate-500">
      <span
        aria-hidden="true"
        className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-slate-600"
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
    <div className="rounded border border-dashed border-slate-300 bg-white px-4 py-8 text-center">
      <p className="font-medium text-slate-700">{title}</p>
      {children && <p className="mx-auto mt-1 max-w-prose text-sm text-slate-500">{children}</p>}
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
    <div role="alert" className="rounded border border-red-200 bg-red-50 px-4 py-3 text-red-800">
      <p className="font-medium">{title}</p>
      <p className="text-sm">{describeError(error)}</p>
      {onRetry && retryable && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-2 rounded border border-red-300 bg-white px-3 py-1 text-sm hover:bg-red-100"
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
    <div role="alert" className="rounded border border-amber-200 bg-amber-50 px-4 py-3 text-amber-900">
      <p className="font-medium">You do not have permission to open this page.</p>
      {children && <p className="text-sm">{children}</p>}
    </div>
  );
}

/** Confirmation after something worked. */
export function SuccessNotice({ children }: { children: ReactNode }) {
  return (
    <div role="status" className="rounded border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800">
      {children}
    </div>
  );
}
