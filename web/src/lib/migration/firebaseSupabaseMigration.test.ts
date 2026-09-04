import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { firebaseMocks, supabaseMocks, linkMocks, importMocks } = vi.hoisted(() => ({
  firebaseMocks: { currentUid: null as string | null },
  supabaseMocks: { enabled: true },
  linkMocks: {
    linkFirebaseUidOnServer: vi.fn().mockResolvedValue(undefined),
    fetchLinkedFirebaseUidFromServer: vi.fn().mockResolvedValue(null),
    linkVerifiedFirebaseUid: vi.fn().mockResolvedValue(false),
  },
  importMocks: {
    supabaseCloudIsEmpty: vi.fn().mockResolvedValue(false),
    importFirebaseCloudToSupabase: vi.fn().mockResolvedValue({
      notesImported: 0,
      tombstonesImported: 0,
    }),
  },
}));

vi.mock('firebase/auth', () => ({
  getAuth: () => ({
    currentUser: firebaseMocks.currentUid
      ? {
          uid: firebaseMocks.currentUid,
          getIdToken: async () => `id-token-for-${firebaseMocks.currentUid}`,
        }
      : null,
  }),
}));
vi.mock('@/lib/firebase', () => ({ initFirebase: vi.fn() }));
vi.mock('@/lib/config', () => ({ isFirebaseConfigured: () => true }));
vi.mock('@/lib/supabase/client', () => ({
  isSupabaseBackendEnabled: () => supabaseMocks.enabled,
}));
vi.mock('@/lib/migration/supabaseUidLink', () => linkMocks);
vi.mock('@/lib/migration/firebaseCloudImport', () => importMocks);

import {
  FIREBASE_SUPABASE_LINK_STORAGE_KEY,
  KNOWN_FIREBASE_UID_STORAGE_KEY,
} from '@/lib/migration/accountIdentity';
import { ensureFirebaseSupabaseMigration } from '@/lib/migration/firebaseSupabaseMigration';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putNote } from '@/lib/local/notesLocalRepository';
import { LAST_MERGED_USER_STORAGE_KEY } from '@/lib/notes/lastMergedUser';
import { createEmptyNote } from '@/types/note';

const ALICE_FIREBASE_UID = 'aliceFirebaseUid28charsabcdx';
const BOB_SUPABASE_UUID = '22222222-2222-4222-8222-222222222222';
const ALICE_SUPABASE_UUID = '11111111-1111-4111-8111-111111111111';

async function seedAliceLocalNotes() {
  await putNote(
    ALICE_FIREBASE_UID,
    createEmptyNote({ id: '1', localId: 1, title: 'Alice private note', timestamp: 1 }),
  );
}

