import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GUEST_OWNER_ID, NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putNote } from '@/lib/local/notesLocalRepository';
import {
  getPendingAttachment,
  listPendingAttachmentsForOwner,
  putPendingAttachment,
} from '@/lib/local/pendingAttachmentRepository';
import { createEmptyNote } from '@/types/note';
import { useNotesStore } from '@/store/notesStore';
import { useAuthStore } from '@/store/authStore';
import { adoptGuestNotesIntoAccount } from '@/lib/local/adoptGuestNotes';
import {
  clearGuestAdoptionIntent,
  consumeGuestAdoptionIntent,
  hasGuestAdoptionIntent,
  markGuestAdoptionIntent,
  MAX_INTENT_AGE_MS,
} from '@/lib/local/guestAdoptionIntent';
import {
  signInWithGoogleSupabase,
  signInWithEmailPasswordSupabase,
  createEmailPasswordAccountSupabase,
  completeSupabaseOAuthRedirect,
  signOutSupabase,
} from '@/lib/auth/supabaseAuth';

const {
  mockSignOut,
  mockSignInWithOAuth,
  mockSignInWithPassword,
  mockSignUp,
  mockExchangeCodeForSession,
} = vi.hoisted(() => ({
  mockSignOut: vi.fn().mockResolvedValue({ error: null }),
  mockSignInWithOAuth: vi.fn().mockResolvedValue({ error: null }),
  mockSignInWithPassword: vi.fn().mockResolvedValue({ data: { user: null, session: null }, error: null }),
  mockSignUp: vi.fn().mockResolvedValue({ data: { user: null, session: null }, error: null }),
  mockExchangeCodeForSession: vi.fn().mockResolvedValue({ data: { user: null, session: null }, error: null }),
}));

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({
    auth: {
      signOut: mockSignOut,
      signInWithOAuth: mockSignInWithOAuth,
      signInWithPassword: mockSignInWithPassword,
      signUp: mockSignUp,
      exchangeCodeForSession: mockExchangeCodeForSession,
    },
  }),
  isSupabaseBackendEnabled: () => true,
}));

const USER_A = '11111111-1111-4111-8111-111111111111';
const USER_B = '22222222-2222-4222-8222-222222222222';

