import type { Note } from '@/types/note';

/** Server revision moved past the local base; the remote row is the RPC winner. */
export class RevisionConflictError extends Error {
  readonly kind = 'revision_conflict' as const;
  readonly noteId: string;
  readonly remote: Note | undefined;

  constructor(noteId: string, remote?: Note) {
    super(`Revision conflict for note ${noteId}`);
    this.name = 'RevisionConflictError';
    this.noteId = noteId;
    this.remote = remote;
  }
}

/** Cloud already tombstoned this note; it must not be resurrected. */
export class RemoteNoteDeletedError extends Error {
  readonly kind = 'remote_deleted' as const;
  readonly noteId: string;

  constructor(noteId: string) {
    super(`Note ${noteId} was deleted in the cloud`);
    this.name = 'RemoteNoteDeletedError';
    this.noteId = noteId;
  }
}

/** Timeout, 5xx, auth failure, malformed RPC, or network exception. Always retryable. */
export class RemoteTransportError extends Error {
  readonly kind = 'transport' as const;

  constructor(message: string, options?: { cause?: unknown }) {
    super(message);
    this.name = 'RemoteTransportError';
    if (options?.cause !== undefined) {
      (this as Error & { cause?: unknown }).cause = options.cause;
    }
  }
}

/** A populated local library plus an unexplained empty cloud snapshot. Fail closed. */
export class SuspiciousEmptySnapshotError extends Error {
  readonly kind = 'suspicious_empty_snapshot' as const;
  readonly expectedCount: number;

  constructor(expectedCount: number) {
    super(
      `Cloud returned no notes but ${expectedCount} were expected — refusing to delete local copies. Check the connection or sign in again.`,
    );
    this.name = 'SuspiciousEmptySnapshotError';
    this.expectedCount = expectedCount;
  }
}

export function isRevisionConflictError(error: unknown): error is RevisionConflictError {
  return error instanceof RevisionConflictError ||
    (error instanceof Error && (error as { kind?: string }).kind === 'revision_conflict');
}

export function isRemoteNoteDeletedError(error: unknown): error is RemoteNoteDeletedError {
  return error instanceof RemoteNoteDeletedError ||
    (error instanceof Error && (error as { kind?: string }).kind === 'remote_deleted');
}

export function isRemoteTransportError(error: unknown): error is RemoteTransportError {
  return error instanceof RemoteTransportError;
}

export function isSuspiciousEmptySnapshotError(
  error: unknown,
): error is SuspiciousEmptySnapshotError {
  return error instanceof SuspiciousEmptySnapshotError;
}

/** Strip accidental note bodies from log/toast strings. */
export function sanitizeSyncErrorMessage(error: unknown): string {
  if (error instanceof RevisionConflictError) return error.message;
  if (error instanceof RemoteNoteDeletedError) return error.message;
  if (error instanceof SuspiciousEmptySnapshotError) return error.message;
  if (error instanceof Error) {
    return error.message.replace(/remote title "[^"]*"/gi, 'remote note');
  }
  return 'Sync failed';
}
