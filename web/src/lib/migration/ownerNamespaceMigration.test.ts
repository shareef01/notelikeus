import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putNote, setOwnerMeta } from '@/lib/local/notesLocalRepository';
import { migrateOwnerNamespace } from '@/lib/migration/ownerNamespaceMigration';
import { createEmptyNote } from '@/types/note';

describe('migrateOwnerNamespace', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  it('copies notes and meta from firebase uid to supabase uuid', async () => {
    const firebaseUid = 'firebaseUid28charsabcdefghij';
    const supabaseUid = '11111111-2222-4333-8444-555555555555';
    const note = createEmptyNote({
      id: '1',
      localId: 1,
      title: 'Migrated',
      content: 'Body',
      timestamp: 1,
    });

    await putNote(firebaseUid, note);
    await setOwnerMeta(firebaseUid, { firebaseHydrated: true, hydratedAt: 42 });

    const migrated = await migrateOwnerNamespace(firebaseUid, supabaseUid);
    expect(migrated).toBe(true);

    expect(await listNotes(firebaseUid)).toEqual([]);
    const targetNotes = await listNotes(supabaseUid);
    expect(targetNotes).toHaveLength(1);
    expect(targetNotes[0]?.title).toBe('Migrated');
  });
});
