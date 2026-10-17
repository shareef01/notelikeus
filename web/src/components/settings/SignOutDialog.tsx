import {
  ModalDialog,
  dialogCancelButtonClass,
  dialogDangerButtonClass,
  dialogPrimaryButtonClass,
} from '@/components/ui/ModalDialog';
import { useId, useState } from 'react';
import { useUnsyncedEditCount } from '@/hooks/useUnsyncedEditCount';
import { ERROR_ALERT, WARNING_ALERT } from '@/lib/ui/alertStyles';

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
  const titleId = useId();
  const unsyncedEdits = useUnsyncedEditCount(open);

  return (
    <ModalDialog open={open} onClose={onCancel} ariaLabelledBy={titleId}>
      {!confirmDelete ? (
        <>
          <h2 id={titleId} className="text-lg font-semibold">
            Sign out?
          </h2>
          <p className="mt-2 text-sm text-brand-muted">
            Your notes stay in your Notelikeus cloud account and aren&apos;t affected by ordinary sign out.
            This only clears locally cached app data on this device, and you&apos;ll need to sign
            in again to keep using Notelikeus.
          </p>
          {unsyncedEdits > 0 && (
            <p
              role="status"
              className={`${WARNING_ALERT} mt-3 px-3 py-2 text-sm`}
            >
              {unsyncedEdits === 1
                ? '1 note has an edit that hasn’t been uploaded yet.'
                : `${unsyncedEdits} notes have edits that haven’t been uploaded yet.`}{' '}
              It stays on this browser and uploads the next time you sign in to this account here.
            </p>
          )}
          <button
            type="button"
            onClick={() => setConfirmDelete(true)}
            className="mt-4 w-full rounded-note border border-red-900/40 py-2.5 text-sm font-semibold text-red-700 dark:text-red-400 transition-colors hover:bg-red-500/10 dark:hover:bg-red-950/30"
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
          <h2 id={titleId} className="text-lg font-semibold text-red-700 dark:text-red-300">
            Delete cloud data and sign out?
          </h2>
          <p className="mt-2 text-sm text-brand-muted">
            This permanently deletes this account&apos;s notes from Notelikeus cloud and from this
            browser, including any edits that haven&apos;t been uploaded yet. This action cannot be
            undone unless you have a backup or another copy elsewhere.
          </p>
          <p className={`${ERROR_ALERT} mt-3 px-3 py-2 text-sm`}>
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
