import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CloudWipeIncompleteError,
  deleteAllSupabaseCloudData,
} from '@/lib/supabase/deleteAllUserCloudData';

const rpc = vi.fn();
const getSession = vi.fn();
const loadAttachmentsWorkerUrl = vi.fn(() => 'http://127.0.0.1:8787');

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
  loadAttachmentsWorkerUrl: () => loadAttachmentsWorkerUrl(),
}));

const KEY_A = 'owners/11111111-2222-4333-8444-555555555555/notes/9/att-1';
const KEY_B = 'owners/11111111-2222-4333-8444-555555555555/notes/9/att-2';

/** The wipe RPC answers with `keys`; every other RPC (the finalize) answers empty. */
function wipeReturning(keys: string[], notesDeleted = 2) {
  rpc.mockImplementation((name: string) => {
    if (name === 'delete_all_user_cloud_data') {
      return Promise.resolve({
        data: { status: 'applied', notes_deleted: notesDeleted, attachment_object_keys: keys },
        error: null,
      });
    }
    return Promise.resolve({ data: { status: 'applied' }, error: null });
  });
}

describe('deleteAllSupabaseCloudData', () => {
  beforeEach(() => {
    loadAttachmentsWorkerUrl.mockReturnValue('http://127.0.0.1:8787');
    getSession.mockResolvedValue({ data: { session: { access_token: 'jwt' } } });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    rpc.mockReset();
    getSession.mockReset();
    loadAttachmentsWorkerUrl.mockReset();
  });

  it('throws when the wipe RPC fails so sign-out does not proceed', async () => {
    rpc.mockResolvedValue({ data: null, error: { message: 'not authenticated' } });
    await expect(deleteAllSupabaseCloudData()).rejects.toEqual({ message: 'not authenticated' });
  });

  it('deletes every listed object and reports the note count', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);
    wipeReturning([KEY_A]);

    await expect(deleteAllSupabaseCloudData()).resolves.toBe(2);
    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:8787/v1/attachments/9/att-1',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('refuses to report success when the Worker rejects every delete', async () => {
    // The regression. The Worker authorizes a DELETE against the attachment's metadata row, and
    // the wipe used to destroy those rows first — so every DELETE came back 403/404 and not one
    // object was removed, while the user was told the wipe had succeeded.
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 403 });
    vi.stubGlobal('fetch', fetchMock);
    wipeReturning([KEY_A, KEY_B]);

    await expect(deleteAllSupabaseCloudData()).rejects.toBeInstanceOf(CloudWipeIncompleteError);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('reports how many objects survived a partial failure', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, status: 200 })
      .mockResolvedValueOnce({ ok: false, status: 500 });
    vi.stubGlobal('fetch', fetchMock);
    wipeReturning([KEY_A, KEY_B]);

    await expect(deleteAllSupabaseCloudData()).rejects.toMatchObject({
      name: 'CloudWipeIncompleteError',
      remainingObjects: 1,
    });
  });

  it('treats a network failure as objects left behind, not as success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    wipeReturning([KEY_A]);

    await expect(deleteAllSupabaseCloudData()).rejects.toBeInstanceOf(CloudWipeIncompleteError);
  });

  it('treats an already-missing object as deleted, so a retry converges', async () => {
    // A second wipe after a partial one: the server no longer knows about the row, and there is
    // nothing left for this client to remove.
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 404 });
    vi.stubGlobal('fetch', fetchMock);
    wipeReturning([KEY_A]);

    await expect(deleteAllSupabaseCloudData()).resolves.toBe(2);
  });

  it('refuses when attachments exist but no Worker is configured to reach them', async () => {
    loadAttachmentsWorkerUrl.mockReturnValue('');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    wipeReturning([KEY_A]);

    await expect(deleteAllSupabaseCloudData()).rejects.toBeInstanceOf(CloudWipeIncompleteError);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses when the session has no token to authorize the deletes with', async () => {
    getSession.mockResolvedValue({ data: { session: null } });
    vi.stubGlobal('fetch', vi.fn());
    wipeReturning([KEY_A]);

    await expect(deleteAllSupabaseCloudData()).rejects.toBeInstanceOf(CloudWipeIncompleteError);
  });

  it('counts an unparseable object key as a failure rather than skipping it', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);
    wipeReturning(['not/a/valid/object/key']);

    await expect(deleteAllSupabaseCloudData()).rejects.toBeInstanceOf(CloudWipeIncompleteError);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('purges the marked metadata once every object is gone', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }));
    wipeReturning([KEY_A]);

    await deleteAllSupabaseCloudData();

    expect(rpc).toHaveBeenCalledWith('finalize_cloud_wipe');
  });

  it('does not purge metadata while objects are still stored', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    wipeReturning([KEY_A]);

    await expect(deleteAllSupabaseCloudData()).rejects.toBeInstanceOf(CloudWipeIncompleteError);
    // Dropping the rows here would strand the surviving objects: the Worker authorizes against
    // them, and the sweeper finds them through them.
    expect(rpc).not.toHaveBeenCalledWith('finalize_cloud_wipe');
  });

  it('handles an account with no attachments at all', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    wipeReturning([], 5);

    await expect(deleteAllSupabaseCloudData()).resolves.toBe(5);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
