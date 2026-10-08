import { useEffect, useId, useRef, type ReactNode } from "react";
import { ui } from "../lib/ui";

/**
 * A modal question with two explicit answers. The confirm button carries the
 * action's own name ("Reject submission"), never a bare "OK", so what is about
 * to happen is on the button that does it.
 */
export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  cancelLabel = "Cancel",
  tone = "neutral",
  busy = false,
  onConfirm,
  onCancel,
}: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  /** "approve" confirms with the stamp's violet: the only place it acts. */
  tone?: "neutral" | "danger" | "approve";
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    // The safe answer has focus, so Enter does not confirm by accident.
    cancelRef.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onCancel]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/50 px-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-md space-y-4 rounded-xs border-2 border-ink bg-paper p-5"
      >
        <h2 id={titleId} className="text-2xl font-bold leading-tight [font-stretch:75%]">
          {title}
        </h2>
        <div className="space-y-2 text-sm text-ink">{children}</div>
        <div className="flex justify-end gap-2">
          <button
            ref={cancelRef}
            type="button"
            onClick={onCancel}
            disabled={busy}
            className={ui.buttonLine}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className={tone === "danger" ? ui.buttonRedPen : tone === "approve" ? ui.buttonStamp : ui.buttonInk}
          >
            {busy ? "Saving…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
