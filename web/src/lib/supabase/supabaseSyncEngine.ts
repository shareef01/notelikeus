import { getSupabaseClient } from '@/lib/supabase/client';
import {
  forgetNoteRevision,
  loadRevisionState,
  rememberNoteRevision,
  saveRevisionState,
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

export async function fetchSnapshotNotes(): Promise<{
  notes: Note[];
  tombstones: Record<string, number>;
  noteRevisions: Record<string, number>;
  maxRevision: number;
}> {
  const { data, error } = await getSupabaseClient().rpc('fetch_full_snapshot');
  if (error) throw error;
  const snapshot = (data ?? {}) as SnapshotResult;
  const notes = (snapshot.notes ?? []).map((row) => supabaseNoteToNote(row));
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
  return { notes, tombstones, noteRevisions, maxRevision };
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
): Promise<boolean> {
  await ensureSupabaseAuthenticated();
  let state = await loadRevisionState(userId);
  let changed = false;

  for (;;) {
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

    for (const change of changes) {
      if (change.type === 'tombstone') {
        const tombstone = change as SupabaseTombstonePayload;
        if (tombstone.note_id) {
          notesById.delete(tombstone.note_id);
          if (tombstone.deleted_at != null) {
            useTombstoneStore
              .getState()
              .mergeFromCloud({ [tombstone.note_id]: tombstone.deleted_at });
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
      notesById.set(note.id, note);
      if (notePayload.revision != null) {
        noteRevisions[note.id] = notePayload.revision;
        maxRevision = Math.max(maxRevision, notePayload.revision);
      }
      changed = true;
    }

    state = {
      lastRemoteRevision: maxRevision,
      noteRevisions,
    };
    await saveRevisionState(userId, state);

    if (!payload.has_more) break;
  }

  return changed;
}
