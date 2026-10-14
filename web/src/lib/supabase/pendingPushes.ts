import { ATTACHMENT_R2_PREFIX } from '@/lib/attachments/attachmentPaths';
import {
  reclaimUncommittedUploads,
  releaseCopiedPendingAttachments,
  releaseCopyAttachments,
  withPendingAttachments,
} from '@/lib/attachments/conflictCopyAttachments';
import {
  dropPendingPush,
  listPendingPushes,
  readPendingEdit,
  resolveConflictedPush,
  settlePendingPush,
} from '@/lib/local/notesLocalRepository';
import { conflictedCopyOf } from '@/lib/notes/conflictResolution';
import { notesUserContentEqual } from '@/lib/notes/noteEquality';
import { loadRevisionState } from '@/lib/supabase/revisionStore';
import { applyNoteChange, NoteConflictError } from '@/lib/supabase/supabaseSyncEngine';
import { useNotesStore } from '@/store/notesStore';
import { useSyncStatusStore } from '@/store/syncStatusStore';
import { useToastStore } from '@/store/toastStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { nextLocalNoteIdAfter, type Note } from '@/types/note';

/** What a flush changed locally, for callers holding their own copy of the library. */
export interface PendingFlushResult {
  /** Notes whose local row a flush rewrote or created: confirmed pushes, cloud winners, copies. */
  upserted: Note[];
  /** Notes a flush removed locally because the cloud had deleted them. */
  removedIds: string[];
}

const flushChains = new Map<string, Promise<PendingFlushResult>>();

/**
 * Pushes every local edit the cloud has not confirmed, each against the revision it was made on.
 *
 * * Accepted: the marker is settled and the row takes the server's stamp.
 * * Refused as a conflict — another device changed or deleted the note: the cloud version stays on
 *   the note and the local one is kept as a new "(conflicted copy)" note, so neither is lost. If the
 *   cloud already holds exactly this content (an earlier push landed and only its reply was lost),
 *   the marker is simply settled.
 * * Anything else (offline, server error): the edit stays pending for the next flush.
 *
 * Flushes for one account run one after another, so two never push the same marker.
 */
export function flushPendingPushes(
  userId: string,
  options?: { isActive?: () => boolean },
): Promise<PendingFlushResult> {
  const previous = flushChains.get(userId) ?? Promise.resolve(emptyResult());
  const next = previous.catch(() => emptyResult()).then(() => runFlush(userId, options));
  flushChains.set(userId, next);
  return next;
}

function emptyResult(): PendingFlushResult {
  return { upserted: [], removedIds: [] };
}

async function runFlush(
  userId: string,
  options?: { isActive?: () => boolean },
): Promise<PendingFlushResult> {
  const isActive = () => options?.isActive?.() !== false;
  const result = emptyResult();
  const attempted = new Set<string>();
  const status = useSyncStatusStore.getState();
  let failed = false;
  status.setFlushing(userId, true);
  try {
    // A conflict adds a copy that is itself pending, so a second pass pushes it straight away.
    for (let pass = 0; pass < 2; pass++) {
      const ids = Object.keys(await listPendingPushes(userId)).filter((id) => !attempted.has(id));
      for (const noteId of ids) {
        // Cut short, so this flush says nothing about whether the rest would have gone through.
        if (!isActive()) return result;
        attempted.add(noteId);
        try {
          await flushOne(userId, noteId, result);
          reportedRejections.delete(rejectionKey(userId, noteId));
        } catch (error) {
          failed = true;
          console.warn(`[Notelikeus] Note ${noteId} is saved locally; its upload will be retried:`, error);
          if (isPermanentRejection(error)) reportRejection(userId, noteId, error);
        }
      }
    }
    status.setFailed(userId, failed);
    return result;
  } catch (error) {
    // The queue itself could not be read: nothing was pushed, so nothing can be called synced.
    status.setFailed(userId, true);
    throw error;
  } finally {
    status.setFlushing(userId, false);
  }
}

/** Notes already reported as refused, so a flush that fails the same way again stays quiet. */
const reportedRejections = new Set<string>();

function rejectionKey(userId: string, noteId: string): string {
  return `${userId}:${noteId}`;
}

/**
 * Whether the server refused the write itself, so every retry will fail the same way.
 *
 * Postgres answers with the SQLSTATE the function raised: data exceptions (22xxx, which is what
 * `validate_note_payload` raises for a title or note that is too long) and integrity violations
 * (23xxx). A network failure, a timeout or a 5xx carries no such code and is expected to pass.
 */
function isPermanentRejection(error: unknown): boolean {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' && /^2[23][0-9A-Z]{3}$/.test(code);
}

/**
 * Tells the user, once per note, that an edit is not reaching the cloud and why. The edit itself
 * stays saved and queued: it is the only copy, and the user can fix it (shorten the note) and save
 * again, which the next flush pushes. Without this the note silently stopped syncing.
 */
