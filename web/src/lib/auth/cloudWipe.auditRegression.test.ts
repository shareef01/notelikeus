import { afterEach, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ rpc: vi.fn(), session: vi.fn(), signOut: vi.fn(), ensure: vi.fn(), clear: vi.fn() }));
vi.mock('@/lib/supabase/client', () => ({ getSupabaseClient: () => ({ rpc: mocks.rpc, auth: { getSession: mocks.session, signOut: mocks.signOut } }) }));
vi.mock('@/lib/supabase/supabaseSyncEngine', () => ({ ensureSupabaseAuthenticated: mocks.ensure }));
vi.mock('@/lib/local/notesLocalRepository', () => ({ clearOwner: mocks.clear }));
vi.mock('@/lib/local/pendingAttachmentRepository', () => ({ clearPendingAttachmentsForOwner: vi.fn() }));
vi.mock('@/lib/bootstrap', () => ({ clearLocalUserData: vi.fn() }));
vi.mock('@/lib/notes/notesSyncService', () => ({ stopNotesRealtimeSync: vi.fn() }));
import { signOutAccount } from '@/lib/auth/accountAuth';
import { useAuthStore } from '@/store/authStore';

afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });
// Regression for account ownership across asynchronous operations.
it('AUD-E05 refuses a wipe after the account switches during token capture', async () => {
  useAuthStore.getState().setUser({ uid: 'A', email: null, displayName: null });
  let finish!: (value: unknown) => void;
  mocks.session.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const deletedOwners: string[] = [];
  mocks.rpc.mockImplementation(async (name: string) => {
    if (name === 'delete_all_user_cloud_data') deletedOwners.push(useAuthStore.getState().user!.uid);
    return { data: { status: 'applied', attachment_object_keys: [] }, error: null };
  });
  mocks.signOut.mockResolvedValue({ error: null });
  const wiping = signOutAccount({ deleteCloudData: true });
  useAuthStore.getState().setUser({ uid: 'B', email: null, displayName: null });
  const rejected = expect(wiping).rejects.toThrow('Account changed');
  finish({ data: { session: { user: { id: 'A' }, access_token: 'A-token' } }, error: null }); await rejected;
  expect(deletedOwners).toEqual([]);
  expect(mocks.clear).not.toHaveBeenCalled();
  expect(mocks.signOut).not.toHaveBeenCalled();
});
