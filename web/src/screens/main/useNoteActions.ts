import { useCallback, useRef } from 'react';
import {
  archiveNoteById,
  emptyTrash,
  removeNotes,
  restoreNoteById,
  restorePermanentlyDeletedNote,
  saveNote,
  trashNoteById,
  type RemoveNotesResult,
} from '@/lib/notes/noteActions';
import {
  cancelDeferredAttachmentGc,
  runDeferredAttachmentGc,
} from '@/lib/attachments/attachmentSyncService';
import {
  commitNotePositions,
  moveBlockedAnnouncement,
  moveBlockReason,
  movedAnnouncement,
  previewMoveNote,
} from '@/lib/notes/noteOrder';
import { runNoteAction } from '@/lib/notes/runNoteAction';
import { showUndoToast } from '@/lib/notes/showUndoToast';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import type { Note } from '@/types/note';

/** `n note` / `n notes`, which every message below needs. */
function plural(count: number, word = 'note'): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}

/**
 * A permanent-delete toast that says where the notes still are.
 *
 * [unconfirmed] of the [total] deleted notes are gone here, but their cloud copies stay on other
 * devices until a later sync finishes the delete (F-05, or a cloud delete that failed). That is
 * not a failure, and reporting it as one hid the Undo for a delete that had happened. [failed]
 * notes were not deleted at all and are still in the list.
 */
export function permanentDeleteMessage(
  message: string,
  total: number,
  unconfirmed: number,
  failed = 0,
): string {
  const sentences = [message];
  if (unconfirmed > 0) {
    const subject =
      total === 1 ? 'It stays' : unconfirmed === 1 ? '1 stays' : `${unconfirmed} stay`;
    sentences.push(`${subject} on your other devices until the next sync`);
  }
  if (failed > 0) {
    sentences.push(`${plural(failed)} couldn't be deleted. Try again`);
  }
  return sentences.length === 1 ? message : `${sentences.join('. ')}.`;
}

/**
 * Reports a batch of permanent deletes: one toast, with an Undo for exactly the notes that went.
 *
 * Throws when nothing was deleted, so {@link runNoteAction} reports it as the failure it is. A
 * partial failure is not one: the deleted notes need their Undo, and the rest are named instead.
 */
