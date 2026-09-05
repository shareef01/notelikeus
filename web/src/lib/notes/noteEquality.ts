import { attachmentsKey } from '@/lib/attachments/attachmentPaths';
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
    note.serverUpdatedAt ?? '',
    note.position,
    note.color,
    note.isPinned ? 1 : 0,
    note.isArchived ? 1 : 0,
    note.isTrashed ? 1 : 0,
    note.reminderTimestamp ?? '',
    note.title,
    note.content,
    labelsKey(note),
    attachmentsKey(note.attachments),
    checklistKey(note),
  ].join('\u001f');
}

export function notesContentKey(notes: Note[]): string {
  return notes
    .map((note) => noteContentKey(note))
    .sort()
    .join('|');
}

export function notesEqual(a: Note, b: Note): boolean {
  if (a === b) return true;
  return (
    a.id === b.id &&
    a.timestamp === b.timestamp &&
    a.serverUpdatedAt === b.serverUpdatedAt &&
    a.position === b.position &&
    a.color === b.color &&
    a.isPinned === b.isPinned &&
    a.isArchived === b.isArchived &&
    a.isTrashed === b.isTrashed &&
    a.reminderTimestamp === b.reminderTimestamp &&
    a.title === b.title &&
    a.content === b.content &&
    labelsKey(a) === labelsKey(b) &&
    attachmentsKey(a.attachments) === attachmentsKey(b.attachments) &&
    checklistKey(a) === checklistKey(b)
  );
}

/**
 * Order-independent library equality. Fast paths: same array reference, then same
 * per-index object identity. Otherwise compares by id without building a giant
 * joined string of every note body.
 */
export function notesContentEqual(a: Note[], b: Note[]): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  let sameRefs = true;
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      sameRefs = false;
      break;
    }
  }
  if (sameRefs) return true;
  const byId = new Map<string, Note>();
  for (const note of a) byId.set(note.id, note);
  for (const note of b) {
    const other = byId.get(note.id);
    if (!other || !notesEqual(note, other)) return false;
  }
  return true;
}
