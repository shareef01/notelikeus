import {
  dropPendingPush,
  listPendingPushes,
  readPendingEdit,
  resolveConflictedPush,
  settlePendingPush,
} from '@/lib/local/notesLocalRepository';
import { notesUserContentEqual } from '@/lib/notes/noteEquality';
import { loadRevisionState } from '@/lib/supabase/revisionStore';
import { applyNoteChange, NoteConflictError } from '@/lib/supabase/supabaseSyncEngine';
import { useNotesStore } from '@/store/notesStore';
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
  // A conflict adds a copy that is itself pending, so a second pass pushes it straight away.
  for (let pass = 0; pass < 2; pass++) {
    const ids = Object.keys(await listPendingPushes(userId)).filter((id) => !attempted.has(id));
    for (const noteId of ids) {
      if (!isActive()) return result;
      attempted.add(noteId);
      try {
        await flushOne(userId, noteId, result);
      } catch (error) {
        console.warn(`[Notelikeus] Note ${noteId} is saved locally; its upload will be retried:`, error);
      }
    }
  }
  return result;
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
    if (error.current && notesUserContentEqual(note, error.current)) {
      accepted = error.current;
    } else {
      await keepBothVersions(userId, note, marker.seq, error.current, result);
      return;
    }
  }

  await settleAccepted(userId, note, marker.seq, accepted, result);
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
  const copy = conflictedCopy(local);
  if (!(await resolveConflictedPush(userId, local.id, seq, copy, current))) return;

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

/**
 * The local version as a new note. Attachments stay with the original: their cloud rows and
 * pending bytes are keyed to that note's id, and pointing a second note at them would break both.
 */
function conflictedCopy(local: Note): Note {
  const maxLocalId = useNotesStore
    .getState()
    .notes.reduce((max, note) => Math.max(max, note.localId), local.localId);
  const localId = nextLocalNoteIdAfter(maxLocalId);
  return {
    ...local,
    id: String(localId),
    localId,
    title: local.title ? `${local.title} (conflicted copy)` : 'Conflicted copy',
    serverUpdatedAt: null,
    attachments: [],
  };
}

/** Shows the confirmed version, unless the UI already moved on to a newer local edit. */
function publish(pushed: Note, accepted: Note): void {
  const store = useNotesStore.getState();
  const shown = store.notes.find((note) => note.id === accepted.id);
  if (!shown || shown.timestamp === pushed.timestamp) store.upsertLocalNote(accepted);
}
