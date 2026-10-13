import {
  mergeRemoteNotes,
  shouldUploadOverRemote,
} from "@/lib/notes/remoteMerge";
import { subscribeSupabaseNoteRealtime } from "@/lib/supabase/supabaseRealtimeSync";
import {
  loadRevisionState,
  forgetNoteRevision,
  saveRevisionState,
  getNoteBaseRevision,
} from "@/lib/supabase/revisionStore";
import {
  applyNoteChange,
  ensureSupabaseAuthenticated,
  fetchSnapshotNotes,
  pullIncrementalChanges,
} from "@/lib/supabase/supabaseSyncEngine";
import {
  beginNotesSyncSession,
  getActiveNotesSyncSession,
} from "@/lib/supabase/syncSession";
import { toError } from "@/lib/errors/formatUnknownError";
import {
  applyRemoteSnapshotAtomically,
  hasPendingPush,
  listNotes,
  listPendingPushes,
} from "@/lib/local/notesLocalRepository";
import { flushPendingPushes, type PendingFlushResult } from "@/lib/supabase/pendingPushes";
import {
  collectPreservedRestoredNotes,
  retryPendingCloudRestores,
  withoutRestoredDeletes,
} from "@/lib/notes/restoreRetry";
import type { RemoteNotesDataSource } from "@/lib/remote/remoteNotesDataSource";
import { useNotesStore } from "@/store/notesStore";
import { unexplainedMissingCloudIds } from "@/lib/notes/unexplainedMissingCloudIds";
import { useTombstoneStore } from "@/store/tombstoneStore";
import type { Note } from "@/types/note";
import { isCloudSyncEligible } from "@/types/note";
import {
  hydrateNotesWithAttachments,
  retryPendingAttachmentGc,
} from "@/lib/attachments/attachmentSyncService";

interface ApplyNoteResult {
  status?: string;
  revision?: number;
  idempotent?: boolean;
  error?: string;
  current?: {
    note_id?: string;
    revision?: number;
  };
}

/** [notes] with a flush's local rewrites applied, so a caller's copy matches what is on disk. */
function withFlushApplied(notes: Note[], flushed: PendingFlushResult): Note[] {
  const removed = new Set(flushed.removedIds);
  const byId = new Map(notes.filter((note) => !removed.has(note.id)).map((note) => [note.id, note]));
  for (const note of flushed.upserted) byId.set(note.id, note);
  return Array.from(byId.values());
}

function accountStillOwnsDelete(userId: string): boolean {
  const session = getActiveNotesSyncSession();
  return !session || (session.isActive() && session.ownerId === userId);
}

