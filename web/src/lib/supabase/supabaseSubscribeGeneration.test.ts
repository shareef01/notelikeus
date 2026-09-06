import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const fetchSnapshotNotes = vi.fn();
const pullIncrementalChanges = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc: vi.fn() }),
  isSupabaseBackendEnabled: () => true,
}));
vi.mock('@/lib/supabase/supabaseSyncEngine', () => ({
  ensureSupabaseAuthenticated: vi.fn().mockResolvedValue(undefined),
  fetchSnapshotNotes: (...args: unknown[]) => fetchSnapshotNotes(...args),
  pullIncrementalChanges: (...args: unknown[]) => pullIncrementalChanges(...args),
  applyNoteChange: vi.fn(),
}));
vi.mock('@/lib/supabase/supabaseRealtimeSync', () => ({
  subscribeSupabaseNoteRealtime: vi.fn(() => () => {}),
}));

import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putNote } from '@/lib/local/notesLocalRepository';
import { loadRevisionState, saveRevisionState } from '@/lib/supabase/revisionStore';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

const USER_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const USER_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

function note(id: string, title: string) {
  return createEmptyNote({ id, localId: Number(id), title });
}

describe('subscription generation / account switch', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    pullIncrementalChanges.mockResolvedValue(false);
  });

  it('does not let a stale snapshot mutate the next account', async () => {
    let releaseA: (() => void) | undefined;
    const aGate = new Promise<void>((resolve) => {
      releaseA = resolve;
    });
    fetchSnapshotNotes.mockImplementation(async () => {
      if (fetchSnapshotNotes.mock.calls.length === 1) {
        await aGate;
        return {
          notes: [note('1', 'A')],
          tombstones: { goneA: 9 },
          noteRevisions: { '1': 10 },
          maxRevision: 10,
        };
      }
      return {
        notes: [note('2', 'B')],
        tombstones: {},
        noteRevisions: { '2': 20 },
        maxRevision: 20,
      };
    });

    const emittedA: string[][] = [];
    const emittedB: string[][] = [];
    const stopA = supabaseRemoteNotesDataSource.subscribeToNotes(USER_A, (notes) => {
      emittedA.push(notes.map((item) => item.id));
    });
    await vi.waitFor(() => expect(fetchSnapshotNotes).toHaveBeenCalled());
    stopA();
    const stopB = supabaseRemoteNotesDataSource.subscribeToNotes(USER_B, (notes) => {
      emittedB.push(notes.map((item) => item.id));
    });

    await vi.waitFor(() => expect(emittedB.length).toBeGreaterThan(0));
    releaseA?.();
    await new Promise((resolve) => setTimeout(resolve, 30));
    stopB();

    expect(emittedA).toEqual([]);
    expect(emittedB.at(-1)).toEqual(['2']);
    expect(useTombstoneStore.getState().isDeleted('goneA')).toBe(false);
    expect((await loadRevisionState(USER_B)).lastRemoteRevision).toBe(20);
    expect((await listNotes(USER_B)).map((item) => item.id)).toEqual(['2']);
    expect(await listNotes(USER_A)).toEqual([]);
  });

  it('ignores an incremental pull that finishes after logout', async () => {
    fetchSnapshotNotes.mockResolvedValue({
      notes: [note('1', 'A')],
      tombstones: {},
      noteRevisions: { '1': 5 },
      maxRevision: 5,
    });
    let releasePull: (() => void) | undefined;
    pullIncrementalChanges.mockImplementation(async (_userId: string, notesById: Map<string, unknown>) => {
      await new Promise<void>((resolve) => {
        releasePull = resolve;
      });
      notesById.set('stale', note('9', 'stale'));
      return true;
    });

    const emitted: string[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(USER_A, (notes) => {
      emitted.push(notes.map((item) => item.id));
    });
    await vi.waitFor(() => expect(emitted.length).toBeGreaterThan(0));
    stop();
    releasePull?.();
    await new Promise((resolve) => setTimeout(resolve, 30));

    expect(emitted.at(-1)).toEqual(['1']);
  });

  it('keeps a locally restored note when a stale cloud tombstone is still in the snapshot', async () => {
    const restored = note('7', 'brought back');
    fetchSnapshotNotes.mockResolvedValue({
      notes: [note('1', 'A')],
      tombstones: { '7': 99 },
      noteRevisions: { '1': 5 },
      maxRevision: 5,
    });
    useTombstoneStore.getState().markRestored('7');
    await putNote(USER_A, restored);

    const emitted: string[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(USER_A, (notes) => {
      emitted.push(notes.map((item) => item.id).sort());
    });
    await vi.waitFor(() => expect(emitted.length).toBeGreaterThan(0));
    stop();

    expect(emitted.at(-1)).toEqual(['1', '7']);
    expect(useTombstoneStore.getState().isDeleted('7')).toBe(false);
    expect((await listNotes(USER_A)).map((item) => item.id).sort()).toEqual(['1', '7']);
  });

  it('keeps unsynced local notes when the first cloud snapshot is empty', async () => {
    await putNote(USER_A, note('7', 'local only'));
    fetchSnapshotNotes.mockResolvedValue({
      notes: [],
      tombstones: {},
      noteRevisions: {},
      maxRevision: 0,
    });

    const emitted: string[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(USER_A, (notes) => {
      emitted.push(notes.map((item) => item.id));
    });
    await vi.waitFor(() => expect(emitted.length).toBeGreaterThan(0));
    stop();

    expect(emitted.at(-1)).toEqual(['7']);
    expect((await listNotes(USER_A)).map((item) => item.id)).toEqual(['7']);
    expect((await loadRevisionState(USER_A)).knownCloudIds).toEqual([]);
  });

  it('keeps unsynced local notes when a populated snapshot does not include them', async () => {
    await putNote(USER_A, note('7', 'local only'));
    fetchSnapshotNotes.mockResolvedValue({
      notes: [note('1', 'A')],
      tombstones: {},
      noteRevisions: { '1': 5 },
      maxRevision: 5,
    });

    const emitted: string[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(USER_A, (notes) => {
      emitted.push(notes.map((item) => item.id).sort());
    });
    await vi.waitFor(() => expect(emitted.length).toBeGreaterThan(0));
    stop();

    expect(emitted.at(-1)).toEqual(['1', '7']);
    expect((await listNotes(USER_A)).map((item) => item.id).sort()).toEqual(['1', '7']);
    expect((await loadRevisionState(USER_A)).knownCloudIds).toEqual(['1']);
  });

  it('tombstones a known cloud note missing from a populated snapshot without dropping local-only notes', async () => {
    await putNote(USER_A, note('1', 'was remote'));
    await putNote(USER_A, note('7', 'local only'));
    await saveRevisionState(USER_A, {
      noteRevisions: { '1': 5 },
      lastRemoteRevision: 5,
      knownCloudIds: ['1'],
    });
    fetchSnapshotNotes.mockResolvedValue({
      notes: [note('2', 'B')],
      tombstones: {},
      noteRevisions: { '2': 8 },
      maxRevision: 8,
    });

    const emitted: string[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(USER_A, (notes) => {
      emitted.push(notes.map((item) => item.id).sort());
    });
    await vi.waitFor(() => expect(emitted.length).toBeGreaterThan(0));
    stop();

    expect(emitted.at(-1)).toEqual(['2', '7']);
    expect((await listNotes(USER_A)).map((item) => item.id).sort()).toEqual(['2', '7']);
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
    expect((await loadRevisionState(USER_A)).knownCloudIds).toEqual(['2']);
  });

  it('persists known cloud ids from a populated snapshot', async () => {
    fetchSnapshotNotes.mockResolvedValue({
      notes: [note('1', 'A'), note('2', 'B')],
      tombstones: {},
      noteRevisions: { '1': 1, '2': 2 },
      maxRevision: 2,
    });

    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(USER_A, () => {});
    await vi.waitFor(async () => {
      expect((await loadRevisionState(USER_A)).knownCloudIds.sort()).toEqual(['1', '2']);
    });
    stop();
  });

  it('does not wipe local notes when an empty snapshot cannot explain known cloud ids', async () => {
    await putNote(USER_A, note('1', 'A'));
    await saveRevisionState(USER_A, { noteRevisions: { '1': 5 }, lastRemoteRevision: 5 });
    fetchSnapshotNotes.mockResolvedValue({
      notes: [],
      tombstones: {},
      noteRevisions: {},
      maxRevision: 0,
    });

    const errors: string[] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(
      USER_A,
      () => {},
      (error) => errors.push(error.message),
    );
    await vi.waitFor(() => expect(errors.length).toBeGreaterThan(0));
    stop();

    expect(errors[0]).toMatch(/refusing to overwrite local copies/);
    expect((await listNotes(USER_A)).map((item) => item.id)).toEqual(['1']);
  });
});
