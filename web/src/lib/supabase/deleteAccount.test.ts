import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getSession = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ auth: { getSession } }),
}));
vi.mock('@/lib/supabase/supabaseSyncEngine', () => ({
  ensureSupabaseAuthenticated: vi.fn(async () => 'owner'),
}));

import { isAccountDeletedError, requestAccountDeletion } from '@/lib/supabase/deleteAccount';

describe('isAccountDeletedError', () => {
  it('recognises the database refusal by code or by message', () => {
    expect(isAccountDeletedError({ code: 'NL003', message: 'x' })).toBe(true);
    expect(isAccountDeletedError(new Error('account_deleted: this account was deleted.'))).toBe(true);
  });

  // The local copy is erased on this answer, so nothing ambiguous may pass for it.
  it.each([
    [new TypeError('Failed to fetch')],
    [{ code: 'PGRST301', message: 'JWT expired' }],
    [{ code: 'NL002', message: 'account_wipe_generation_stale' }],
    [{ status: 401, message: 'refresh_token_not_found' }],
    [null],
  ])('does not read %o as a deleted account', (error) => {
    expect(isAccountDeletedError(error)).toBe(false);
  });
});

describe('requestAccountDeletion', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', 'https://worker.example/');
    getSession.mockResolvedValue({ data: { session: { access_token: 'token' } } });
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('sends the session token to the account route', async () => {
    const fetchMock = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await requestAccountDeletion();

    expect(fetchMock).toHaveBeenCalledWith('https://worker.example/v1/account', {
      method: 'DELETE',
      headers: { Authorization: 'Bearer token' },
    });
  });

  it('treats 202 as success: the sweep finishes what the notes already lost', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 202 })));

    await expect(requestAccountDeletion()).resolves.toBeUndefined();
  });

  it.each([401, 403, 503])('throws on %i', async (status) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status })));

    await expect(requestAccountDeletion()).rejects.toThrow();
  });

  it('throws when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));

    await expect(requestAccountDeletion()).rejects.toThrow(/was not deleted/);
  });

  it('refuses in a build with no Worker', async () => {
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', '');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestAccountDeletion()).rejects.toThrow(/not available/);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
