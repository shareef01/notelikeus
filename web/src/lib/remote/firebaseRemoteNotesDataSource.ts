import {
  deleteNote,
  subscribeToNotes,
  syncNotesWithCloud,
  uploadAllNotes,
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

  uploadAllNotes(userId, notes) {
    return uploadAllNotes(userId, notes);
  },

  syncNotesWithCloud(userId, localNotes, previouslyKnownCloudIds) {
    return syncNotesWithCloud(userId, localNotes, previouslyKnownCloudIds);
  },
};

