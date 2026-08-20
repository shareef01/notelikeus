import {
  ModalDialog,
  dialogCancelButtonClass,
  dialogDangerButtonClass,
  dialogPrimaryButtonClass,
} from '@/components/ui/ModalDialog';
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
  const [confirmDelete, setConfirmDelete] = useState(false);

  return (
    <ModalDialog
      open={open}
      onClose={onCancel}
      ariaLabel={confirmDelete ? 'Confirm cloud data deletion' : 'Sign out of Google?'}
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
            <button type="button" onClick={onCancel} className={dialogCancelButtonClass}>
              Cancel
            </button>
            <button type="button" onClick={onSignOut} className={dialogPrimaryButtonClass}>
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
              className={dialogCancelButtonClass}
            >
              Back
            </button>
            <button
              type="button"
              onClick={onSignOutAndDelete}
              className={dialogDangerButtonClass}
            >
              Delete cloud data and sign out
            </button>
          </div>
        </>
      )}
    </ModalDialog>
  );
}
