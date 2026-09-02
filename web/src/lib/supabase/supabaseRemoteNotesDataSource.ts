import {
  mergeRemoteNotes,
  shouldUploadOverRemote,
} from '@/lib/firestore/notesRepository';
import { getSupabaseClient } from '@/lib/supabase/client';
import { SUPABASE_PULL_INTERVAL_MS } from '@/lib/supabase/constants';
import {
  forgetNoteRevision,
  getNoteBaseRevision,
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
import type { RemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSource';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';
import { isCloudSyncEligible } from '@/types/note';

interface ApplyNoteResult {
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

async function ensureAuthenticated(): Promise<void> {
  const { data, error } = await getSupabaseClient().auth.getSession();
  if (error) throw error;
  if (!data.session) {
    throw new Error(
      'Supabase session missing — sign in via Supabase Auth (Phase 5) before using the Supabase backend.',
    );
  }
}

function applyServerFields(note: Note, result: ApplyNoteResult): Note {
  return {
    ...note,
    serverUpdatedAt:
      result.server_updated_at ?? note.serverUpdatedAt ?? Date.now(),
  };
}

async function fetchSnapshotNotes(): Promise<{
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

async function applyNoteChange(
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

export const supabaseRemoteNotesDataSource: RemoteNotesDataSource = {
  subscribeToNotes(userId, onData, onError) {
    let stopped = false;
    let notesById = new Map<string, Note>();
    let timer: ReturnType<typeof setInterval> | null = null;

    const emit = () => {
      onData(Array.from(notesById.values()));
    };

    const bootstrap = async () => {
      try {
        await ensureAuthenticated();
        const snapshot = await fetchSnapshotNotes();
        useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
        notesById = new Map(snapshot.notes.map((note) => [note.id, note]));
        await saveRevisionState(userId, {
          noteRevisions: snapshot.noteRevisions,
          lastRemoteRevision: snapshot.maxRevision,
        });
        emit();
      } catch (error) {
        onError?.(error instanceof Error ? error : new Error(String(error)));
      }
    };

    const pull = async () => {
      if (stopped) return;
      try {
        await ensureAuthenticated();
        const state = await loadRevisionState(userId);
        const { data, error } = await getSupabaseClient().rpc('pull_changes', {
          p_after_revision: state.lastRemoteRevision,
          p_limit: 100,
        });
        if (error) throw error;
        const payload = (data ?? {}) as PullChangesResult;
        const changes = payload.changes ?? [];
        if (changes.length === 0) return;

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
        }

        await saveRevisionState(userId, { lastRemoteRevision: maxRevision, noteRevisions });
        emit();
      } catch (error) {
        onError?.(error instanceof Error ? error : new Error(String(error)));
      }
    };

    void bootstrap();
    timer = setInterval(() => {
      void pull();
    }, SUPABASE_PULL_INTERVAL_MS);

    return () => {
      stopped = true;
      if (timer) clearInterval(timer);
    };
  },

  async fetchAllNotes(userId) {
    await ensureAuthenticated();
    const snapshot = await fetchSnapshotNotes();
    useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
    await saveRevisionState(userId, {
      noteRevisions: snapshot.noteRevisions,
      lastRemoteRevision: snapshot.maxRevision,
    });
    return snapshot.notes;
  },

  async upsertNote(userId, note) {
    await ensureAuthenticated();
    if (useTombstoneStore.getState().isDeleted(note.id)) {
      await this.deleteNote(userId, note.id);
      return;
    }
    const state = await loadRevisionState(userId);
    const baseRevision = getNoteBaseRevision(state, note.id);
    const updated = await applyNoteChange(userId, note, baseRevision);
    void updated;
  },

  async deleteNote(userId, noteId) {
    await ensureAuthenticated();
    const state = await loadRevisionState(userId);
    const baseRevision = getNoteBaseRevision(state, noteId);
    if (baseRevision == null) {
      useTombstoneStore.getState().markDeleted(noteId);
      return;
    }
    const { data, error } = await getSupabaseClient().rpc('apply_note_delete', {
      p_note_id: noteId,
      p_base_revision: baseRevision,
    });
    if (error) throw error;
    const result = (data ?? {}) as ApplyNoteResult;
    if (result.status === 'conflict') {
      if (result.idempotent) {
        await forgetNoteRevision(userId, noteId);
        return;
      }
      throw new Error(`Delete conflict for note ${noteId}`);
    }
    if (result.revision != null) {
      await rememberNoteRevision(userId, noteId, result.revision);
      await forgetNoteRevision(userId, noteId);
      await saveRevisionState(userId, {
        lastRemoteRevision: Math.max(state.lastRemoteRevision, result.revision),
      });
    }
    useTombstoneStore.getState().markDeleted(noteId);
  },

  async syncNotesWithCloud(userId, localNotes, previouslyKnownCloudIds) {
    await ensureAuthenticated();
    const { notes: remoteNotes, tombstones, noteRevisions, maxRevision } =
      await fetchSnapshotNotes();
    useTombstoneStore.getState().mergeFromCloud(tombstones);

    if (remoteNotes.length === 0 && previouslyKnownCloudIds.size > 0) {
      throw new Error(
        `Cloud returned no notes but ${previouslyKnownCloudIds.size} were expected — refusing to ` +
          `delete local copies. Check the connection or sign in again.`,
      );
    }

    const remoteById = new Map(remoteNotes.map((note) => [note.id, note]));
    const cloudIds = new Set(remoteById.keys());
    const isDeleted = (id: string) => useTombstoneStore.getState().isDeleted(id);

    let merged = await mergeRemoteNotes(localNotes, remoteNotes);
    merged = merged.filter((note) => !isDeleted(note.id));

    let changes = 0;
    const droppedLocalIds = new Set<string>();

    for (const localNote of localNotes) {
      if (isDeleted(localNote.id)) continue;

      if (cloudIds.has(localNote.id)) {
        if (!isCloudSyncEligible(localNote)) continue;
        const remote = remoteById.get(localNote.id);
        if (shouldUploadOverRemote(localNote, remote)) {
          const state = await loadRevisionState(userId);
          const baseRevision = getNoteBaseRevision(state, localNote.id);
          try {
            const updated = await applyNoteChange(userId, localNote, baseRevision);
            merged = merged.map((note) => (note.id === localNote.id ? updated : note));
            changes++;
          } catch {
            // Conflict — keep merged remote winner from mergeRemoteNotes.
          }
        }
        continue;
      }

      if (previouslyKnownCloudIds.has(localNote.id)) {
        droppedLocalIds.add(localNote.id);
        useTombstoneStore.getState().markDeleted(localNote.id);
        changes++;
        continue;
      }

      if (isCloudSyncEligible(localNote)) {
        const updated = await applyNoteChange(userId, localNote, null);
        merged = merged.map((note) => (note.id === localNote.id ? updated : note));
        if (!merged.some((note) => note.id === localNote.id)) {
          merged.push(updated);
        }
        changes++;
      }
    }

    if (droppedLocalIds.size > 0) {
      merged = merged.filter((note) => !droppedLocalIds.has(note.id));
    }

    for (const remoteNote of remoteNotes) {
      if (isDeleted(remoteNote.id)) continue;
      if (!merged.some((note) => note.id === remoteNote.id)) {
        merged.push(remoteNote);
        changes++;
      }
    }

    await saveRevisionState(userId, {
      noteRevisions,
      lastRemoteRevision: maxRevision,
    });

    return {
      merged,
      remoteIds: remoteNotes.map((note) => note.id),
    };
  },
};
