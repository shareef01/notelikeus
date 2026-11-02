import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sweepPendingAccountDeletions } from './accountDeletion';
import type { WorkerEnv } from './auth';
import { handleAttachmentRequest } from './index';

const OWNER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const OTHER = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

function fakeBucket() {
  const objects = new Set<string>();
  return {
    objects,
    failDeletes: false,
    async list(options: { prefix?: string; limit?: number }) {
      const keys = [...objects]
        .filter((key) => key.startsWith(options.prefix ?? ''))
        .sort()
        .slice(0, options.limit ?? 1000);
      return { objects: keys.map((key) => ({ key })), truncated: false };
    },
    async delete(keys: string | string[]) {
      if (this.failDeletes) throw new Error('r2 unavailable');
      for (const key of Array.isArray(keys) ? keys : [keys]) objects.delete(key);
    },
  };
}

let bucket: ReturnType<typeof fakeBucket>;
let env: WorkerEnv;
/** Every outbound call in order, as `METHOD path`. The order is the correctness argument here. */
let calls: string[];
let responses: {
  user?: Response;
  request?: () => Response;
  adminDelete?: () => Response;
  pending?: unknown[];
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

function request(method = 'DELETE', path = '/v1/account'): Request {
  return new Request(`https://worker.example${path}`, {
    method,
    headers: { Authorization: 'Bearer user-token' },
  });
}

beforeEach(() => {
  bucket = fakeBucket();
  env = {
    ATTACHMENTS_BUCKET: bucket as unknown as R2Bucket,
    SUPABASE_URL: 'https://project.supabase.co',
    SUPABASE_ANON_KEY: 'anon-key',
    SUPABASE_SERVICE_ROLE_KEY: 'service-role-key',
  };
  calls = [];
  responses = {};
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      calls.push(`${init?.method ?? 'GET'} ${url.pathname}`);
      if (url.pathname === '/auth/v1/user') {
        return responses.user ?? json({ id: OWNER });
      }
      if (url.pathname.startsWith('/auth/v1/admin/users/')) {
        return responses.adminDelete?.() ?? json({});
      }
      if (url.pathname.endsWith('/request_account_deletion')) {
        return responses.request?.() ?? json({ status: 'accepted' });
      }
      if (url.pathname.endsWith('/list_pending_account_deletions')) {
        return json(responses.pending ?? []);
      }
      return json({ status: 'applied' });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DELETE /v1/account', () => {
  it('purges the rows, then the objects, then the auth user, then records it', async () => {
    bucket.objects.add(`owners/${OWNER}/notes/1/att1`);
    bucket.objects.add(`owners/${OWNER}/notes/2/unreferenced`);
    bucket.objects.add(`owners/${OTHER}/notes/1/att1`);

    const response = await handleAttachmentRequest(request(), env);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: 'deleted' });
    expect([...bucket.objects]).toEqual([`owners/${OTHER}/notes/1/att1`]);
    expect(calls).toEqual([
      'GET /auth/v1/user',
      'POST /rest/v1/rpc/request_account_deletion',
      `DELETE /auth/v1/admin/users/${OWNER}`,
      'POST /rest/v1/rpc/mark_account_auth_user_deleted',
    ]);
  });

  it('acts on the authenticated caller and ignores anything in the request', async () => {
    const response = await handleAttachmentRequest(
      request('DELETE', `/v1/account?owner=${OTHER}`),
      env,
    );

    expect(response.status).toBe(200);
    expect(calls).toContain(`DELETE /auth/v1/admin/users/${OWNER}`);
    expect(calls).not.toContain(`DELETE /auth/v1/admin/users/${OTHER}`);
  });

  it('refuses before anything is destroyed when it could not finish the job', async () => {
    delete env.SUPABASE_SERVICE_ROLE_KEY;
    bucket.objects.add(`owners/${OWNER}/notes/1/att1`);

    const response = await handleAttachmentRequest(request(), env);

    expect(response.status).toBe(503);
    expect(bucket.objects.size).toBe(1);
    expect(calls).toEqual(['GET /auth/v1/user']);
  });

  it('answers 401 for a rejected token and touches nothing', async () => {
    responses.user = new Response('nope', { status: 401 });
    bucket.objects.add(`owners/${OWNER}/notes/1/att1`);

    const response = await handleAttachmentRequest(request(), env);

    expect(response.status).toBe(401);
    expect(bucket.objects.size).toBe(1);
    expect(calls).toEqual(['GET /auth/v1/user']);
  });

  it('touches no bytes and no user when the database does not accept the request', async () => {
    responses.request = () => new Response('boom', { status: 500 });
    bucket.objects.add(`owners/${OWNER}/notes/1/att1`);

    const response = await handleAttachmentRequest(request(), env);

    expect(response.status).toBe(502);
    expect(bucket.objects.size).toBe(1);
    expect(calls.some((call) => call.includes('/admin/'))).toBe(false);
  });

  it('leaves the auth user for the sweep when R2 fails, and says pending', async () => {
    bucket.objects.add(`owners/${OWNER}/notes/1/att1`);
    bucket.failDeletes = true;

    const response = await handleAttachmentRequest(request(), env);

    expect(response.status).toBe(202);
    expect(await response.json()).toEqual({ status: 'pending' });
    expect(calls.some((call) => call.includes('/admin/'))).toBe(false);
  });

  it('says pending when the auth delete fails, and does not record it', async () => {
    responses.adminDelete = () => new Response('boom', { status: 500 });

    const response = await handleAttachmentRequest(request(), env);

    expect(response.status).toBe(202);
    expect(calls).not.toContain('POST /rest/v1/rpc/mark_account_auth_user_deleted');
  });

  it('treats an auth user that is already gone as success', async () => {
    responses.adminDelete = () => new Response('gone', { status: 404 });

    const response = await handleAttachmentRequest(request(), env);

    expect(response.status).toBe(200);
    expect(calls).toContain('POST /rest/v1/rpc/mark_account_auth_user_deleted');
  });

  it('answers 405 to any other method without authenticating', async () => {
    const response = await handleAttachmentRequest(request('POST'), env);

    expect(response.status).toBe(405);
    expect(calls).toEqual([]);
  });
});

