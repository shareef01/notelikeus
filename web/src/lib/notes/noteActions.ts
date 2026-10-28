import { restoreCloudNote, RestoreRefusedError } from '@/lib/notes/tombstones';
import { authoredContentDigest } from '@/lib/notes/conflictResolution';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { isPendingAttachment } from '@/lib/attachments/attachmentPaths';
import {
  deleteNote as deleteLocalIndexedDbNote,
  putLocalEdit,
  putNote,
} from '@/lib/local/notesLocalRepository';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import { notesEqual } from '@/lib/notes/noteEquality';
import { CloudDeleteUnconfirmedError } from '@/lib/remote/remoteNotesDataSource';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { loadRevisionState } from '@/lib/supabase/revisionStore';
import { getNoteBaseRevision } from '@/lib/supabase/revisionStore';
import type { Note } from '@/types/note';

async function persistLocalNote(
  note: Note,
  targetOwnerId?: string,
  { asEdit = true }: { asEdit?: boolean } = {},
): Promise<void> {
  const ownerId = targetOwnerId ?? resolveOwnerId();
  if (!ownerId) return;
  // A signed-in edit is recorded as waiting for the cloud in the same transaction, so no snapshot
  // or pull can overwrite it before it has been pushed (F-06). Guest notes never sync.
  if (asEdit && useAuthStore.getState().user?.uid === ownerId) {
    await putLocalEdit(ownerId, note);
  } else {
    await putNote(ownerId, note);
  }
}

async function pushNote(note: Note): Promise<void> {
  const ownerId = resolveOwnerId();
  if (ownerId) {
    // Persist-first: commit to IndexedDB before mutating memory
    await persistLocalNote(note, ownerId);
  }
  if (resolveOwnerId() !== ownerId) return;
  useNotesStore.getState().upsertLocalNote(note);
  const currentUid = useAuthStore.getState().user?.uid;
  if (!currentUid || currentUid !== ownerId) return;
  try {
    await getRemoteNotesDataSource().upsertNote(currentUid, note);
  } catch (error) {
    // If Supabase fails, DO NOT rollback local note.
    // Local edit remains committed. Reconciliation retries remote later.
    console.warn('Remote note upsert failed, note remains saved locally:', error);
  }
}

function getNote(noteId: string): Note | undefined {
  return useNotesStore.getState().notes.find((note) => note.id === noteId);
}

function withTimestamp(note: Note, patch: Partial<Note>): Note {
  return { ...note, ...patch, timestamp: Date.now() };
}

/** Save locally and optionally push to the cloud when signed in — no React hooks. */
export async function saveNote(note: Note): Promise<void> {
  const sessionOwnerId = resolveOwnerId();
  const existing = getNote(note.id);
  const toSave = note;

  // Clean up removed attachments if any
  if (isR2AttachmentsEnabled() && existing) {
    const { deleteAttachmentsForNote } = await import(
      '@/lib/attachments/attachmentSyncService'
    );
    const nextIds = new Set(toSave.attachments.map((attachment) => attachment.id));
    const removed = existing.attachments.filter(
      (attachment) => !nextIds.has(attachment.id),
    );
    if (removed.length > 0) {
      await deleteAttachmentsForNote(note.id, removed);
    }
  }

  // Local data is primary: text note persistence must NOT wait for cloud attachment upload!
  if (existing && notesEqual(existing, toSave)) return;
  await pushNote(toSave);
  if (resolveOwnerId() !== sessionOwnerId) return;

  // If there are pending attachments and R2 is enabled, attempt upload
  const hasPending = toSave.attachments.some((a) => isPendingAttachment(a.storagePath));
  if (isR2AttachmentsEnabled() && hasPending) {
    const { syncNoteAttachments } = await import(
      '@/lib/attachments/attachmentSyncService'
    );
    try {
      const synced = await syncNoteAttachments(toSave);
      // Verify session ownership has not changed during the attachment upload
      if (resolveOwnerId() !== sessionOwnerId) {
        console.warn('[Notelikeus] Session changed during attachment upload; dropping stale note write');
        return;
      }
      if (!notesEqual(toSave, synced)) {
        await persistLocalNote(synced, sessionOwnerId ?? undefined);
        useNotesStore.getState().upsertLocalNote(synced);
        const currentUid = useAuthStore.getState().user?.uid;
        if (currentUid && currentUid === sessionOwnerId) {
          try {
            await getRemoteNotesDataSource().upsertNote(currentUid, synced);
          } catch {
            // Transient network failure; retry on reconciliation
          }
        }
      }
    } catch (error) {
      // Offline/Worker failure: note text and local pending blobs survive safely
      console.warn('Attachment upload failed or offline; note text remains durably saved locally:', error);
    }
  }
}

