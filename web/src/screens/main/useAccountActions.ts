import { commitImportedNotes } from '@/lib/backup/commitImportedNotes';
import { exportNotesBackup } from '@/lib/backup/exportBackup';
import { importNotesFromBackup, readBackupFile } from '@/lib/backup/importBackup';
import { looksLikeBundle } from '@/lib/backup/bundle/backupBundle';
import {
  buildBundleFromNotes,
  downloadBundle,
  planBundleImport,
  readBundleFile,
  releaseStagedBundle,
  stagePlannedAttachments,
  type BackupExportOutcome,
  type BundleExportMode,
  type PlannedBundleImport,
} from '@/lib/backup/bundle/bundleTransfer';
import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { formatUnknownError } from '@/lib/errors/formatUnknownError';
import { isReauthenticationRequired } from '@/lib/auth/reauthentication';
import { useToastStore } from '@/store/toastStore';
import type { Note } from '@/types/note';
import { useState } from 'react';

/** Every path here reports through the same toast, success or failure. */
function toast(message: string, kind?: 'error') {
  useToastStore.getState().show(message, kind);
}

function messageOf(error: unknown, fallback: string): string {
  return formatUnknownError(error, fallback);
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

interface AccountActionsDeps {
  notes: Note[];
  userId: string | undefined;
  closeSignOutConfirm: () => void;
  closeDeleteAccountConfirm: () => void;
  closeProfile: () => void;
}

/** A destructive action the server refused until the user confirms it is them. */
export type IdentityCheck = 'deleteAccount' | 'wipeCloudData';

/** A parsed backup waiting on the user to confirm that importing it is what they meant. */
export interface PendingImport {
  merged: Note[];
  result: { notesImported: number; labelsCreated: number };
  /** Images this import will restore. Zero for a plain JSON backup, which carries none. */
  attachmentsImported: number;
  /** Images the file named but could not supply. Reported, never fatal. */
  attachmentsSkipped: number;
  warnings: string[];
  /** A bundle's plan, whose images are only written once the user confirms. */
  bundle?: PlannedBundleImport;
}

/**
 * Sign-out and backup transfer — the account-level actions, as opposed to note-level ones.
 *
 * Lifted out of `MainScreen` alongside `useNoteActions`, and kept separate from it because the
 * failure modes are different in kind: a note action fails and offers an undo, while these fail
 * and can only say so.
 */
export function useAccountActions({
  notes,
  userId,
  closeSignOutConfirm,
  closeDeleteAccountConfirm,
  closeProfile,
}: AccountActionsDeps) {
  const [pendingImport, setPendingImport] = useState<PendingImport | null>(null);
  /** The destructive action waiting for a new sign-in code before it is retried (AUD-005). */
  const [identityCheck, setIdentityCheck] = useState<IdentityCheck | null>(null);

  const signOut = async (deleteCloudData: boolean) => {
    closeSignOutConfirm();
    closeProfile();

    try {
      const { signOutAccount } = await import('@/lib/auth/accountAuth');
      await signOutAccount({ deleteCloudData });
      toast(deleteCloudData ? 'Signed out and cloud data deleted' : 'Signed out');
    } catch (error) {
      // Nothing was wiped and the user is still signed in: ask for a code, then wipe.
      if (deleteCloudData && isReauthenticationRequired(error)) {
        setIdentityCheck('wipeCloudData');
        return;
      }
      toast(messageOf(error, 'Sign out failed'), 'error');
    }
  };

  /**
   * Leaves the dialog open on failure, with the toast saying why: nothing was deleted, and the user
   * should be able to try again without typing the confirmation a second time. When the server wants
   * a recent sign-in first, the confirmation is already given, so the code is all that is asked for.
   */
  const deleteAccount = async () => {
    try {
      const { deleteAccount: deleteAccountAndLocalCopy } = await import('@/lib/auth/accountAuth');
      await deleteAccountAndLocalCopy();
      closeDeleteAccountConfirm();
      closeProfile();
      toast('Your account was deleted');
    } catch (error) {
      if (isReauthenticationRequired(error)) {
        closeDeleteAccountConfirm();
        setIdentityCheck('deleteAccount');
        return;
      }
      toast(messageOf(error, 'Could not delete your account. Nothing was deleted.'), 'error');
    }
  };

  /** Retries the waiting action with the fresh session the code started. */
  const confirmIdentity = async () => {
    const action = identityCheck;
    try {
      const auth = await import('@/lib/auth/accountAuth');
      if (action === 'deleteAccount') {
        await auth.deleteAccount();
        closeProfile();
        toast('Your account was deleted');
      } else if (action === 'wipeCloudData') {
        await auth.signOutAccount({ deleteCloudData: true });
        toast('Signed out and cloud data deleted');
      }
    } catch (error) {
      toast(
        messageOf(
          error,
          action === 'deleteAccount'
            ? 'Could not delete your account. Nothing was deleted.'
            : 'Could not delete your cloud data. Nothing was deleted.',
        ),
        'error',
      );
    } finally {
      setIdentityCheck(null);
    }
  };

  /** The JSON backup, unchanged. Kept because it is the format other builds already read. */
  const exportBackup = () => {
    try {
      exportNotesBackup(notes);
      const totalAttachments = notes.reduce(
        (sum, n) => sum + (n.attachments?.length ?? 0),
        0,
      );
      if (totalAttachments > 0) {
        toast(
          'Notes backup exported. Images are not included in JSON backups (use Complete Backup to include images).',
        );
      } else {
        toast('Backup exported');
      }
    } catch (error) {
      toast(messageOf(error, 'Export failed'), 'error');
    }
  };

  /**
   * Builds and downloads a bundle, and says exactly how complete it is.
   *
   * "Complete" is only ever said when every image is in the file. Anything less is reported as an
   * incomplete backup with its numbers, and as an error, because the delete-account dialog offers this
   * export as the way to keep your data and a cheerful toast is what made a partial one look safe.
   */
  const exportBundle = async (mode: BundleExportMode): Promise<BackupExportOutcome | null> => {
    try {
      const downloadCloudAttachment =
        mode === 'complete'
          ? (noteId: string, attachmentId: string) =>
              getAttachmentBlobStore().download(noteId, attachmentId)
          : undefined;
      const bundle = await buildBundleFromNotes(notes, undefined, { mode, downloadCloudAttachment });
      downloadBundle(bundle.bytes);
      const { attachmentsIncluded: included, attachmentsTotal: total } = bundle;
      if (!bundle.complete) {
        toast(
          `Incomplete backup: ${included} of ${plural(total, 'image')} included, ${
            total - included
          } not in this file. ${bundle.warnings.join(' ')}`.trim(),
          'error',
        );
      } else if (total > 0) {
        toast(`Complete backup exported with all ${plural(total, 'image')}`);
      } else {
        toast('Complete backup exported');
      }
      return { complete: bundle.complete, included, total };
    } catch (error) {
      toast(messageOf(error, 'Export failed'), 'error');
      return null;
    }
  };

  /**
   * The complete backup: notes plus every image, downloading the ones that live only in the cloud.
   * Signed out (guest) every image is already on this device, so there is nothing to download.
   */
  const exportCompleteBackup = () =>
    exportBundle(userId && isR2AttachmentsEnabled() ? 'complete' : 'device');

  /** The offline backup: only images already stored on this device, and no network at all. */
  const exportDeviceBackup = async () => {
    await exportBundle('device');
  };

  /**
   * Parses the file and describes what importing it would do, without writing anything.
   *
   * Import adds the backup's notes as new notes rather than restoring over what is here, so
   * importing the same file twice produces two sets. That is a surprise worth showing before it
   * happens rather than explaining afterwards, which is why parsing and committing are separate.
   *
   * The format is decided by the file's own bytes — a ZIP signature — not by its extension, so a
   * bundle renamed by a mail client still imports and a JSON file named `.nlkbak` is not treated
   * as an archive.
   */
  const prepareImport = async (file: File) => {
    try {
      const head = new Uint8Array(await file.slice(0, 4).arrayBuffer());
      if (looksLikeBundle(file, head)) {
        const plan = await planBundleImport(await readBundleFile(file), notes);
        if (plan.notesImported === 0) {
          toast('No notes found in backup');
          return;
        }
        setPendingImport({
          merged: plan.merged,
          result: { notesImported: plan.notesImported, labelsCreated: plan.labelsCreated },
          attachmentsImported: plan.attachmentsImported,
          attachmentsSkipped: plan.attachmentsSkipped,
          warnings: plan.warnings,
          bundle: plan,
        });
        return;
      }

      const json = await readBackupFile(file);
      const { merged, result } = importNotesFromBackup(json, notes);
      if (result.notesImported === 0) {
        toast('No notes found in backup');
        return;
      }
      setPendingImport({
        merged,
        result,
        attachmentsImported: 0,
        attachmentsSkipped: 0,
        warnings: [],
      });
    } catch (error) {
      toast(messageOf(error, 'Import failed'), 'error');
    }
  };

  const cancelImport = () => setPendingImport(null);

  const confirmImport = async () => {
    const staged = pendingImport;
    if (!staged) return;
    setPendingImport(null);
    // A bundle's images are written only now, after the user said yes; a preview that was
    // cancelled or abandoned never wrote any.
    const bundle = staged.bundle ? await stagePlannedAttachments(staged.bundle) : null;
    const merged = bundle?.merged ?? staged.merged;
    const attachmentsImported = bundle?.attachmentsImported ?? staged.attachmentsImported;
    const attachmentsSkipped = bundle?.attachmentsSkipped ?? staged.attachmentsSkipped;
    try {
      const uploadedToCloud = await commitImportedNotes(
        merged,
        staged.result.notesImported,
        userId,
      );

      const parts: string[] = [];
      if (staged.result.notesImported > 0) {
        parts.push(plural(staged.result.notesImported, 'note'));
      }
      if (staged.result.labelsCreated > 0) {
        parts.push(plural(staged.result.labelsCreated, 'label'));
      }
      if (attachmentsImported > 0) {
        parts.push(plural(attachmentsImported, 'image'));
      }
      const base =
        parts.length > 0 ? `Imported ${parts.join(' and ')}` : 'No notes found in backup';
      const synced = uploadedToCloud ? `${base} and synced to cloud` : base;
      // A partial failure is named in the same breath as the success, so the count and the
      // caveat cannot be read separately.
      toast(
        attachmentsSkipped > 0
          ? `${synced} — ${plural(attachmentsSkipped, 'image')} could not be restored`
          : synced,
      );
    } catch (error) {
      // No note was written, so nothing references the images just staged: take them back.
      if (bundle) await releaseStagedBundle(bundle);
      toast(messageOf(error, 'Import failed'), 'error');
    }
  };

  return {
    signOut,
    deleteAccount,
    identityCheck,
    confirmIdentity,
    cancelIdentityCheck: () => setIdentityCheck(null),
    exportBackup,
    exportCompleteBackup,
    exportDeviceBackup,
    prepareImport,
    pendingImport,
    confirmImport,
    cancelImport,
  };
}
