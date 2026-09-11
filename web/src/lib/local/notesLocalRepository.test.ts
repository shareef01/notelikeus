import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { resetNotesCryptoKeyForTests } from '@/lib/crypto/notesCryptoKey';
import { isSealedStoredNote } from '@/lib/local/notesSealing';
import { GUEST_OWNER_ID, NOTES_DB_NAME, NOTES_STORE } from '@/lib/local/constants';
import { resetNotesDatabaseForTests, withStore } from '@/lib/local/idb';
import {
  abortNextPutNotesForTests,
  abortNextRemotePageApplyForTests,
  applyRemotePageAtomically,
  clearOwner,
  getOwnerMeta,
  listNotes,
  putNote,
  putNotes,
  replaceAllNotes,
  setOwnerMeta,
} from '@/lib/local/notesLocalRepository';
import { createEmptyNote } from '@/types/note';

function makeNote(id: string, localId: number) {
  return createEmptyNote({ id, localId, title: `Note ${id}`, content: `Body ${id}` });
}

describe('notesLocalRepository', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    await resetNotesCryptoKeyForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  it('persists and lists notes per owner namespace', async () => {
    await putNote('user-a', makeNote('1', 1));
    await putNote('user-b', makeNote('2', 2));

    const aNotes = await listNotes('user-a');
    const bNotes = await listNotes('user-b');

    expect(aNotes).toHaveLength(1);
    expect(aNotes[0]?.id).toBe('1');
    expect(bNotes).toHaveLength(1);
    expect(bNotes[0]?.id).toBe('2');
  });

  it('clears an owner namespace without touching another', async () => {
    await putNote('user-a', makeNote('1', 1));
    await putNote('user-b', makeNote('2', 2));
    await setOwnerMeta('user-a', { firebaseHydrated: true, hydratedAt: 1 });

    await clearOwner('user-a');

    expect(await listNotes('user-a')).toHaveLength(0);
    expect(await listNotes('user-b')).toHaveLength(1);
    expect(await getOwnerMeta('user-a')).toBeNull();
  });

  /**
   * An aborted transaction must reject, not hang.
   *
   * IndexedDB fires `abort` with no preceding `error` when the browser reclaims storage or hits an
   * internal fault, so a handler on `error` alone leaves the promise unsettled forever. That is
   * worse than a failure everywhere this is awaited: `hydrateIndexedDbFromRemote` waits on it
   * before the app reports ready, and `applyNotes` rolls the optimistic UI back in its `.catch`,
   * so a hang leaves the store claiming a durable write that never happened.
   */
  it('rejects rather than hanging when the write transaction aborts', async () => {
    abortNextPutNotesForTests();

    await expect(putNotes('user-a', [makeNote('1', 1)])).rejects.toThrow();
    expect(await listNotes('user-a')).toHaveLength(0);
  });

  it('recovers on the next write after an abort', async () => {
    abortNextPutNotesForTests();
    await expect(putNotes('user-a', [makeNote('1', 1)])).rejects.toThrow();

    // The flag is consumed, not sticky: a retry must go through.
    await putNotes('user-a', [makeNote('1', 1), makeNote('2', 2)]);
    expect((await listNotes('user-a')).map((note) => note.id).sort()).toEqual(['1', '2']);
  });

  it('replaces all notes atomically for hydration', async () => {
    await putNote(GUEST_OWNER_ID, makeNote('1', 1));
    await replaceAllNotes(GUEST_OWNER_ID, [makeNote('9', 9), makeNote('10', 10)]);

    const notes = await listNotes(GUEST_OWNER_ID);
    const ids = notes.map((note) => note.id);
    expect(ids.sort()).toEqual(['10', '9']);
  });

  it('keeps old notes and cursor when a remote page transaction aborts', async () => {
    await putNote('user-a', makeNote('1', 1));
    await setOwnerMeta('user-a', { lastRemoteRevision: 50, noteRevisions: { '1': 50 } });
    abortNextRemotePageApplyForTests();

    await expect(
      applyRemotePageAtomically({
        ownerId: 'user-a',
        upserts: [makeNote('2', 2)],
        deletedNoteIds: ['1'],
        noteRevisions: { '2': 101 },
        lastRemoteRevision: 101,
      }),
    ).rejects.toThrow(/abort/i);

    expect((await listNotes('user-a')).map((note) => note.id)).toEqual(['1']);
    expect((await getOwnerMeta('user-a'))?.lastRemoteRevision).toBe(50);
  });

  it('commits notes and cursor together', async () => {
    await applyRemotePageAtomically({
      ownerId: 'user-a',
      upserts: [makeNote('2', 2)],
      deletedNoteIds: [],
      noteRevisions: { '2': 101 },
      lastRemoteRevision: 101,
    });
    expect((await listNotes('user-a')).map((note) => note.id)).toEqual(['2']);
    expect((await getOwnerMeta('user-a'))?.lastRemoteRevision).toBe(101);
    expect((await getOwnerMeta('user-a'))?.noteRevisions).toEqual({ '2': 101 });
  });

  it('commits known cloud ids with the notes and cursor', async () => {
    await applyRemotePageAtomically({
      ownerId: 'user-a',
      upserts: [makeNote('2', 2)],
      deletedNoteIds: [],
      noteRevisions: { '2': 101 },
      lastRemoteRevision: 101,
      knownCloudIds: ['2'],
    });
    expect((await getOwnerMeta('user-a'))?.knownCloudIds).toEqual(['2']);
  });

  it('removes tombstoned notes and advances the cursor atomically', async () => {
    await putNote('user-a', makeNote('1', 1));
    await applyRemotePageAtomically({
      ownerId: 'user-a',
      upserts: [],
      deletedNoteIds: ['1'],
      noteRevisions: {},
      lastRemoteRevision: 80,
    });
    expect(await listNotes('user-a')).toEqual([]);
    expect((await getOwnerMeta('user-a'))?.lastRemoteRevision).toBe(80);
  });

  it('never rewinds the cursor for a stale page', async () => {
    await applyRemotePageAtomically({
      ownerId: 'user-a',
      upserts: [makeNote('2', 2)],
      deletedNoteIds: [],
      noteRevisions: { '2': 101 },
      lastRemoteRevision: 101,
    });
    await applyRemotePageAtomically({
      ownerId: 'user-a',
      upserts: [makeNote('stale', 3)],
      deletedNoteIds: [],
      noteRevisions: { stale: 100 },
      lastRemoteRevision: 100,
    });
    expect((await getOwnerMeta('user-a'))?.lastRemoteRevision).toBe(101);
    expect((await listNotes('user-a')).map((note) => note.id)).toEqual(['2']);
  });

  it('seals title/content at rest while returning plaintext to callers', async () => {
    const note = makeNote('seal-1', 10);
    await putNote('user-seal', note);

    const raw = await withStore<{ note: { title?: string; content?: string; sealedBody?: ArrayBuffer } } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get(['user-seal', 'seal-1']),
    );
    expect(raw?.note).toBeDefined();
    expect(isSealedStoredNote(raw!.note as never)).toBe(true);
    expect(raw?.note.title).toBe('');
    expect(raw?.note.content).toBe('');
    expect(JSON.stringify(raw?.note)).not.toContain('Body seal-1');

    const listed = await listNotes('user-seal');
    expect(listed).toHaveLength(1);
    expect(listed[0]?.title).toBe('Note seal-1');
    expect(listed[0]?.content).toBe('Body seal-1');
  });

  it('dual-reads legacy plaintext and migrates it', async () => {
    const legacy = makeNote('legacy-1', 11);
    await withStore(NOTES_STORE, 'readwrite', (store) => {
      store.put({ ownerId: 'user-legacy', id: legacy.id, note: legacy });
    });

    const listed = await listNotes('user-legacy');
    expect(listed[0]?.title).toBe('Note legacy-1');
    expect(listed[0]?.content).toBe('Body legacy-1');

    let sealed = false;
    for (let i = 0; i < 40; i++) {
      const raw = await withStore<{ note: { sealedBody?: ArrayBuffer } } | undefined>(
        NOTES_STORE,
        'readonly',
        (store) => store.get(['user-legacy', 'legacy-1']),
      );
      if (raw?.note && isSealedStoredNote(raw.note as never)) {
        sealed = true;
        break;
      }
      await new Promise((r) => setTimeout(r, 25));
    }
    expect(sealed).toBe(true);

    const again = await listNotes('user-legacy');
    expect(again[0]?.content).toBe('Body legacy-1');
  });
});
