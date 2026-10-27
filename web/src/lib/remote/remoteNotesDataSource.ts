import type { Note } from '@/types/note';

export interface RemoteNotesSnapshot {
  notes: Note[];
  noteIds: string[];
}

/**
 * F-05: the note was suppressed locally, but the cloud row could not be deleted because this
 * browser never learned its revision.
 *
 * Raised instead of returning success, which is what it used to do. The cloud copy is live on every
 * other device until a later snapshot shows it unchanged, at which point the delete is finished
 * against the revision that snapshot observed (`notesSyncService.partitionTombstoned`). Adopting
 * whatever the failed delete answered with instead is what DEL-6 forbids.
 *
 * Lives here rather than in the Supabase source so callers can recognise it without loading
 * supabase-js.
 */
export class CloudDeleteUnconfirmedError extends Error {
  readonly noteId: string;

  constructor(noteId: string) {
    super(
      `Couldn’t delete note ${noteId} from the cloud: this browser no longer knows its revision. ` +
        'It is hidden here, but still on your other devices until the next sync.',
    );
    this.name = 'CloudDeleteUnconfirmedError';
    this.noteId = noteId;
  }
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

  deleteNote(userId: string, noteId: string, baseRevision?: number): Promise<void>;

  /** Backup import / bulk recovery. Returns how many notes were sent to the cloud. */
  uploadAllNotes(userId: string, notes: Note[]): Promise<number>;

  syncNotesWithCloud(
    userId: string,
    localNotes: Note[],
    previouslyKnownCloudIds: Set<string>,
  ): Promise<{ merged: Note[]; remoteIds: string[] }>;

  /**
   * Pushes the local edits the cloud has not confirmed, and nothing else: no snapshot. For the
   * moments a full sync is throttled but an edit made offline should not have to wait.
   */
  pushPendingEdits?(userId: string): Promise<void>;
}
