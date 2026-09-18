import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearLocalUserData, clearLocalUserDataForAccountSwitch } from '@/lib/bootstrap';
import {
  clearPendingAttachmentStore,
  getPendingAttachmentBlob,
  peekPendingAttachment,
  releasePendingAttachment,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { useAuthStore } from '@/store/authStore';

describe('pendingAttachmentStore isolation', () => {
  beforeEach(() => {
    clearPendingAttachmentStore();
    useAuthStore.setState({ user: null, isReady: true, guestMode: false });
  });

  it('purges in-memory pending attachment store on normal sign-out', async () => {
    useAuthStore.setState({
      user: { uid: 'user-a', email: 'a@example.com', displayName: null },
      isReady: true,
      guestMode: false,
    });

    const blob = new Blob(['secret photo data'], { type: 'image/png' });
    const saved = await storePendingAttachment('att-1', blob, 'image/png', 'note-1', 'user-a');
    expect(saved).toBe(true);
    expect(peekPendingAttachment('att-1')).toBeDefined();

    // Sign out
    clearLocalUserData();

    // Staged blob must be gone from memory
    expect(peekPendingAttachment('att-1')).toBeUndefined();
    expect(await getPendingAttachmentBlob('att-1', 'note-1', 'user-b')).toBeUndefined();
  });

  it('purges in-memory pending attachment store on account switch', async () => {
    useAuthStore.setState({
      user: { uid: 'user-a', email: 'a@example.com', displayName: null },
      isReady: true,
      guestMode: false,
    });

    const blob = new Blob(['secret invoice'], { type: 'image/jpeg' });
    await storePendingAttachment('att-2', blob, 'image/jpeg', 'note-2', 'user-a');
    expect(peekPendingAttachment('att-2')).toBeDefined();

    // Account switch
    await clearLocalUserDataForAccountSwitch('user-a');

    expect(peekPendingAttachment('att-2')).toBeUndefined();
    expect(await getPendingAttachmentBlob('att-2', 'note-2', 'user-b')).toBeUndefined();
  });

  it('refuses to return pending attachment when requesting owner does not match', async () => {
    const blob = new Blob(['secret drawing'], { type: 'image/png' });
    await storePendingAttachment('att-3', blob, 'image/png', 'note-3', 'user-a');

    // Matching owner succeeds
    expect(peekPendingAttachment('att-3', 'user-a')).toBeDefined();

    // Mismatched owner fails
    expect(peekPendingAttachment('att-3', 'user-b')).toBeUndefined();
    expect(await getPendingAttachmentBlob('att-3', 'note-3', 'user-b')).toBeUndefined();
  });

  it('colliding attachment IDs across two owners never leak and resolve to their respective owners', async () => {
    // Owner A stages same-att-id before note ID allocation (noteId = '')
    await storePendingAttachment('same-att-id', new Blob(['user a image']), 'image/png', undefined, 'user-a');

    // Owner B stages same-att-id with noteId = 'note-42'
    await storePendingAttachment('same-att-id', new Blob(['user b image']), 'image/png', 'note-42', 'user-b');

    // Also store an attachment unique to Owner A
    await storePendingAttachment('only-user-a', new Blob(['only a']), 'image/png', undefined, 'user-a');

    // Clear process memory to force IndexedDB recovery
    clearPendingAttachmentStore();

    // User B requests same-att-id: must receive User B's blob, never User A's
    const resB = await getPendingAttachmentBlob('same-att-id', 'note-42', 'user-b');
    expect(resB).toBeDefined();
    expect(await (resB!.blob as Blob).text()).toBe('user b image');
    expect(await (resB!.blob as Blob).text()).not.toBe('user a image');

    // User A requests same-att-id: must receive User A's blob, never User B's
    const resA = await getPendingAttachmentBlob('same-att-id', 'note-allocated-a', 'user-a');
    expect(resA).toBeDefined();
    expect(await (resA!.blob as Blob).text()).toBe('user a image');
    expect(await (resA!.blob as Blob).text()).not.toBe('user b image');

    // User B attempts to query User A's unique attachment: strictly undefined
    expect(await getPendingAttachmentBlob('only-user-a', 'note-42', 'user-b')).toBeUndefined();
    expect(await getPendingAttachmentBlob('only-user-a', undefined, 'user-b')).toBeUndefined();
  });

  it('no-session lookup returns undefined and refuses to stage or leak cached entries', async () => {
    await storePendingAttachment('att-no-session', new Blob(['secret']), 'image/png', 'n1', 'user-a');

    // Set no active session
    useAuthStore.setState({ user: null, guestMode: false });

    // Staging without session fails
    expect(await storePendingAttachment('att-fail', new Blob(['fail']), 'image/png', 'n1')).toBe(false);

    // Lookups without session return undefined
    expect(peekPendingAttachment('att-no-session')).toBeUndefined();
    expect(await getPendingAttachmentBlob('att-no-session', 'n1')).toBeUndefined();
  });

  it('guest owner namespace is strictly isolated from authenticated users', async () => {
    useAuthStore.setState({ user: null, guestMode: true });

    expect(await storePendingAttachment('att-guest', new Blob(['guest image']), 'image/png', undefined)).toBe(true);

    clearPendingAttachmentStore();

    // Guest lookup works
    const guestRes = await getPendingAttachmentBlob('att-guest', 'guest-note-1');
    expect(guestRes).toBeDefined();
    expect(await (guestRes!.blob as Blob).text()).toBe('guest image');

    // User A cannot retrieve guest blob
    expect(await getPendingAttachmentBlob('att-guest', 'guest-note-1', 'user-a')).toBeUndefined();
  });

  it('account switch isolates pre-ID pending attachment staged on a new note', async () => {
    useAuthStore.setState({
      user: { uid: 'user-a', email: null, displayName: null },
      isReady: true,
      guestMode: false,
    });

    await storePendingAttachment('att-pre-id', new Blob(['a photo']), 'image/png', undefined, 'user-a');

    // Switch to User B
    await clearLocalUserDataForAccountSwitch('user-a');
    useAuthStore.setState({
      user: { uid: 'user-b', email: null, displayName: null },
      isReady: true,
      guestMode: false,
    });

    expect(peekPendingAttachment('att-pre-id')).toBeUndefined();
    expect(await getPendingAttachmentBlob('att-pre-id', 'note-1')).toBeUndefined();
  });

  it('logout and login as same user preserves pending offline data recovery', async () => {
    useAuthStore.setState({
      user: { uid: 'user-a', email: null, displayName: null },
      isReady: true,
      guestMode: false,
    });

    await storePendingAttachment('att-same-user', new Blob(['offline draft']), 'image/png', undefined, 'user-a');

    // Sign out
    clearLocalUserData();
    expect(peekPendingAttachment('att-same-user')).toBeUndefined();

    // Sign back in as User A
    useAuthStore.setState({
      user: { uid: 'user-a', email: null, displayName: null },
      isReady: true,
      guestMode: false,
    });

    const recovered = await getPendingAttachmentBlob('att-same-user', 'note-allocated-99');
    expect(recovered).toBeDefined();
    expect(await (recovered!.blob as Blob).text()).toBe('offline draft');
  });

  it('existing note exact lookup succeeds without invoking owner fallback', async () => {
    const repo = await import('@/lib/local/pendingAttachmentRepository');
    const fallbackSpy = vi.spyOn(repo, 'findPendingAttachmentForOwner');

    await storePendingAttachment('att-exact', new Blob(['exact data']), 'image/png', 'note-exact-1', 'user-a');
    clearPendingAttachmentStore();

    const res = await getPendingAttachmentBlob('att-exact', 'note-exact-1', 'user-a');
    expect(res).toBeDefined();
    expect(fallbackSpy).not.toHaveBeenCalled();

    fallbackSpy.mockRestore();
  });

  it('cleanup removes pending rows on discard, removal, and successful upload without leaving orphans', async () => {
    const repo = await import('@/lib/local/pendingAttachmentRepository');

    // Case 1: Discard on new note (staged with noteId = undefined, released with noteId = undefined)
    await storePendingAttachment('att-c1', new Blob(['c1']), 'image/png', undefined, 'user-a');
    await releasePendingAttachment('att-c1', undefined, 'user-a');
    const remainingA = (await repo.listPendingAttachmentsForOwner('user-a')).filter((r) => r.attachmentId === 'att-c1');
    expect(remainingA).toHaveLength(0);

    // Case 2: Remove after note persisted (staged with noteId = undefined, released with noteId = 'note-42')
    await storePendingAttachment('att-c2', new Blob(['c2']), 'image/png', undefined, 'user-a');
    await releasePendingAttachment('att-c2', 'note-42', 'user-a');
    const remainingB = (await repo.listPendingAttachmentsForOwner('user-a')).filter((r) => r.attachmentId === 'att-c2');
    expect(remainingB).toHaveLength(0);

    // Case 3: Successful upload release (staged with noteId = undefined, released with noteId = 'note-42')
    await storePendingAttachment('att-c3', new Blob(['c3']), 'image/png', undefined, 'user-a');
    await releasePendingAttachment('att-c3', 'note-42', 'user-a');
    const remainingC = (await repo.listPendingAttachmentsForOwner('user-a')).filter((r) => r.attachmentId === 'att-c3');
    expect(remainingC).toHaveLength(0);
  });

  it('crypto AAD binds ciphertext to ownerId and attachmentId', async () => {
    const { getAttachmentCryptoKey } = await import('@/lib/crypto/attachmentCryptoKey');
    const { attachmentAad, openAttachmentBytes, sealAttachmentBytes } = await import('@/lib/crypto/attachmentBytesCodec');
    const key = await getAttachmentCryptoKey();
    expect(key).toBeDefined();

    const plain = new TextEncoder().encode('top secret payload');
    const aadA = attachmentAad('user-a', 'att-aad');
    const sealed = await sealAttachmentBytes(key!, plain, aadA);

    // Decrypting with matching owner AAD succeeds regardless of note ID
    const opened = await openAttachmentBytes(key!, sealed, aadA);
    expect(new TextDecoder().decode(opened)).toBe('top secret payload');

    // Decrypting with mismatched owner AAD fails
    const aadB = attachmentAad('user-b', 'att-aad');
    await expect(openAttachmentBytes(key!, sealed, aadB)).rejects.toThrow();
  });
});
