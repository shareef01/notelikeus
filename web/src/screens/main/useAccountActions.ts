import { commitImportedNotes } from '@/lib/backup/commitImportedNotes';
import { exportNotesBackup } from '@/lib/backup/exportBackup';
import { importNotesFromBackup, readBackupFile } from '@/lib/backup/importBackup';
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

  const exportBackup = () => {
    try {
      exportNotesBackup(notes);
      toast('Backup exported');
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
   */
  const prepareImport = async (file: File) => {
    try {
      const json = await readBackupFile(file);
      const { merged, result } = importNotesFromBackup(json, notes);
      if (result.notesImported === 0) {
        toast('No notes found in backup');
        return;
      }
      setPendingImport({ merged, result });
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
        parts.push(
          `${staged.result.notesImported} note${staged.result.notesImported === 1 ? '' : 's'}`,
        );
      }
      if (staged.result.labelsCreated > 0) {
        parts.push(
          `${staged.result.labelsCreated} label${staged.result.labelsCreated === 1 ? '' : 's'}`,
        );
      }
      const base =
        parts.length > 0 ? `Imported ${parts.join(' and ')}` : 'No notes found in backup';
      toast(uploadedToCloud ? `${base} and synced to cloud` : base);
    } catch (error) {
      toast(messageOf(error, 'Import failed'), 'error');
    }
  };

  return { signOut, exportBackup, prepareImport, pendingImport, confirmImport, cancelImport };
}
