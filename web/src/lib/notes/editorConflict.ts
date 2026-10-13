import { authoredContentEqual } from '@/lib/notes/conflictResolution';
import type { Note } from '@/types/note';

/**
 * What an editor save may do to the note it was opened on.
 *
 * The editor keeps its own copy of the note while open, but an upload's base revision comes from
 * the revision store, which a pull advances underneath it. So a save typed against revision 5,
 * landing after revision 6 arrived from another device, used to pass the server's revision check
 * and replace revision 6 wholesale — the other device's edit gone without a trace.
 *
 * - `save`: nothing newer has arrived; write the draft over the note.
 * - `adopt-stamp`: the stored note is newer only in its server stamp — this device's own write
 *   echoing back, or identical words — so the draft is still a straight edit of it.
 * - `fork`: another device changed the words since the draft was loaded. The draft is saved as a
 *   conflicted copy and the note keeps the other device's version.
 */
export type EditorSaveDecision = 'save' | 'adopt-stamp' | 'fork';

export function decideEditorSave(args: {
  draft: Note;
  stored: Note | undefined;
  /** The version the editor last loaded or wrote; null for a note that was never saved. */
  lastSaved: Note | null;
}): EditorSaveDecision {
  const { draft, stored, lastSaved } = args;
  if (!stored || stored.serverUpdatedAt == null) return 'save';
  if (draft.serverUpdatedAt == null) return 'adopt-stamp';
  if (stored.serverUpdatedAt <= draft.serverUpdatedAt) return 'save';
  if (authoredContentEqual(stored, draft)) return 'adopt-stamp';
  if (lastSaved && authoredContentEqual(stored, lastSaved)) return 'adopt-stamp';
  return 'fork';
}
