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
import { rememberNoteRevision } from '@/lib/supabase/revisionStore';
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
});