/**
 * How far a permanent delete reached.
 *
 * `cloudDeleteUnconfirmed` (F-05): the note is gone from this browser, but its cloud copy is still
 * live on other devices because this browser never learned its revision. A later snapshot finishes
 * the delete if that copy is unchanged; if it was edited elsewhere, the note comes back. The delete
 * itself did not fail, so callers report it as done and say where the note still is.
 */
export type RemoveNoteOutcome = 'deleted' | 'cloudDeleteUnconfirmed';

/** Remove locally and from the cloud when signed in. Tombstoned so a later cloud
 * merge can never resurrect it, even if the remote delete below fails or a stale
 * copy exists from before this device last synced. Guest-mode deletes skip the
 * tombstone: guest notes live in IndexedDB only, so a persisted tombstone could
 * later suppress an unrelated real cloud note that happens to reuse the same id. */
export async function removeNote(noteId: string): Promise<RemoveNoteOutcome> {
  const existing = getNote(noteId);
  const isGuest = useAuthStore.getState().guestMode;
  const attachments = existing?.attachments ?? [];
  const ownerId = resolveOwnerId();

  let baselineRevision: number | null = null;
  // Whether a cloud copy may exist. Assumed until the revision state says otherwise, so a delete
  // this browser cannot vouch for is never reported as complete.
  let mayBeInCloud = true;
  const userId = useAuthStore.getState().user?.uid;
  if (userId && ownerId) {
    // A note already carrying a baseline has been deleted once: that first observation is the
    // authority the user's decision was made against. Re-capturing here would let a repeated
    // invocation (double submit, retry, or a delayed call) read a newer revision and widen what the
    // delete is allowed to destroy, so the recorded baseline always wins.
    const recorded = useTombstoneStore.getState().baselineRevisionById[noteId];
    if (recorded != null) {
      baselineRevision = recorded;
    } else {
      const state = await loadRevisionState(ownerId);
      baselineRevision = getNoteBaseRevision(state, noteId);
      mayBeInCloud = state.knownCloudIds.includes(noteId);
    }
  }

  // Persist-first: commit deletion to IndexedDB before mutating in-memory store
  if (ownerId) {
    await deleteLocalIndexedDbNote(ownerId, noteId);
  }

  if (!isGuest) {
    // Without a known revision the delete cannot be sent yet. The fingerprint of what the user
    // deleted lets a later snapshot finish it, but only against a cloud copy that still says
    // exactly that (notesSyncService.partitionTombstoned).
    const digest = baselineRevision == null && existing ? authoredContentDigest(existing) : null;
    useTombstoneStore.getState().markDeleted(noteId, baselineRevision, undefined, digest);
  }
  useNotesStore.getState().removeLocalNote(noteId);

  if (userId) {
    // Remote delete: locally committed delete stays deleted/tombstoned even if remote fails
    try {
      await getRemoteNotesDataSource().deleteNote(userId, noteId, baselineRevision ?? undefined);
    } catch (error) {
      if (!(error instanceof CloudDeleteUnconfirmedError)) throw error;
      // The tombstone's fingerprint lets a later snapshot finish this delete. Attachments are left
      // alone: a cloud copy that is still live may still reference them. A note no snapshot has
      // ever shown never reached the cloud, so there is nothing left on other devices to report.
      return mayBeInCloud ? 'cloudDeleteUnconfirmed' : 'deleted';
    }
  }

  if (!isR2AttachmentsEnabled() || attachments.length === 0) return 'deleted';
  const { gcAttachmentsAfterNoteDelete } = await import(
    '@/lib/attachments/attachmentSyncService'
  );
  useTombstoneStore.getState().markPendingAttachmentGc(
    noteId,
    attachments.map((attachment) => attachment.id),
  );
  try {
    await gcAttachmentsAfterNoteDelete(noteId, attachments);
    useTombstoneStore.getState().clearPendingAttachmentGc(noteId);
  } catch {
    // Note stays deleted; GC retries on the next snapshot or pull.
  }
  return 'deleted';
}