export function reportPermanentDelete(
  result: RemoveNotesResult,
  snapshots: Note[],
  message: (deleted: number) => string,
): void {
  const { removedIds, pendingInCloud, failedIds } = result;
  if (removedIds.length === 0) {
    throw new Error(`${plural(failedIds.length)} could not be deleted`);
  }
  const removed = new Set(removedIds);
  const restorable = snapshots.filter((note) => removed.has(note.id));
  const restorableIds = new Set(restorable.map((note) => note.id));
  showUndoToast({
    message: permanentDeleteMessage(
      message(removedIds.length),
      removedIds.length,
      pendingInCloud,
      failedIds.length,
    ),
    tone: failedIds.length > 0 ? 'error' : 'default',
    revert: async () => {
      // The images were held back for exactly this (AUDIT-003). A note with no snapshot cannot
      // come back, so its cleanup goes ahead.
      cancelDeferredAttachmentGc([...restorableIds]);
      void runDeferredAttachmentGc(removedIds.filter((id) => !restorableIds.has(id)));
      await Promise.all(restorable.map((note) => restorePermanentlyDeletedNote(note)));
    },
    onExpire: () => {
      void runDeferredAttachmentGc(removedIds);
    },
  });
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
  const archiveNote = useCallback(
    (note: Note) =>
      runNoteAction('Archive', async () => {
        const previous = { ...note };
        await archiveNoteById(note.id);
        showUndoToast({
          message: 'Note archived',
          revert: () => saveNote(previous),
        });
      }),
    [],
  );

  const trashNote = useCallback(
    (note: Note) =>
      runNoteAction('Move to trash', async () => {
        const previous = { ...note };
        await trashNoteById(note.id);
        showUndoToast({
          message: 'Note moved to trash',
          revert: () => saveNote(previous),
        });
      }),
    [],
  );

  const restoreNote = useCallback(
    (note: Note) =>
      runNoteAction('Restore', async () => {
        await restoreNoteById(note.id);
        useToastStore.getState().show('Note restored');
      }),
    [],
  );

  const permanentlyDeleteNote = useCallback(
    (note: Note) =>
      runNoteAction('Delete', async () => {
        const previous = { ...note };
        const result = await removeNotes([note.id], { deferAttachmentGc: true });
        reportPermanentDelete(result, [previous], () => 'Note deleted permanently');
      }),
    [],
  );

  const emptyTheTrash = useCallback(
    () =>
      runNoteAction('Empty trash', async () => {
        closeEmptyTrashConfirm();
        const trashedSnapshots = notes.filter((note) => note.isTrashed).map((note) => ({ ...note }));
        const result = await emptyTrash({ deferAttachmentGc: true });
        if (result.removedIds.length === 0 && result.failedIds.length === 0) {
          useToastStore.getState().show('Trash is already empty');
          return;
        }
        reportPermanentDelete(result, trashedSnapshots, (deleted) => `Deleted ${plural(deleted)} permanently`);
      }),
    [closeEmptyTrashConfirm, notes],
  );

  const togglePinNote = useCallback(
    (note: Note) =>
      runNoteAction('Pin', async () => {
        const pin = !note.isPinned;
        await saveNote({ ...note, isPinned: pin, timestamp: Date.now() });
        useToastStore.getState().show(pin ? 'Note pinned' : 'Note unpinned');
      }),
    [],
  );

  const bulkPinToggle = useCallback(
    () =>
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
      }),
    [clearSelection, getSelectedSnapshots, selectionAllPinned],
  );

  const bulkArchive = useCallback(
    () =>
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
      }),
    [clearSelection, getSelectedSnapshots],
  );

  const bulkTrash = useCallback(
    () =>
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
      }),
    [clearSelection, getSelectedSnapshots],
  );

  const bulkRestore = useCallback(
    () =>
      runNoteAction('Restore', async () => {
        const snapshots = getSelectedSnapshots();
        if (snapshots.length === 0) return;
        await Promise.all(snapshots.map((note) => restoreNoteById(note.id)));
        clearSelection();
        useToastStore.getState().show(`${plural(snapshots.length)} restored`);
      }),
    [clearSelection, getSelectedSnapshots],
  );

  const bulkPermanentDelete = useCallback(
    () =>
      runNoteAction('Delete', async () => {
        closeBulkDeleteConfirm();
        const snapshots = getSelectedSnapshots();
        if (snapshots.length === 0) return;
        const result = await removeNotes(
          snapshots.map((note) => note.id),
          { deferAttachmentGc: true },
        );
        clearSelection();
        reportPermanentDelete(result, snapshots, (deleted) => `${plural(deleted)} deleted permanently`);
      }),
    [clearSelection, closeBulkDeleteConfirm, getSelectedSnapshots],
  );

  /**
   * Reordering is two steps by design: the drag previews into the store on every move so the list
   * follows the finger, and only the drop persists. Writing on each move would be a remote
   * commit per pixel of travel.
   */
  // Where each note moved since the last commit started, so a drag that wanders and comes back
  // writes nothing, and a failed save can put the note back where it was.
  const originalPositions = useRef(new Map<string, number>());
  // The shown list as of the latest render. A drag can move several times between renders.
  const visibleRef = useRef(filteredNotes);
  visibleRef.current = filteredNotes;

  /** Moves a note within the shown list, for display; `reorderComplete` persists it. */
  const moveNote = useCallback((fromIndex: number, toIndex: number): boolean => {
    const store = useNotesStore.getState();
    const preview = previewMoveNote(store.notes, visibleRef.current, fromIndex, toIndex);
    if (!preview) return false;
    for (const [id, position] of preview.previousPositions) {
      if (!originalPositions.current.has(id)) originalPositions.current.set(id, position);
    }
    store.setNotes(preview.notes);
    // The list re-sorts from positions on the next render; until then, later moves in the same
    // gesture need the order they produced.
    const ordered = [...visibleRef.current];
    const [item] = ordered.splice(fromIndex, 1);
    ordered.splice(toIndex, 0, item);
    visibleRef.current = ordered;
    return true;
  }, []);

  const reorderComplete = useCallback(() => {
    const originals = originalPositions.current;
    if (originals.size === 0) return;
    originalPositions.current = new Map();
    void runNoteAction('Reorder', async () => {
      const failed = await commitNotePositions(useNotesStore.getState().notes, originals);
      if (failed.length === 0) return;
      // The move is already on screen; without this it looks saved until the next reload.
      const failedIds = new Set(failed);
      const store = useNotesStore.getState();
      store.setNotes(
        store.notes.map((note) =>
          failedIds.has(note.id) ? { ...note, position: originals.get(note.id) ?? note.position } : note,
        ),
      );
      throw new Error(`${plural(failed.length)} could not be moved`);
    });
  }, []);

  /**
   * One step up or down: the Move up / Move down actions on a card, the selection bar and the
   * reorder handle's arrow keys. Says where the note ended up, or why it could not move.
   */
  const moveNoteBy = useCallback(
    (noteId: string, delta: -1 | 1) => {
      const visible = visibleRef.current;
      const index = visible.findIndex((note) => note.id === noteId);
      if (index < 0) return;
      const blocked = moveBlockReason(visible, index, delta);
      const toast = useToastStore.getState();
      if (blocked) {
        toast.show(moveBlockedAnnouncement(blocked));
        return;
      }
      if (!moveNote(index, index + delta)) return;
      reorderComplete();
      toast.show(movedAnnouncement(index + delta + 1, visible.length));
    },
    [moveNote, reorderComplete],
  );

  return {
    archiveNote,
    trashNote,
    restoreNote,
    permanentlyDeleteNote,
    emptyTheTrash,
    togglePinNote,
    bulkPinToggle,
    bulkArchive,
    bulkTrash,
    bulkRestore,
    bulkPermanentDelete,
    moveNote,
    reorderComplete,
    moveNoteBy,
  };
}
