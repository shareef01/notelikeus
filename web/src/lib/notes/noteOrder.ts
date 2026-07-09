import { saveNote } from '@/lib/notes/noteActions';
import type { Note } from '@/types/note';

/** Swap note order in the full list while dragging in the filtered list. */
export function previewMoveNote(
  allNotes: Note[],
  filteredNotes: Note[],
  fromIndex: number,
  toIndex: number,
): Note[] | null {
  if (
    fromIndex < 0 ||
    toIndex < 0 ||
    fromIndex >= filteredNotes.length ||
    toIndex >= filteredNotes.length ||
    fromIndex === toIndex
  ) {
    return null;
  }

  const fromNote = filteredNotes[fromIndex];
  const toNote = filteredNotes[toIndex];
  if (fromNote.isPinned !== toNote.isPinned) return null;

  const fromFull = allNotes.findIndex((note) => note.id === fromNote.id);
  const toFull = allNotes.findIndex((note) => note.id === toNote.id);
  if (fromFull < 0 || toFull < 0) return null;

  const next = [...allNotes];
  const [item] = next.splice(fromFull, 1);
  next.splice(toFull, 0, item);
  return next;
}

/** Persist position indices after a manual reorder completes. */
export async function commitNotePositions(notes: Note[]): Promise<void> {
  const updates = notes
    .map((note, index) => (note.position === index ? null : { ...note, position: index }))
    .filter((note): note is Note => note != null);

  await Promise.all(updates.map((note) => saveNote(note)));
}
