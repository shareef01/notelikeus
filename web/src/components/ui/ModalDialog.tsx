import type { ReactNode } from 'react';
import { useId } from 'react';
import { useFocusTrap } from '@/hooks/useFocusTrap';

/** Shared button styling for dialog footers. */
export const dialogCancelButtonClass =
  'rounded-note px-4 py-2 text-sm text-brand-muted transition-colors hover:text-brand-primary';
export const dialogDangerButtonClass =
  'rounded-note bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:opacity-40 disabled:hover:bg-red-600';
export const dialogPrimaryButtonClass =
  'rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-surface transition-transform active:scale-95';

interface ModalDialogProps {
  open: boolean;
  /** Escape and focus-trap escape hatch — the dialog's own cancel action. */
  onClose: () => void;
  /** Used when the dialog has no visible heading. Prefer `ariaLabelledBy` when it does. */
  ariaLabel?: string;
  /** Id of the visible heading. Takes precedence over `ariaLabel`. */
  ariaLabelledBy?: string;
  children: ReactNode;
}

/**
 * Centered modal panel over a dimmed backdrop (bottom-anchored on mobile), with focus trapped
 * inside it. The scaffold every small dialog shares; sheets use ResponsiveSheet instead.
 */
export function ModalDialog({
  open,
  onClose,
  ariaLabel,
  ariaLabelledBy,
  children,
}: ModalDialogProps) {
  const panelRef = useFocusTrap<HTMLDivElement>(open, onClose);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 animate-in fade-in duration-200 sm:items-center sm:p-6">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={ariaLabelledBy}
        aria-label={ariaLabelledBy ? undefined : ariaLabel}
        className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl animate-in zoom-in-95 duration-200"
      >
        {children}
      </div>
    </div>
  );
}

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: ReactNode;
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: () => void;
  confirmDisabled?: boolean;
}

/** Destructive confirmation: title, explanation, Cancel and a red confirm button. */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  onCancel,
  onConfirm,
  confirmDisabled = false,
}: ConfirmDialogProps) {
  const titleId = useId();
  return (
    <ModalDialog open={open} onClose={onCancel} ariaLabelledBy={titleId}>
      <h2 id={titleId} className="text-lg font-semibold">
        {title}
      </h2>
      <p className="mt-2 text-sm text-brand-muted">{description}</p>
      <div className="mt-5 flex justify-end gap-2">
        <button type="button" onClick={onCancel} className={dialogCancelButtonClass}>
          Cancel
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={confirmDisabled}
          className={dialogDangerButtonClass}
        >
          {confirmLabel}
        </button>
      </div>
    </ModalDialog>
  );
}
