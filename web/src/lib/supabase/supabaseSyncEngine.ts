import { applyRemotePageAtomically } from '@/lib/local/notesLocalRepository';
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
}

interface SnapshotResult {
  notes?: SupabaseNotePayload[];
  tombstones?: SupabaseTombstonePayload[];
  note_count?: number;
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
  return { notes: hydratedNotes, tombstones, noteRevisions, maxRevision };
}

export async function applyNoteChange(
  userId: string,
  note: Note,
  baseRevision: number | null,
): Promise<Note> {
  const args = noteToSupabaseRpcArgs(note, baseRevision);
  const { data, error } = await getSupabaseClient().rpc('apply_note_change', args);
  if (error) throw error;
  const result = (data ?? {}) as ApplyNoteResult;
  if (result.status === 'conflict') {
    if (result.error === 'note_deleted') {
      useTombstoneStore.getState().markDeleted(note.id);
      await forgetNoteRevision(userId, note.id);
      throw new Error(`Note ${note.id} was deleted in the cloud`);
    }
    if (result.current) {
      const remote = supabaseNoteToNote(result.current);
      if (result.current.revision != null) {
        await rememberNoteRevision(userId, note.id, result.current.revision);
      }
      throw new Error(
        `Revision conflict for note ${note.id}: remote title "${remote.title}"`,
      );
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
      const note = supabaseNoteToNote(notePayload);
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
