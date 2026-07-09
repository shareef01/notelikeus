import type { Note } from '@/types/note';

export function notesContentKey(notes: Note[]): string {
  return [...notes]
    .map((note) => `${note.id}:${note.timestamp}`)
    .sort()
    .join('|');
}

export function notesContentEqual(a: Note[], b: Note[]): boolean {
  if (a.length !== b.length) return false;
  return notesContentKey(a) === notesContentKey(b);
}

export function noteContentKey(note: Note): string {
  return [
    note.id,
    note.timestamp,
    note.position,
    note.isPinned ? 1 : 0,
    note.isArchived ? 1 : 0,
    note.isTrashed ? 1 : 0,
  ].join(':');
}

export function notesEqual(a: Note, b: Note): boolean {
  return noteContentKey(a) === noteContentKey(b);
}
