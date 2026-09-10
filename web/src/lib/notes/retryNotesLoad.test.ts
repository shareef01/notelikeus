import { beforeEach, describe, expect, it, vi } from 'vitest';

const { localMocks, syncMocks } = vi.hoisted(() => ({
  localMocks: {
    loadLocalNotesIntoStore: vi.fn(),
    hydrateIndexedDbFromRemote: vi.fn(),
  },
  syncMocks: {
    startNotesRealtimeSync: vi.fn(),
    stopNotesRealtimeSync: vi.fn(),
  },
}));

vi.mock('@/lib/local/hydrateFromRemote', () => localMocks);
vi.mock('@/lib/notes/notesSyncService', () => syncMocks);

import { retryNotesLoad } from '@/lib/notes/retryNotesLoad';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote, type Note } from '@/types/note';

function note(id: string): Note {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}`, timestamp: 1 });
}

/**
 * The Retry button used to reload the page. Reloading throws away in-memory state to re-run work
 * the app can re-run in place, and it cannot distinguish the two failures that reach it: a local
 * read that failed, and a cloud round trip that failed over notes that are already loaded.
 */
describe('retryNotesLoad', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useAuthStore.getState().enterGuestMode();
    localMocks.loadLocalNotesIntoStore.mockResolvedValue([]);
    localMocks.hydrateIndexedDbFromRemote.mockResolvedValue(undefined);
  });

  it('reloads the local mirror without reloading the page', async () => {
    localMocks.loadLocalNotesIntoStore.mockImplementation(async () => {
      useNotesStore.getState().setNotes([note('1')]);
      return [];
    });
    useNotesStore.getState().setError('Could not load notes stored on this device.');

    await retryNotesLoad();

    expect(localMocks.loadLocalNotesIntoStore).toHaveBeenCalledTimes(1);
    expect(useNotesStore.getState().error).toBeNull();
    expect(useNotesStore.getState().status).toBe('ready');
    expect(useNotesStore.getState().notes).toHaveLength(1);
  });

  it('reports a failed local read as the blocking error', async () => {
    localMocks.loadLocalNotesIntoStore.mockRejectedValue(
      new DOMException('The operation failed', 'InvalidStateError'),
    );

    await retryNotesLoad();

    expect(useNotesStore.getState().status).toBe('error');
    expect(useNotesStore.getState().error).toBe('The operation failed');
    expect(useNotesStore.getState().syncError).toBeNull();
  });

  /** PostgREST and Supabase Auth throw plain objects. `String(error)` on one reads `[object Object]`. */
  it('never renders an object-shaped failure as [object Object]', async () => {
    localMocks.loadLocalNotesIntoStore.mockRejectedValue({
      message: 'JWT expired',
      code: 'PGRST301',
    });

    await retryNotesLoad();

    expect(useNotesStore.getState().error).toBe('JWT expired');
    expect(useNotesStore.getState().error).not.toContain('[object Object]');
  });

  it('falls back to a generic message for an unreadable failure shape', async () => {
    localMocks.loadLocalNotesIntoStore.mockRejectedValue({ nothing: 'useful' });

    await retryNotesLoad();

    expect(useNotesStore.getState().error).toBe('Could not load notes. Please try again.');
    expect(useNotesStore.getState().error).not.toContain('[object Object]');
  });

  it('keeps local notes visible when only the cloud step fails', async () => {
    useAuthStore.getState().setUser({ uid: 'u1' } as never);
    localMocks.loadLocalNotesIntoStore.mockImplementation(async () => {
      useNotesStore.getState().setNotes([note('1'), note('2')]);
      return [];
    });
    localMocks.hydrateIndexedDbFromRemote.mockRejectedValue({ message: 'Failed to fetch' });

    await retryNotesLoad();

    const state = useNotesStore.getState();
    expect(state.error).toBeNull();
    expect(state.status).toBe('ready');
    expect(state.syncError).toBe('Failed to fetch');
    expect(state.notes).toHaveLength(2);
    // Realtime is still attached: it is what recovers on its own when the connection returns.
    expect(syncMocks.startNotesRealtimeSync).toHaveBeenCalledWith('u1');
  });

  it('clears a stale sync banner once the cloud step succeeds', async () => {
    useAuthStore.getState().setUser({ uid: 'u1' } as never);
    useNotesStore.getState().setSyncError('Could not sync notes');

    await retryNotesLoad();

    expect(useNotesStore.getState().syncError).toBeNull();
    expect(useNotesStore.getState().status).toBe('ready');
  });

  it('does not attempt a cloud round trip for a guest', async () => {
    await retryNotesLoad();

    expect(localMocks.hydrateIndexedDbFromRemote).not.toHaveBeenCalled();
    expect(useNotesStore.getState().status).toBe('ready');
  });
});
