import type { Note } from '@/types/note';

function checklistKey(note: Note): string {
  return note.checklist.map((item) => `${item.position}:${item.isChecked}:${item.text}`).join(',');
}

function labelsKey(note: Note): string {
  return note.labels.map((label) => label.name).join(',');
}

/** Fingerprint used to skip no-op store writes. Includes content so same-timestamp edits apply. */
export function noteContentKey(note: Note): string {
  return [
    note.id,
    note.timestamp,
    note.position,
    note.color,
    note.isPinned ? 1 : 0,
    note.isArchived ? 1 : 0,
    note.isTrashed ? 1 : 0,
    note.isLocked ? 1 : 0,
    note.reminderTimestamp ?? '',
    note.title,
    note.content,
    labelsKey(note),
    checklistKey(note),
  ].join('\u001f');
}

export function notesContentKey(notes: Note[]): string {
  return [...notes]
    .map((note) => noteContentKey(note))
    .sort()
    .join('|');
}

export function notesContentEqual(a: Note[], b: Note[]): boolean {
  if (a.length !== b.length) return false;
  return notesContentKey(a) === notesContentKey(b);
}

export function notesEqual(a: Note, b: Note): boolean {
  return noteContentKey(a) === noteContentKey(b);
}
