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

interface RpcCallLog {
  url: string;
  body: Record<string, unknown>;
}

let rpcLog: RpcCallLog[] = [];

/**
 * Bearer token is the user id. Note authorization:
 * USER_A may use note `1` only; `fake` / `tombstoned` are rejected; USER_B is never allowed.
 */
function mockSupabaseAuth(
  validTokens: Record<string, string>,
  customHandler?: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined | void,
) {
  rpcLog = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string | URL, init?: RequestInit) => {
      const url = String(input);
      if (customHandler) {
        const custom = await customHandler(url, init);
        if (custom) return custom;
      }
      const headers = init?.headers as Record<string, string> | undefined;
      const auth = headers?.Authorization ?? '';
      const token = auth.replace('Bearer ', '');
      const id = validTokens[token];
      if (!id) return new Response('unauthorized', { status: 401 });
      if (url.includes('/auth/v1/user')) {
        return new Response(JSON.stringify({ id }), { status: 200 });
      }

      const body = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
      rpcLog.push({ url, body });

      if (url.includes('/rest/v1/rpc/authorize_note_attachment_')) {
        const noteId = (body.p_note_id as string) ?? '';
        const attachmentId = (body.p_attachment_id as string) ?? '';
        const allowed =
          id === USER_A &&
          noteId === '1' &&
          attachmentId !== 'missing' &&
          !attachmentId.includes(' ');
        return new Response(
          JSON.stringify({
            allowed,
            object_key: allowed ? `owners/${id}/notes/${noteId}/${attachmentId}` : undefined,
            max_bytes: 10 * 1024 * 1024,
          }),
          { status: 200 },
        );
      }
      if (url.includes('/rest/v1/rpc/finalize_note_attachment_put')) {
        const noteId = (body.p_note_id as string) ?? '';
        const attachmentId = (body.p_attachment_id as string) ?? '';
        const objectKey = (body.p_object_key as string) ?? '';
        const expectedKey = `owners/${id}/notes/${noteId}/${attachmentId}`;
        if (id !== USER_A || noteId !== '1' || objectKey !== expectedKey) {
          return new Response(JSON.stringify({ error: 'forbidden' }), { status: 403 });
        }
        return new Response(
          JSON.stringify({
            attachment_id: attachmentId,
            object_key: objectKey,
            status: 'registered',
          }),
          { status: 200 },
        );
      }
      if (url.includes('/rest/v1/rpc/finalize_note_attachment_delete')) {
        return new Response(JSON.stringify({ status: 'deleted' }), { status: 200 });
      }
      return new Response('not found', { status: 404 });
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

  it('rejects an unknown method with 405 and Allow header', async () => {
    const response = await handleAttachmentRequest(request('PATCH', '/v1/attachments/1/a', USER_A), env);
    expect(response.status).toBe(405);
    expect(response.headers.get('Allow')).toBe('GET, PUT, DELETE, OPTIONS');
  });

  it('maps Supabase auth 401 to Worker 401', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/auth/v1/user')) {
        return new Response('unauthorized', { status: 401 });
      }
    });
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/a', USER_A), env);
    expect(response.status).toBe(401);
  });

  it('maps Supabase auth 500 to Worker 502 Bad Gateway', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/auth/v1/user')) {
        return new Response('internal error', { status: 500 });
      }
    });
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/a', USER_A), env);
    expect(response.status).toBe(502);
  });

  it('maps Supabase auth network failure to Worker 503 Service Unavailable', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/auth/v1/user')) {
        throw new Error('network down');
      }
    });
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/a', USER_A), env);
    expect(response.status).toBe(503);
  });

  it('maps Supabase RPC 500 to Worker 502', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/authorize_note_attachment_')) {
        return new Response('database error', { status: 500 });
      }
    });
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/a', USER_A), env);
    expect(response.status).toBe(502);
  });

  it('maps Supabase RPC network failure to Worker 503', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/authorize_note_attachment_')) {
        throw new Error('rpc unreachable');
      }
    });
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/a', USER_A), env);
    expect(response.status).toBe(503);
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
    expect(response.status).toBe(404);
    // B is not authorized for A's note; generic 404 does not leak A's object. A's object survives.
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

  it('accepts every MIME type the backup format can produce', async () => {
    // The backup allowlist and this one must agree, or an imported image is dropped at upload.
    for (const contentType of ['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'image/gif']) {
      const response = await handleAttachmentRequest(
        request('PUT', `/v1/attachments/1/${contentType.replace(/\W/g, '')}`, USER_A, {
          body: new Uint8Array([1]),
          headers: { 'Content-Type': contentType },
        }),
        env,
      );
      expect(response.status, contentType).toBe(200);
    }
  });

  it('rejects an upload with no content type at all', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/x', USER_A, { body: new Uint8Array([1]) }),
      env,
    );
    expect(response.status).toBe(415);
  });

  it('rejects a PUT for a note the backend does not authorize', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/fake/x', USER_A, {
        body: new Uint8Array([1]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    expect(response.status).toBe(403);
    expect(bucket.objects.size).toBe(0);
  });

  it('rejects a PUT for a tombstoned note', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/tombstoned/x', USER_A, {
        body: new Uint8Array([1]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    expect(response.status).toBe(403);
  });

  it('rejects USER_B uploading into USER_A note 1', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', USER_B, {
        body: new Uint8Array([1]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    expect(response.status).toBe(403);
  });

  it('GET without authorized metadata is a generic 404', async () => {
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/missing', USER_A), env);
    expect(response.status).toBe(404);
  });

  it('DELETE cleanup is idempotent', async () => {
    const first = await handleAttachmentRequest(request('DELETE', '/v1/attachments/1/att1', USER_A), env);
    const second = await handleAttachmentRequest(request('DELETE', '/v1/attachments/1/att1', USER_A), env);
    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
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
    expect(response.headers.get('Cache-Control')).toBe('private, no-store');
  });

  it('rejects malformed percent-encoded routes with 404 without crashing', async () => {
    for (const badPath of [
      '/v1/attachments/%/att',
      '/v1/attachments/%ZZ/att',
      '/v1/attachments/%E0%A4%A/att',
      '/v1/attachments/note/%C3%28',
    ]) {
      const response = await handleAttachmentRequest(request('GET', badPath, USER_A), env);
      expect(response.status).toBe(404);
    }
  });

  it('never trusts an RPC object_key over the locally derived key on PUT', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/authorize_note_attachment_put')) {
        return new Response(
          JSON.stringify({
            allowed: true,
            object_key: `owners/${USER_B}/notes/1/att1`, // Malicious cross-owner key!
            max_bytes: 10 * 1024 * 1024,
          }),
          { status: 200 },
        );
      }
    });

    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', USER_A, {
        body: new Uint8Array([1, 2, 3]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    expect(response.status).toBe(403);
    expect(bucket.objects.size).toBe(0);
  });

  it('never trusts an RPC object_key over the locally derived key on GET', async () => {
    bucket.objects.set(`owners/${USER_B}/notes/1/att1`, {
      body: new Uint8Array([9, 9, 9]),
      contentType: 'image/png',
    });

    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/authorize_note_attachment_get')) {
        return new Response(
          JSON.stringify({
            allowed: true,
            object_key: `owners/${USER_B}/notes/1/att1`, // Malicious mismatch!
          }),
          { status: 200 },
        );
      }
    });

    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/att1', USER_A), env);
    expect(response.status).toBe(403);
  });

  it('never trusts an RPC object_key over the locally derived key on DELETE', async () => {
    bucket.objects.set(`owners/${USER_B}/notes/1/att1`, {
      body: new Uint8Array([9, 9, 9]),
      contentType: 'image/png',
    });

    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/authorize_note_attachment_delete')) {
        return new Response(
          JSON.stringify({
            allowed: true,
            object_key: `owners/${USER_B}/notes/1/att1`, // Malicious mismatch!
          }),
          { status: 200 },
        );
      }
    });

    const response = await handleAttachmentRequest(request('DELETE', '/v1/attachments/1/att1', USER_A), env);
    expect(response.status).toBe(403);
    // USER_B's object MUST NOT have been deleted
    expect(bucket.objects.has(`owners/${USER_B}/notes/1/att1`)).toBe(true);
  });

  it('two-phase PUT executes preflight, R2 write, and finalize in strict order', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', USER_A, {
        body: new Uint8Array([1, 2, 3]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    expect(response.status).toBe(200);

    const rpcNames = rpcLog.map((c) => c.url.split('/rpc/')[1]);
    expect(rpcNames).toEqual(['authorize_note_attachment_put', 'finalize_note_attachment_put']);
  });

  it('aborts PUT and never calls finalize if R2 PUT throws', async () => {
    bucket.put = async () => {
      throw new Error('R2 write error');
    };

    await expect(
      handleAttachmentRequest(
        request('PUT', '/v1/attachments/1/att1', USER_A, {
          body: new Uint8Array([1, 2, 3]),
          headers: { 'Content-Type': 'image/png' },
        }),
        env,
      ),
    ).rejects.toThrow('R2 write error');

    const finalizeCalls = rpcLog.filter((c) => c.url.includes('finalize_note_attachment_put'));
    expect(finalizeCalls).toHaveLength(0);
  });

  it('attempts compensating R2 delete if finalize PUT fails', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/finalize_note_attachment_put')) {
        return new Response(JSON.stringify({ error: 'quota exceeded' }), { status: 403 });
      }
    });

    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', USER_A, {
        body: new Uint8Array([1, 2, 3]),
        headers: { 'Content-Type': 'image/png' },
      }),
      env,
    );
    expect(response.status).toBe(403);
    // Compensating delete must have removed the object from R2!
    expect(bucket.objects.has(`owners/${USER_A}/notes/1/att1`)).toBe(false);
  });

  it('aborts DELETE and never calls finalize_delete if R2 DELETE throws', async () => {
    bucket.delete = async () => {
      throw new Error('R2 delete error');
    };

    await expect(
      handleAttachmentRequest(request('DELETE', '/v1/attachments/1/att1', USER_A), env),
    ).rejects.toThrow('R2 delete error');

    const finalizeCalls = rpcLog.filter((c) => c.url.includes('finalize_note_attachment_delete'));
    expect(finalizeCalls).toHaveLength(0);
  });
});