export const supabaseRemoteNotesDataSource: RemoteNotesDataSource = {
  subscribeToNotes(userId, onData, onError) {
    const session = beginNotesSyncSession(userId);
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
    let unsubscribeRealtime = () => {};

    const emit = () => {
      if (!session.isActive()) return;
      for (const note of useNotesStore.getState().notes) {
        if (useTombstoneStore.getState().isDeleted(note.id)) continue;
        const existing = notesById.get(note.id);
        // An unconfirmed local edit is newer than anything the cloud has for this device to show.
        if (!existing || hasPendingPush(userId, note.id) || shouldUploadOverRemote(note, existing)) {
          notesById.set(note.id, note);
        }
      }
      onData(Array.from(notesById.values()));
    };

    const loadBaseline = async () => {
      await ensureSupabaseAuthenticated();
      if (!session.isActive()) return;
      // Edits made while offline go up first, against the revisions they were made on, so the
      // snapshot below already contains them — or, on a conflict, both versions.
      await flushPendingPushes(userId, { isActive: () => session.isActive() });
      if (!session.isActive()) return;
      const snapshot = await fetchSnapshotNotes({ hydrateAttachments: false });
      if (!session.isActive()) return;
      const prior = await loadRevisionState(userId);
      const local = await listNotes(userId);
      const snapshotIds = new Set(snapshot.notes.map((note) => note.id));
      const tombstoneIds = new Set(Object.keys(snapshot.tombstones));
      const knownIds = [
        ...new Set([
          ...Object.keys(prior.noteRevisions),
          ...prior.knownCloudIds,
        ]),
      ];
      const unexplained = unexplainedMissingCloudIds(
        knownIds,
        snapshotIds,
        (id) => tombstoneIds.has(id),
      );
      if (snapshot.notes.length === 0 && unexplained.length > 0) {
        throw new Error(
          `Cloud returned no notes but ${unexplained.length} were expected — ` +
            `refusing to overwrite local copies. Check the connection or sign in again.`,
        );
      }
      const preserved = collectPreservedRestoredNotes(snapshotIds, [
        ...local,
        ...useNotesStore.getState().notes,
      ]);
      const knownIdSet = new Set(knownIds);
      const keepUnsynced = local.filter(
        (note) =>
          !snapshotIds.has(note.id) &&
          !tombstoneIds.has(note.id) &&
          !knownIdSet.has(note.id),
      );
      const remoteDeletes = withoutRestoredDeletes([
        ...Object.keys(snapshot.tombstones),
        ...(snapshot.notes.length > 0 ? unexplained : []),
      ]);
      if (snapshot.notes.length > 0) {
        for (const id of unexplained) {
          if (!useTombstoneStore.getState().isRestored(id)) {
            useTombstoneStore.getState().markDeleted(id);
          }
        }
      }
      await applyRemoteSnapshotAtomically({
        ownerId: userId,
        notes: [...snapshot.notes, ...preserved, ...keepUnsynced],
        deletedNoteIds: remoteDeletes,
        noteRevisions:
          snapshot.notes.length === 0
            ? Object.fromEntries(
                Object.entries(prior.noteRevisions).filter(
                  ([id]) => !tombstoneIds.has(id),
                ),
              )
            : snapshot.noteRevisions,
        lastRemoteRevision: Math.max(
          prior.lastRemoteRevision,
          snapshot.maxRevision,
        ),
        knownCloudIds: snapshot.notes.map((note) => note.id),
      });
      if (!session.isActive()) return;
      useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
      useTombstoneStore
        .getState()
        .acknowledgeRestoredLiveNotes(snapshot.notes.map((note) => note.id));
      await retryPendingCloudRestores(userId, [
        ...snapshot.notes,
        ...preserved,
        ...keepUnsynced,
        ...useNotesStore.getState().notes,
      ]);
      if (!session.isActive()) return;
      await retryPendingAttachmentGc();
      if (!session.isActive()) return;
      notesById = new Map(
        [...snapshot.notes, ...preserved, ...keepUnsynced].map((note) => [
          note.id,
          note,
        ]),
      );
      hasBaseline = true;
      try {
        const hydrated = await hydrateNotesWithAttachments(
          Array.from(notesById.values()),
        );
        if (session.isActive()) {
          notesById = new Map(hydrated.map((note) => [note.id, note]));
        }
      } catch {
        // Textual snapshot is already durable; attachment hydration retries on the next pull.
      }
      emit();
    };

    const pull = async () => {
      if (!hasBaseline) {
        await loadBaseline();
        return;
      }
      const changed = await pullIncrementalChanges(userId, notesById, {
        isActive: () => session.isActive(),
      });
      if (!session.isActive()) return;
      if (changed) emit();
    };

    void session.enqueue(async () => {
      try {
        await loadBaseline();
      } catch (error: unknown) {
        onError?.(toError(error, "Notes sync failed"));
      }
      if (!session.isActive()) return;
      unsubscribeRealtime = subscribeSupabaseNoteRealtime(
        userId,
        () => session.requestPull(pull, onError),
        () => session.requestPull(pull, onError),
      );
      if (hasBaseline) await pull();
    }, onError);

    return () => {
      session.invalidate();
      unsubscribeRealtime();
    };
  },

  async fetchAllNotes(userId) {
    await ensureSupabaseAuthenticated();
    const snapshot = await fetchSnapshotNotes();
    useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
    useTombstoneStore
      .getState()
      .acknowledgeRestoredLiveNotes(snapshot.notes.map((note) => note.id));
    await retryPendingCloudRestores(userId, [
      ...snapshot.notes,
      ...useNotesStore.getState().notes,
    ]);
    await retryPendingAttachmentGc();
    await saveRevisionState(userId, {
      noteRevisions: snapshot.noteRevisions,
      lastRemoteRevision: snapshot.maxRevision,
      knownCloudIds: snapshot.notes.map((note) => note.id),
    });
    return snapshot.notes;
  },

  async upsertNote(userId, note) {
    await ensureSupabaseAuthenticated();
    if (useTombstoneStore.getState().isDeleted(note.id)) {
      await this.deleteNote(userId, note.id);
      return;
    }
    if (note.id in (await listPendingPushes(userId))) {
      // A local edit: push it against the revision it was made on, not whatever is known now.
      await flushPendingPushes(userId);
      return;
    }
    const state = await loadRevisionState(userId);
    const baseRevision = getNoteBaseRevision(state, note.id);
    const updated = await applyNoteChange(userId, note, baseRevision);
    void updated;
  },

  async deleteNote(userId, noteId, baseRevision) {
    await ensureSupabaseAuthenticated();

    if (baseRevision == null) {
      // Path 2 remediation: strict delete intent bound to immutable revision.
      // Leave local suppression, but fail the remote live note delete.
      useTombstoneStore.getState().markDeleted(noteId);
      return;
    }

    if (!accountStillOwnsDelete(userId)) {
      throw new Error("Account changed during delete");
    }
    const { getSupabaseClient } = await import("@/lib/supabase/client");

    try {
      const { data, error } = await getSupabaseClient().rpc(
        "apply_note_delete",
        {
          p_note_id: noteId,
          p_base_revision: baseRevision,
        },
      );
      if (error) {
        throw error;
      }
      if (!accountStillOwnsDelete(userId)) {
        throw new Error("Account changed during delete");
      }
      const result = (data ?? {}) as ApplyNoteResult;

      if (result.idempotent) {
        await forgetNoteRevision(userId, noteId);
        useTombstoneStore.getState().markDeleted(noteId, baseRevision);
        return;
      }

      if (result.status === "conflict") {
        if (result.error === "note_not_found") {
          await forgetNoteRevision(userId, noteId);
          useTombstoneStore.getState().markDeleted(noteId, baseRevision);
          return;
        }
        throw new Error(`Delete conflict for note ${noteId}`);
      }

      // The note is gone, so its revision goes too. The pull cursor is left alone: the delete's
      // revision says nothing about changes other devices committed below it (F-04).
      await forgetNoteRevision(userId, noteId);
      useTombstoneStore.getState().markDeleted(noteId, baseRevision);
      return;
    } catch (error) {
      useTombstoneStore.getState().markDeleted(noteId, baseRevision);
      throw error;
    }
  },

  async uploadAllNotes(userId, notes) {
    await ensureSupabaseAuthenticated();
    const prior = await loadRevisionState(userId);
    const snapshot = await fetchSnapshotNotes();
    const snapshotIds = new Set(snapshot.notes.map((note) => note.id));
    const tombstoneIds = new Set(Object.keys(snapshot.tombstones));
    const knownIds = [
      ...new Set([...Object.keys(prior.noteRevisions), ...prior.knownCloudIds]),
    ];
    const unexplained = unexplainedMissingCloudIds(
      knownIds,
      snapshotIds,
      (id) => tombstoneIds.has(id),
    );
    // Same fail-open hazard Kotlin already closed: an empty snapshot must not look like
    // "local wins every id". upsertNote would then push the whole library over whatever the
    // cloud actually holds — including a newer copy the fetch just failed to return.
    if (snapshot.notes.length === 0 && unexplained.length > 0) {
      throw new Error(
        `Cloud returned no notes but ${unexplained.length} were expected — ` +
          `refusing to overwrite the cloud. Check the connection or sign in again.`,
      );
    }
    useTombstoneStore.getState().mergeFromCloud(snapshot.tombstones);
    useTombstoneStore
      .getState()
      .acknowledgeRestoredLiveNotes(snapshot.notes.map((note) => note.id));
    await retryPendingCloudRestores(userId, [...snapshot.notes, ...notes]);
    await saveRevisionState(userId, {
      noteRevisions: snapshot.noteRevisions,
      lastRemoteRevision: snapshot.maxRevision,
      knownCloudIds: snapshot.notes.map((note) => note.id),
    });

    const remoteById = new Map(snapshot.notes.map((note) => [note.id, note]));
    let uploaded = 0;
    for (const note of notes) {
      if (!isCloudSyncEligible(note)) continue;
      if (useTombstoneStore.getState().isDeleted(note.id)) continue;
      const remote = remoteById.get(note.id);
      if (
        remote &&
        remote.serverUpdatedAt != null &&
        note.serverUpdatedAt == null
      )
        continue;
      if (!shouldUploadOverRemote(note, remote)) continue;
      await this.upsertNote(userId, note);
      uploaded += 1;
    }
    return uploaded;
  },

  async syncNotesWithCloud(userId, callerNotes, previouslyKnownCloudIds) {
    await ensureSupabaseAuthenticated();
    // Unconfirmed local edits first, so the snapshot reflects them and the merge below never
    // decides between a local edit and the cloud by timestamps alone.
    const localNotes = withFlushApplied(callerNotes, await flushPendingPushes(userId));
    const isPending = (id: string) => hasPendingPush(userId, id);
    const {
      notes: remoteNotes,
      tombstones,
      noteRevisions,
      maxRevision,
    } = await fetchSnapshotNotes();
    useTombstoneStore.getState().mergeFromCloud(tombstones);
    useTombstoneStore
      .getState()
      .acknowledgeRestoredLiveNotes(remoteNotes.map((note) => note.id));
    await retryPendingCloudRestores(userId, [...remoteNotes, ...localNotes]);

    const remoteById = new Map(remoteNotes.map((note) => [note.id, note]));
    const cloudIds = new Set(remoteById.keys());
    const isDeleted = (id: string) =>
      useTombstoneStore.getState().isDeleted(id);

    // The same rule loadBaseline() applies, and for the same reason: refusing on the raw size of
    // the known-id set turned a user who deleted their last note on another device into a
    // permanently failing sync, because every one of those ids has a tombstone explaining it.
    // Ids with no tombstone at all still trip the guard, which is the case it exists for.
    const tombstonedRemotely = new Set(Object.keys(tombstones));
    const unexplainedMissing = unexplainedMissingCloudIds(
      previouslyKnownCloudIds,
      cloudIds,
      (id) => tombstonedRemotely.has(id) || isDeleted(id),
    );
    if (remoteNotes.length === 0 && unexplainedMissing.length > 0) {
      throw new Error(
        `Cloud returned no notes but ${unexplainedMissing.length} were expected — refusing to ` +
          `delete local copies. Check the connection or sign in again.`,
      );
    }

    let merged = await mergeRemoteNotes(localNotes, remoteNotes);
    // An edit still pending (the push failed again) stays as the user left it.
    const localById = new Map(localNotes.map((note) => [note.id, note]));
    merged = merged.map((note) => (isPending(note.id) ? (localById.get(note.id) ?? note) : note));
    merged = merged.filter((note) => !isDeleted(note.id));

    let changes = 0;
    const droppedLocalIds = new Set<string>();

    for (const localNote of localNotes) {
      if (isDeleted(localNote.id)) continue;
      // Pushed by the flush above, against its own base revision; never re-pushed here.
      if (isPending(localNote.id)) continue;

      if (cloudIds.has(localNote.id)) {
        if (!isCloudSyncEligible(localNote)) continue;
        const remote = remoteById.get(localNote.id);
        if (shouldUploadOverRemote(localNote, remote)) {
          const state = await loadRevisionState(userId);
          const baseRevision = getNoteBaseRevision(state, localNote.id);
          try {
            const updated = await applyNoteChange(
              userId,
              localNote,
              baseRevision,
            );
            merged = merged.map((note) =>
              note.id === localNote.id ? updated : note,
            );
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
        merged = merged.map((note) =>
          note.id === localNote.id ? updated : note,
        );
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
      knownCloudIds: remoteNotes.map((note) => note.id),
    });

    return {
      merged,
      remoteIds: remoteNotes.map((note) => note.id),
    };
  },
};