describe('ensureFirebaseSupabaseMigration — Firebase uid ownership', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    localStorage.clear();
    firebaseMocks.currentUid = null;
    supabaseMocks.enabled = true;
    linkMocks.fetchLinkedFirebaseUidFromServer.mockResolvedValue(null);
    linkMocks.linkVerifiedFirebaseUid.mockResolvedValue(false);
    importMocks.supabaseCloudIsEmpty.mockResolvedValue(false);
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  it('does not claim a Firebase uid left behind by a previous user of this browser', async () => {
    // Alice used this profile with Firebase; her breadcrumbs and notes are still on the device.
    await seedAliceLocalNotes();
    localStorage.setItem(LAST_MERGED_USER_STORAGE_KEY, ALICE_FIREBASE_UID);
    localStorage.setItem(KNOWN_FIREBASE_UID_STORAGE_KEY, ALICE_FIREBASE_UID);
    // Bob signs in to Supabase. There is no Firebase session, so nothing proves he owns the uid.
    firebaseMocks.currentUid = null;

    await ensureFirebaseSupabaseMigration(BOB_SUPABASE_UUID);

    expect(linkMocks.linkFirebaseUidOnServer).not.toHaveBeenCalled();
    expect(localStorage.getItem(FIREBASE_SUPABASE_LINK_STORAGE_KEY)).toBeNull();
    expect(await listNotes(BOB_SUPABASE_UUID)).toEqual([]);
    expect(await listNotes(ALICE_FIREBASE_UID)).toHaveLength(1);
  });

  it('claims the uid when a live Firebase session proves ownership', async () => {
    await seedAliceLocalNotes();
    localStorage.setItem(LAST_MERGED_USER_STORAGE_KEY, ALICE_FIREBASE_UID);
    firebaseMocks.currentUid = ALICE_FIREBASE_UID;

    await ensureFirebaseSupabaseMigration(ALICE_SUPABASE_UUID);

    expect(linkMocks.linkFirebaseUidOnServer).toHaveBeenCalledWith(ALICE_FIREBASE_UID);
    expect(await listNotes(ALICE_SUPABASE_UUID)).toHaveLength(1);
  });

  it('keeps honouring a mapping this Supabase account already holds server-side', async () => {
    await seedAliceLocalNotes();
    linkMocks.fetchLinkedFirebaseUidFromServer.mockResolvedValue(ALICE_FIREBASE_UID);
    firebaseMocks.currentUid = null;

    await ensureFirebaseSupabaseMigration(ALICE_SUPABASE_UUID);

    expect(linkMocks.linkFirebaseUidOnServer).toHaveBeenCalledWith(ALICE_FIREBASE_UID);
    expect(await listNotes(ALICE_SUPABASE_UUID)).toHaveLength(1);
  });

  it('prefers the live Firebase session over a stale device breadcrumb', async () => {
    localStorage.setItem(KNOWN_FIREBASE_UID_STORAGE_KEY, ALICE_FIREBASE_UID);
    firebaseMocks.currentUid = 'bobFirebaseUid28charsabcdefg';

    await ensureFirebaseSupabaseMigration(BOB_SUPABASE_UUID);

    expect(linkMocks.linkFirebaseUidOnServer).toHaveBeenCalledWith('bobFirebaseUid28charsabcdefg');
  });

  it('does not import Firebase cloud notes without a session for that uid', async () => {
    linkMocks.fetchLinkedFirebaseUidFromServer.mockResolvedValue(ALICE_FIREBASE_UID);
    importMocks.supabaseCloudIsEmpty.mockResolvedValue(true);
    firebaseMocks.currentUid = null;

    await ensureFirebaseSupabaseMigration(ALICE_SUPABASE_UUID);

    expect(importMocks.importFirebaseCloudToSupabase).not.toHaveBeenCalled();
  });
});

describe('ensureFirebaseSupabaseMigration — proving the claim', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    localStorage.clear();
    firebaseMocks.currentUid = null;
    supabaseMocks.enabled = true;
    linkMocks.fetchLinkedFirebaseUidFromServer.mockResolvedValue(null);
    linkMocks.linkVerifiedFirebaseUid.mockResolvedValue(false);
    importMocks.supabaseCloudIsEmpty.mockResolvedValue(false);
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  it('sends a Firebase ID token for proof when a live session can supply one', async () => {
    firebaseMocks.currentUid = ALICE_FIREBASE_UID;
    linkMocks.linkVerifiedFirebaseUid.mockResolvedValue(true);

    await ensureFirebaseSupabaseMigration(ALICE_SUPABASE_UUID);

    expect(linkMocks.linkVerifiedFirebaseUid).toHaveBeenCalledWith(
      `id-token-for-${ALICE_FIREBASE_UID}`,
    );
    // Proven, so the unverified assertion is not also written.
    expect(linkMocks.linkFirebaseUidOnServer).not.toHaveBeenCalled();
  });

  it('falls back to an unverified claim when the Worker cannot verify', async () => {
    firebaseMocks.currentUid = ALICE_FIREBASE_UID;
    linkMocks.linkVerifiedFirebaseUid.mockResolvedValue(false);

    await ensureFirebaseSupabaseMigration(ALICE_SUPABASE_UUID);

    expect(linkMocks.linkFirebaseUidOnServer).toHaveBeenCalledWith(ALICE_FIREBASE_UID);
  });

  it('falls back to an unverified claim when verification throws', async () => {
    firebaseMocks.currentUid = ALICE_FIREBASE_UID;
    linkMocks.linkVerifiedFirebaseUid.mockRejectedValue(new Error('worker down'));

    await ensureFirebaseSupabaseMigration(ALICE_SUPABASE_UUID);

    expect(linkMocks.linkFirebaseUidOnServer).toHaveBeenCalledWith(ALICE_FIREBASE_UID);
  });

  it('does not attempt proof without a live Firebase session for that uid', async () => {
    linkMocks.fetchLinkedFirebaseUidFromServer.mockResolvedValue(ALICE_FIREBASE_UID);
    firebaseMocks.currentUid = null;

    await ensureFirebaseSupabaseMigration(ALICE_SUPABASE_UUID);

    expect(linkMocks.linkVerifiedFirebaseUid).not.toHaveBeenCalled();
    expect(linkMocks.linkFirebaseUidOnServer).toHaveBeenCalledWith(ALICE_FIREBASE_UID);
  });
});
