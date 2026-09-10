import { toError } from '@/lib/errors/formatUnknownError';

/**
 * Narrow coordinator for one signed-in web sync session.
 *
 * Invariants:
 * - Old owner sessions cannot emit.
 * - Only one pull mutates a session at a time.
 * - Cursor never decreases.
 * - Cursor cannot advance beyond durable local state.
 * - Tombstone wins unless an explicit server restore succeeds.
 * - Blob deletion happens only after authoritative note deletion.
 * - Restore marker clears only after live remote note confirmation.
 */

export class NotesSyncSession {
  readonly ownerId: string;
  readonly generation: number;
  private active = true;
  private queue: Promise<void> = Promise.resolve();
  private pullQueued = false;

  constructor(ownerId: string, generation: number) {
    this.ownerId = ownerId;
    this.generation = generation;
  }

  isActive(): boolean {
    return this.active;
  }

  invalidate(): void {
    this.active = false;
  }

  enqueue(task: () => Promise<void>, onError?: (error: Error) => void): Promise<void> {
    this.queue = this.queue
      .then(() => (this.active ? task() : undefined))
      .catch((error: unknown) => {
        onError?.(toError(error, 'Notes sync failed'));
      });
    return this.queue;
  }

  requestPull(runPull: () => Promise<void>, onError?: (error: Error) => void): void {
    if (!this.active) return;
    if (this.pullQueued) return;
    this.pullQueued = true;
    void this.enqueue(async () => {
      this.pullQueued = false;
      if (!this.active) return;
      await runPull();
    }, onError);
  }
}

let nextGeneration = 1;
let currentSession: NotesSyncSession | null = null;

export function beginNotesSyncSession(ownerId: string): NotesSyncSession {
  currentSession?.invalidate();
  currentSession = new NotesSyncSession(ownerId, nextGeneration++);
  return currentSession;
}

export function getActiveNotesSyncSession(): NotesSyncSession | null {
  return currentSession;
}

