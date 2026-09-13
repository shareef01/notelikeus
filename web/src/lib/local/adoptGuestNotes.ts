import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { deleteNote, listNotes, putNotes } from '@/lib/local/notesLocalRepository';
import {
  deletePendingAttachment,
  getPendingAttachment,
  listPendingAttachmentsForOwner,
  putPendingAttachment,
} from '@/lib/local/pendingAttachmentRepository';
import { consumeGuestAdoptionIntent } from '@/lib/local/guestAdoptionIntent';

import type { Note } from '@/types/note';

function areNotesIdentical(a: Note, b: Note): boolean {
  return (
    a.id === b.id &&
    a.localId === b.localId &&
    a.title === b.title &&
    a.content === b.content &&
    a.timestamp === b.timestamp &&
    a.color === b.color &&
    a.isPinned === b.isPinned &&
    a.isArchived === b.isArchived &&
    a.isTrashed === b.isTrashed &&
    a.position === b.position &&
    a.reminderTimestamp === b.reminderTimestamp &&
    JSON.stringify(a.labels ?? []) === JSON.stringify(b.labels ?? []) &&
    JSON.stringify(a.attachments ?? []) === JSON.stringify(b.attachments ?? []) &&
    JSON.stringify(a.checklist ?? []) === JSON.stringify(b.checklist ?? [])
  );
}

export interface AdoptGuestNotesOptions {
  /**
   * If true (default), adoption only proceeds if an active one-shot intent signal
   * was set during the current session while in Guest Mode.
   */
  requireIntent?: boolean;
}

/**
 * Adopts notes and pending attachments created during an active guest session into the
 * authenticated user's account namespace.
 *
 * Privacy & Safety Invariants:
 * 1. Requires active intent signal: Stray/residual __guest__ data left by another person on a
 *    shared browser is NEVER adopted by an unrelated user who signs in directly.
 * 2. Collision safety: If a guest note shares a UUID with an existing authenticated note:
 *    - If identical (from prior partial adoption attempt), it is treated as already copied.
 *    - If differing, the authenticated note is NEVER overwritten; colliding guest note remains in __guest__.
 * 3. Atomic copy-then-delete: All destination notes and attachments are copied and verified
 *    before any guest source records are deleted.
 * 4. Partial failure resilience: A failure copying any attachment halts before cleanup, preserving
 *    the original guest records for retry.
 */
export async function adoptGuestNotesIntoAccount(
  userId: string,
  options: AdoptGuestNotesOptions = {},
): Promise<void> {
  const { requireIntent = true } = options;

  if (!userId || userId === GUEST_OWNER_ID) return;

  if (requireIntent && !consumeGuestAdoptionIntent()) {
    // No active guest session transition intent. Refuse to adopt to preserve cross-account privacy.
    return;
  }

  const guestNotes = await listNotes(GUEST_OWNER_ID);
  const guestPending = await listPendingAttachmentsForOwner(GUEST_OWNER_ID);

  if (guestNotes.length === 0 && guestPending.length === 0) {
    return;
  }

  // Check for UUID collisions with existing authenticated notes
  const existingUserNotes = await listNotes(userId);
  const existingMap = new Map(existingUserNotes.map((n) => [n.id, n]));

  const notesToCopy: Note[] = [];
  const notesToClean: Note[] = [];
  const collidingNotes: Note[] = [];

  for (const guestNote of guestNotes) {
    const existing = existingMap.get(guestNote.id);
    if (!existing) {
      // New note to adopt
      notesToCopy.push(guestNote);
      notesToClean.push(guestNote);
    } else if (areNotesIdentical(existing, guestNote)) {
      // Already copied to userId in a prior partial attempt; safe to clean once verified
      notesToClean.push(guestNote);
    } else {
      // Real collision: authenticated user already has a different note with this ID!
      // Must NOT overwrite, must NOT delete from guest.
      collidingNotes.push(guestNote);
    }
  }

  if (collidingNotes.length > 0) {
    console.warn(
      `[Notelikeus] Skipping adoption of ${collidingNotes.length} guest note(s) with colliding IDs to protect authenticated data`,
    );
  }

  // Pending attachments: only adopt attachments belonging to adopted notes
  const cleanNoteIds = new Set(notesToClean.map((n) => n.id));
  const attachmentsToAdopt = guestPending.filter((p) => cleanNoteIds.has(p.noteId));

  // 1. Copy non-colliding new notes to authenticated account
  if (notesToCopy.length > 0) {
    await putNotes(userId, notesToCopy);
  }

  // 2. Copy pending attachments to authenticated account
  if (attachmentsToAdopt.length > 0) {
    for (const record of attachmentsToAdopt) {
      await putPendingAttachment({
        ...record,
        ownerId: userId,
      });
    }
  }

  // 3. Verify notes commit in destination
  if (notesToClean.length > 0) {
    const updatedUserNotes = await listNotes(userId);
    const updatedMap = new Map(updatedUserNotes.map((n) => [n.id, n]));
    for (const note of notesToClean) {
      const verified = updatedMap.get(note.id);
      if (!verified || !areNotesIdentical(verified, note)) {
        throw new Error(`Guest note adoption verification failed for note ${note.id}`);
      }
    }
  }

  // 4. Verify attachments commit in destination
  for (const record of attachmentsToAdopt) {
    const verified = await getPendingAttachment(userId, record.noteId, record.attachmentId);
    if (!verified) {
      throw new Error(`Guest attachment adoption verification failed for ${record.attachmentId}`);
    }
  }

  // 5. Clean up ONLY adopted notes from guest source (colliding notes remain in guest source)
  for (const note of notesToClean) {
    try {
      await deleteNote(GUEST_OWNER_ID, note.id);
    } catch (error) {
      console.warn(`[Notelikeus] Failed to delete adopted guest note ${note.id}:`, error);
    }
  }

  // Clean up guest pending attachments
  for (const record of attachmentsToAdopt) {
    try {
      await deletePendingAttachment(GUEST_OWNER_ID, record.noteId, record.attachmentId);
    } catch (error) {
      console.warn(
        `[Notelikeus] Failed to delete adopted guest attachment ${record.attachmentId}:`,
        error,
      );
    }
  }
}
