import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Note } from '@/types/note';

const fetchSnapshotNotes = vi.fn();
const pullIncrementalChanges = vi.fn();
const applyNoteChange = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc: vi.fn() }),
  isSupabaseBackendEnabled: () => true,
}));
vi.mock('@/lib/supabase/supabaseSyncEngine', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/supabase/supabaseSyncEngine')>();
  return {
    NoteConflictError: actual.NoteConflictError,
    ensureSupabaseAuthenticated: vi.fn().mockResolvedValue(undefined),
    fetchSnapshotNotes: (...args: unknown[]) => fetchSnapshotNotes(...args),
    pullIncrementalChanges: (...args: unknown[]) => pullIncrementalChanges(...args),
    applyNoteChange: (...args: unknown[]) => applyNoteChange(...args),
  };
});
vi.mock('@/lib/supabase/supabaseRealtimeSync', () => ({
  subscribeSupabaseNoteRealtime: vi.fn(() => () => {}),
}));

import { pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import { storePendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { getPendingAttachment } from '@/lib/local/pendingAttachmentRepository';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import {
  applyRemotePageAtomically,
  listNotes,
  listPendingPushes,
  putNote,
} from '@/lib/local/notesLocalRepository';
import { saveNote } from '@/lib/notes/noteActions';
import {
  resetRemoteNotesDataSourceForTests,
  setRemoteNotesDataSourceForTests,
} from '@/lib/remote/remoteNotesDataSourceRegistry';
import { rememberNoteRevision, saveRevisionState } from '@/lib/supabase/revisionStore';
import { NoteConflictError } from '@/lib/supabase/supabaseSyncEngine';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { flushPendingPushes } from '@/lib/supabase/pendingPushes';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

/**
 * F-06: a note edited while the browser could not reach the cloud must survive whatever the cloud
 * says next — the page-load snapshot, an incremental pull, a reconcile — and be pushed against the
 * revision it was edited from. When another device changed the note in the meantime, both versions
 * must survive.
 *
 * The cloud is a small fake with the server's optimistic-concurrency rule: a write whose base
 * revision is not the row's current revision is refused with the current row.
 */
const USER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

let online = true;
let revisionSeq = 0;
const cloud = new Map<string, { note: Note; revision: number }>();
/** Notes deleted in the cloud: note id → deleted-at. The server refuses writes to them. */
const cloudTombstones = new Map<string, number>();

function cloudWrite(note: Note): Note {
  const revision = ++revisionSeq;
  const stored = { ...note, serverUpdatedAt: 1_000 + revision };
  cloud.set(note.id, { note: stored, revision });
  return stored;
}

function offlineError() {
  return new TypeError('Failed to fetch');
}

function noteOne(title: string): Note {
  return { ...createEmptyNote({ id: '1', localId: 1, title }), timestamp: 50 };
}

function titlesById(notes: Note[]): Record<string, string> {
  return Object.fromEntries(notes.map((note) => [note.id, note.title]));
}

async function editWhileOffline(title = 'offline edit'): Promise<Note> {
  online = false;
  const current = useNotesStore.getState().notes.find((note) => note.id === '1')!;
  const edited = { ...current, title, timestamp: current.timestamp + 100 };
  await saveNote(edited);
  online = true;
  return edited;
}

/** Another device writes the note through the server, as it would on Android. */
function anotherDeviceEdits(title: string): Note {
  const row = cloud.get('1')!;
  return cloudWrite({ ...row.note, title, timestamp: row.note.timestamp + 1 });
}

describe('F-06 offline edit durability', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    online = true;
    revisionSeq = 0;
    cloud.clear();
    cloudTombstones.clear();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();

    applyNoteChange.mockImplementation(async (userId: string, note: Note, base: number | null) => {
      if (!online) throw offlineError();
      if (cloudTombstones.has(note.id)) {
        // What applyNoteChange does with the server's note_deleted answer.
        useTombstoneStore.getState().markDeleted(note.id);
        throw new NoteConflictError(note.id, null, `Note ${note.id} was deleted in the cloud`);
      }
      const row = cloud.get(note.id);
      if (row && base !== row.revision) throw new NoteConflictError(note.id, row.note);
      if (!row && base != null) throw new NoteConflictError(note.id, null, undefined, true);
      const stored = cloudWrite(note);
      await rememberNoteRevision(userId, note.id, cloud.get(note.id)!.revision);
      return stored;
    });
    fetchSnapshotNotes.mockImplementation(async () => {
      if (!online) throw offlineError();
      const rows = [...cloud.values()];
      return {
        notes: rows.map((row) => row.note),
        tombstones: Object.fromEntries(cloudTombstones),
        noteRevisions: Object.fromEntries(rows.map((row) => [row.note.id, row.revision])),
        maxRevision: revisionSeq,
      };
    });
    pullIncrementalChanges.mockResolvedValue(false);

    setRemoteNotesDataSourceForTests(supabaseRemoteNotesDataSource);
    useAuthStore.getState().setUser({ uid: USER, email: null, displayName: null });

    // Synced to the cloud before the connection dropped.
    const synced = cloudWrite(noteOne('cloud version'));
    await putNote(USER, synced);
    useNotesStore.getState().setNotes([synced]);
    await saveRevisionState(USER, {
      noteRevisions: { '1': cloud.get('1')!.revision },
      lastRemoteRevision: revisionSeq,
      knownCloudIds: ['1'],
    });
    return () => {
      resetRemoteNotesDataSourceForTests();
      useAuthStore.getState().setUser(null);
    };
  });

  async function subscribeOnce(): Promise<Note[]> {
    const emitted: Note[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(USER, (notes) => {
      emitted.push(notes);
    });
    await vi.waitFor(() => expect(emitted.length).toBeGreaterThan(0));
    stop();
    return emitted.at(-1)!;
  }

  it('keeps the edit through the next page-load snapshot and pushes it', async () => {
    await editWhileOffline();

    const emitted = await subscribeOnce();

    expect(titlesById(await listNotes(USER))).toEqual({ '1': 'offline edit' });
    expect(titlesById(emitted)).toEqual({ '1': 'offline edit' });
    expect(cloud.get('1')!.note.title).toBe('offline edit');
    expect(await listPendingPushes(USER)).toEqual({});
  });

  it('keeps the edit when an incremental pull brings another device\'s version', async () => {
    await editWhileOffline();
    const android = anotherDeviceEdits('android edit');

    await applyRemotePageAtomically({
      ownerId: USER,
      upserts: [android],
      deletedNoteIds: [],
      noteRevisions: { '1': cloud.get('1')!.revision },
      lastRemoteRevision: revisionSeq,
    });

    expect(titlesById(await listNotes(USER))).toEqual({ '1': 'offline edit' });
    expect(Object.keys(await listPendingPushes(USER))).toEqual(['1']);
  });

  it('keeps both versions when another device changed the note: theirs in place, ours as a copy', async () => {
    await editWhileOffline();
    anotherDeviceEdits('android edit');

    const emitted = await subscribeOnce();

    const stored = await listNotes(USER);
    expect(stored.find((note) => note.id === '1')?.title).toBe('android edit');
    const copy = stored.find((note) => note.id !== '1');
    expect(copy?.title).toBe('offline edit (conflicted copy)');
    expect(emitted.map((note) => note.title).sort()).toEqual([
      'android edit',
      'offline edit (conflicted copy)',
    ]);
    expect(cloud.get(copy!.id)?.note.title).toBe('offline edit (conflicted copy)');
    expect(cloud.get('1')!.note.title).toBe('android edit');
    expect(await listPendingPushes(USER)).toEqual({});
  });

  it('gives the copy the images that were never uploaded, staged under the copy', async () => {
    const bytes = new Blob(['photo'], { type: 'image/png' });
    expect(await storePendingAttachment('att-photo', bytes, 'image/png', '1', USER)).toBe(true);
    online = false;
    const current = useNotesStore.getState().notes.find((note) => note.id === '1')!;
    await saveNote({
      ...current,
      title: 'offline edit',
      timestamp: current.timestamp + 100,
      attachments: [
        { id: 'att-photo', noteId: 1, storagePath: pendingStoragePath('att-photo'), type: 'image', mimeType: 'image/png' },
      ],
    });
    online = true;
    anotherDeviceEdits('android edit');

    await subscribeOnce();

    const copy = (await listNotes(USER)).find((note) => note.id !== '1')!;
    expect(copy.attachments).toHaveLength(1);
    const [moved] = copy.attachments;
    // A new id: the cloud keys attachments by id, so the copy cannot share the original's.
    expect(moved.id).not.toBe('att-photo');
    expect(moved.storagePath).toBe(pendingStoragePath(moved.id));
    expect(moved.noteId).toBe(copy.localId);
    expect((await getPendingAttachment(USER, copy.id, moved.id))?.sizeBytes).toBe(bytes.size);
    // The cloud version that kept id 1 never had the image, so its staged record is released.
    expect(await getPendingAttachment(USER, '1', 'att-photo')).toBeNull();
  });

  it('keeps the edit as a copy when another device deleted the note meanwhile', async () => {
    await editWhileOffline();
    cloud.delete('1');
    cloudTombstones.set('1', 2_000);

    await subscribeOnce();

    const stored = await listNotes(USER);
    expect(stored.map((note) => note.id)).not.toContain('1');
    expect(stored.map((note) => note.title)).toEqual(['offline edit (conflicted copy)']);
    expect([...cloud.values()].map((row) => row.note.title)).toEqual(['offline edit (conflicted copy)']);
    expect(await listPendingPushes(USER)).toEqual({});
  });

  it('keeps both versions on reconcile too', async () => {
    const edited = await editWhileOffline();
    anotherDeviceEdits('android edit');

    const result = await supabaseRemoteNotesDataSource.syncNotesWithCloud(
      USER,
      [edited],
      new Set(['1']),
    );

    expect(result.merged.map((note) => note.title).sort()).toEqual([
      'android edit',
      'offline edit (conflicted copy)',
    ]);
    expect(cloud.get('1')!.note.title).toBe('android edit');
  });

  it('does not make a copy when the earlier push landed but its reply was lost', async () => {
    const edited = await editWhileOffline();
    // The server applied it; this device never heard back, so it still holds the old base.
    cloudWrite(edited);

    await subscribeOnce();

    expect(titlesById(await listNotes(USER))).toEqual({ '1': 'offline edit' });
    expect(cloud.size).toBe(1);
    expect(await listPendingPushes(USER)).toEqual({});
  });

  it('pushes the edit as a new note when the cloud no longer has it at all', async () => {
    await editWhileOffline();
    cloud.clear(); // wiped: no row, no tombstone

    await subscribeOnce();

    expect(cloud.get('1')?.note.title).toBe('offline edit');
    expect(titlesById(await listNotes(USER))).toEqual({ '1': 'offline edit' });
    expect(await listPendingPushes(USER)).toEqual({});
  });

  describe('an edit the server refuses outright', () => {
    const tooLong = Object.assign(new Error('note content exceeds 100000 characters'), {
      code: '22023',
    });

    it('tells the user once, names the reason, and keeps the edit', async () => {
      const edited = await editWhileOffline();
      applyNoteChange.mockRejectedValue(tooLong);
      useToastStore.getState().dismiss();

      await flushPendingPushes(USER);

      const toast = useToastStore.getState().message;
      expect(toast?.tone).toBe('error');
      expect(toast?.text).toContain('offline edit');
      expect(toast?.text).toContain('note content exceeds 100000 characters');
      expect(titlesById(await listNotes(USER))).toEqual({ '1': edited.title });
      expect(Object.keys(await listPendingPushes(USER))).toEqual(['1']);

      useToastStore.getState().dismiss();
      await flushPendingPushes(USER);
      expect(useToastStore.getState().message).toBeNull();
    });

    it('stays quiet about a network failure, which is expected to pass', async () => {
      await editWhileOffline();
      online = false;
      useToastStore.getState().dismiss();

      await flushPendingPushes(USER);

      expect(useToastStore.getState().message).toBeNull();
    });
  });

  it('keeps the edit pending while the cloud is still unreachable', async () => {
    await editWhileOffline();
    online = false;

    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(USER, () => {}, () => {});
    await vi.waitFor(() => expect(fetchSnapshotNotes).toHaveBeenCalled());
    stop();

    expect(titlesById(await listNotes(USER))).toEqual({ '1': 'offline edit' });
    expect(Object.keys(await listPendingPushes(USER))).toEqual(['1']);
  });

  it('pushes a second offline edit against the revision the first one produced', async () => {
    await editWhileOffline('first');
    await subscribeOnce();
    await editWhileOffline('second');

    await subscribeOnce();

    expect(cloud.size).toBe(1);
    expect(cloud.get('1')!.note.title).toBe('second');
    expect(titlesById(await listNotes(USER))).toEqual({ '1': 'second' });
  });
});
