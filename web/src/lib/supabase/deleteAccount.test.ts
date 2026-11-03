import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getSession = vi.fn();
/** `get_sync_generation`: answers normally while the account is live, NL003 once it is being deleted. */
const rpc = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ auth: { getSession }, rpc }),
}));

const LIVE = { data: { wipe_generation: 0 }, error: null };
const DELETED = {
  data: null,
  error: { code: 'NL003', message: 'account_deleted: this Notelikeus account was deleted.' },
};
vi.mock('@/lib/supabase/supabaseSyncEngine', () => ({
  ensureSupabaseAuthenticated: vi.fn(async () => 'owner'),
}));

import {
  AccountDeletionOutcomeUnknownError,
  isAccountDeletedError,
  requestAccountDeletion,
} from '@/lib/supabase/deleteAccount';
import { ReauthenticationRequiredError } from '@/lib/auth/reauthentication';

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

  // AUD-009: text that merely contains the answer, such as a note title quoted back, is not it.
  it.each([
    [{ code: '22023', message: 'note title mentions account_deleted: and NL003' }],
    [{ code: '23502', message: 'null value', details: 'Failing row contains (account_deleted: x)' }],
    [new Error('Revision conflict for "account_deleted: my note"')],
  ])('does not read %o, which only quotes it, as a deleted account', (error) => {
    expect(isAccountDeletedError(error)).toBe(false);
  });
});

describe('requestAccountDeletion', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', 'https://worker.example/');
    getSession.mockResolvedValue({ data: { session: { access_token: 'token' } } });
    rpc.mockReset();
    rpc.mockResolvedValue(LIVE);
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

  // AUD-005: the Worker refuses a session that did not sign in recently, before deleting anything.
  it('turns the Worker reauthentication answer into ReauthenticationRequiredError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{"error":"reauthentication_required"}', { status: 403 })),
    );

    await expect(requestAccountDeletion()).rejects.toBeInstanceOf(ReauthenticationRequiredError);
  });

  it('does not read any other 403 as a request to sign in again', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('Forbidden', { status: 403 })));

    const failure = await requestAccountDeletion().catch((error: unknown) => error);
    expect(failure).not.toBeInstanceOf(ReauthenticationRequiredError);
    expect(String(failure)).toContain('Could not delete your account');
  });

  it('treats 202 as success: the sweep finishes what the notes already lost', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 202 })));

    await expect(requestAccountDeletion()).resolves.toBeUndefined();
  });

  it.each([401, 403, 503])('throws on %i', async (status) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status })));

    await expect(requestAccountDeletion()).rejects.toThrow();
  });

  it('throws when the server cannot be reached and the account is still there', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));

    await expect(requestAccountDeletion()).rejects.toThrow(/was not deleted/);
    expect(rpc).toHaveBeenCalledWith('get_sync_generation');
  });

  // AUD-010: a failure that may have come after the request was accepted is settled by asking.
  it('treats a dropped connection as success when the account turns out deleted', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    rpc.mockResolvedValue(DELETED);

    await expect(requestAccountDeletion()).resolves.toBeUndefined();
  });

  it('treats a 5xx as success when the account turns out deleted', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('Upstream database unreachable', { status: 503 })));
    rpc.mockResolvedValue(DELETED);

    await expect(requestAccountDeletion()).resolves.toBeUndefined();
  });

  it('says nothing was deleted after a 5xx only when the account is still there', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 502 })));

    await expect(requestAccountDeletion()).rejects.toThrow(/Nothing was deleted/);
  });

  it('says it cannot tell when the check cannot answer either', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 502 })));
    rpc.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(requestAccountDeletion()).rejects.toBeInstanceOf(AccountDeletionOutcomeUnknownError);
  });

  it('does not second-guess a 4xx refusal', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('Forbidden', { status: 403 })));
    rpc.mockResolvedValue(DELETED);

    await expect(requestAccountDeletion()).rejects.toThrow(/Nothing was deleted/);
    expect(rpc).not.toHaveBeenCalled();
  });

  it('refuses in a build with no Worker', async () => {
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', '');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestAccountDeletion()).rejects.toThrow(/not available/);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