/** What a batch of permanent deletes achieved, note by note. */
export interface RemoveNotesResult {
  /** Gone from this browser, so an Undo can put them back. */
  removedIds: string[];
  /**
   * Of [removedIds], how many may still be on other devices: the cloud delete was unconfirmed
   * (F-05) or failed. Both are finished from the tombstone by a later sync.
   */
  pendingInCloud: number;
  /** Still in this browser: the local delete itself failed. */
  failedIds: string[];
}

/**
 * {@link removeNote} for several notes, settling every one.
 *
 * `Promise.all` rejected on the first failure, so the caller reported the whole batch as failed and
 * offered no Undo, while every other note in it had been deleted. A rejected remove whose note is
 * no longer here failed only in the cloud: the local delete and its tombstone are committed, and
 * the tombstone retries the cloud delete, so that note counts as removed.
 */
export async function removeNotes(noteIds: string[]): Promise<RemoveNotesResult> {
  const settled = await Promise.allSettled(noteIds.map((noteId) => removeNote(noteId)));
  const result: RemoveNotesResult = { removedIds: [], pendingInCloud: 0, failedIds: [] };
  settled.forEach((outcome, index) => {
    const noteId = noteIds[index]!;
    if (outcome.status === 'fulfilled') {
      result.removedIds.push(noteId);
      if (outcome.value === 'cloudDeleteUnconfirmed') result.pendingInCloud += 1;
      return;
    }
    console.warn(`[Notelikeus] Delete of note ${noteId} failed:`, outcome.reason);
    if (getNote(noteId)) {
      result.failedIds.push(noteId);
    } else {
      result.removedIds.push(noteId);
      result.pendingInCloud += 1;
    }
  });
  return result;
}

export async function trashNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isTrashed: true, isArchived: false, isPinned: false });
  await pushNote(updated);
  return updated;
}

export async function restoreNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isTrashed: false, isArchived: false });
  await pushNote(updated);
  return updated;
}

export async function archiveNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isArchived: true, isTrashed: false, isPinned: false });
  await pushNote(updated);
  return updated;
}

export async function emptyTrash(): Promise<RemoveNotesResult> {
  const trashed = useNotesStore.getState().notes.filter((note) => note.isTrashed);
  return removeNotes(trashed.map((note) => note.id));
}

/** Reverse a permanent delete within the undo window. Clears both tombstones so the
 * realtime listener and future merges (which suppress tombstoned ids) keep the note live. */
export async function restorePermanentlyDeletedNote(note: Note): Promise<void> {
  const userId = useAuthStore.getState().user?.uid;
  useTombstoneStore.getState().markRestored(note.id);

  // Persist-first: commit to IndexedDB before clearing tombstone protection. Not recorded as a
  // pending edit: restore_note publishes it, and its restore marker already survives a failure.
  try {
    await persistLocalNote(note, undefined, { asEdit: false });
  } catch (error) {
    useTombstoneStore.getState().clearRestored([note.id]);
    throw error;
  }

  useTombstoneStore.getState().clearIds([note.id]);
  useNotesStore.getState().upsertLocalNote(note);
  if (userId) {
    try {
      await restoreCloudNote(userId, note);
    } catch (error) {
      // A refusal is terminal (F-14): the cloud row is live and this baseline cannot overwrite it,
      // so the marker must not stay set — every later reconcile would retry a call that can only be
      // refused again. The local undo stands and the next snapshot reconciles the live row. Rethrown
      // either way, because the user asked for this and needs to hear that it did not happen.
      if (error instanceof RestoreRefusedError) {
        useTombstoneStore.getState().clearRestored([note.id]);
      }
      throw error;
    }
  }
  useTombstoneStore.getState().clearRestored([note.id]);
}
