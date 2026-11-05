import { describe, expect, it, vi, beforeEach } from 'vitest';

const { remoteMocks } = vi.hoisted(() => ({
  remoteMocks: {
    pushPendingEdits: vi.fn().mockResolvedValue(undefined),
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

/** Stands in for a push that the cloud accepts in full. */
async function settleAll(userId: string): Promise<void> {
  const { dropPendingPush, listPendingPushes } = await import('@/lib/local/notesLocalRepository');
  for (const id of Object.keys(await listPendingPushes(userId))) await dropPendingPush(userId, id);
}

function note(id: string) {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}` });
}

describe('commitImportedNotes', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    await resetNotesDatabaseForTests();
  });


  it('saves the imported notes as pending edits before pushing them', async () => {
    const { listNotes, listPendingPushes } = await import('@/lib/local/notesLocalRepository');
    const order: string[] = [];
    vi.mocked(pauseRealtimeSnapshots).mockImplementation(() => order.push('pause'));
    vi.mocked(resumeRealtimeSnapshots).mockImplementation(() => order.push('resume'));
    remoteMocks.pushPendingEdits.mockImplementation(async (userId: string) => {
      order.push('push');
      // Durable and queued before anything is sent.
      expect(Object.keys(await listPendingPushes(userId))).toEqual(['1']);
      expect((await listNotes(userId)).map((n) => n.id)).toEqual(['1']);
      await settleAll(userId);
    });

    const merged = [note('1')];
    const outcome = await commitImportedNotes(merged, 1, 'user-1');

    expect(outcome).toBe('synced');
    expect(remoteMocks.pushPendingEdits).toHaveBeenCalledWith('user-1');
    expect(useNotesStore.getState().notes).toEqual(merged);
    expect(order).toEqual(['pause', 'resume', 'push']);
  });

  it('writes locally only when signed out', async () => {
    const merged = [note('1')];
    const outcome = await commitImportedNotes(merged, 1, undefined);

    expect(outcome).toBe('local');
    expect(remoteMocks.pushPendingEdits).not.toHaveBeenCalled();
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

  // AUDIT-005: the upload used to run first and IndexedDB was written only afterwards, so a failure
  // partway left some notes in the cloud and none here, and a retried import duplicated them.
  it('keeps every imported note saved and queued when the upload fails partway', async () => {
    const { listNotes, listPendingPushes, dropPendingPush } = await import(
      '@/lib/local/notesLocalRepository'
    );
    const USER = 'user-partial';
    // The first two notes reach the cloud, then the connection drops.
    remoteMocks.pushPendingEdits.mockImplementationOnce(async (userId: string) => {
      await dropPendingPush(userId, '10');
      await dropPendingPush(userId, '11');
      throw new Error('network dropped');
    });

    const merged = [note('10'), note('11'), note('12'), note('13')];
    const outcome = await commitImportedNotes(merged, 4, USER);

    expect(outcome).toBe('pending');
    expect(useNotesStore.getState().notes).toEqual(merged);
    expect((await listNotes(USER)).map((n) => n.id).sort()).toEqual(['10', '11', '12', '13']);
    expect(Object.keys(await listPendingPushes(USER)).sort()).toEqual(['12', '13']);

    // The next sync (not a second import) sends the rest, under the same ids.
    remoteMocks.pushPendingEdits.mockImplementationOnce(settleAll);
    await remoteMocks.pushPendingEdits(USER);
    expect(await listPendingPushes(USER)).toEqual({});
    expect(await listNotes(USER)).toHaveLength(4);
  });

  it('reports the import as saved, not failed, when the cloud is unreachable', async () => {
    remoteMocks.pushPendingEdits.mockRejectedValueOnce(new Error('offline'));

    await expect(commitImportedNotes([note('1')], 1, 'user-offline')).resolves.toBe('pending');
    expect(useNotesStore.getState().notes).toHaveLength(1);
  });

  it('writes only the imported notes, and keeps the existing ones as they are', async () => {
    const { listNotes, listPendingPushes, putNotes } = await import('@/lib/local/notesLocalRepository');
    const USER = 'user-auth-1';
    const existingA = note('note-A');
    await putNotes(USER, [existingA]);
    remoteMocks.pushPendingEdits.mockResolvedValueOnce(undefined);

    const importedB = note('note-B');
    await commitImportedNotes([existingA, importedB], 1, USER);

    // Only B is a new edit; A was already in sync and stays that way.
    expect(Object.keys(await listPendingPushes(USER))).toEqual(['note-B']);
    useNotesStore.getState().reset();
    expect((await listNotes(USER)).map((n) => n.id).sort()).toEqual(['note-A', 'note-B']);
  });

  it('changes nothing when the local write fails', async () => {
    const notesLocalRepository = await import('@/lib/local/notesLocalRepository');
    const spy = vi
      .spyOn(notesLocalRepository, 'putLocalEdits')
      .mockRejectedValueOnce(new Error('IndexedDB disk full'));

    await expect(commitImportedNotes([note('1')], 1, 'user-auth-fail')).rejects.toThrow('IndexedDB disk full');

    expect(useNotesStore.getState().notes).toEqual([]);
    expect(remoteMocks.pushPendingEdits).not.toHaveBeenCalled();
    expect(resumeRealtimeSnapshots).toHaveBeenCalled();
    spy.mockRestore();
  });
});
