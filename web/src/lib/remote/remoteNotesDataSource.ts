import type { Note } from '@/types/note';

export interface RemoteNotesSnapshot {
  notes: Note[];
  noteIds: string[];
}

/**
 * Platform-agnostic remote note transport for the web sync layer.
 * The production implementation is Supabase RPC + Realtime.
 */
export interface RemoteNotesDataSource {
  subscribeToNotes(
    userId: string,
    onData: (notes: Note[]) => void,
    onError?: (error: Error) => void,
  ): () => void;

  fetchAllNotes(userId: string): Promise<Note[]>;

  upsertNote(userId: string, note: Note): Promise<void>;

  deleteNote(userId: string, noteId: string): Promise<void>;

  /** Backup import / bulk recovery. Returns how many notes were sent to the cloud. */
  uploadAllNotes(userId: string, notes: Note[]): Promise<number>;

  syncNotesWithCloud(
    userId: string,
    localNotes: Note[],
    previouslyKnownCloudIds: Set<string>,
  ): Promise<{ merged: Note[]; remoteIds: string[] }>;
}
