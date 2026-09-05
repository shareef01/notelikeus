import {
  mergeRemoteNotes,
  shouldUploadOverRemote,
} from '@/lib/notes/remoteMerge';
import { subscribeSupabaseNoteRealtime } from '@/lib/supabase/supabaseRealtimeSync';
import {
  loadRevisionState,
  forgetNoteRevision,
  rememberNoteRevision,
  saveRevisionState,
  getNoteBaseRevision,
} from '@/lib/supabase/revisionStore';
import {
  applyNoteChange,
  ensureSupabaseAuthenticated,
  fetchSnapshotNotes,
  pullIncrementalChanges,
} from '@/lib/supabase/supabaseSyncEngine';
import type { RemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSource';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';
import { isCloudSyncEligible } from '@/types/note';

interface ApplyNoteResult {
  status?: string;
  revision?: number;
  idempotent?: boolean;
  error?: string;
}

export const supabaseRemoteNotesDataSource: RemoteNotesDataSource = {
  subscribeToNotes(userId, onData, onError) {
    let stopped = false;
    let notesById = new Map<string, Note>();

    const emit = () => {
      onData(Array.from(notesById.values()));
    };

    const bootstrap = async () => {
      try {
        await ensureSupabaseAuthenticated();
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
        const changed = await pullIncrementalChanges(userId, notesById);
        if (changed) emit();
      } catch (error) {
        onError?.(error instanceof Error ? error : new Error(String(error)));
      }
    };

    void bootstrap();

    const unsubscribeRealtime = subscribeSupabaseNoteRealtime(
      userId,
      () => {
        void pull();
      },
      () => {
        void pull();
      },
    );

    return () => {
      stopped = true;
      unsubscribeRealtime();
    };
  },

  async fetchAllNotes(userId) {
    await ensureSupabaseAuthenticated();
    const snapshot = await fetchSnapshotNotes();
    useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
    await saveRevisionState(userId, {
      noteRevisions: snapshot.noteRevisions,
      lastRemoteRevision: snapshot.maxRevision,
    });
    return snapshot.notes;
  },

  async upsertNote(userId, note) {
    await ensureSupabaseAuthenticated();
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
    await ensureSupabaseAuthenticated();
    const state = await loadRevisionState(userId);
    const baseRevision = getNoteBaseRevision(state, noteId);
    if (baseRevision == null) {
      useTombstoneStore.getState().markDeleted(noteId);
      return;
    }
    const { getSupabaseClient } = await import('@/lib/supabase/client');
    const { data, error } = await getSupabaseClient().rpc('apply_note_delete', {
      p_note_id: noteId,
      p_base_revision: baseRevision,
    });
    if (error) throw error;
    const result = (data ?? {}) as ApplyNoteResult;
    // apply_note_delete answers an already-tombstoned note with
    // {status: 'applied', idempotent: true} and no revision — not 'conflict'. Reading `idempotent`
    // off the conflict branch made that cleanup unreachable and left a stale revision behind.
    if (result.idempotent) {
      await forgetNoteRevision(userId, noteId);
      useTombstoneStore.getState().markDeleted(noteId);
      return;
    }
    if (result.status === 'conflict') {
      // The server has neither the note nor a tombstone (e.g. after an account wipe): there is
      // nothing left to delete, so drop the stale local revision instead of failing forever.
      if (result.error === 'note_not_found') {
        await forgetNoteRevision(userId, noteId);
        useTombstoneStore.getState().markDeleted(noteId);
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

  async uploadAllNotes(userId, notes) {
    await ensureSupabaseAuthenticated();
    let uploaded = 0;
    for (const note of notes) {
      if (!isCloudSyncEligible(note)) continue;
      if (useTombstoneStore.getState().isDeleted(note.id)) continue;
      await this.upsertNote(userId, note);
      uploaded += 1;
    }
    return uploaded;
  },

  async syncNotesWithCloud(userId, localNotes, previouslyKnownCloudIds) {
    await ensureSupabaseAuthenticated();
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
