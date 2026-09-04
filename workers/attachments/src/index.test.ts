import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { handleAttachmentRequest, type WorkerEnv } from './index';
import { MAX_ATTACHMENT_BYTES } from './limits';

const USER_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const USER_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

/** Minimal in-memory stand-in for the R2 binding. Keys are opaque strings, as in R2. */
function fakeBucket() {
  const objects = new Map<string, { body: Uint8Array; contentType: string }>();
  return {
    objects,
    async put(key: string, body: ArrayBuffer | Uint8Array, options?: { httpMetadata?: { contentType?: string } }) {
      objects.set(key, {
        body: body instanceof Uint8Array ? body : new Uint8Array(body),
        contentType: options?.httpMetadata?.contentType ?? '',
      });
    },
    async get(key: string) {
      const stored = objects.get(key);
      if (!stored) return null;
      return {
        body: stored.body,
        httpEtag: '"etag"',
        writeHttpMetadata(headers: Headers) {
          headers.set('content-type', stored.contentType);
        },
      };
    },
    async delete(key: string) {
      objects.delete(key);
    },
  };
}

/** Bearer token is the user id; anything else is an invalid token. */
function mockSupabaseAuth(validTokens: Record<string, string>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (_url: string, init?: RequestInit) => {
      const auth = (init?.headers as Record<string, string> | undefined)?.Authorization ?? '';
      const token = auth.replace('Bearer ', '');
      const id = validTokens[token];
      if (!id) return new Response('unauthorized', { status: 401 });
      return new Response(JSON.stringify({ id }), { status: 200 });
    }),
  );
}

let bucket: ReturnType<typeof fakeBucket>;
let env: WorkerEnv;

function request(method: string, path: string, token?: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  return new Request(`https://worker.example${path}`, { ...init, method, headers });
}

beforeEach(() => {
  bucket = fakeBucket();
  env = {
    ATTACHMENTS_BUCKET: bucket as unknown as R2Bucket,
    SUPABASE_URL: 'https://project.supabase.co',
    SUPABASE_ANON_KEY: 'anon-key',
  };
  mockSupabaseAuth({ [USER_A]: USER_A, [USER_B]: USER_B });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('attachment worker authentication', () => {
  it('rejects a request with no Authorization header', async () => {
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/a'), env);
    expect(response.status).toBe(401);
  });

  it('rejects a malformed Authorization header', async () => {
    const req = new Request('https://worker.example/v1/attachments/1/a', {
      headers: { Authorization: 'Basic hunter2' },
    });
    expect((await handleAttachmentRequest(req, env)).status).toBe(401);
  });

  it('rejects a token Supabase does not recognise', async () => {
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/a', 'expired'), env);
    expect(response.status).toBe(401);
  });

  it('rejects an unknown method', async () => {
    const response = await handleAttachmentRequest(request('PATCH', '/v1/attachments/1/a', USER_A), env);
    expect(response.status).toBe(405);
  });
});

describe('attachment worker owner isolation', () => {
  async function uploadAsA() {
    return handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', USER_A, {
        body: new Uint8Array([1, 2, 3]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
  }

  it('stores under the authenticated user, not anything the client sends', async () => {
    const response = await uploadAsA();
    expect(response.status).toBe(200);
    expect([...bucket.objects.keys()]).toEqual([`owners/${USER_A}/notes/1/att1`]);
  });

  it('does not let B read A object at the same note/attachment path', async () => {
    await uploadAsA();
    const response = await handleAttachmentRequest(
      request('GET', '/v1/attachments/1/att1', USER_B),
      env,
    );
    expect(response.status).toBe(404);
    expect(bucket.objects.has(`owners/${USER_A}/notes/1/att1`)).toBe(true);
  });

  it('does not let B delete A object', async () => {
    await uploadAsA();
    const response = await handleAttachmentRequest(
      request('DELETE', '/v1/attachments/1/att1', USER_B),
      env,
    );
    expect(response.status).toBe(200);
    // B deleted only their own (absent) key; A's object survives.
    expect(bucket.objects.has(`owners/${USER_A}/notes/1/att1`)).toBe(true);
  });

  it('does not let B overwrite A object', async () => {
    await uploadAsA();
    await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', USER_B, {
        body: new Uint8Array([9, 9, 9, 9]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    expect(bucket.objects.get(`owners/${USER_A}/notes/1/att1`)?.body).toEqual(
      new Uint8Array([1, 2, 3]),
    );
  });

  it('rejects path segments that try to escape the owner prefix', async () => {
    for (const path of [
      '/v1/attachments/%2e%2e%2f%2e%2e/att1',
      '/v1/attachments/1%2fother/att1',
      '/v1/attachments//att1',
      '/v1/attachments/1/att1/extra',
      `/v1/attachments/${'x'.repeat(129)}/att1`,
    ]) {
      const response = await handleAttachmentRequest(request('GET', path, USER_A), env);
      expect(response.status, path).toBe(404);
    }
  });
});

describe('attachment worker upload limits', () => {
  it('rejects a body larger than the cap even when Content-Length lies', async () => {
    const oversized = new Uint8Array(MAX_ATTACHMENT_BYTES + 1024);
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/big', USER_A, {
        body: oversized,
        headers: { 'Content-Type': 'image/png', 'Content-Length': '10' },
      }),
      env,
    );
    expect(response.status).toBe(413);
    expect(bucket.objects.size).toBe(0);
  });

  it('rejects a declared oversize before reading the body', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/big', USER_A, {
        body: new Uint8Array([1]),
        headers: {
          'Content-Type': 'image/png',
          'Content-Length': String(MAX_ATTACHMENT_BYTES + 1),
        },
      }),
      env,
    );
    expect(response.status).toBe(413);
  });

  it('accepts a body at exactly the cap', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/atlimit', USER_A, {
        body: new Uint8Array(MAX_ATTACHMENT_BYTES),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    expect(response.status).toBe(200);
  });

  it('rejects non-image content types', async () => {
    for (const contentType of ['text/html', 'image/svg+xml', 'application/octet-stream']) {
      const response = await handleAttachmentRequest(
        request('PUT', '/v1/attachments/1/x', USER_A, {
          body: new Uint8Array([1]),
          headers: { 'Content-Type': contentType },
        }),
        env,
      );
      expect(response.status, contentType).toBe(415);
    }
    expect(bucket.objects.size).toBe(0);
  });

  it('rejects an upload with no content type at all', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/x', USER_A, { body: new Uint8Array([1]) }),
      env,
    );
    expect(response.status).toBe(415);
  });

  it('serves stored bytes with sniffing disabled', async () => {
    await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', USER_A, {
        body: new Uint8Array([1]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    const response = await handleAttachmentRequest(
      request('GET', '/v1/attachments/1/att1', USER_A),
      env,
    );
    expect(response.headers.get('X-Content-Type-Options')).toBe('nosniff');
    expect(response.headers.get('Content-Disposition')).toBe('attachment');
  });
});