describe('F04: Guest to Authenticated Note Adoption & Cross-Account Privacy', () => {
  beforeEach(async () => {
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    clearGuestAdoptionIntent();
    try {
      sessionStorage.clear();
    } catch {
      // ignore
    }
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    vi.restoreAllMocks();
  });

  // Test 1: activeGuestSessionIsAdoptedOnSignIn
  it('activeGuestSessionIsAdoptedOnSignIn', async () => {
    // 1. Same current browser session, guestMode = true
    useAuthStore.getState().enterGuestMode();
    expect(useAuthStore.getState().guestMode).toBe(true);

    // 2. Create Guest Note A & pending attachment
    const guestNoteA = createEmptyNote({
      id: 'guest-note-a',
      localId: 101,
      title: 'Alice Guest Note A',
      content: 'Private Guest Thoughts',
    });
    await putNote(GUEST_OWNER_ID, guestNoteA);

    const dummyBlob = new Blob(['sample-guest-attachment'], { type: 'image/png' });
    await putPendingAttachment({
      ownerId: GUEST_OWNER_ID,
      noteId: 'guest-note-a',
      attachmentId: 'att-guest-a',
      blob: dummyBlob,
      mimeType: 'image/png',
      sizeBytes: dummyBlob.size,
      createdAt: Date.now(),
    });

    // 3. User A signs in from active guest session
    markGuestAdoptionIntent();
    useAuthStore.getState().exitGuestMode();
    useAuthStore.getState().setUser({ uid: USER_A, email: 'alice@example.com', displayName: 'Alice' });

    // Adoption runs during bootstrap
    await adoptGuestNotesIntoAccount(USER_A);

    // Expected:
    // Note A adopted to User A
    const userANotes = await listNotes(USER_A);
    expect(userANotes).toHaveLength(1);
    expect(userANotes[0]?.id).toBe('guest-note-a');
    expect(userANotes[0]?.title).toBe('Alice Guest Note A');

    // Pending attachments adopted
    const userAAttachment = await getPendingAttachment(USER_A, 'guest-note-a', 'att-guest-a');
    expect(userAAttachment).not.toBeNull();
    expect(userAAttachment?.ownerId).toBe(USER_A);

    // Guest source cleaned only after success
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(0);
    expect(await listPendingAttachmentsForOwner(GUEST_OWNER_ID)).toHaveLength(0);
  });

  // Test 2: residualGuestDataIsNotAdoptedOnDirectSignIn
  it('residualGuestDataIsNotAdoptedOnDirectSignIn', async () => {
    // Setup: IndexedDB contains __guest__ Note A (left from a previous user/session)
    const guestNoteA = createEmptyNote({
      id: 'guest-note-alice',
      localId: 102,
      title: 'Alice Residual Note',
      content: 'Super Secret Guest Note',
    });
    await putNote(GUEST_OWNER_ID, guestNoteA);

    // Person Bob opens browser, guestMode = false
    expect(useAuthStore.getState().guestMode).toBe(false);
    expect(hasGuestAdoptionIntent()).toBe(false);

    // Authenticate User B directly (Bob)
    useAuthStore.getState().setUser({ uid: USER_B, email: 'bob@example.com', displayName: 'Bob' });

    // Sync initialization attempts adoption check
    await adoptGuestNotesIntoAccount(USER_B);

    // Expected:
    // User B namespace DOES NOT contain Alice's Note A!
    const userBNotes = await listNotes(USER_B);
    expect(userBNotes).toHaveLength(0);

    // Guest source remains intact
    const guestNotes = await listNotes(GUEST_OWNER_ID);
    expect(guestNotes).toHaveLength(1);
    expect(guestNotes[0]?.id).toBe('guest-note-alice');
    expect(guestNotes[0]?.title).toBe('Alice Residual Note');
  });

  // Test 3: residualGuestAttachmentsAreNotAdoptedOnDirectSignIn
  it('residualGuestAttachmentsAreNotAdoptedOnDirectSignIn', async () => {
    // Setup: Pending attachment under __guest__
    const guestNoteA = createEmptyNote({ id: 'guest-note-alice', localId: 103, title: 'Alice Note' });
    await putNote(GUEST_OWNER_ID, guestNoteA);

    const dummyBlob = new Blob(['alice-private-photo'], { type: 'image/jpeg' });
    await putPendingAttachment({
      ownerId: GUEST_OWNER_ID,
      noteId: 'guest-note-alice',
      attachmentId: 'att-alice-secret',
      blob: dummyBlob,
      mimeType: 'image/jpeg',
      sizeBytes: dummyBlob.size,
      createdAt: Date.now(),
    });

    // Bob signs in directly without entering guest mode
    expect(useAuthStore.getState().guestMode).toBe(false);
    await adoptGuestNotesIntoAccount(USER_B);

    // Expected: No attachment copied to User B
    const userBAttachment = await getPendingAttachment(
      USER_B,
      'guest-note-alice',
      'att-alice-secret',
    );
    expect(userBAttachment).toBeNull();

    // Guest attachment remains intact
    const guestPending = await listPendingAttachmentsForOwner(GUEST_OWNER_ID);
    expect(guestPending).toHaveLength(1);
    expect(guestPending[0]?.attachmentId).toBe('att-alice-secret');
  });

  // Test 4: accountSwitchDoesNotConsumeGuestNamespace
  it('accountSwitchDoesNotConsumeGuestNamespace', async () => {
    // Setup: residual __guest__ content already present
    const guestNote = createEmptyNote({ id: 'residual-note', localId: 104, title: 'Residual Guest Note' });
    await putNote(GUEST_OWNER_ID, guestNote);

    // Sequence:
    // 1. User A authenticated
    useAuthStore.getState().setUser({ uid: USER_A, email: 'alice@example.com', displayName: 'Alice' });
    await adoptGuestNotesIntoAccount(USER_A);
    expect(await listNotes(USER_A)).toHaveLength(0);

    // 2. Sign out
    await signOutSupabase();

    // 3. User B authenticated
    useAuthStore.getState().setUser({ uid: USER_B, email: 'bob@example.com', displayName: 'Bob' });
    await adoptGuestNotesIntoAccount(USER_B);

    // Expected:
    // Neither authenticated account silently consumed guest content
    expect(await listNotes(USER_A)).toHaveLength(0);
    expect(await listNotes(USER_B)).toHaveLength(0);
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
  });

  // Test 5: guestAdoptionDoesNotOverwriteExistingAuthenticatedNoteWithSameId (UUID Collision)
  it('guestAdoptionDoesNotOverwriteExistingAuthenticatedNoteWithSameId', async () => {
    const COLLIDING_UUID = 'uuid-collision-1111';

    // Authenticated account already has Note X with id = UUID-1
    const authenticatedNote = createEmptyNote({
      id: COLLIDING_UUID,
      localId: 201,
      title: 'Authenticated Important Note',
      content: 'Cloud Authenticated Content - DO NOT OVERWRITE',
      timestamp: 2000,
    });
    await putNote(USER_A, authenticatedNote);

    // Guest namespace contains stale/imported Note Y with same id = UUID-1
    const collidingGuestNote = createEmptyNote({
      id: COLLIDING_UUID,
      localId: 202,
      title: 'Guest Stale Note',
      content: 'Different Guest Content',
      timestamp: 1000,
    });
    await putNote(GUEST_OWNER_ID, collidingGuestNote);

    // Also a non-colliding guest note
    const normalGuestNote = createEmptyNote({
      id: 'normal-guest-note',
      localId: 203,
      title: 'Normal Guest Note',
      content: 'Normal content',
    });
    await putNote(GUEST_OWNER_ID, normalGuestNote);

    markGuestAdoptionIntent();
    await adoptGuestNotesIntoAccount(USER_A);

    // Expected Safe Behavior:
    // 1. Authenticated note is PRESERVED intact and NEVER overwritten!
    const userNotes = await listNotes(USER_A);
    const adoptedColliding = userNotes.find((n) => n.id === COLLIDING_UUID);
    expect(adoptedColliding?.title).toBe('Authenticated Important Note');
    expect(adoptedColliding?.content).toBe('Cloud Authenticated Content - DO NOT OVERWRITE');

    // 2. Non-colliding note is adopted
    expect(userNotes.find((n) => n.id === 'normal-guest-note')).toBeDefined();

    // 3. Colliding note is retained in guest source for manual recovery
    const guestNotes = await listNotes(GUEST_OWNER_ID);
    expect(guestNotes).toHaveLength(1);
    expect(guestNotes[0]?.id).toBe(COLLIDING_UUID);
    expect(guestNotes[0]?.title).toBe('Guest Stale Note');
  });

  // Test 6: attachmentFailureOrderingPreservesGuestSource
  it('attachmentFailureOrderingPreservesGuestSource', async () => {
    const guestNote = createEmptyNote({ id: 'note-with-two-attachments', localId: 301, title: 'Two Attachments' });
    await putNote(GUEST_OWNER_ID, guestNote);

    const blob1 = new Blob(['att-1'], { type: 'text/plain' });
    const blob2 = new Blob(['att-2'], { type: 'text/plain' });

    await putPendingAttachment({
      ownerId: GUEST_OWNER_ID,
      noteId: 'note-with-two-attachments',
      attachmentId: 'att-1',
      blob: blob1,
      mimeType: 'text/plain',
      sizeBytes: blob1.size,
      createdAt: Date.now(),
    });
    await putPendingAttachment({
      ownerId: GUEST_OWNER_ID,
      noteId: 'note-with-two-attachments',
      attachmentId: 'att-2',
      blob: blob2,
      mimeType: 'text/plain',
      sizeBytes: blob2.size,
      createdAt: Date.now(),
    });

    // Mock failure on attachment 2 write
    const attRepo = await import('@/lib/local/pendingAttachmentRepository');
    const originalPutAttachment = attRepo.putPendingAttachment;
    vi.spyOn(attRepo, 'putPendingAttachment').mockImplementation(async (record) => {
      if (record.attachmentId === 'att-2' && record.ownerId === USER_A) {
        throw new Error('Attachment 2 write failed');
      }
      return originalPutAttachment(record);
    });

    markGuestAdoptionIntent();
    await expect(adoptGuestNotesIntoAccount(USER_A)).rejects.toThrow('Attachment 2 write failed');

    // Expected Invariants:
    // 1. Guest source notes remain
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
    // 2. Guest pending attachments remain
    const guestAtts = await listPendingAttachmentsForOwner(GUEST_OWNER_ID);
    expect(guestAtts).toHaveLength(2);
    // 3. No source attachment is deleted before all required copies succeed
    expect(await getPendingAttachment(GUEST_OWNER_ID, 'note-with-two-attachments', 'att-1')).not.toBeNull();
    expect(await getPendingAttachment(GUEST_OWNER_ID, 'note-with-two-attachments', 'att-2')).not.toBeNull();

    // Un-mock failure and retry
    vi.restoreAllMocks();
    markGuestAdoptionIntent();
    await adoptGuestNotesIntoAccount(USER_A);

    // Expected: complete adoption with no duplicates/data loss
    const userNotes = await listNotes(USER_A);
    expect(userNotes).toHaveLength(1);
    expect(userNotes[0]?.id).toBe('note-with-two-attachments');

    expect(await getPendingAttachment(USER_A, 'note-with-two-attachments', 'att-1')).not.toBeNull();
    expect(await getPendingAttachment(USER_A, 'note-with-two-attachments', 'att-2')).not.toBeNull();

    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(0);
    expect(await listPendingAttachmentsForOwner(GUEST_OWNER_ID)).toHaveLength(0);
  });

  // Test 7: cleanupFailurePreservesAuthenticatedData
  it('cleanupFailurePreservesAuthenticatedData', async () => {
    const guestNote = createEmptyNote({ id: 'guest-note-cleanup-fail', localId: 401, title: 'Cleanup Fail Test' });
    await putNote(GUEST_OWNER_ID, guestNote);

    // Mock cleanup deleteNote failure
    const notesRepo = await import('@/lib/local/notesLocalRepository');
    const originalDeleteNote = notesRepo.deleteNote;
    vi.spyOn(notesRepo, 'deleteNote').mockImplementation(async (ownerId, noteId) => {
      if (ownerId === GUEST_OWNER_ID) {
        throw new Error('Storage lock during cleanup');
      }
      return originalDeleteNote(ownerId, noteId);
    });

    markGuestAdoptionIntent();
    // Function handles cleanup failure gracefully with warning
    await adoptGuestNotesIntoAccount(USER_A);

    // Expected:
    // Destination copy is successful and authenticated data is verified and NOT lost
    const userNotes = await listNotes(USER_A);
    expect(userNotes).toHaveLength(1);
    expect(userNotes[0]?.id).toBe('guest-note-cleanup-fail');

    // Source note remains as duplicate due to cleanup failure, avoiding data destruction
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);

    // Retry cleanup when storage recovers
    vi.restoreAllMocks();
    markGuestAdoptionIntent();
    await adoptGuestNotesIntoAccount(USER_A);

    // Authenticated data remains intact (no duplicates created)
    expect(await listNotes(USER_A)).toHaveLength(1);
    // Source successfully cleaned up
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(0);
  });
});

