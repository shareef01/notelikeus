import {
  deleteNote,
  subscribeToNotes,
  syncNotesWithCloud,
  upsertNote,
} from '@/lib/firestore/notesRepository';
import { fetchAllNotes } from '@/lib/remote/firebaseNotesFetch';
import type { RemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSource';

/** Firebase/Firestore implementation of [RemoteNotesDataSource]. */
export const firebaseRemoteNotesDataSource: RemoteNotesDataSource = {
  subscribeToNotes(userId, onData, onError) {
    return subscribeToNotes(userId, onData, onError);
  },

  async fetchAllNotes(userId) {
    return fetchAllNotes(userId);
  },

  upsertNote(userId, note) {
    return upsertNote(userId, note);
  },

  deleteNote(userId, noteId) {
    return deleteNote(userId, noteId);
  },

  syncNotesWithCloud(userId, localNotes, previouslyKnownCloudIds) {
    return syncNotesWithCloud(userId, localNotes, previouslyKnownCloudIds);
  },
};

let activeDataSource: RemoteNotesDataSource = firebaseRemoteNotesDataSource;

export function getRemoteNotesDataSource(): RemoteNotesDataSource {
  return activeDataSource;
}

/** Test-only hook for future Supabase adapter parity tests. */
export function setRemoteNotesDataSourceForTests(source: RemoteNotesDataSource): void {
  activeDataSource = source;
}

export function resetRemoteNotesDataSourceForTests(): void {
  activeDataSource = firebaseRemoteNotesDataSource;
}
