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
  return `${note.id}:${note.timestamp}`;
}

export function notesEqual(a: Note, b: Note): boolean {
  return noteContentKey(a) === noteContentKey(b);
}
