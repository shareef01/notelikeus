import { keepingLocalOnlyAttachments } from '@/lib/attachments/attachmentPaths';
import { applyRemotePageAtomically } from '@/lib/local/notesLocalRepository';
import { useNotesStore } from '@/store/notesStore';
import {
  retryPendingCloudRestores,
  withoutRestoredDeletes,
} from '@/lib/notes/restoreRetry';
import { getSupabaseClient } from '@/lib/supabase/client';
import {
  forgetNoteRevision,
  loadRevisionState,
  rememberNoteRevision,
} from '@/lib/supabase/revisionStore';
import {
  noteToSupabaseRpcArgs,
  parseTombstoneMap,
  supabaseNoteToNote,
  type SupabaseNotePayload,
  type SupabaseTombstonePayload,
} from '@/lib/supabase/supabaseNoteMapper';
import {
  asWipeRefusal,
  recordWipeGeneration,
  wipeGenerationForPush,
} from '@/lib/supabase/wipeGeneration';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';
import {
  hydrateNotesWithAttachments,
  retryPendingAttachmentGc,
} from '@/lib/attachments/attachmentSyncService';

export interface ApplyNoteResult {
  status?: string;
  revision?: number;
  server_updated_at?: number;
  error?: string;
  current?: SupabaseNotePayload;
  idempotent?: boolean;
}

interface PullChangesResult {
  changes?: SupabaseNotePayload[] | SupabaseTombstonePayload[];
  has_more?: boolean;
  wipe_generation?: number;
}

interface SnapshotResult {
  notes?: SupabaseNotePayload[];
  tombstones?: SupabaseTombstonePayload[];
  note_count?: number;
  wipe_generation?: number;
}

/**
 * The server refused a write because the note is no longer at the revision it was based on.
 *
 * Kept distinct from transport failures on purpose: a network error means "try again later",
 * while this means someone else's version is already in the cloud and retrying would overwrite it.
 * [current] is that version, or null when the note was deleted in the cloud — or, when [missing],
 * when the cloud has neither the note nor a tombstone for it (its data was wiped).
 */
export class NoteConflictError extends Error {
  readonly noteId: string;
  readonly current: Note | null;
  readonly missing: boolean;

  constructor(noteId: string, current: Note | null, message?: string, missing = false) {
    super(message ?? `Revision conflict for note ${noteId}`);
    this.name = 'NoteConflictError';
    this.noteId = noteId;
    this.current = current;
    this.missing = missing;
  }
}

export async function ensureSupabaseAuthenticated(): Promise<void> {
  const { data, error } = await getSupabaseClient().auth.getSession();
  if (error) throw error;
  if (!data.session) {
    throw new Error(
      'Supabase session missing — sign in via Supabase Auth (Phase 5) before using the Supabase backend.',
    );
  }
}

export function applyServerFields(note: Note, result: ApplyNoteResult): Note {
  return {
    ...note,
    serverUpdatedAt:
      result.server_updated_at ?? note.serverUpdatedAt ?? Date.now(),
  };
}

export async function fetchSnapshotNotes(options?: {
  hydrateAttachments?: boolean;
}): Promise<{
  notes: Note[];
  tombstones: Record<string, number>;
  noteRevisions: Record<string, number>;
  maxRevision: number;
  wipeGeneration: number;
}> {
  const { data, error } = await getSupabaseClient().rpc('fetch_full_snapshot');
  if (error) throw error;
  // SQL NULL used to mean "zero notes" because fetch_full_snapshot's outer FROM notes
  // matched nothing — that also dropped tombstones. Refuse it; the RPC must return JSON.
  if (data == null) {
    throw new Error('Incomplete snapshot: fetch_full_snapshot returned null');
  }
  const snapshot = data as SnapshotResult;
  const rows = snapshot.notes ?? [];
  // `note_count` is a separate COUNT(*) so a truncated jsonb_agg cannot look like a full library.
  if (typeof snapshot.note_count !== 'number') {
    throw new Error('Incomplete snapshot: missing note_count');
  }
  if (snapshot.note_count !== rows.length) {
    throw new Error(
      `Incomplete snapshot: expected ${snapshot.note_count} notes, got ${rows.length}`,
    );
  }
  const notes = rows.map((row) => supabaseNoteToNote(row));
  const hydratedNotes =
    options?.hydrateAttachments === false ? notes : await hydrateNotesWithAttachments(notes);
  const tombstones = parseTombstoneMap(snapshot.tombstones ?? []);
  const noteRevisions: Record<string, number> = {};
  let maxRevision = 0;
  for (const row of snapshot.notes ?? []) {
    if (row.revision != null) {
      noteRevisions[row.note_id] = row.revision;
      maxRevision = Math.max(maxRevision, row.revision);
    }
  }
  for (const row of snapshot.tombstones ?? []) {
    if (row.revision != null) {
      maxRevision = Math.max(maxRevision, row.revision);
    }
  }
  // The account's wipe generation, published alongside the rows: an empty snapshot at a generation
  // this browser has not seen is an intentional wipe, not a truncated read (F-05's guard).
  const wipeGeneration =
    typeof snapshot.wipe_generation === 'number' && Number.isFinite(snapshot.wipe_generation)
      ? snapshot.wipe_generation
      : 0;
  return { notes: hydratedNotes, tombstones, noteRevisions, maxRevision, wipeGeneration };
}