function reportRejection(userId: string, noteId: string, error: unknown): void {
  const key = rejectionKey(userId, noteId);
  if (reportedRejections.has(key)) return;
  reportedRejections.add(key);
  const title = useNotesStore.getState().notes.find((note) => note.id === noteId)?.title.trim();
  const reason = error instanceof Error ? error.message : String(error);
  useToastStore
    .getState()
    .show(
      `${title ? `"${title}"` : 'A note'} can't sync: ${reason}. Your edit is kept on this device.`,
      'error',
    );
}

async function flushOne(userId: string, noteId: string, result: PendingFlushResult): Promise<void> {
  if (useTombstoneStore.getState().isDeleted(noteId)) {
    // Deleted on this device: the delete path owns what happens in the cloud now.
    await dropPendingPush(userId, noteId);
    return;
  }
  const pending = await readPendingEdit(userId, noteId);
  if (!pending) {
    await dropPendingPush(userId, noteId);
    return;
  }
  const { marker, note } = pending;

  let accepted: Note;
  try {
    accepted = await applyNoteChange(userId, note, marker.baseRevision);
  } catch (error) {
    if (!(error instanceof NoteConflictError)) throw error;
    if (error.missing) {
      // Nothing in the cloud to conflict with — no note, no tombstone — so this is a create.
      accepted = await applyNoteChange(userId, note, null);
      await settleAccepted(userId, note, marker.seq, accepted, result);
      return;
    }
    // The cloud's version carries no attachments (the payload has none), so the comparison leaves
    // them out; comparing them made every note with an image look changed elsewhere.
    if (error.current && notesUserContentEqual({ ...note, attachments: [] }, error.current)) {
      accepted = await adoptSameVersion(userId, note, error.current);
    } else {
      await keepBothVersions(userId, note, marker.seq, error.current, result);
      return;
    }
  }

  await settleAccepted(userId, note, marker.seq, accepted, result);
}

/**
 * The cloud already holds [note]'s words — usually this device's own push whose reply was lost. The
 * attachments stay this device's: the payload cannot speak for them. Uploads only become visible
 * when a commit names them, so a version with any is committed once more against the cloud's
 * revision, naming them, rather than assumed to have done so.
 */
async function adoptSameVersion(userId: string, note: Note, current: Note): Promise<Note> {
  if (!note.attachments.some((attachment) => attachment.storagePath.startsWith(ATTACHMENT_R2_PREFIX))) {
    return { ...current, attachments: note.attachments };
  }
  const revision = (await loadRevisionState(userId)).noteRevisions[note.id] ?? null;
  return applyNoteChange(userId, note, revision);
}

async function settleAccepted(
  userId: string,
  pushed: Note,
  seq: number,
  accepted: Note,
  result: PendingFlushResult,
): Promise<void> {
  const revision = (await loadRevisionState(userId)).noteRevisions[accepted.id] ?? null;
  if ((await settlePendingPush(userId, seq, accepted, revision)) === 'settled') {
    publish(pushed, accepted);
    result.upserted.push(accepted);
  }
}

async function keepBothVersions(
  userId: string,
  local: Note,
  seq: number,
  current: Note | null,
  result: PendingFlushResult,
): Promise<void> {
  // Images not uploaded yet exist only here, under the local note's id, and an upload this push was
  // to name is visible nowhere until a commit does: both go with the copy.
  const source = await reclaimUncommittedUploads(local, userId, { noteGone: current === null });
  const copy = await withPendingAttachments(source, conflictedCopy(local), userId);
  if (!(await resolveConflictedPush(userId, local.id, seq, copy, current))) {
    await releaseCopyAttachments(copy, userId);
    return;
  }
  await releaseCopiedPendingAttachments(source, current, userId);

  const store = useNotesStore.getState();
  if (current) {
    store.upsertLocalNote(current);
    result.upserted.push(current);
  } else {
    store.removeLocalNote(local.id);
    result.removedIds.push(local.id);
  }
  store.upsertLocalNote(copy);
  result.upserted.push(copy);
}

/** The local version as a new note; see conflictResolution.ts for what the copy keeps. */
function conflictedCopy(local: Note): Note {
  const maxLocalId = useNotesStore
    .getState()
    .notes.reduce((max, note) => Math.max(max, note.localId), local.localId);
  return conflictedCopyOf(local, nextLocalNoteIdAfter(maxLocalId));
}

/** Shows the confirmed version, unless the UI already moved on to a newer local edit. */
function publish(pushed: Note, accepted: Note): void {
  const store = useNotesStore.getState();
  const shown = store.notes.find((note) => note.id === accepted.id);
  if (!shown || shown.timestamp === pushed.timestamp) store.upsertLocalNote(accepted);
}
