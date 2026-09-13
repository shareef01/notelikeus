import { describe, expect, it, vi, beforeEach } from 'vitest';

const { remoteMocks } = vi.hoisted(() => ({
  remoteMocks: {
    uploadAllNotes: vi.fn().mockResolvedValue(1),
  },
}));

vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => remoteMocks,
}));

vi.mock('@/lib/notes/notesSyncService', () => ({
  pauseRealtimeSnapshots: vi.fn(),
  resumeRealtimeSnapshots: vi.fn(),
}));

import {
  pauseRealtimeSnapshots,
  resumeRealtimeSnapshots,
} from '@/lib/notes/notesSyncService';
import { commitImportedNotes } from '@/lib/backup/commitImportedNotes';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';

function note(id: string) {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}` });
}

describe('commitImportedNotes', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    await resetNotesDatabaseForTests();
  });


  it('uploads before writing the store when signed in', async () => {
    const order: string[] = [];
    vi.mocked(pauseRealtimeSnapshots).mockImplementation(() => order.push('pause'));
    vi.mocked(remoteMocks.uploadAllNotes).mockImplementation(async () => {
      order.push('upload');
      expect(useNotesStore.getState().notes).toEqual([]);
      return 1;
    });
    vi.mocked(resumeRealtimeSnapshots).mockImplementation(() => order.push('resume'));

    const merged = [note('1')];
    const uploaded = await commitImportedNotes(merged, 1, 'user-1');

    expect(uploaded).toBe(true);
    expect(remoteMocks.uploadAllNotes).toHaveBeenCalledWith('user-1', merged);
    expect(useNotesStore.getState().notes).toEqual(merged);
    expect(order).toEqual(['pause', 'upload', 'resume']);
  });

  it('does not write the store if the upload fails', async () => {
    vi.mocked(remoteMocks.uploadAllNotes).mockRejectedValueOnce(new Error('offline'));

    await expect(commitImportedNotes([note('1')], 1, 'user-1')).rejects.toThrow('offline');

    expect(useNotesStore.getState().notes).toEqual([]);
    expect(resumeRealtimeSnapshots).toHaveBeenCalled();
  });

  it('writes locally only when signed out', async () => {
    const merged = [note('1')];
    const uploaded = await commitImportedNotes(merged, 1, undefined);

    expect(uploaded).toBe(false);
    expect(remoteMocks.uploadAllNotes).not.toHaveBeenCalled();
    expect(useNotesStore.getState().notes).toEqual(merged);
  });

  // Test A: guestBackupImportPersistsAcrossReload
  it('guestBackupImportPersistsAcrossReload', async () => {
    const { listNotes } = await import('@/lib/local/notesLocalRepository');
    const { GUEST_OWNER_ID } = await import('@/lib/local/constants');

    const imported = [note('guest-backup-1')];
    await commitImportedNotes(imported, 1, undefined);

    // Simulate page reload by resetting in-memory store
    useNotesStore.getState().reset();
    expect(useNotesStore.getState().notes).toEqual([]);

    // Reload from IndexedDB for guest owner
    const persisted = await listNotes(GUEST_OWNER_ID);
    expect(persisted).toHaveLength(1);
    expect(persisted[0]?.id).toBe('guest-backup-1');
  });

  // Test B: failedGuestPersistenceDoesNotReportSuccessfulImport
  it('failedGuestPersistenceDoesNotReportSuccessfulImport', async () => {
    const notesLocalRepository = await import('@/lib/local/notesLocalRepository');
    vi.spyOn(notesLocalRepository, 'putNotes').mockRejectedValueOnce(new Error('IndexedDB quota exceeded'));

    const imported = [note('guest-failed-1')];
    await expect(commitImportedNotes(imported, 1, undefined)).rejects.toThrow('IndexedDB quota exceeded');

    // In-memory store must NOT claim success
    expect(useNotesStore.getState().notes).toEqual([]);
  });

  // Test C: guestImportUsesGuestOwnerNamespace
  it('guestImportUsesGuestOwnerNamespace', async () => {
    const notesLocalRepository = await import('@/lib/local/notesLocalRepository');
    const putNotesSpy = vi.spyOn(notesLocalRepository, 'putNotes');
    const { GUEST_OWNER_ID } = await import('@/lib/local/constants');

    const imported = [note('guest-ns-1')];
    await commitImportedNotes(imported, 1, undefined);

    expect(putNotesSpy).toHaveBeenCalledWith(GUEST_OWNER_ID, imported);
  });

  // Test D: guestImportIsEncryptedAtRestInIndexedDB
  it('guestImportIsEncryptedAtRestInIndexedDB', async () => {
    const { NOTES_STORE } = await import('@/lib/local/constants');
    const { withStore } = await import('@/lib/local/idb');
    const { GUEST_OWNER_ID } = await import('@/lib/local/constants');
    const { isSealedStoredNote } = await import('@/lib/local/notesSealing');

    const imported = [
      createEmptyNote({
        id: 'guest-encrypted-note',
        localId: 101,
        title: 'Secret Guest Title',
        content: 'Secret Guest Content',
      }),
    ];
    await commitImportedNotes(imported, 1, undefined);

    const raw = await withStore<{ note: { title: string; content: string; sealedBody?: ArrayBuffer } } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get([GUEST_OWNER_ID, 'guest-encrypted-note']),
    );

    expect(raw).toBeDefined();
    expect(raw!.note.sealedBody).toBeDefined();
    expect(isSealedStoredNote(raw!.note as never)).toBe(true);
    // On-disk shell must be blanked
    expect(raw!.note.title).toBe('');
    expect(raw!.note.content).toBe('');
  });

  // Test E: authenticatedRemoteFailureLeavesCleanState
  it('authenticatedRemoteFailureLeavesCleanState', async () => {
    const { listNotes } = await import('@/lib/local/notesLocalRepository');
    const { GUEST_OWNER_ID } = await import('@/lib/local/constants');

    vi.mocked(remoteMocks.uploadAllNotes).mockRejectedValueOnce(new Error('network error'));

    const imported = [note('auth-fail-1')];
    await expect(commitImportedNotes(imported, 1, 'user-xyz')).rejects.toThrow('network error');

    // UI store is empty
    expect(useNotesStore.getState().notes).toEqual([]);

    // Neither guest nor user namespace in IndexedDB was polluted
    expect(await listNotes(GUEST_OWNER_ID)).toEqual([]);
    expect(await listNotes('user-xyz')).toEqual([]);
  });
});
