import {
  ModalDialog,
  dialogCancelButtonClass,
  dialogDangerButtonClass,
} from '@/components/ui/ModalDialog';
import { ERROR_ALERT } from '@/lib/ui/alertStyles';
import { useEffect, useId, useState } from 'react';

/** Exact, and in capitals, so it cannot be typed by reflex. */
export const DELETE_ACCOUNT_CONFIRMATION = 'DELETE';

interface DeleteAccountDialogProps {
  open: boolean;
  onCancel: () => void;
  onExportBackup: () => void;
  onConfirm: () => Promise<void>;
}

export function DeleteAccountDialog({
  open,
  onCancel,
  onExportBackup,
  onConfirm,
}: DeleteAccountDialogProps) {
  const [typed, setTyped] = useState('');
  const [deleting, setDeleting] = useState(false);
  const titleId = useId();
  const inputId = useId();

  // A reopened dialog never starts already armed.
  useEffect(() => {
    if (!open) setTyped('');
  }, [open]);

  const confirm = async () => {
    setDeleting(true);
    try {
      await onConfirm();
    } finally {
      setDeleting(false);
    }
  };

  return (
    <ModalDialog open={open} onClose={onCancel} ariaLabelledBy={titleId}>
      <h2 id={titleId} className="text-lg font-semibold text-red-700 dark:text-red-300">
        Delete your account?
      </h2>
      <p className="mt-2 text-sm text-brand-muted">
        This permanently deletes your account, your notes and your images from Notelikeus cloud, and
        removes them from this browser, including any edits that haven&apos;t been uploaded yet. Your
        other signed-in devices remove their copies the next time they sync.
      </p>
      <p className={`${ERROR_ALERT} mt-3 px-3 py-2 text-sm`}>
        This happens right away and cannot be undone.
      </p>
      <button
        type="button"
        onClick={onExportBackup}
        className="mt-3 w-full rounded-note border border-brand-muted/40 py-2.5 text-sm font-semibold transition-colors hover:bg-brand-muted/10"
      >
        Export a backup first
      </button>
      <label htmlFor={inputId} className="mt-4 block text-sm">
        Type {DELETE_ACCOUNT_CONFIRMATION} to confirm
      </label>
      <input
        id={inputId}
        type="text"
        value={typed}
        onChange={(event) => setTyped(event.target.value)}
        autoComplete="off"
        spellCheck={false}
        className="mt-1 w-full rounded-note border border-brand-muted/40 bg-transparent px-3 py-2 text-sm"
      />
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" onClick={onCancel} className={dialogCancelButtonClass}>
          Cancel
        </button>
        <button
          type="button"
          onClick={() => void confirm()}
          disabled={typed !== DELETE_ACCOUNT_CONFIRMATION || deleting}
          className={`${dialogDangerButtonClass} disabled:opacity-50`}
        >
          {deleting ? 'Deleting…' : 'Delete account'}
        </button>
      </div>
    </ModalDialog>
  );
}
