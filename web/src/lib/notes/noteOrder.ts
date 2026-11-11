import { saveNote } from '@/lib/notes/noteActions';
import type { Note, NoteFilter, NoteQueryFilters } from '@/types/note';

/**
 * Manual note order, matching Kotlin `NoteQuery.allowsManualReorder` and
 * `MainViewModel.previewMoveNote` / `commitNoteOrder`.
 *
 * Positions are written by index over the list as it is shown. The store's own array is in no
 * particular order (IndexedDB key order, with archived and trashed notes mixed in), so numbering
 * it would put notes the user never moved somewhere they did not choose.
 */

/** Whether anything narrows the list. Scope and sort are not filters (Kotlin `hasActiveFilters`). */
export function hasActiveNoteFilters(filters: NoteQueryFilters): boolean {
  return (
    (filters.searchQuery ?? '').trim().length > 0 ||
    filters.colorArgb != null ||
    filters.labelName != null
  );
}

/** The piles a manual order means something in. Trash is emptied, not arranged. */
function scopeAllowsReorder(filter: NoteFilter): boolean {
  return filter === 'active' || filter === 'archived';
}

/**
 * Whether notes can be moved by hand right now.
 *
 * Only under the manual sort: moving a note writes its position and bumps its timestamp (so the
 * move survives the sync conflict guard), and under a date sort that would rewrite the dates of
 * every note it touched and then re-sort the list out from under the user. Not while a filter is
 * active either: position is a property of the whole list, and a filtered subset cannot say where
 * a note goes among the notes that are hidden.
 */
export function allowsManualReorder(filters: NoteQueryFilters): boolean {
  return (
    scopeAllowsReorder(filters.filter) &&
    (filters.sortOrder ?? 'manual') === 'manual' &&
    !hasActiveNoteFilters(filters)
  );
}

/**
 * Whether choosing the manual sort would make reordering possible: the blocker worth explaining,
 * because one tap fixes it. A filter is not, since no sort makes a filtered move mean anything.
 */
export function switchingSortWouldAllowReorder(filters: NoteQueryFilters): boolean {
  return (
    scopeAllowsReorder(filters.filter) &&
    (filters.sortOrder ?? 'manual') !== 'manual' &&
    !hasActiveNoteFilters(filters)
  );
}

/** Same text as native `cd_reorder_blocked`. */
export const REORDER_BLOCKED_MESSAGE = 'Reordering is off while notes are sorted automatically';

export type MoveBlockReason = 'first' | 'last' | 'pinned-boundary';

/**
 * Why the note at `index` cannot move one step by `delta`, or null when it can.
 * Pinned notes stay above the others, so a move never crosses that boundary.
 */
export function moveBlockReason(
  visible: readonly Note[],
  index: number,
  delta: -1 | 1,
): MoveBlockReason | null {
  const target = index + delta;
  if (target < 0) return 'first';
  if (target >= visible.length) return 'last';
  if (visible[index].isPinned !== visible[target].isPinned) return 'pinned-boundary';
  return null;
}

/** The shown list with one note moved, or null when the move is out of range or crosses the pin. */
export function moveInOrder(visible: readonly Note[], fromIndex: number, toIndex: number): Note[] | null {
  if (
    fromIndex < 0 ||
    toIndex < 0 ||
    fromIndex >= visible.length ||
    toIndex >= visible.length ||
    fromIndex === toIndex
  ) {
    return null;
  }
  if (visible[fromIndex].isPinned !== visible[toIndex].isPinned) return null;
  const next = [...visible];
  const [item] = next.splice(fromIndex, 1);
  next.splice(toIndex, 0, item);
  return next;
}

export interface PreviewedMove {
  /** The store's notes with the new positions applied; timestamps are untouched until commit. */
  notes: Note[];
  /** Position each renumbered note had before this move, by id. */
  previousPositions: Map<string, number>;
}

/**
 * Applies a move to the store's notes for display, without persisting it.
 *
 * Every note in the shown list is numbered by its new index, as native `writeNotePositions` does
 * with the list it is given. Notes outside the list keep their positions.
 */
export function previewMoveNote(
  allNotes: readonly Note[],
  visible: readonly Note[],
  fromIndex: number,
  toIndex: number,
): PreviewedMove | null {
  const ordered = moveInOrder(visible, fromIndex, toIndex);
  if (!ordered) return null;
  const nextPosition = new Map(ordered.map((note, index) => [note.id, index]));
  const previousPositions = new Map<string, number>();
  const notes = allNotes.map((note) => {
    const position = nextPosition.get(note.id);
    if (position == null || position === note.position) return note;
    previousPositions.set(note.id, note.position);
    return { ...note, position };
  });
  return { notes, previousPositions };
}

/**
 * Persists the notes whose position differs from where they started, bumping each one's
 * timestamp so the upload's conflict guard lets the new position through to the cloud (native
 * `NoteRepositoryImpl.writeNotePositions` does the same). A note moved and moved back is left
 * alone. Resolves to the ids whose save failed.
 */
export async function commitNotePositions(
  notes: readonly Note[],
  originalPositions: ReadonlyMap<string, number>,
  now: number = Date.now(),
): Promise<string[]> {
  const changed = notes.filter((note) => {
    const original = originalPositions.get(note.id);
    return original != null && original !== note.position;
  });
  const results = await Promise.allSettled(
    changed.map((note) => saveNote({ ...note, timestamp: now })),
  );
  return changed.filter((_, index) => results[index].status === 'rejected').map((note) => note.id);
}

/** "Moved to position 2 of 5", for the live announcement after a move. */
export function movedAnnouncement(position: number, total: number): string {
  return `Moved to position ${position} of ${total}`;
}

export function moveBlockedAnnouncement(reason: MoveBlockReason): string {
  switch (reason) {
    case 'first':
      return 'Already at the top';
    case 'last':
      return 'Already at the bottom';
    case 'pinned-boundary':
      return 'Pinned notes stay above the others';
  }
}