describe('pending account deletion sweep', () => {
  it('does nothing without a service-role key', async () => {
    delete env.SUPABASE_SERVICE_ROLE_KEY;
    responses.pending = [{ owner_id: OWNER, auth_user_deleted: false, settled: false }];

    expect(await sweepPendingAccountDeletions(env)).toEqual({ scanned: 0, deleted: 0, skipped: 0 });
    expect(calls).toEqual([]);
  });

  it('finishes a request that died before the auth user went', async () => {
    responses.pending = [{ owner_id: OWNER, auth_user_deleted: false, settled: false }];
    bucket.objects.add(`owners/${OWNER}/notes/1/att1`);

    expect(await sweepPendingAccountDeletions(env)).toEqual({ scanned: 1, deleted: 1, skipped: 0 });
    expect(bucket.objects.size).toBe(0);
    expect(calls).toContain(`DELETE /auth/v1/admin/users/${OWNER}`);
  });

  it('removes bytes an in-flight upload landed after the user went', async () => {
    responses.pending = [{ owner_id: OWNER, auth_user_deleted: true, settled: false }];
    bucket.objects.add(`owners/${OWNER}/notes/1/late`);

    expect(await sweepPendingAccountDeletions(env)).toEqual({ scanned: 1, deleted: 0, skipped: 1 });
    expect(bucket.objects.size).toBe(0);
    expect(calls).not.toContain('POST /rest/v1/rpc/complete_account_deletion');
    expect(calls.some((call) => call.includes('/admin/'))).toBe(false);
  });

  it('forgets a settled request only once the prefix is empty', async () => {
    responses.pending = [{ owner_id: OWNER, auth_user_deleted: true, settled: true }];
    bucket.objects.add(`owners/${OWNER}/notes/1/late`);
    bucket.failDeletes = true;

    expect(await sweepPendingAccountDeletions(env)).toEqual({ scanned: 1, deleted: 0, skipped: 1 });
    expect(calls).not.toContain('POST /rest/v1/rpc/complete_account_deletion');

    bucket.failDeletes = false;
    expect(await sweepPendingAccountDeletions(env)).toEqual({ scanned: 1, deleted: 1, skipped: 0 });
    expect(calls).toContain('POST /rest/v1/rpc/complete_account_deletion');
  });

  it('never lists or deletes outside one valid owner prefix', async () => {
    responses.pending = [
      { owner_id: '', auth_user_deleted: false, settled: false },
      { owner_id: `${OWNER}/../`, auth_user_deleted: true, settled: true },
    ];
    bucket.objects.add(`owners/${OTHER}/notes/1/att1`);

    expect(await sweepPendingAccountDeletions(env)).toEqual({ scanned: 2, deleted: 0, skipped: 2 });
    expect(bucket.objects.size).toBe(1);
    expect(calls.some((call) => call.includes('/admin/'))).toBe(false);
  });

  it('keeps going after one account fails', async () => {
    responses.pending = [
      { owner_id: OTHER, auth_user_deleted: false, settled: false },
      { owner_id: OWNER, auth_user_deleted: false, settled: false },
    ];
    let first = true;
    responses.adminDelete = () => {
      if (first) {
        first = false;
        return new Response('boom', { status: 500 });
      }
      return json({});
    };

    expect(await sweepPendingAccountDeletions(env)).toEqual({ scanned: 2, deleted: 1, skipped: 1 });
  });
});