export async function applyNoteChange(
  userId: string,
  note: Note,
  baseRevision: number | null,
): Promise<Note> {
  const wipeGeneration = await wipeGenerationForPush(userId);
  const args = noteToSupabaseRpcArgs(note, baseRevision, wipeGeneration);
  const { data, error } = await getSupabaseClient().rpc('apply_note_change', args);
  // A wipe refusal throws a typed error and blocks every later push; anything else rethrows.
  if (error) await asWipeRefusal(userId, error);
  const result = (data ?? {}) as ApplyNoteResult;
  if (result.status === 'conflict') {
    if (result.error === 'note_deleted') {
      useTombstoneStore.getState().markDeleted(note.id);
      await forgetNoteRevision(userId, note.id);
      throw new NoteConflictError(note.id, null, `Note ${note.id} was deleted in the cloud`);
    }
    if (result.current) {
      const remoteRevision = result.current.revision;
      if (remoteRevision != null) {
        await rememberNoteRevision(userId, note.id, remoteRevision);
      }
      const localRevStr =
        baseRevision != null ? `local revision ${baseRevision}` : 'no local revision';
      const remoteRevStr =
        remoteRevision != null ? `remote revision ${remoteRevision}` : 'unknown remote revision';
      throw new NoteConflictError(
        note.id,
        supabaseNoteToNote(result.current),
        `Revision conflict for note ${note.id} (${localRevStr}, ${remoteRevStr})`,
      );
    }
    if (result.error === 'note_not_found') {
      throw new NoteConflictError(note.id, null, `Revision conflict for note ${note.id}`, true);
    }
    throw new Error(`Revision conflict for note ${note.id}`);
  }
  if (result.status !== 'applied' || result.revision == null) {
    throw new Error(`Unexpected apply_note_change response for note ${note.id}`);
  }
  await rememberNoteRevision(userId, note.id, result.revision);
  return applyServerFields(note, result);
}

export async function pullIncrementalChanges(
  userId: string,
  notesById: Map<string, Note>,
  options?: { isActive?: () => boolean },
): Promise<boolean> {
  const stillActive = () => options?.isActive?.() !== false;
  await ensureSupabaseAuthenticated();
  if (!stillActive()) return false;
  let state = await loadRevisionState(userId);
  let changed = false;

  for (;;) {
    if (!stillActive()) return changed;
    const { data, error } = await getSupabaseClient().rpc('pull_changes', {
      p_after_revision: state.lastRemoteRevision,
      p_limit: 100,
    });
    if (error) throw error;
    const payload = (data ?? {}) as PullChangesResult;
    const changes = payload.changes ?? [];
    // Every pull carries the generation, so an account wiped on another device is learned about
    // here even when it had no changes left to report.
    if (typeof payload.wipe_generation === 'number') {
      await recordWipeGeneration(userId, payload.wipe_generation);
    }
    if (changes.length === 0) break;

    let maxRevision = state.lastRemoteRevision;
    const noteRevisions = { ...state.noteRevisions };
    const upserts: Note[] = [];
    const deletedNoteIds: string[] = [];
    const tombstones: Record<string, number> = {};

    for (const change of changes) {
      if (change.type === 'tombstone') {
        const tombstone = change as SupabaseTombstonePayload;
        if (tombstone.note_id) {
          deletedNoteIds.push(tombstone.note_id);
          if (tombstone.deleted_at != null) {
            tombstones[tombstone.note_id] = tombstone.deleted_at;
          }
          delete noteRevisions[tombstone.note_id];
        }
        if (tombstone.revision != null) {
          maxRevision = Math.max(maxRevision, tombstone.revision);
        }
        changed = true;
        continue;
      }

      const notePayload = change as SupabaseNotePayload;
      if (!notePayload.note_id) continue;
      const local =
        useNotesStore.getState().notes.find((candidate) => candidate.id === notePayload.note_id) ??
        notesById.get(notePayload.note_id);
      const note = keepingLocalOnlyAttachments(supabaseNoteToNote(notePayload), local);
      upserts.push(note);
      if (notePayload.revision != null) {
        noteRevisions[note.id] = notePayload.revision;
        maxRevision = Math.max(maxRevision, notePayload.revision);
      }
      changed = true;
    }

    if (!stillActive()) return changed;
    const safeDeletes = withoutRestoredDeletes(deletedNoteIds);
    const knownCloudIds = [
      ...new Set([
        ...state.knownCloudIds.filter((id) => !safeDeletes.includes(id)),
        ...upserts.map((note) => note.id),
      ]),
    ];
    await applyRemotePageAtomically({
      ownerId: userId,
      upserts,
      deletedNoteIds: safeDeletes,
      noteRevisions,
      lastRemoteRevision: maxRevision,
      knownCloudIds,
    });
    if (!stillActive()) return changed;

    for (const noteId of safeDeletes) {
      notesById.delete(noteId);
    }
    if (Object.keys(tombstones).length > 0) {
      useTombstoneStore.getState().mergeFromCloud(tombstones);
    }
    useTombstoneStore.getState().acknowledgeRestoredLiveNotes(upserts.map((note) => note.id));
    for (const note of upserts) {
      notesById.set(note.id, note);
    }
    await retryPendingCloudRestores(userId, [...notesById.values(), ...upserts]);
    if (!stillActive()) return changed;
    state = {
      lastRemoteRevision: maxRevision,
      noteRevisions,
      knownCloudIds,
    };

    if (!payload.has_more) break;
  }

  if (changed && stillActive()) {
    const hydrated = await hydrateNotesWithAttachments(Array.from(notesById.values()));
    if (!stillActive()) return changed;
    notesById.clear();
    for (const note of hydrated) {
      notesById.set(note.id, note);
    }
  }

  if (stillActive()) {
    await retryPendingAttachmentGc();
  }

  return changed;
}
