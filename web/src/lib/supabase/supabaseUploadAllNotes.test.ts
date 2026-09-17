import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc: vi.fn() }),
  isSupabaseBackendEnabled: () => true,
}));
vi.mock('@/lib/supabase/supabaseSyncEngine', () => ({
  ensureSupabaseAuthenticated: vi.fn().mockResolvedValue(undefined),
  applyNoteChange: vi.fn(),
  fetchSnapshotNotes: vi.fn(),
  pullIncrementalChanges: vi.fn(),
}));
vi.mock('@/lib/supabase/supabaseRealtimeSync', () => ({
  subscribeSupabaseNoteRealtime: vi.fn(() => () => {}),
}));

import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { loadRevisionState, rememberNoteRevision } from '@/lib/supabase/revisionStore';
import { applyNoteChange, fetchSnapshotNotes } from '@/lib/supabase/supabaseSyncEngine';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = '11111111-1111-4111-8111-111111111111';

function note(partial: Partial<Note> & Pick<Note, 'id' | 'localId'>): Note {
  return createEmptyNote(partial);
}

function emptySnapshot() {
  return { notes: [] as Note[], tombstones: {}, noteRevisions: {}, maxRevision: 0 };
}

describe('supabaseRemoteNotesDataSource.uploadAllNotes', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    vi.mocked(applyNoteChange).mockImplementation(async (_userId, uploaded) => uploaded);
  });

  it('uploads a note that the cloud does not have', async () => {
    vi.mocked(fetchSnapshotNotes).mockResolvedValue(emptySnapshot());
    const imported = note({ id: '9', localId: 9, title: 'From backup', timestamp: 1 });

    const uploaded = await supabaseRemoteNotesDataSource.uploadAllNotes(USER, [imported]);

    expect(uploaded).toBe(1);
    expect(applyNoteChange).toHaveBeenCalledWith(USER, imported, null);
  });

  it('does not overwrite a confirmed cloud note with an older unconfirmed local copy', async () => {
    const remote = note({
      id: '1',
      localId: 1,
      title: 'Cloud wins',
      timestamp: 100,
      serverUpdatedAt: 500,
    });
    const staleLocal = note({
      id: '1',
      localId: 1,
      title: 'Old backup',
      timestamp: 999_999_999,
      serverUpdatedAt: null,
    });
    vi.mocked(fetchSnapshotNotes).mockResolvedValue({
      notes: [remote],
      tombstones: {},
      noteRevisions: { '1': 10_042 },
      maxRevision: 10_042,
    });

    const uploaded = await supabaseRemoteNotesDataSource.uploadAllNotes(USER, [staleLocal]);

    expect(uploaded).toBe(0);
    expect(applyNoteChange).not.toHaveBeenCalled();
  });

  it('still uploads newly imported ids when an existing cloud note should win', async () => {
    const remote = note({
      id: '1',
      localId: 1,
      title: 'Cloud wins',
      timestamp: 100,
      serverUpdatedAt: 500,
    });
    const staleLocal = note({
      id: '1',
      localId: 1,
      title: 'Old backup',
      timestamp: 999_999_999,
      serverUpdatedAt: null,
    });
    const imported = note({ id: '2', localId: 2, title: 'New from backup', timestamp: 1 });
    vi.mocked(fetchSnapshotNotes).mockResolvedValue({
      notes: [remote],
      tombstones: {},
      noteRevisions: { '1': 10_042 },
      maxRevision: 10_042,
    });

    const uploaded = await supabaseRemoteNotesDataSource.uploadAllNotes(USER, [
      staleLocal,
      imported,
    ]);

    expect(uploaded).toBe(1);
    expect(applyNoteChange).toHaveBeenCalledTimes(1);
    expect(applyNoteChange).toHaveBeenCalledWith(USER, imported, null);
  });

  it('refuses to push the library when a snapshot is empty but this account already has revisions', async () => {
    await rememberNoteRevision(USER, '1', 10_005);
    vi.mocked(fetchSnapshotNotes).mockResolvedValue(emptySnapshot());

    await expect(
      supabaseRemoteNotesDataSource.uploadAllNotes(USER, [
        note({ id: '1', localId: 1, title: 'Local' }),
      ]),
    ).rejects.toThrow(/refusing to overwrite the cloud/);

    expect(applyNoteChange).not.toHaveBeenCalled();
  });

  it('allows a first upload onto a legitimately empty cloud', async () => {
    vi.mocked(fetchSnapshotNotes).mockResolvedValue(emptySnapshot());
    const first = note({ id: '1', localId: 1, title: 'First note' });

    const uploaded = await supabaseRemoteNotesDataSource.uploadAllNotes(USER, [first]);

    expect(uploaded).toBe(1);
    expect(applyNoteChange).toHaveBeenCalledWith(USER, first, null);
  });

  it('skips notes that already have a cloud tombstone', async () => {
    vi.mocked(fetchSnapshotNotes).mockResolvedValue({
      notes: [],
      tombstones: { '1': 1_700_000_000_000 },
      noteRevisions: {},
      maxRevision: 10_001,
    });

    const uploaded = await supabaseRemoteNotesDataSource.uploadAllNotes(USER, [
      note({ id: '1', localId: 1, title: 'Deleted elsewhere' }),
    ]);

    expect(uploaded).toBe(0);
    expect(applyNoteChange).not.toHaveBeenCalled();
  });

  // F1-A: all missing IDs explained by remote tombstones
  it('F1-A: accepts upload when all missing cloud IDs are explained by remote tombstones', async () => {
    await rememberNoteRevision(USER, 'note-A', 10_001);
    await rememberNoteRevision(USER, 'note-B', 10_002);

    vi.mocked(fetchSnapshotNotes).mockResolvedValue({
      notes: [],
      tombstones: {
        'note-A': 1_700_000_000_000,
        'note-B': 1_700_000_000_100,
      },
      noteRevisions: {},
      maxRevision: 10_002,
    });

    const validNewNote = note({ id: 'note-C', localId: 3, title: 'New Valid Note' });
    const uploaded = await supabaseRemoteNotesDataSource.uploadAllNotes(USER, [validNewNote]);

    expect(uploaded).toBe(1);
    expect(applyNoteChange).toHaveBeenCalledWith(USER, validNewNote, null);
  });

  // F1-B: unexplained empty cloud remains blocked
  it('F1-B: rejects upload when empty cloud has unexplained missing IDs (preserves suspect-empty protection)', async () => {
    await rememberNoteRevision(USER, 'note-A', 10_001);
    await rememberNoteRevision(USER, 'note-B', 10_002);

    vi.mocked(fetchSnapshotNotes).mockResolvedValue({
      notes: [],
      tombstones: {},
      noteRevisions: {},
      maxRevision: 0,
    });

    const validNewNote = note({ id: 'note-C', localId: 3, title: 'New Valid Note' });
    await expect(
      supabaseRemoteNotesDataSource.uploadAllNotes(USER, [validNewNote]),
    ).rejects.toThrow(/refusing to overwrite the cloud/);

    expect(applyNoteChange).not.toHaveBeenCalled();
  });

  // F1-C: partially explained disappearance remains blocked
  it('F1-C: rejects upload when cloud disappearance is only partially explained by tombstones', async () => {
    await rememberNoteRevision(USER, 'note-A', 10_001);
    await rememberNoteRevision(USER, 'note-B', 10_002);

    // note-A is explained by tombstone, but note-B is mysteriously absent
    vi.mocked(fetchSnapshotNotes).mockResolvedValue({
      notes: [],
      tombstones: {
        'note-A': 1_700_000_000_000,
      },
      noteRevisions: {},
      maxRevision: 10_001,
    });

    const validNewNote = note({ id: 'note-C', localId: 3, title: 'New Valid Note' });
    await expect(
      supabaseRemoteNotesDataSource.uploadAllNotes(USER, [validNewNote]),
    ).rejects.toThrow(/1 were expected/);

    expect(applyNoteChange).not.toHaveBeenCalled();
  });

  // F1-D: complete cloud state consisting of live + tombstoned notes
  it('F1-D: accepts upload when cloud state consists of live notes and tombstoned notes', async () => {
    await rememberNoteRevision(USER, 'note-A', 10_001);
    await rememberNoteRevision(USER, 'note-B', 10_002);

    const liveA = note({ id: 'note-A', localId: 1, title: 'Live A', serverUpdatedAt: 100 });
    vi.mocked(fetchSnapshotNotes).mockResolvedValue({
      notes: [liveA],
      tombstones: {
        'note-B': 1_700_000_000_000,
      },
      noteRevisions: { 'note-A': 10_001 },
      maxRevision: 10_002,
    });

    const validNewNote = note({ id: 'note-C', localId: 3, title: 'New Valid Note' });
    const uploaded = await supabaseRemoteNotesDataSource.uploadAllNotes(USER, [validNewNote]);

    expect(uploaded).toBe(1);
    expect(applyNoteChange).toHaveBeenCalledWith(USER, validNewNote, null);
  });

  // F1-E: persistent-lockout reproduction
  it('F1-E: reproduces persistent lockout when legitimately tombstoned cloud rejects and retains stale revisions', async () => {
    // 1. Seed prior revision state with A and B
    await rememberNoteRevision(USER, 'note-A', 10_001);
    await rememberNoteRevision(USER, 'note-B', 10_002);

    // 2. Return remote empty snapshot with tombstones A and B
    vi.mocked(fetchSnapshotNotes).mockResolvedValue({
      notes: [],
      tombstones: {
        'note-A': 1_700_000_000_000,
        'note-B': 1_700_000_000_100,
      },
      noteRevisions: {},
      maxRevision: 10_002,
    });

    const validNewNote = note({ id: 'note-C', localId: 3, title: 'New Valid Note' });

    // 3. Call uploadAllNotes - on audited code, this erroneously rejects
    // We expect this call to succeed once remediated; here we test that after remediation it does NOT lock out,
    // and we verify the revision state is updated properly.
    const uploaded = await supabaseRemoteNotesDataSource.uploadAllNotes(USER, [validNewNote]);
    expect(uploaded).toBe(1);

    // 4. Stored revision state should now be updated with the latest snapshot
    const updatedState = await loadRevisionState(USER);
    expect(updatedState.lastRemoteRevision).toBe(10_002);
  });
});
