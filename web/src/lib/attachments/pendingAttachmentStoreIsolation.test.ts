import { beforeEach, describe, expect, it } from 'vitest';
import { clearLocalUserData, clearLocalUserDataForAccountSwitch } from '@/lib/bootstrap';
import {
  clearPendingAttachmentStore,
  getPendingAttachmentBlob,
  peekPendingAttachment,
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
});
