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

import { NOTES_DB_NAME } from '@/lib/local/constants';
import { hydrateIndexedDbFromRemote } from '@/lib/local/hydrateFromRemote';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putNote } from '@/lib/local/notesLocalRepository';
import {
  startNotesRealtimeSync,
  stopNotesRealtimeSync,
} from '@/lib/notes/notesSyncService';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = '11111111-1111-4111-8111-111111111111';

function note(id: string): Note {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}`, timestamp: 1 });
}

/**
 * A first signed-in session can land local notes in the owner namespace before anything
 * has been uploaded, so the very first `fetch_full_snapshot` legitimately answers with zero notes
 * while IndexedDB holds the user's whole library. The same shape occurs when the wrong account is
 * signed in, or when a backend/RLS problem answers successfully but empty.
 *
 * `syncNotesWithCloud` already refuses to reconcile that case, but it is keyed on
 * `previouslyKnownCloudIds`, which is empty for an account that has never synced — so it does not
 * cover the migration. An empty snapshot must not replace a populated local library: IndexedDB is
 * the durable copy, and blanking the in-memory store also empties the set that the upload path
 * reads, so the notes would never be pushed.
 */
describe('empty remote snapshot vs populated local library', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    stopNotesRealtimeSync();
    useNotesStore.getState().reset();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    remoteMocks.subscribeToNotes.mockImplementation(() => () => {});
  });

  it('keeps migrated local notes when the first cloud snapshot is empty', async () => {
    for (const id of ['1', '2', '3']) await putNote(USER, note(id));
    remoteMocks.fetchAllNotes.mockResolvedValue([]);

    await hydrateIndexedDbFromRemote(USER);

    expect(await listNotes(USER)).toHaveLength(3);
    expect(useNotesStore.getState().notes).toHaveLength(3);
  });

  it('keeps unsynced local notes when the first cloud snapshot already has other notes', async () => {
    await putNote(USER, note('7'));
    remoteMocks.fetchAllNotes.mockResolvedValue([note('1')]);

    await hydrateIndexedDbFromRemote(USER);

    expect(useNotesStore.getState().notes.map((n) => n.id).sort()).toEqual(['1', '7']);
    expect((await listNotes(USER)).map((n) => n.id).sort()).toEqual(['1', '7']);
  });

  it('still hydrates from the cloud when the local namespace is empty', async () => {
    remoteMocks.fetchAllNotes.mockResolvedValue([note('9')]);

    await hydrateIndexedDbFromRemote(USER);

    expect(useNotesStore.getState().notes.map((n) => n.id)).toEqual(['9']);
    expect(await listNotes(USER)).toHaveLength(1);
  });

  it('does not blank a populated store when a realtime snapshot arrives empty', () => {
    useNotesStore.getState().setNotes([note('1'), note('2')]);
    let emit: ((notes: Note[]) => void) | undefined;
    remoteMocks.subscribeToNotes.mockImplementation((_uid: string, onData: (n: Note[]) => void) => {
      emit = onData;
      return () => {};
    });

    startNotesRealtimeSync(USER);
    emit?.([]);

    expect(useNotesStore.getState().notes).toHaveLength(2);
  });

  it('still applies a genuinely empty snapshot when nothing is held locally', () => {
    useNotesStore.getState().setNotes([]);
    let emit: ((notes: Note[]) => void) | undefined;
    remoteMocks.subscribeToNotes.mockImplementation((_uid: string, onData: (n: Note[]) => void) => {
      emit = onData;
      return () => {};
    });

    startNotesRealtimeSync(USER);
    emit?.([]);

    expect(useNotesStore.getState().notes).toEqual([]);
    expect(useNotesStore.getState().status).toBe('ready');
  });

  it('applies a non-empty snapshot normally', () => {
    useNotesStore.getState().setNotes([note('1')]);
    let emit: ((notes: Note[]) => void) | undefined;
    remoteMocks.subscribeToNotes.mockImplementation((_uid: string, onData: (n: Note[]) => void) => {
      emit = onData;
      return () => {};
    });

    startNotesRealtimeSync(USER);
    emit?.([note('1'), note('5')]);

    expect(useNotesStore.getState().notes.map((n) => n.id).sort()).toEqual(['1', '5']);
  });

  it('does not overwrite a newer local edit with a stale subscribe emit', async () => {
    const local = createEmptyNote({
      id: '1',
      localId: 1,
      title: 'typed locally',
      timestamp: 100,
    });
    const stale = createEmptyNote({
      id: '1',
      localId: 1,
      title: 'stale snapshot',
      timestamp: 1,
    });
    await putNote(USER, local);
    useNotesStore.getState().setNotes([local]);
    let emit: ((notes: Note[]) => void) | undefined;
    remoteMocks.subscribeToNotes.mockImplementation((_uid: string, onData: (n: Note[]) => void) => {
      emit = onData;
      return () => {};
    });

    startNotesRealtimeSync(USER);
    emit?.([stale]);
    await vi.waitFor(async () => {
      expect(useNotesStore.getState().notes[0]?.title).toBe('typed locally');
      expect((await listNotes(USER))[0]?.title).toBe('typed locally');
    });
  });

  it('still applies a newer remote copy over a stale local one', () => {
    const local = createEmptyNote({
      id: '1',
      localId: 1,
      title: 'stale local',
      timestamp: 10,
      serverUpdatedAt: 10,
    });
    const remote = createEmptyNote({
      id: '1',
      localId: 1,
      title: 'newer remote',
      timestamp: 20,
      serverUpdatedAt: 30,
    });
    useNotesStore.getState().setNotes([local]);
    let emit: ((notes: Note[]) => void) | undefined;
    remoteMocks.subscribeToNotes.mockImplementation((_uid: string, onData: (n: Note[]) => void) => {
      emit = onData;
      return () => {};
    });

    startNotesRealtimeSync(USER);
    emit?.([remote]);

    expect(useNotesStore.getState().notes[0]?.title).toBe('newer remote');
  });

  it('keeps a local-only note that a later emit never included', async () => {
    const localOnly = note('7');
    await putNote(USER, localOnly);
    useNotesStore.getState().setNotes([localOnly]);
    let emit: ((notes: Note[]) => void) | undefined;
    remoteMocks.subscribeToNotes.mockImplementation((_uid: string, onData: (n: Note[]) => void) => {
      emit = onData;
      return () => {};
    });

    startNotesRealtimeSync(USER);
    emit?.([note('1')]);
    await vi.waitFor(() => {
      expect(useNotesStore.getState().notes.map((n) => n.id).sort()).toEqual(['1', '7']);
    });
  });

  it('lets a snapshot empty by deletion through once every note is tombstoned', () => {
    useNotesStore.getState().setNotes([note('1')]);
    useTombstoneStore.getState().markDeleted('1');
    let emit: ((notes: Note[]) => void) | undefined;
    remoteMocks.subscribeToNotes.mockImplementation((_uid: string, onData: (n: Note[]) => void) => {
      emit = onData;
      return () => {};
    });

    startNotesRealtimeSync(USER);
    emit?.([]);

    expect(useNotesStore.getState().notes).toEqual([]);
  });
});