describe('F04 Stale-Intent Lifecycle & Failed/Cancelled Auth Invalidation', () => {
  beforeEach(async () => {
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    clearGuestAdoptionIntent();
    try {
      sessionStorage.clear();
    } catch {
      // ignore
    }
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    vi.restoreAllMocks();
  });

  // Test A ÔÇö OAuth cancelled
  it('oauthCancelledClearsAdoptionIntentAndPreservesGuestData', async () => {
    useAuthStore.getState().enterGuestMode();
    const guestNote = createEmptyNote({
      id: 'guest-note-oauth-cancel',
      localId: 501,
      title: 'OAuth Cancel Note',
    });
    await putNote(GUEST_OWNER_ID, guestNote);

    mockSignInWithOAuth.mockResolvedValueOnce({
      error: new Error('User closed popup / cancelled OAuth flow'),
    });

    await expect(signInWithGoogleSupabase()).rejects.toThrow('User closed popup');

    // Expected:
    // 1. Guest Note remains intact under __guest__
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
    // 2. Adoption intent is cleared and not armed
    expect(hasGuestAdoptionIntent()).toBe(false);
    expect(consumeGuestAdoptionIntent()).toBe(false);
    expect(sessionStorage.getItem('notelikeus-pending-guest-adoption')).toBeNull();
  });

  // Test B ÔÇö OAuth fails, later unrelated direct login
  it('oauthFailureDoesNotAllowLaterUnrelatedLoginToAdoptGuestData', async () => {
    // 1. Alice uses Guest Mode
    useAuthStore.getState().enterGuestMode();
    const aliceGuestNote = createEmptyNote({
      id: 'alice-secret-note',
      localId: 502,
      title: 'Alice Private Thoughts',
    });
    await putNote(GUEST_OWNER_ID, aliceGuestNote);

    // 2. Alice starts OAuth which fails on provider redirect callback
    markGuestAdoptionIntent('oauth');
    // Simulate browser redirect back with error parameter in URL
    window.history.replaceState(
      {},
      '',
      '/app?error=access_denied&error_description=Consent+denied',
    );

    await expect(completeSupabaseOAuthRedirect()).rejects.toThrow('access_denied');

    // Verify intent was cleared immediately on callback error
    expect(hasGuestAdoptionIntent()).toBe(false);
    expect(sessionStorage.getItem('notelikeus-pending-guest-adoption')).toBeNull();

    // Restore clean URL
    window.history.replaceState({}, '', '/app');

    // 3. Later in SAME TAB: Person Bob signs in directly with email/password
    useAuthStore.getState().reset();
    expect(useAuthStore.getState().guestMode).toBe(false);
    useAuthStore.getState().setUser({ uid: USER_B, email: 'bob@example.com', displayName: 'Bob' });

    // Sync attempts bootstrap
    await adoptGuestNotesIntoAccount(USER_B);

    // Expected: Bob MUST NOT adopt Alice's Guest Note!
    expect(await listNotes(USER_B)).toHaveLength(0);
    // Alice's guest note remains intact under __guest__
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
    expect((await listNotes(GUEST_OWNER_ID))[0]?.title).toBe('Alice Private Thoughts');
  });

  // Test C ÔÇö Email/password login failure
  it('passwordLoginFailureClearsIntentAndPreventsLaterAdoption', async () => {
    // 1. Guest session with note
    useAuthStore.getState().enterGuestMode();
    const guestNote = createEmptyNote({
      id: 'guest-note-pw-fail',
      localId: 503,
      title: 'Password Fail Note',
    });
    await putNote(GUEST_OWNER_ID, guestNote);

    // 2. Password login attempt fails (e.g. invalid credentials)
    mockSignInWithPassword.mockResolvedValueOnce({
      data: { user: null, session: null },
      error: new Error('Invalid login credentials'),
    });

    await expect(
      signInWithEmailPasswordSupabase('alice@example.com', 'wrongpassword'),
    ).rejects.toThrow('Invalid login credentials');

    // Expected: failed auth leaves NO adoption intent
    expect(hasGuestAdoptionIntent()).toBe(false);
    expect(consumeGuestAdoptionIntent()).toBe(false);
    expect(sessionStorage.getItem('notelikeus-pending-guest-adoption')).toBeNull();

    // 3. Later unrelated direct login
    useAuthStore.getState().reset();
    useAuthStore.getState().setUser({ uid: USER_B, email: 'bob@example.com', displayName: 'Bob' });

    await adoptGuestNotesIntoAccount(USER_B);

    // User B receives 0 guest notes
    expect(await listNotes(USER_B)).toHaveLength(0);
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
  });

  // Test D ÔÇö Signup failure
  it('signupFailureClearsIntentAndPreventsIndefiniteArming', async () => {
    useAuthStore.getState().enterGuestMode();
    const guestNote = createEmptyNote({
      id: 'guest-note-signup-fail',
      localId: 504,
      title: 'Signup Fail Note',
    });
    await putNote(GUEST_OWNER_ID, guestNote);

    mockSignUp.mockResolvedValueOnce({
      data: { user: null, session: null },
      error: new Error('Password too weak'),
    });

    await expect(
      createEmailPasswordAccountSupabase('newuser@example.com', '123'),
    ).rejects.toThrow('Password too weak');

    // Expected: intent does not remain armed
    expect(hasGuestAdoptionIntent()).toBe(false);
    expect(sessionStorage.getItem('notelikeus-pending-guest-adoption')).toBeNull();

    // Later login has no intent
    useAuthStore.getState().reset();
    useAuthStore.getState().setUser({ uid: USER_B, email: 'bob@example.com', displayName: 'Bob' });
    await adoptGuestNotesIntoAccount(USER_B);

    expect(await listNotes(USER_B)).toHaveLength(0);
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
  });

  // Test E ÔÇö Successful auth consumes intent exactly once
  it('successfulAuthConsumesIntentExactlyOnce', async () => {
    useAuthStore.getState().enterGuestMode();
    const guestNote = createEmptyNote({
      id: 'guest-note-once',
      localId: 505,
      title: 'Adopt Once Note',
    });
    await putNote(GUEST_OWNER_ID, guestNote);

    markGuestAdoptionIntent('auth-transition');
    expect(hasGuestAdoptionIntent()).toBe(true);

    // 1. First adoption succeeds
    await adoptGuestNotesIntoAccount(USER_A);
    expect(await listNotes(USER_A)).toHaveLength(1);
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(0);

    // Intent is consumed atomically
    expect(hasGuestAdoptionIntent()).toBe(false);
    expect(consumeGuestAdoptionIntent()).toBe(false);

    // 2. Later sync reinitialization
    await adoptGuestNotesIntoAccount(USER_A);
    expect(await listNotes(USER_A)).toHaveLength(1);

    // 3. User logs out and logs back in to same account
    await signOutSupabase();
    expect(hasGuestAdoptionIntent()).toBe(false);

    // Simulate another note left under __guest__ (e.g. from an offline tab)
    const strayNote = createEmptyNote({
      id: 'stray-note',
      localId: 506,
      title: 'Stray Offline Note',
    });
    await putNote(GUEST_OWNER_ID, strayNote);

    // Log in again directly
    useAuthStore.getState().setUser({ uid: USER_A, email: 'alice@example.com', displayName: 'Alice' });
    await adoptGuestNotesIntoAccount(USER_A);

    // Stray note is NOT adopted!
    const userNotes = await listNotes(USER_A);
    expect(userNotes).toHaveLength(1);
    expect(userNotes[0]?.id).toBe('guest-note-once');
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
  });

  // Test F ÔÇö Expired intent (> 10 minutes) fails closed
  it('expiredIntentFailsClosedAndRejectsAdoption', async () => {
    const ELEVEN_MINUTES_AGO = Date.now() - (MAX_INTENT_AGE_MS + 60_000);
    const staleIntent = {
      nonce: 'stale-nonce-1234',
      startedAt: ELEVEN_MINUTES_AGO,
      flow: 'oauth',
    };

    sessionStorage.setItem('notelikeus-pending-guest-adoption', JSON.stringify(staleIntent));

    const guestNote = createEmptyNote({
      id: 'abandoned-guest-note',
      localId: 507,
      title: 'Abandoned Yesterday',
    });
    await putNote(GUEST_OWNER_ID, guestNote);

    // Verify parser rejects expired timestamp
    expect(hasGuestAdoptionIntent()).toBe(false);
    expect(consumeGuestAdoptionIntent()).toBe(false);

    // Direct sign-in does not adopt
    await adoptGuestNotesIntoAccount(USER_B);
    expect(await listNotes(USER_B)).toHaveLength(0);
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
  });
});
