import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import {
  allocateLocalNoteIdForOwner,
  reserveLocalNoteIdRange,
} from '@/lib/local/localNoteIdAllocator';
import { putNote } from '@/lib/local/notesLocalRepository';
import { createEmptyNote } from '@/types/note';

describe('localNoteIdAllocator', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  it('issues unique ids for concurrent allocations on the same owner', async () => {
    const [a, b, c, d] = await Promise.all([
      allocateLocalNoteIdForOwner('owner-a'),
      allocateLocalNoteIdForOwner('owner-a'),
      allocateLocalNoteIdForOwner('owner-a'),
      allocateLocalNoteIdForOwner('owner-a'),
    ]);
    const ids = new Set([a, b, c, d]);
    expect(ids.size).toBe(4);
    for (const id of ids) {
      expect(id).toBeLessThanOrEqual(Number.MAX_SAFE_INTEGER);
      expect(id).toBeGreaterThan(0);
    }
  });

  it('does not collide across owners', async () => {
    const a = await allocateLocalNoteIdForOwner('owner-a');
    const b = await allocateLocalNoteIdForOwner('owner-b');
    expect(a).toBeGreaterThan(0);
    expect(b).toBeGreaterThan(0);
  });

  it('stays strictly above existing note localIds', async () => {
    await putNote(
      'owner-a',
      createEmptyNote({ id: '900', localId: 900, title: 'Existing' }),
    );
    const next = await allocateLocalNoteIdForOwner('owner-a');
    expect(next).toBeGreaterThan(900);
  });

  it('reserves a contiguous range for backup import', async () => {
    const first = await reserveLocalNoteIdRange('owner-a', 5, 10);
    expect(first).toBeGreaterThan(10);
    const next = await allocateLocalNoteIdForOwner('owner-a');
    expect(next).toBe(first + 5);
  });

  it('never exceeds MAX_SAFE_INTEGER', async () => {
    const id = await allocateLocalNoteIdForOwner('owner-a', Number.MAX_SAFE_INTEGER - 2);
    expect(id).toBeLessThanOrEqual(Number.MAX_SAFE_INTEGER);
  });
});
