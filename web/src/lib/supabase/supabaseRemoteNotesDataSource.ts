import { shouldUploadOverRemote } from '@/lib/notes/remoteMerge';
import { reconcileLocalAndRemoteSnapshot } from '@/lib/notes/reconcileLocalAndRemoteSnapshot';
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
import {
  isRemoteNoteDeletedError,
  isRevisionConflictError,
} from '@/lib/remote/remoteErrors';
import { getOwnerMeta, setOwnerMeta } from '@/lib/local/notesLocalRepository';
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
    /**
     * Whether [notesById] holds a full snapshot rather than a handful of pulled deltas.
     *
     * `onData` is contractually the caller's *complete* note set: notesSyncService diffs it
     * against the previously known cloud ids and tombstones everything missing. `pull_changes`
     * only returns notes above the persisted revision cursor, so layering it on a map that the
     * snapshot never filled emits "the library is now just these two notes" — and the diff then
     * deletes the rest on this device and, via the tombstone purge, in the cloud and on every
     * other device. Nothing may be emitted until a snapshot has actually landed.
     */
    let hasBaseline = false;
    /** Serialises bootstrap against pulls so a realtime wake cannot emit a half-built map. */
    let queue: Promise<void> = Promise.resolve();

    const emit = () => {
      onData(Array.from(notesById.values()));
    };

    const loadBaseline = async () => {
      await ensureSupabaseAuthenticated();
      const snapshot = await fetchSnapshotNotes();
      useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
      notesById = new Map(snapshot.notes.map((note) => [note.id, note]));
      await saveRevisionState(userId, {
        noteRevisions: snapshot.noteRevisions,
        lastRemoteRevision: snapshot.maxRevision,
      });
      hasBaseline = true;
      emit();
    };

    const pull = async () => {
      // A failed bootstrap leaves no baseline to apply deltas to. Re-fetching the snapshot is
      // both the correct emission and the recovery path; the realtime wake/fallback tick is what
      // retries it until the transport comes back.
      if (!hasBaseline) {
        await loadBaseline();
        return;
      }
      const changed = await pullIncrementalChanges(userId, notesById);
      if (changed) emit();
    };

    const run = (task: () => Promise<void>): Promise<void> => {
      queue = queue
        .then(() => (stopped ? undefined : task()))
        .catch((error: unknown) => {
          onError?.(error instanceof Error ? error : new Error(String(error)));
        });
      return queue;
    };

    void run(loadBaseline);

    const unsubscribeRealtime = subscribeSupabaseNoteRealtime(
      userId,
      () => {
        void run(pull);
      },
      () => {
        void run(pull);
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
    let state = await loadRevisionState(userId);
    let baseRevision = getNoteBaseRevision(state, noteId);
    // Same hole Kotlin already closed: a delete issued before this tab's revision map is
    // populated (fresh IDB, failed baseline, purge of a tombstoned cloud row) used to return
    // without calling apply_note_delete. The note stayed in the cloud and came back on the
    // next device that synced.
    if (baseRevision == null) {
      const snapshot = await fetchSnapshotNotes();
      useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
      await saveRevisionState(userId, {
        noteRevisions: snapshot.noteRevisions,
        lastRemoteRevision: snapshot.maxRevision,
      });
      state = await loadRevisionState(userId);
      baseRevision = getNoteBaseRevision(state, noteId);
    }
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
    const prior = await loadRevisionState(userId);
    const snapshot = await fetchSnapshotNotes();
    // Same fail-open hazard Kotlin already closed: an empty snapshot must not look like
    // "local wins every id". upsertNote would then push the whole library over whatever the
    // cloud actually holds — including a newer copy the fetch just failed to return.
    if (snapshot.notes.length === 0 && Object.keys(prior.noteRevisions).length > 0) {
      throw new Error(
        `Cloud returned no notes but ${Object.keys(prior.noteRevisions).length} were expected — ` +
          `refusing to overwrite the cloud. Check the connection or sign in again.`,
      );
    }
    useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
    await saveRevisionState(userId, {
      noteRevisions: snapshot.noteRevisions,
      lastRemoteRevision: snapshot.maxRevision,
    });

    const remoteById = new Map(snapshot.notes.map((note) => [note.id, note]));
    let uploaded = 0;
    for (const note of notes) {
      if (!isCloudSyncEligible(note)) continue;
      if (useTombstoneStore.getState().isDeleted(note.id)) continue;
      const remote = remoteById.get(note.id);
      if (!shouldUploadOverRemote(note, remote)) continue;
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

    const meta = await getOwnerMeta(userId);
    const prior = await loadRevisionState(userId);
    const knownRemoteIds = new Set(previouslyKnownCloudIds);
    for (const id of meta?.knownRemoteIds ?? []) knownRemoteIds.add(id);
    for (const id of Object.keys(prior.noteRevisions)) knownRemoteIds.add(id);

    const reconciled = reconcileLocalAndRemoteSnapshot({
      localNotes,
      remoteNotes,
      remoteTombstones: tombstones,
      knownRemoteIds,
      isDeleted: (id) => useTombstoneStore.getState().isDeleted(id),
    });

    for (const id of reconciled.newlyDeletedIds) {
      useTombstoneStore.getState().markDeleted(id);
    }

    let merged = reconciled.merged;

    for (const localNote of reconciled.toUpload) {
      if (!isCloudSyncEligible(localNote)) continue;
      if (useTombstoneStore.getState().isDeleted(localNote.id)) continue;
      const state = await loadRevisionState(userId);
      const remote = remoteNotes.find((note) => note.id === localNote.id);
      const baseRevision = remote ? getNoteBaseRevision(state, localNote.id) : null;
      try {
        const updated = await applyNoteChange(userId, localNote, baseRevision);
        merged = merged.map((note) => (note.id === localNote.id ? updated : note));
        if (!merged.some((note) => note.id === localNote.id)) {
          merged.push(updated);
        }
      } catch (error) {
        if (isRemoteNoteDeletedError(error)) {
          merged = merged.filter((note) => note.id !== localNote.id);
          continue;
        }
        if (isRevisionConflictError(error)) {
          if (error.remote) {
            merged = merged.map((note) => (note.id === localNote.id ? error.remote! : note));
          }
          continue;
        }
        throw error;
      }
    }

    await saveRevisionState(userId, {
      noteRevisions,
      lastRemoteRevision: maxRevision,
    });
    await setOwnerMeta(userId, {
      knownRemoteIds: [...reconciled.nextKnownRemoteIds],
    });

    return {
      merged,
      remoteIds: remoteNotes.map((note) => note.id),
    };
  },
};
