import { beforeEach, describe, expect, it, vi } from 'vitest';

const calls: string[] = [];
const deleteAllSupabaseCloudData = vi.fn(async () => {
  calls.push('wipe-cloud');
  return 3;
});
const signOutSupabase = vi.fn(async () => {
  calls.push('sign-out');
});
const clearOwner = vi.fn(async (ownerId: string) => {
  calls.push(`clear-notes:${ownerId}`);
});
const clearPendingAttachmentsForOwner = vi.fn(async (ownerId: string) => {
  calls.push(`clear-attachments:${ownerId}`);
});

vi.mock('@/lib/supabase/deleteAllUserCloudData', () => ({
  deleteAllSupabaseCloudData: () => deleteAllSupabaseCloudData(),
}));
vi.mock('@/lib/auth/supabaseAuth', () => ({
  sendEmailSignInCodeSupabase: vi.fn(),
  verifyEmailSignInCodeSupabase: vi.fn(),
  signOutSupabase: () => signOutSupabase(),
}));
vi.mock('@/lib/local/notesLocalRepository', () => ({
  clearOwner: (ownerId: string) => clearOwner(ownerId),
}));
vi.mock('@/lib/local/pendingAttachmentRepository', () => ({
  clearPendingAttachmentsForOwner: (ownerId: string) => clearPendingAttachmentsForOwner(ownerId),
}));

import { signOutAccount } from '@/lib/auth/accountAuth';
import { useAuthStore } from '@/store/authStore';

/**
 * "Delete cloud data and sign out" must remove this browser's copy of the account too. Leaving it
 * meant the next sign-in uploaded those notes — pending edits included — straight back into the
 * cloud the user had just emptied. Android and Windows already wipe local account data on sign-out.
 */
describe('signOutAccount', () => {
  beforeEach(() => {
    calls.length = 0;
    vi.clearAllMocks();
    useAuthStore.getState().setUser({ uid: 'user-a', email: null, displayName: null });
  });

  it('wipes the cloud, then this browser’s copy of the account, then signs out', async () => {
    await signOutAccount({ deleteCloudData: true });

    expect(calls).toEqual([
      'wipe-cloud',
      'clear-notes:user-a',
      'clear-attachments:user-a',
      'sign-out',
    ]);
  });

  it('keeps everything local, and stays signed in, when the cloud wipe fails', async () => {
    deleteAllSupabaseCloudData.mockRejectedValueOnce(new Error('wipe incomplete'));

    await expect(signOutAccount({ deleteCloudData: true })).rejects.toThrow('wipe incomplete');

    expect(calls).toEqual([]);
  });

  it('keeps this browser’s copy on an ordinary sign-out, so unsynced edits survive', async () => {
    await signOutAccount();

    expect(calls).toEqual(['sign-out']);
  });
});
