import { afterEach, describe, expect, it, vi } from 'vitest';
import { deleteAllSupabaseCloudData } from '@/lib/supabase/deleteAllUserCloudData';

const rpc = vi.fn();
const getSession = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({
    rpc,
    auth: { getSession },
  }),
}));

vi.mock('@/lib/supabase/supabaseSyncEngine', () => ({
  ensureSupabaseAuthenticated: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('@/lib/attachments/attachmentConfig', () => ({
  loadAttachmentsWorkerUrl: () => 'http://127.0.0.1:8787',
}));

describe('deleteAllSupabaseCloudData', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    rpc.mockReset();
    getSession.mockReset();
  });

  it('throws when the wipe RPC fails so sign-out does not proceed', async () => {
    rpc.mockResolvedValue({ data: null, error: { message: 'not authenticated' } });
    await expect(deleteAllSupabaseCloudData()).rejects.toEqual({ message: 'not authenticated' });
  });

  it('returns deleted note count and best-effort deletes R2 keys', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    getSession.mockResolvedValue({
      data: { session: { access_token: 'jwt' } },
    });
    rpc.mockResolvedValue({
      data: {
        status: 'applied',
        notes_deleted: 2,
        attachment_object_keys: [
          'owners/11111111-2222-4333-8444-555555555555/notes/9/att-1',
        ],
      },
      error: null,
    });

    await expect(deleteAllSupabaseCloudData()).resolves.toBe(2);
    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:8787/v1/attachments/9/att-1',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });
});
