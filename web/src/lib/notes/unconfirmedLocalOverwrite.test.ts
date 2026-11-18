import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Note } from '@/types/note';

const fetchSnapshotNotes = vi.fn();
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
    pullIncrementalChanges: vi.fn().mockResolvedValue(false),
    applyNoteChange: (...args: unknown[]) => applyNoteChange(...args),
  };
});
vi.mock('@/lib/supabase/supabaseRealtimeSync', () => ({
  subscribeSupabaseNoteRealtime: vi.fn(() => () => {}),
}));

import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { isConflictedCopyTitle } from '@/lib/notes/conflictResolution';
import { rememberNoteRevision } from '@/lib/supabase/revisionStore';
import { NoteConflictError } from '@/lib/supabase/supabaseSyncEngine';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

/**
 * A local note the server never stamped (`serverUpdatedAt: null`) that is not in the pending-push
 * queue — a note carried over from the legacy store or from a build that predates the queue — meets
 * a cloud copy of the same id that the server has confirmed.
 *
 * `shouldUploadOverRemote` lets such a note win when its client `timestamp` is later than the
 * server's stamp, which a device clock that runs ahead makes true for any edit. The reconcile then
 * pushed it with whatever revision this browser had learned for the id. The first attempt is
 * refused (no revision known yet) but the refusal records the cloud's revision, so the next sync
 * pushed the same stale copy against it and the server accepted it: the confirmed cloud edit was
 * overwritten with no conflicted copy. Both versions must survive instead.
 */
const USER = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

let revisionSeq = 0;
const cloud = new Map<string, { note: Note; revision: number }>();

function cloudWrite(note: Note): Note {
  const revision = ++revisionSeq;
  const stored = { ...note, attachments: [], serverUpdatedAt: 1_000 + revision };
  cloud.set(note.id, { note: stored, revision });
  return stored;
}

describe('unconfirmed local note against a confirmed cloud copy', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    revisionSeq = 0;
    cloud.clear();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();

    applyNoteChange.mockImplementation(async (userId: string, note: Note, base: number | null) => {
      const row = cloud.get(note.id);
      if (row && base !== row.revision) {
        await rememberNoteRevision(userId, note.id, row.revision);
        throw new NoteConflictError(note.id, row.note);
      }
      if (!row && base != null) throw new NoteConflictError(note.id, null, undefined, true);
      const stored = cloudWrite(note);
      await rememberNoteRevision(userId, note.id, cloud.get(note.id)!.revision);
      return { ...stored, attachments: note.attachments };
    });
    fetchSnapshotNotes.mockImplementation(async () => {
      const rows = [...cloud.values()];
      return {
        notes: rows.map((row) => row.note),
        tombstones: {},
        noteRevisions: Object.fromEntries(rows.map((row) => [row.note.id, row.revision])),
        maxRevision: revisionSeq,
        wipeGeneration: 0,
      };
    });

    useAuthStore.getState().setUser({ uid: USER, email: null, displayName: null });
    useNotesStore.getState().setNotes([]);
    return () => useAuthStore.getState().setUser(null);
  });

  it('never overwrites the confirmed cloud edit, and keeps the local words as a copy', async () => {
    cloudWrite({ ...createEmptyNote({ id: '7', localId: 7, title: 'newer cloud edit' }), timestamp: 900 });
    // Edited on a device whose clock runs ahead of the server's: its timestamp beats the stamp.
    const local: Note = {
      ...createEmptyNote({ id: '7', localId: 7, title: 'older local words' }),
      timestamp: 5_000,
      serverUpdatedAt: null,
    };

    let notes = [local];
    for (let pass = 0; pass < 2; pass++) {
      const { merged } = await supabaseRemoteNotesDataSource.syncNotesWithCloud(USER, notes, new Set());
      notes = merged;
    }

    expect(cloud.get('7')!.note.title).toBe('newer cloud edit');
    expect(notes.find((note) => note.id === '7')?.title).toBe('newer cloud edit');
    const copies = notes.filter((note) => isConflictedCopyTitle(note.title));
    expect(copies).toHaveLength(1);
    expect(copies[0]!.title).toContain('older local words');
    // The copy is an ordinary new note: it reaches the cloud like any other.
    expect([...cloud.values()].some((row) => row.note.id === copies[0]!.id)).toBe(true);
  });
});
