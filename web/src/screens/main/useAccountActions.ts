import { commitImportedNotes } from '@/lib/backup/commitImportedNotes';
import { exportNotesBackup } from '@/lib/backup/exportBackup';
import { importNotesFromBackup, readBackupFile } from '@/lib/backup/importBackup';
import { looksLikeBundle } from '@/lib/backup/bundle/backupBundle';
import {
  buildBundleFromNotes,
  downloadBundle,
  planBundleImport,
  readBundleFile,
} from '@/lib/backup/bundle/bundleTransfer';
import { useToastStore } from '@/store/toastStore';
import type { Note } from '@/types/note';
import { useState } from 'react';

/** Every path here reports through the same toast, success or failure. */
function toast(message: string, kind?: 'error') {
  useToastStore.getState().show(message, kind);
}

function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

interface AccountActionsDeps {
  notes: Note[];
  userId: string | undefined;
  closeSignOutConfirm: () => void;
  closeProfile: () => void;
}

/** A parsed backup waiting on the user to confirm that importing it is what they meant. */
export interface PendingImport {
  merged: Note[];
  result: { notesImported: number; labelsCreated: number };
  /** Images this import will restore. Zero for a plain JSON backup, which carries none. */
  attachmentsImported: number;
  /** Images the file named but could not supply. Reported, never fatal. */
  attachmentsSkipped: number;
  warnings: string[];
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
  closeProfile,
}: AccountActionsDeps) {
  const [pendingImport, setPendingImport] = useState<PendingImport | null>(null);

  const signOut = async (deleteCloudData: boolean) => {
    closeSignOutConfirm();
    closeProfile();

    try {
      const { signOutGoogle } = await import('@/lib/auth/googleAuth');
      await signOutGoogle({ deleteCloudData });
      toast(deleteCloudData ? 'Signed out and cloud data deleted' : 'Signed out');
    } catch (error) {
      toast(messageOf(error, 'Sign out failed'), 'error');
    }
  };

  /** The JSON backup, unchanged. Kept because it is the format other builds already read. */
  const exportBackup = () => {
    try {
      exportNotesBackup(notes);
      toast('Backup exported');
    } catch (error) {
      toast(messageOf(error, 'Export failed'), 'error');
    }
  };

  /**
   * The complete backup: the same JSON document plus the attachment bytes this device holds.
   *
   * Reports what it could not include rather than producing a bundle that quietly has fewer
   * images than the library does — a backup you cannot trust the completeness of is worse than
   * one that tells you what is missing.
   */
  const exportCompleteBackup = async () => {
    try {
      const bundle = await buildBundleFromNotes(notes);
      downloadBundle(bundle.bytes);
      const summary =
        bundle.attachmentsIncluded > 0
          ? `Backup exported with ${plural(bundle.attachmentsIncluded, 'image')}`
          : 'Backup exported';
      if (bundle.attachmentsSkipped > 0) {
        toast(`${summary}. ${bundle.warnings[0] ?? ''}`.trim());
      } else {
        toast(summary);
      }
    } catch (error) {
      toast(messageOf(error, 'Export failed'), 'error');
    }
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
    try {
      const uploadedToCloud = await commitImportedNotes(
        staged.merged,
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
      if (staged.attachmentsImported > 0) {
        parts.push(plural(staged.attachmentsImported, 'image'));
      }
      const base =
        parts.length > 0 ? `Imported ${parts.join(' and ')}` : 'No notes found in backup';
      const synced = uploadedToCloud ? `${base} and synced to cloud` : base;
      // A partial failure is named in the same breath as the success, so the count and the
      // caveat cannot be read separately.
      toast(
        staged.attachmentsSkipped > 0
          ? `${synced} — ${plural(staged.attachmentsSkipped, 'image')} could not be restored`
          : synced,
      );
    } catch (error) {
      toast(messageOf(error, 'Import failed'), 'error');
    }
  };

  return {
    signOut,
    exportBackup,
    exportCompleteBackup,
    prepareImport,
    pendingImport,
    confirmImport,
    cancelImport,
  };
}
