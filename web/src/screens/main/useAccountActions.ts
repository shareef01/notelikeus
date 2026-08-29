import { signOutGoogle } from '@/lib/auth/googleAuth';
import { exportNotesBackup } from '@/lib/backup/exportBackup';
import { importNotesFromBackup, readBackupFile } from '@/lib/backup/importBackup';
import { uploadAllNotes } from '@/lib/firestore/notesRepository';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import type { Note } from '@/types/note';

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
  const signOut = async (deleteCloudData: boolean) => {
    closeSignOutConfirm();
    closeProfile();

    try {
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

  const importBackup = async (file: File) => {
    try {
      const json = await readBackupFile(file);
      const { merged, result } = importNotesFromBackup(json, notes);
      // Web notes live in the Firestore-backed store only — there is no durable local DB.
      // Always upload when signed in so the realtime listener cannot wipe an "import only"
      // mirror before the user notices.
      useNotesStore.getState().setNotes(merged);

      let uploadedToCloud = false;
      if (userId && result.notesImported > 0) {
        await uploadAllNotes(userId, merged);
        uploadedToCloud = true;
      }

      const parts: string[] = [];
      if (result.notesImported > 0) {
        parts.push(`${result.notesImported} note${result.notesImported === 1 ? '' : 's'}`);
      }
      if (result.labelsCreated > 0) {
        parts.push(`${result.labelsCreated} label${result.labelsCreated === 1 ? '' : 's'}`);
      }
      const base =
        parts.length > 0 ? `Imported ${parts.join(' and ')}` : 'No notes found in backup';
      toast(uploadedToCloud ? `${base} and synced to cloud` : base);
    } catch (error) {
      toast(messageOf(error, 'Import failed'), 'error');
    }
  };

  return { signOut, exportBackup, importBackup };
}
