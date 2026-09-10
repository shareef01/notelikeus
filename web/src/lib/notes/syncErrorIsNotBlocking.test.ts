import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { remoteMocks } = vi.hoisted(() => ({
  remoteMocks: {
    fetchAllNotes: vi.fn(),
    subscribeToNotes: vi.fn(),
    syncNotesWithCloud: vi.fn(),
    upsertNote: vi.fn(),
    deleteNote: vi.fn(),
    uploadAllNotes: vi.fn(),
  },
}));

vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => remoteMocks,
}));

import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { putNote } from '@/lib/local/notesLocalRepository';
import {
  startNotesRealtimeSync,
  stopNotesRealtimeSync,
  waitForRealtimeMirrorWriteForTests,
} from '@/lib/notes/notesSyncService';
import { resolveNotesViewState } from '@/screens/main/notesViewState';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = '11111111-1111-4111-8111-111111111111';

function note(id: string): Note {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}`, timestamp: 1 });
}

type Handlers = {
  onData: (notes: Note[]) => void;
  onError: (error: unknown) => void;
};

function captureSubscription(): Handlers {
  const handlers = {} as Handlers;
  remoteMocks.subscribeToNotes.mockImplementation(
    (_userId: string, onData: Handlers['onData'], onError: Handlers['onError']) => {
      handlers.onData = onData;
      handlers.onError = onError;
      return () => {};
    },
  );
  return handlers;
}

/**
 * A realtime or reconcile failure means the cloud is behind, not that the library is unreadable.
 * Routing it to the blocking channel took the screen away from notes sitting in IndexedDB — and,
 * because the same channel drives the load status, it also replaced the empty state of a genuinely
 * empty account with an error.
 */
describe('cloud sync failures are non-blocking', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    await waitForRealtimeMirrorWriteForTests();
    stopNotesRealtimeSync();
    useNotesStore.getState().reset();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
  });

  it('leaves local notes visible and the status ready when the subscription errors', async () => {
    await putNote(USER, note('1'));
    useNotesStore.getState().setNotes([note('1'), note('2')]);
    const handlers = captureSubscription();

    startNotesRealtimeSync(USER);
    handlers.onError(new Error('Realtime channel error'));

    const state = useNotesStore.getState();
    expect(state.error).toBeNull();
    expect(state.status).toBe('ready');
    expect(state.syncError).toBe('Realtime channel error');
    expect(state.notes).toHaveLength(2);

    expect(
      resolveNotesViewState({
        isLoading: state.status === 'loading',
        error: state.error,
        syncError: state.syncError,
        notesCount: state.notes.length,
        filteredCount: state.notes.length,
      }),
    ).toEqual({ kind: 'content', syncWarning: 'Realtime channel error' });
  });

  /** PostgREST throws plain objects; `new Error(String(error))` renders them as `[object Object]`. */
  it('never puts [object Object] in front of the user', () => {
    const handlers = captureSubscription();
    startNotesRealtimeSync(USER);

    handlers.onError({ message: 'JWT expired', code: 'PGRST301' });
    expect(useNotesStore.getState().syncError).toBe('JWT expired');

    handlers.onError({ unexpected: { nested: true } });
    expect(useNotesStore.getState().syncError).toBe('Could not sync notes. Please try again.');
    expect(useNotesStore.getState().syncError).not.toContain('[object Object]');
  });

  it('clears the sync banner when a snapshot arrives', async () => {
    const handlers = captureSubscription();
    startNotesRealtimeSync(USER);
    handlers.onError(new Error('Realtime channel error'));
    expect(useNotesStore.getState().syncError).toBe('Realtime channel error');

    handlers.onData([note('1')]);
    await waitForRealtimeMirrorWriteForTests();

    expect(useNotesStore.getState().syncError).toBeNull();
    expect(useNotesStore.getState().notes.map((n) => n.id)).toEqual(['1']);
  });

  /**
   * A brand-new account with nothing in it and a failing sync must still read as "no notes yet",
   * not as a broken app — the error-vs-empty exclusivity rule, seen from the store.
   */
  it('shows the empty state, not an error screen, for an empty account whose sync fails', () => {
    const handlers = captureSubscription();
    startNotesRealtimeSync(USER);

    handlers.onError(new Error('Failed to fetch'));

    const state = useNotesStore.getState();
    expect(
      resolveNotesViewState({
        isLoading: state.status === 'loading',
        error: state.error,
        syncError: state.syncError,
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({ kind: 'empty', syncWarning: 'Failed to fetch' });
  });
});
