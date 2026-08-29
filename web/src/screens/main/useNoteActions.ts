import {
  archiveNoteById,
  emptyTrash,
  removeNote,
  restoreNoteById,
  restorePermanentlyDeletedNote,
  saveNote,
  trashNoteById,
} from '@/lib/notes/noteActions';
import { commitNotePositions, previewMoveNote } from '@/lib/notes/noteOrder';
import { runNoteAction } from '@/lib/notes/runNoteAction';
import { showUndoToast } from '@/lib/notes/showUndoToast';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import type { Note } from '@/types/note';

/** `n note` / `n notes`, which every message below needs. */
function plural(count: number, word = 'note'): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}

interface NoteActionsDeps {
  notes: Note[];
  filteredNotes: Note[];
  /** Copies of the selected notes, taken before the action mutates them. */
  getSelectedSnapshots: () => Note[];
  selectionAllPinned: boolean;
  clearSelection: () => void;
  closeEmptyTrashConfirm: () => void;
  closeBulkDeleteConfirm: () => void;
}

/**
 * Every mutation the notes list can perform, single and bulk.
 *
 * The web counterpart of `NoteActionsController` on the Kotlin side, and lifted out of
 * `MainScreen` for the same reason: these are twelve handlers that share one shape — snapshot,
 * mutate, offer an undo built from the snapshot — and that shape is invisible when they are spread
 * through a 950-line component between unrelated state.
 *
 * Snapshots are taken *before* the mutation on purpose. `showUndoToast`'s revert closes over them,
 * so reading the store afterwards would revert to the post-action state and undo nothing.
 */
export function useNoteActions({
  notes,
  filteredNotes,
  getSelectedSnapshots,
  selectionAllPinned,
  clearSelection,
  closeEmptyTrashConfirm,
  closeBulkDeleteConfirm,
}: NoteActionsDeps) {
  const archiveNote = (note: Note) =>
    runNoteAction('Archive', async () => {
      const previous = { ...note };
      await archiveNoteById(note.id);
      showUndoToast({
        message: 'Note archived',
        revert: () => saveNote(previous),
      });
    });

  const trashNote = (note: Note) =>
    runNoteAction('Move to trash', async () => {
      const previous = { ...note };
      await trashNoteById(note.id);
      showUndoToast({
        message: 'Note moved to trash',
        revert: () => saveNote(previous),
      });
    });

  const restoreNote = (note: Note) =>
    runNoteAction('Restore', async () => {
      await restoreNoteById(note.id);
      useToastStore.getState().show('Note restored');
    });

  const permanentlyDeleteNote = (note: Note) =>
    runNoteAction('Delete', async () => {
      const previous = { ...note };
      await removeNote(note.id);
      showUndoToast({
        message: 'Note deleted permanently',
        revert: () => restorePermanentlyDeletedNote(previous),
      });
    });

  const emptyTheTrash = () =>
    runNoteAction('Empty trash', async () => {
      closeEmptyTrashConfirm();
      const trashedSnapshots = notes.filter((note) => note.isTrashed).map((note) => ({ ...note }));
      const count = await emptyTrash();
      if (count === 0) {
        useToastStore.getState().show('Trash is already empty');
        return;
      }
      showUndoToast({
        message: `Deleted ${plural(count)} permanently`,
        revert: async () => {
          await Promise.all(trashedSnapshots.map((note) => restorePermanentlyDeletedNote(note)));
        },
      });
    });

  const bulkPinToggle = () =>
    runNoteAction('Pin', async () => {
      const snapshots = getSelectedSnapshots();
      if (snapshots.length === 0) return;
      const pin = !selectionAllPinned;
      await Promise.all(
        snapshots.map((note) => saveNote({ ...note, isPinned: pin, timestamp: Date.now() })),
      );
      clearSelection();
      useToastStore
        .getState()
        .show(`${plural(snapshots.length)} ${pin ? 'pinned' : 'unpinned'}`);
    });

  const bulkArchive = () =>
    runNoteAction('Archive', async () => {
      const snapshots = getSelectedSnapshots();
      if (snapshots.length === 0) return;
      await Promise.all(snapshots.map((note) => archiveNoteById(note.id)));
      clearSelection();
      showUndoToast({
        message: `${plural(snapshots.length)} archived`,
        revert: async () => {
          await Promise.all(snapshots.map((note) => saveNote(note)));
        },
      });
    });

  const bulkTrash = () =>
    runNoteAction('Move to trash', async () => {
      const snapshots = getSelectedSnapshots();
      if (snapshots.length === 0) return;
      await Promise.all(snapshots.map((note) => trashNoteById(note.id)));
      clearSelection();
      showUndoToast({
        message: `${plural(snapshots.length)} moved to trash`,
        revert: async () => {
          await Promise.all(snapshots.map((note) => saveNote(note)));
        },
      });
    });

  const bulkRestore = () =>
    runNoteAction('Restore', async () => {
      const snapshots = getSelectedSnapshots();
      if (snapshots.length === 0) return;
      await Promise.all(snapshots.map((note) => restoreNoteById(note.id)));
      clearSelection();
      useToastStore.getState().show(`${plural(snapshots.length)} restored`);
    });

  const bulkPermanentDelete = () =>
    runNoteAction('Delete', async () => {
      closeBulkDeleteConfirm();
      const snapshots = getSelectedSnapshots();
      if (snapshots.length === 0) return;
      await Promise.all(snapshots.map((note) => removeNote(note.id)));
      clearSelection();
      showUndoToast({
        message: `${plural(snapshots.length)} deleted permanently`,
        revert: async () => {
          await Promise.all(snapshots.map((note) => restorePermanentlyDeletedNote(note)));
        },
      });
    });

  /**
   * Reordering is two steps by design: the drag previews into the store on every move so the list
   * follows the finger, and only the drop persists. Writing on each move would be a Firestore
   * commit per pixel of travel.
   */
  const moveNote = (fromIndex: number, toIndex: number) => {
    const reordered = previewMoveNote(notes, filteredNotes, fromIndex, toIndex);
    if (reordered) {
      useNotesStore.getState().setNotes(reordered);
    }
  };

  const reorderComplete = () => {
    void runNoteAction('Reorder', async () => {
      await commitNotePositions(useNotesStore.getState().notes);
    });
  };

  return {
    archiveNote,
    trashNote,
    restoreNote,
    permanentlyDeleteNote,
    emptyTheTrash,
    bulkPinToggle,
    bulkArchive,
    bulkTrash,
    bulkRestore,
    bulkPermanentDelete,
    moveNote,
    reorderComplete,
  };
}
