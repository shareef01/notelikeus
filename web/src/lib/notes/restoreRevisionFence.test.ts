import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const rpc = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  isSupabaseBackendEnabled: () => true,
  loadSupabaseUrl: () => 'http://localhost:54321',
  loadSupabaseAnonKey: () => 'anon',
  getSupabaseClient: () => ({
    rpc,
    auth: {
      getSession: () => Promise.resolve({ data: { session: { user: { id: 'u' } } }, error: null }),
    },
  }),
}));

import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { restorePermanentlyDeletedNote } from '@/lib/notes/noteActions';
import { retryPendingCloudRestores } from '@/lib/notes/restoreRetry';
import { RestoreRefusedError, restoreCloudNote } from '@/lib/notes/tombstones';
import { loadRevisionState, saveRevisionState } from '@/lib/supabase/revisionStore';
import { useAuthStore } from '@/store/authStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = '44444444-4444-4444-8444-444444444444';

function note(id = 'n1'): Note {
  return createEmptyNote({ id, localId: 1, title: 'Restored', timestamp: 10 });
}

/** What the fenced `restore_note` answers when the live row moved on. */
function conflict(currentRevision: number) {
  return {
    data: {
      status: 'conflict',
      error: 'note_changed',
      current: { note_id: 'n1', revision: currentRevision },
    },
    error: null,
  };
}

/**
 * F-14: `restore_note` is fenced on the revision the caller presents, so an offline device's undo
 * cannot put its older copy on top of an edit made elsewhere. These cover the client half of that:
 * the revision it presents, what it does with a refusal, and that a refusal is terminal rather than
 * retried forever.
 */
describe('restore revision fence (F-14)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    useAuthStore.getState().setUser({ uid: USER, email: null, displayName: null });
    await resetNotesDatabaseForTests();
    rpc.mockResolvedValue({ data: { status: 'applied', revision: 99 }, error: null });
  });

  it('presents the revision this browser holds instead of a hard-coded null', async () => {
    await saveRevisionState(USER, { noteRevisions: { n1: 42 } });

    await restoreCloudNote(USER, note());

    expect(rpc).toHaveBeenCalledWith(
      'restore_note',
      expect.objectContaining({ p_base_revision: 42 }),
    );
  });

  it('presents null only when it genuinely knows nothing', async () => {
    await restoreCloudNote(USER, note());

    expect(rpc).toHaveBeenCalledWith(
      'restore_note',
      expect.objectContaining({ p_base_revision: null }),
    );
  });

  it('turns a refusal into a typed error and learns the revision the server actually has', async () => {
    await saveRevisionState(USER, { noteRevisions: { n1: 42 } });
    rpc.mockResolvedValueOnce(conflict(77));

    await expect(restoreCloudNote(USER, note())).rejects.toBeInstanceOf(RestoreRefusedError);

    expect((await loadRevisionState(USER)).noteRevisions['n1']).toBe(77);
  });

  it('explains itself rather than reporting a bare failure', async () => {
    rpc.mockResolvedValueOnce(conflict(77));

    await expect(restoreCloudNote(USER, note())).rejects.toThrow(/changed on another device/);
  });

  it('stops retrying after a refusal, because the same request can only be refused again', async () => {
    useTombstoneStore.getState().markRestored('n1');
    rpc.mockResolvedValue(conflict(77));

    await retryPendingCloudRestores(USER, [note()]);

    expect(rpc).toHaveBeenCalled();
    expect(useTombstoneStore.getState().isRestored('n1')).toBe(false);
  });

  it('keeps retrying after a transient failure, which a refusal is not', async () => {
    useTombstoneStore.getState().markRestored('n1');
    rpc.mockRejectedValue(new Error('offline'));

    await retryPendingCloudRestores(USER, [note()]);

    expect(useTombstoneStore.getState().isRestored('n1')).toBe(true);
  });

  it('an undo that is refused tells the user and leaves no marker behind', async () => {
    rpc.mockResolvedValueOnce(conflict(77));
    const target = note();
    useTombstoneStore.getState().markDeleted(target.id);

    await expect(restorePermanentlyDeletedNote(target)).rejects.toBeInstanceOf(RestoreRefusedError);

    // The marker would otherwise make every later reconcile retry a call that cannot succeed.
    expect(useTombstoneStore.getState().isRestored(target.id)).toBe(false);
    // ...and the local undo itself stands: the user asked for the note to be back.
    expect(useTombstoneStore.getState().isDeleted(target.id)).toBe(false);
  });
});
