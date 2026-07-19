import { useFocusTrap } from '@/hooks/useFocusTrap';

interface SignOutDialogProps {
  open: boolean;
  onCancel: () => void;
  onSignOut: () => void;
  onSignOutAndDelete: () => void;
}

export function SignOutDialog({
  open,
  onCancel,
  onSignOut,
  onSignOutAndDelete,
}: SignOutDialogProps) {
  const panelRef = useFocusTrap<HTMLDivElement>(open, onCancel);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 animate-in fade-in duration-200 sm:items-center sm:p-6">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label="Sign out of Google?"
        className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl animate-in zoom-in-95 duration-200"
      >
        <h4 className="text-lg font-semibold">Sign out of Google?</h4>
        <p className="mt-2 text-sm text-brand-muted">
          Signing out clears notes on this device so another Google account cannot inherit them.
          Cloud copies remain unless you delete them. You&apos;ll need to sign in again to keep
          using Notelikeus.
        </p>
        <button
          type="button"
          onClick={onSignOutAndDelete}
          className="mt-4 w-full rounded-note py-2.5 text-sm font-semibold text-red-400 transition-colors hover:bg-red-950/30"
        >
          Sign out and delete cloud data
        </button>
        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-note px-4 py-2 text-sm text-brand-muted transition-colors hover:text-brand-primary"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onSignOut}
            className="rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-surface transition-transform active:scale-95"
          >
            Sign out
          </button>
        </div>
      </div>
    </div>
  );
}
