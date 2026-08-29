import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, type Firestore } from 'firebase/firestore';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Exercises the sync layer against a real Firestore emulator.
 *
 * The rest of the web suite is pure-function tests, so nothing covered the parts that actually
 * talk to Firestore: whether a note round-trips through the mapper and the security rules, whether
 * delete-on-absence removes the right notes, and whether the empty-cloud guard fires before
 * anything is deleted. Those are the destructive paths — a wrong tombstone loses a note — and they
 * are also what a Firebase SDK major upgrade is most likely to break.
 *
 * Run with `npm run test:sync`, which starts the emulator first.
 */

const hoisted = vi.hoisted(() => ({ db: null as Firestore | null }));

vi.mock('@/lib/firebase', () => ({
  getFirestoreDb: () => {
    if (!hoisted.db) throw new Error('emulator Firestore not initialised');
    return hoisted.db;
  },
  isFirestoreMemoryCache: () => true,
}));

import {
  deleteNote,
  fetchRemoteNotes,
  syncNotesWithCloud,
  upsertNote,
} from '@/lib/firestore/notesRepository';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const PROJECT_ID = 'notelikeus-sync-test';
const USER = 'user-1';
const RULES_PATH = resolve(process.cwd(), '..', 'firestore.rules');

let testEnv: RulesTestEnvironment;

function note(id: string, overrides: Partial<Note> = {}): Note {
  return {
    ...createEmptyNote({ id, localId: Number(id) }),
    title: `Note ${id}`,
    content: 'body',
    timestamp: 1_000,
    ...overrides,
  };
}

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(RULES_PATH, 'utf8') },
  });
  // The real firestore.rules are loaded and enforced, so this exercises the production rules as
  // well as the sync logic — an unauthenticated client fails on `allow read: if isOwner(userId)`.
  // `sub` is derived from the uid by authenticatedContext, so it must not be passed explicitly.
  hoisted.db = testEnv.authenticatedContext(USER).firestore() as unknown as Firestore;
});

afterAll(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  useTombstoneStore.getState().reset();
  await testEnv.clearFirestore();
});

describe('notes sync against a real Firestore', () => {
  it('round-trips a note through the mapper and the security rules', async () => {
    await upsertNote(USER, note('1', { title: 'Trip', content: 'Pack bags' }));

    const remote = await fetchRemoteNotes(USER);

    expect(remote).toHaveLength(1);
    expect(remote[0].title).toBe('Trip');
    expect(remote[0].content).toBe('Pack bags');
    // Written as a server timestamp, so it must come back resolved. Conflict resolution treats a
    // null here as "unconfirmed" and lets remote win, so a regression is silently destructive.
    expect(remote[0].serverUpdatedAt).toBeTypeOf('number');
  });

  it('drops a local note that disappeared from the cloud, and tombstones it', async () => {
    // Two notes deliberately: deleting the *only* note empties the cloud, which trips the
    // suspicious-empty guard instead — a real deletion of everything propagates through
    // tombstones, not through absence. Delete-on-absence is only reachable while other notes
    // remain, so this is the shape that actually exercises it.
    const kept = note('1');
    const removed = note('2');
    await upsertNote(USER, kept);
    await upsertNote(USER, removed);
    const first = await syncNotesWithCloud(USER, [kept, removed]);
    expect(first.remoteIds.sort()).toEqual(['1', '2']);

    // Deleted on another device: gone from the cloud while still present locally.
    await deleteDoc(doc(hoisted.db!, 'users', USER, 'notes', '2'));

    const second = await syncNotesWithCloud(USER, [kept, removed], new Set(first.remoteIds));

    expect(second.merged.map((n) => n.id)).toContain('1');
    expect(second.merged.map((n) => n.id)).not.toContain('2');
    expect(useTombstoneStore.getState().isDeleted('2')).toBe(true);
    // A cloud tombstone so the other devices learn about it too.
    const tombstone = await getDoc(doc(hoisted.db!, 'users', USER, 'tombstones', '2'));
    expect(tombstone.exists()).toBe(true);
  });

  it('re-uploads a local note the cloud has never seen instead of deleting it', async () => {
    const local = note('9');

    // No previously-known ids: absence means "never synced", not "deleted elsewhere".
    const result = await syncNotesWithCloud(USER, [local]);

    expect(result.merged.map((n) => n.id)).toContain('9');
    expect(useTombstoneStore.getState().isDeleted('9')).toBe(false);
    expect((await fetchRemoteNotes(USER)).map((n) => n.id)).toContain('9');
  });

  it('refuses to sync when the cloud comes back empty but notes were expected', async () => {
    const local = note('1');

    // Nothing in the cloud, yet we know ids were there — a failed read, not a mass deletion
    // (a real one leaves tombstones). Deleting local copies here would be unrecoverable.
    await expect(syncNotesWithCloud(USER, [local], new Set(['1', '2']))).rejects.toThrow(
      /refusing to delete local copies/i,
    );

    expect(useTombstoneStore.getState().isDeleted('1')).toBe(false);
  });

  it('does not resurrect a note that was deleted, even if a save is still in flight', async () => {
    const local = note('1');
    await upsertNote(USER, local);
    await deleteNote(USER, '1');

    // A save that raced the delete. upsertNote reads the tombstone first precisely for this.
    await upsertNote(USER, local);

    expect((await fetchRemoteNotes(USER)).map((n) => n.id)).not.toContain('1');
  });

  it('lets a confirmed remote edit win over an unconfirmed local one', async () => {
    const local = note('1', { title: 'Local edit' });
    await upsertNote(USER, { ...local, title: 'Remote edit' });

    // The local copy has never been confirmed by the server, so the remote revision wins.
    const result = await syncNotesWithCloud(USER, [{ ...local, serverUpdatedAt: null }]);

    expect(result.merged.find((n) => n.id === '1')?.title).toBe('Remote edit');
  });

  it('uploads a live edit of a note that already has a server stamp', async () => {
    await upsertNote(USER, note('1', { title: 'First', timestamp: 1_000 }));
    const remote = (await fetchRemoteNotes(USER))[0];
    expect(remote.serverUpdatedAt).toBeTypeOf('number');

    await upsertNote(USER, {
      ...remote,
      title: 'Edited',
      timestamp: Date.now(),
    });

    const after = await fetchRemoteNotes(USER);
    expect(after).toHaveLength(1);
    expect(after[0].title).toBe('Edited');
  });

  it('still writes a live edit whose editor copy never received the resolved stamp', async () => {
    await upsertNote(USER, note('1', { title: 'First', timestamp: 1_000 }));
    const remote = (await fetchRemoteNotes(USER))[0];
    expect(remote.serverUpdatedAt).toBeTypeOf('number');

    await upsertNote(USER, {
      ...note('1', {
        title: 'Edited',
        timestamp: Date.now(),
        serverUpdatedAt: null,
      }),
    });

    const after = await fetchRemoteNotes(USER);
    expect(after).toHaveLength(1);
    expect(after[0].title).toBe('Edited');
  });

  it('does not overwrite a strictly newer remote revision with a stale live save', async () => {
    await upsertNote(USER, note('1', { title: 'First', timestamp: 1_000 }));
    const remote = (await fetchRemoteNotes(USER))[0];
    expect(remote.serverUpdatedAt).toBeTypeOf('number');

    await upsertNote(USER, {
      ...note('1', {
        title: 'Stale local',
        timestamp: 1,
        serverUpdatedAt: remote.serverUpdatedAt,
      }),
    });

    const after = await fetchRemoteNotes(USER);
    expect(after).toHaveLength(1);
    expect(after[0].title).toBe('First');
  });
});
