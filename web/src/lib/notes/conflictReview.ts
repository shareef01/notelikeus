import { CONFLICTED_COPY_SUFFIX, isConflictedCopyTitle } from '@/lib/notes/conflictResolution';
import { saveNote, trashNoteById } from '@/lib/notes/noteActions';
import type { Note } from '@/types/note';

/**
 * Reviewing a conflicted copy against the note it was split from (AU-08).
 *
 * A copy carries no link to its original — the copy is an ordinary note so every client can sync
 * it — but its title is the original's title plus a fixed suffix, so the original is found by
 * title. That is a best guess, and the UI says so: when no single live note matches, the review
 * offers the copy on its own rather than pairing it with the wrong note.
 *
 * Nothing here deletes: "keep one" moves the other version to Trash, from where it can be restored.
 */

/** The title the copy's original had, or null for an untitled copy (nothing to match on). */
export function originalTitleOf(copyTitle: string): string | null {
  if (!copyTitle.endsWith(CONFLICTED_COPY_SUFFIX)) return null;
  return copyTitle.slice(0, -CONFLICTED_COPY_SUFFIX.length);
}

/** The live note the copy was most likely split from, or null when there is no unambiguous match. */
export function findConflictOriginal(copy: Note, notes: readonly Note[]): Note | null {
  const title = originalTitleOf(copy.title);
  if (title == null || title.trim().length === 0) return null;
  const candidates = notes.filter(
    (note) =>
      note.id !== copy.id &&
      !note.isTrashed &&
      !isConflictedCopyTitle(note.title) &&
      note.title === title,
  );
  return candidates.length === 1 ? (candidates[0] ?? null) : null;
}

export interface ReviewLine {
  text: string;
  /** Not present in the other version. */
  changed: boolean;
}

function noteLines(note: Note): string[] {
  const body = note.content.length > 0 ? note.content.split('\n') : [];
  const items = [...note.checklist]
    .sort((a, b) => a.position - b.position)
    .map((item) => `${item.isChecked ? '☑' : '☐'} ${item.text}`);
  return [...body, ...items];
}

/** [lines], each marked changed when [other] has no unmatched copy of it left. */
function markAgainst(lines: string[], other: Map<string, number>): ReviewLine[] {
  const left = new Map(other);
  return lines.map((text) => {
    const n = left.get(text) ?? 0;
    if (n > 0) {
      left.set(text, n - 1);
      return { text, changed: false };
    }
    return { text, changed: true };
  });
}

/**
 * Each version's lines, marked where the other version has no such line. Deliberately simple —
 * notes are short, and "which lines are only here" is what a person comparing two versions needs.
 */
export function compareVersions(a: Note, b: Note): { a: ReviewLine[]; b: ReviewLine[] } {
  const linesA = noteLines(a);
  const linesB = noteLines(b);
  const countB = new Map<string, number>();
  for (const line of linesB) countB.set(line, (countB.get(line) ?? 0) + 1);
  const countA = new Map<string, number>();
  for (const line of linesA) countA.set(line, (countA.get(line) ?? 0) + 1);
  return { a: markAgainst(linesA, countB), b: markAgainst(linesB, countA) };
}

function withTitle(note: Note, title: string): Note {
  return { ...note, title, timestamp: Date.now() };
}

/** Keeps both notes: the copy loses its suffix and becomes an ordinary note. */
export async function keepBoth(copy: Note): Promise<void> {
  await saveNote(withTitle(copy, originalTitleOf(copy.title) ?? ''));
}

/** Keeps the copy's version: the original goes to Trash, the copy takes the original title. */
export async function keepCopy(copy: Note, original: Note): Promise<void> {
  await trashNoteById(original.id);
  await saveNote(withTitle(copy, originalTitleOf(copy.title) ?? ''));
}

/** Keeps the current note: the copy goes to Trash. */
export async function keepOriginal(copy: Note): Promise<void> {
  await trashNoteById(copy.id);
}
