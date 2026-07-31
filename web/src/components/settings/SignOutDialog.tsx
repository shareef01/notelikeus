import { useFocusTrap } from '@/hooks/useFocusTrap';
import { useState } from 'react';

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
  const [confirmDelete, setConfirmDelete] = useState(false);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 animate-in fade-in duration-200 sm:items-center sm:p-6">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={confirmDelete ? 'Confirm cloud data deletion' : 'Sign out of Google?'}
        className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl animate-in zoom-in-95 duration-200"
      >
        {!confirmDelete ? (
          <>
            <h4 className="text-lg font-semibold">Sign out of Google?</h4>
            <p className="mt-2 text-sm text-brand-muted">
              Your notes stay in your Google account and aren&apos;t affected by ordinary sign out.
              This only clears locally cached app data on this device, and you&apos;ll need to sign
              in again to keep using Notelikeus.
            </p>
            <button
              type="button"
              onClick={() => setConfirmDelete(true)}
              className="mt-4 w-full rounded-note border border-red-900/40 py-2.5 text-sm font-semibold text-red-400 transition-colors hover:bg-red-950/30"
            >
              Review permanent cloud deletion…
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
          </>
        ) : (
          <>
            <h4 className="text-lg font-semibold text-red-300">Delete cloud data and sign out?</h4>
            <p className="mt-2 text-sm text-brand-muted">
              This permanently deletes your synced notes from Firestore for this Google account.
              It does not just remove this device. This action cannot be undone unless you have a
              backup or another copy elsewhere.
            </p>
            <p className="mt-3 rounded-note border border-red-900/30 bg-red-950/20 px-3 py-2 text-sm text-red-200">
              Use this only if you want to erase your cloud notes, not if you only want to sign
              out on this device.
            </p>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setConfirmDelete(false)}
                className="rounded-note px-4 py-2 text-sm text-brand-muted transition-colors hover:text-brand-primary"
              >
                Back
              </button>
              <button
                type="button"
                onClick={onSignOutAndDelete}
                className="rounded-note bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700"
              >
                Delete cloud data and sign out
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
