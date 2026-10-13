import type { Note } from '@/types/note';

/**
 * What a sync conflict leaves behind: the cloud version keeps the note's id and the other version
 * becomes a new note whose title says so. Shared by the push queue (pendingPushes.ts), the editor
 * (editorConflict.ts) and the card marker, so every path writes — and recognises — the same title,
 * which is also the one the Kotlin client writes (ConflictResolution.kt).
 */

export const CONFLICTED_COPY_SUFFIX = ' (conflicted copy)';
const CONFLICTED_COPY_BLANK_TITLE = 'Conflicted copy';
/** `validate_note_payload` rejects longer titles; a copy that can never upload is not preserved. */
const MAX_TITLE_LENGTH = 2000;

function checklistKey(note: Note): string {
  return [...note.checklist]
    .sort((a, b) => a.position - b.position)
    .map((item) => `${item.isChecked ? 1 : 0}:${item.text}`)
    .join('\u001e');
}

function labelsKey(note: Note): string {
  return note.labels
    .map((label) => label.name)
    .sort()
    .join('\u001e');
}

/**
 * Whether two versions carry the same words: title, body, labels, checklist.
 *
 * Narrower than `notesUserContentEqual` on purpose. Pin, colour and order are not worth a duplicate
 * note, and attachment paths change on their own (a pending upload becomes `r2:` when it lands), so
 * comparing them would read this device's own upload echoing back as another device's edit.
 */
export function authoredContentEqual(a: Note, b: Note): boolean {
  return (
    a.title === b.title &&
    a.content === b.content &&
    checklistKey(a) === checklistKey(b) &&
    labelsKey(a) === labelsKey(b)
  );
}

/**
 * [local] as a new, never-synced note under [localId]. Attachments stay with the original: their
 * cloud rows and pending bytes are keyed to that note's id, and pointing a second note at them
 * would break both.
 */
export function conflictedCopyOf(local: Note, localId: number): Note {
  const title =
    local.title.trim().length === 0
      ? CONFLICTED_COPY_BLANK_TITLE
      : local.title.slice(0, MAX_TITLE_LENGTH - CONFLICTED_COPY_SUFFIX.length) + CONFLICTED_COPY_SUFFIX;
  return {
    ...local,
    id: String(localId),
    localId,
    title,
    serverUpdatedAt: null,
    attachments: [],
    checklist: local.checklist.map((item, index) => ({ ...item, id: `chk-${localId}-${index}` })),
  };
}

/** Whether [title] is one a conflict resolution wrote (every client writes the same suffix). */
export function isConflictedCopyTitle(title: string): boolean {
  return title === CONFLICTED_COPY_BLANK_TITLE || title.endsWith(CONFLICTED_COPY_SUFFIX);
}
