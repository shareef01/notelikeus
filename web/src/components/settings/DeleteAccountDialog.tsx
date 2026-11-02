import {
  ModalDialog,
  dialogCancelButtonClass,
  dialogDangerButtonClass,
} from '@/components/ui/ModalDialog';
import { ERROR_ALERT, WARNING_ALERT } from '@/lib/ui/alertStyles';
import type { BackupExportOutcome } from '@/lib/backup/bundle/bundleTransfer';
import { useEffect, useId, useState } from 'react';

/** Exact, and in capitals, so it cannot be typed by reflex. */
export const DELETE_ACCOUNT_CONFIRMATION = 'DELETE';

interface DeleteAccountDialogProps {
  open: boolean;
  onCancel: () => void;
  /** The complete backup. Resolves to what it contained, or null when it failed outright. */
  onExportBackup: () => Promise<BackupExportOutcome | null> | void;
  onConfirm: () => Promise<void>;
}

type BackupState = 'none' | 'exporting' | 'failed' | BackupExportOutcome;

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

/**
 * The last step before an irreversible delete.
 *
 * The backup offered here has to tell the truth about itself, because it is the thing the user is
 * relying on: a backup that left images out is shown as incomplete, with the count, and deleting after
 * one needs a second, explicit acknowledgement that those images will be lost (AUD-001). Deleting
 * without exporting anything stays possible: the account is the user's to delete.
 */
export function DeleteAccountDialog({
  open,
  onCancel,
  onExportBackup,
  onConfirm,
}: DeleteAccountDialogProps) {
  const [typed, setTyped] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [backup, setBackup] = useState<BackupState>('none');
  const [acceptedLoss, setAcceptedLoss] = useState(false);
  const titleId = useId();
  const inputId = useId();
  const lossId = useId();

  // A reopened dialog never starts already armed, and never carries an earlier export's answer.
  useEffect(() => {
    if (!open) {
      setTyped('');
      setBackup('none');
      setAcceptedLoss(false);
    }
  }, [open]);

  const exportBackup = async () => {
    setBackup('exporting');
    setAcceptedLoss(false);
    const outcome = await onExportBackup();
    // A caller that reports nothing back gets no claim about completeness either way.
    setBackup(outcome === undefined ? 'none' : (outcome ?? 'failed'));
  };

  const incomplete = typeof backup === 'object' && !backup.complete;
  const armed =
    typed === DELETE_ACCOUNT_CONFIRMATION && !deleting && backup !== 'exporting' && (!incomplete || acceptedLoss);

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
        onClick={() => void exportBackup()}
        disabled={backup === 'exporting' || deleting}
        className="mt-3 w-full rounded-note border border-brand-muted/40 py-2.5 text-sm font-semibold transition-colors hover:bg-brand-muted/10 disabled:opacity-50"
      >
        {backup === 'exporting' ? 'Exporting a complete backup…' : 'Export a complete backup first'}
      </button>
      {typeof backup === 'object' && backup.complete && (
        <p role="status" className="mt-2 text-sm text-brand-muted">
          Complete backup saved: your notes and all{' '}
          {backup.total === 1 ? '1 image' : `${backup.total} images`} are in the file.
        </p>
      )}
      {incomplete && (
        <div role="alert" className={`${WARNING_ALERT} mt-2 px-3 py-2 text-sm`}>
          <p>
            This backup is incomplete: {plural(backup.total - backup.included, 'image')} of{' '}
            {backup.total} could not be included. Deleting your account deletes{' '}
            {backup.total - backup.included === 1 ? 'it' : 'them'} for good. Try the export again
            while online, or continue only if you accept the loss.
          </p>
          <label htmlFor={lossId} className="mt-2 flex items-start gap-2">
            <input
              id={lossId}
              type="checkbox"
              checked={acceptedLoss}
              onChange={(event) => setAcceptedLoss(event.target.checked)}
              className="mt-0.5"
            />
            <span>
              I understand {plural(backup.total - backup.included, 'image')} will be permanently lost
            </span>
          </label>
        </div>
      )}
      {backup === 'failed' && (
        <p role="alert" className={`${WARNING_ALERT} mt-2 px-3 py-2 text-sm`}>
          The backup failed, so nothing was saved. Nothing has been deleted.
        </p>
      )}
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
          disabled={!armed}
          className={`${dialogDangerButtonClass} disabled:opacity-50`}
        >
          {deleting ? 'Deleting…' : 'Delete account'}
        </button>
      </div>
    </ModalDialog>
  );
}
