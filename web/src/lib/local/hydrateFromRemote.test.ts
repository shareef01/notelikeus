import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { remoteMocks } = vi.hoisted(() => ({
  remoteMocks: {
    fetchAllNotes: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => remoteMocks,
}));

import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putNote, getOwnerMeta } from '@/lib/local/notesLocalRepository';
import {
  hydrateIndexedDbFromRemote,
  loadLocalNotesIntoStore,
} from '@/lib/local/hydrateFromRemote';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = 'user-tombstone-test';

function makeNote(id: string, title = `Note ${id}`): Note {
  return createEmptyNote({ id, localId: Number(id) || 1, title, timestamp: 1 });
}

describe('hydrateFromRemote & loadLocalNotesIntoStore', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
  });

  it('suppresses tombstoned stale IndexedDB notes on startup and cleans them from storage', async () => {
    const liveNote = makeNote('live-1', 'Surviving note');
    const tombstonedNote = makeNote('dead-1', 'Should be deleted');

    // Both notes exist in IndexedDB (e.g. from an earlier session)
    await putNote(USER, liveNote);
    await putNote(USER, tombstonedNote);

    // Tombstone store marks dead-1 deleted
    useTombstoneStore.getState().markDeleted('dead-1');

    // Startup hydration runs
    const result = await loadLocalNotesIntoStore(USER);

    // dead-1 MUST NOT appear in the returned list or in the in-memory store
    expect(result.map((n) => n.id)).toEqual(['live-1']);
    expect(useNotesStore.getState().notes.map((n) => n.id)).toEqual(['live-1']);

    // Stale IndexedDB row must also be cleaned so the durable mirror converges
    await vi.waitFor(async () => {
      const persisted = await listNotes(USER);
      expect(persisted.map((n) => n.id)).toEqual(['live-1']);
    });
  });

  it('keeps remote-deleted / tombstoned notes absent across simulated app restart', async () => {
    const noteA = makeNote('note-a');
    await putNote(USER, noteA);

    // Tombstone note A
    useTombstoneStore.getState().markDeleted('note-a');

    // Simulate app restart / memory wipe
    useNotesStore.getState().reset();
    expect(useNotesStore.getState().notes).toHaveLength(0);

    // Rehydrate on fresh startup
    await loadLocalNotesIntoStore(USER);

    // note-a must remain completely absent
    expect(useNotesStore.getState().notes.some((n) => n.id === 'note-a')).toBe(false);
  });

  it('populates IndexedDB from remote snapshot and marks owner hydrated', async () => {
    const cloudNote = makeNote('cloud-1', 'Remote cloud note');
    remoteMocks.fetchAllNotes.mockResolvedValueOnce([cloudNote]);

    await hydrateIndexedDbFromRemote(USER);

    expect(useNotesStore.getState().notes.map((n) => n.id)).toEqual(['cloud-1']);
    const meta = await getOwnerMeta(USER);
    expect(meta?.remoteHydrated).toBe(true);

    const persisted = await listNotes(USER);
    expect(persisted.map((n) => n.id)).toEqual(['cloud-1']);
  });
});
