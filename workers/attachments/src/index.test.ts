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
    async head(key: string) {
      const stored = objects.get(key);
      if (!stored) return null;
      return { key, size: stored.body.byteLength, httpEtag: '"etag"' };
    },
    async delete(key: string) {
      objects.delete(key);
    },
  };
}

/**
 * Attachment metadata, as the database would hold it.
 *
 * The double models the real state machine rather than answering every call the same way: a row
 * exists or it does not, and a delete moves it through claimed and then confirmed. The protocol
 * tests below are about exactly those transitions, so a stateless double cannot express them.
 */
interface AttachmentRow {
  mimeType: string;
  sizeBytes: number;
  deleted: boolean;
  deleteClaimed: boolean;
  objectDeleted: boolean;
}

const attachmentRows = new Map<string, AttachmentRow>();

function rowKey(owner: string, noteId: string, attachmentId: string): string {
  return `${owner}/${noteId}/${attachmentId}`;
}

/** USER_A may use note `1` only; `fake` / `tombstoned` are rejected; USER_B is never allowed. */
function noteIsWritable(userId: string, noteId: string, attachmentId: string): boolean {
  return (
    userId === USER_A &&
    noteId === '1' &&
    attachmentId !== 'missing' &&
    !attachmentId.includes(' ')
  );
}

interface RpcCallLog {
  url: string;
  body: Record<string, unknown>;
}

let rpcLog: RpcCallLog[] = [];
let authCalls = 0;

function rpcNames(): string[] {
  return rpcLog.map((call) => call.url.split('/rpc/')[1]!);
}

/** Bearer token is the user id. */
function mockSupabaseAuth(
  validTokens: Record<string, string>,
  customHandler?: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined | void,
) {
  rpcLog = [];
  authCalls = 0;
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
      if (url.includes('/auth/v1/user')) authCalls += 1;
      if (!id) return new Response('unauthorized', { status: 401 });
      if (url.includes('/auth/v1/user')) {
        return new Response(JSON.stringify({ id }), { status: 200 });
      }

      const body = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
      rpcLog.push({ url, body });
      const noteId = (body.p_note_id as string) ?? '';
      const attachmentId = (body.p_attachment_id as string) ?? '';
      const key = rowKey(id, noteId, attachmentId);
      const objectKey = `owners/${id}/notes/${noteId}/${attachmentId}`;

      // Once a delete is claimed the identity is retired, and all three PUT RPCs say so.
      const terminal = (() => {
        const row = attachmentRows.get(key);
        return row != null && (row.deleteClaimed || row.objectDeleted);
      })();

      if (url.includes('/rest/v1/rpc/precheck_note_attachment_put')) {
        if (terminal) {
          return new Response(
            JSON.stringify({ allowed: false, reason: 'terminally_deleted' }),
            { status: 200 },
          );
        }
        const allowed = noteIsWritable(id, noteId, attachmentId);
        return new Response(
          JSON.stringify({
            allowed,
            object_key: allowed ? objectKey : undefined,
            max_bytes: 10 * 1024 * 1024,
          }),
          { status: 200 },
        );
      }
      if (url.includes('/rest/v1/rpc/authorize_note_attachment_put')) {
        if (terminal) {
          return new Response(
            JSON.stringify({ allowed: false, reason: 'terminally_deleted' }),
            { status: 200 },
          );
        }
        const allowed = noteIsWritable(id, noteId, attachmentId);
        const row = attachmentRows.get(key);
        const live = row && !row.deleted ? row : undefined;
        return new Response(
          JSON.stringify({
            allowed,
            object_key: allowed ? objectKey : undefined,
            already_live: allowed ? live != null : undefined,
            mime_type: live?.mimeType,
            size_bytes: live?.sizeBytes,
            max_bytes: 10 * 1024 * 1024,
          }),
          { status: 200 },
        );
      }
      if (url.includes('/rest/v1/rpc/authorize_note_attachment_get')) {
        const row = attachmentRows.get(key);
        const allowed = noteIsWritable(id, noteId, attachmentId) && row != null && !row.deleted;
        return new Response(
          JSON.stringify({ allowed, object_key: allowed ? objectKey : undefined }),
          { status: 200 },
        );
      }
      if (url.includes('/rest/v1/rpc/finalize_note_attachment_put')) {
        if (terminal) {
          return new Response(
            JSON.stringify({ allowed: false, reason: 'terminally_deleted', object_key: objectKey }),
            { status: 200 },
          );
        }
        const expectedKey = objectKey;
        if (id !== USER_A || noteId !== '1' || (body.p_object_key as string) !== expectedKey) {
          return new Response(JSON.stringify({ error: 'forbidden' }), { status: 403 });
        }
        attachmentRows.set(key, {
          mimeType: (body.p_mime_type as string) ?? 'image/png',
          sizeBytes: (body.p_size_bytes as number) ?? 0,
          deleted: false,
          deleteClaimed: false,
          objectDeleted: false,
        });
        return new Response(
          JSON.stringify({ attachment_id: attachmentId, object_key: expectedKey, status: 'registered' }),
          { status: 200 },
        );
      }
      if (url.includes('/rest/v1/rpc/begin_note_attachment_delete')) {
        const row = attachmentRows.get(key);
        // No row means nothing this caller owns: the same answer for "never existed" and
        // "already purged", so neither enumerates anything.
        if (!row) return new Response(JSON.stringify({ allowed: false }), { status: 200 });
        const alreadyClaimed = row.deleteClaimed;
        row.deleted = true;
        row.deleteClaimed = true;
        return new Response(
          JSON.stringify({
            allowed: true,
            object_key: objectKey,
            already_claimed: alreadyClaimed,
            object_deleted: row.objectDeleted,
          }),
          { status: 200 },
        );
      }
      if (url.includes('/rest/v1/rpc/finalize_note_attachment_delete')) {
        const row = attachmentRows.get(key);
        if (row) {
          row.deleted = true;
          row.deleteClaimed = true;
          row.objectDeleted = true;
        }
        return new Response(
          JSON.stringify({ allowed: true, confirmed: row != null, object_key: objectKey }),
          { status: 200 },
        );
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

async function upload(
  noteId: string,
  attachmentId: string,
  token: string,
  body: BodyInit = new Uint8Array([1, 2, 3]),
) {
  return handleAttachmentRequest(
    request('PUT', `/v1/attachments/${noteId}/${attachmentId}`, token, {
      body,
      headers: { 'Content-Type': 'image/png' },
    }),
    env,
  );
}

beforeEach(() => {
  bucket = fakeBucket();
  attachmentRows.clear();
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

  it('maps a missing RPC (schema behind the Worker) to 503, not a refusal', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/')) {
        return new Response(
          JSON.stringify({ code: 'PGRST202', message: 'Could not find the function' }),
          { status: 404 },
        );
      }
    });
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/att1', USER_A), env);
    expect(response.status).toBe(503);
  });

  it('treats a malformed RPC body as an upstream failure, not an answer', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/')) {
        return new Response('<html>gateway</html>', { status: 200 });
      }
    });
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/att1', USER_A), env);
    expect(response.status).toBe(502);
  });

  it('does not read a JSON null RPC body as a rejected token', async () => {
    // `null` is a valid JSON document and used to fall through the `!authz` check as a 401,
    // which blamed the caller's token for an upstream fault.
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/')) {
        return new Response('null', { status: 200 });
      }
    });
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/att1', USER_A), env);
    expect(response.status).toBe(502);
  });
});

/**
 * Cheap local validation runs before anything is spent upstream.
 *
 * Everything decided here is a property of the request alone — the route shape, the method, the
 * upload headers — so refusing early cannot tell an anonymous caller anything about which notes
 * or attachments exist.
 */
describe('attachment worker request ordering', () => {
  it('rejects an unroutable path without authenticating', async () => {
    const response = await handleAttachmentRequest(
      request('PATCH', '/invalid-path', 'garbage'),
      env,
    );
    expect(response.status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('rejects an unsupported method without authenticating', async () => {
    const response = await handleAttachmentRequest(
      request('PATCH', '/v1/attachments/1/att1', 'garbage'),
      env,
    );
    expect(response.status).toBe(405);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('rejects an unsupported upload media type without authenticating', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', 'garbage', {
        body: new Uint8Array([1]),
        headers: { 'Content-Type': 'text/html' },
      }),
      env,
    );
    expect(response.status).toBe(415);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('rejects a declared oversize upload without authenticating', async () => {
    const response = await handleAttachmentRequest(
      request('PUT', '/v1/attachments/1/att1', 'garbage', {
        body: new Uint8Array([1]),
        headers: {
          'Content-Type': 'image/png',
          'Content-Length': String(MAX_ATTACHMENT_BYTES + 1),
        },
      }),
      env,
    );
    expect(response.status).toBe(413);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('still authenticates before touching any resource on a routable request', async () => {
    const response = await handleAttachmentRequest(
      request('GET', '/v1/attachments/1/att1', 'garbage'),
      env,
    );
    expect(response.status).toBe(401);
    expect(authCalls).toBe(1);
    expect(rpcLog).toHaveLength(0);
  });
});

describe('attachment worker owner isolation', () => {
  it('stores under the authenticated user, not anything the client sends', async () => {
    const response = await upload('1', 'att1', USER_A);
    expect(response.status).toBe(200);
    expect([...bucket.objects.keys()]).toEqual([`owners/${USER_A}/notes/1/att1`]);
  });

  it('does not let B read A object at the same note/attachment path', async () => {
    await upload('1', 'att1', USER_A);
    const response = await handleAttachmentRequest(
      request('GET', '/v1/attachments/1/att1', USER_B),
      env,
    );
    expect(response.status).toBe(404);
    expect(bucket.objects.has(`owners/${USER_A}/notes/1/att1`)).toBe(true);
  });

  it('does not let B delete A object', async () => {
    await upload('1', 'att1', USER_A);
    const response = await handleAttachmentRequest(
      request('DELETE', '/v1/attachments/1/att1', USER_B),
      env,
    );
    expect(response.status).toBe(404);
    // B is not authorized for A's note; generic 404 does not leak A's object. A's object survives.
    expect(bucket.objects.has(`owners/${USER_A}/notes/1/att1`)).toBe(true);
  });

  it('does not let B overwrite A object', async () => {
    await upload('1', 'att1', USER_A);
    await upload('1', 'att1', USER_B, new Uint8Array([9, 9, 9, 9]));
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
    const response = await upload('1', 'atlimit', USER_A, new Uint8Array(MAX_ATTACHMENT_BYTES));
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
    const response = await upload('fake', 'x', USER_A);
    expect(response.status).toBe(403);
    expect(bucket.objects.size).toBe(0);
  });

  it('rejects a PUT for a tombstoned note', async () => {
    const response = await upload('tombstoned', 'x', USER_A);
    expect(response.status).toBe(403);
  });

  it('rejects USER_B uploading into USER_A note 1', async () => {
    const response = await upload('1', 'att1', USER_B);
    expect(response.status).toBe(403);
  });

  it('GET without authorized metadata is a generic 404', async () => {
    const response = await handleAttachmentRequest(request('GET', '/v1/attachments/1/missing', USER_A), env);
    expect(response.status).toBe(404);
  });

  it('serves stored bytes with sniffing disabled', async () => {
    await upload('1', 'att1', USER_A, new Uint8Array([1]));
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

    const response = await upload('1', 'att1', USER_A);
    expect(response.status).toBe(403);
    expect(bucket.objects.size).toBe(0);
  });

  it('never trusts an RPC object_key over the locally derived key on the PUT precheck', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/precheck_note_attachment_put')) {
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

    const response = await upload('1', 'att1', USER_A);
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

  it('three-phase PUT prechecks, authorizes against real bytes, writes, then finalizes', async () => {
    const response = await upload('1', 'att1', USER_A);
    expect(response.status).toBe(200);
    expect(rpcNames()).toEqual([
      'precheck_note_attachment_put',
      'authorize_note_attachment_put',
      'finalize_note_attachment_put',
    ]);
  });

  it('reports an R2 write failure as retryable and leaves nothing behind', async () => {
    bucket.put = async () => {
      throw new Error('R2 write error');
    };

    const response = await upload('1', 'att1', USER_A);

    expect(response.status).toBe(502);
    expect(rpcNames()).not.toContain('finalize_note_attachment_put');
    expect(bucket.objects.size).toBe(0);
  });

  it('attempts compensating R2 delete if finalize PUT fails', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/finalize_note_attachment_put')) {
        return new Response(JSON.stringify({ error: 'quota exceeded' }), { status: 403 });
      }
    });

    const response = await upload('1', 'att1', USER_A);
    expect(response.status).toBe(403);
    // Compensating delete must have removed the object from R2!
    expect(bucket.objects.has(`owners/${USER_A}/notes/1/att1`)).toBe(false);
  });
});

/**
 * An upload body is only read once the caller is known to own the note.
 *
 * Before, the Worker buffered up to 10 MB into memory and only then asked whether the caller was
 * allowed to upload it at all, so any authenticated account could make the Worker do that work
 * for someone else's note.
 */
describe('PUT authorization before the body is consumed', () => {
  /** A body that reports how many chunks were actually pulled from it. */
  function countingBody(chunks: number) {
    const state = { pulled: 0 };
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (state.pulled >= chunks) {
          controller.close();
          return;
        }
        state.pulled += 1;
        controller.enqueue(new Uint8Array(1024));
      },
    });
    return { stream, state };
  }

  function streamingUpload(path: string, token: string, body: ReadableStream<Uint8Array>) {
    return handleAttachmentRequest(
      new Request(`https://worker.example${path}`, {
        method: 'PUT',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'image/png' },
        body,
        // Node requires this for a streaming request body.
        duplex: 'half',
      } as RequestInit & { duplex: 'half' }),
      env,
    );
  }

  it('does not read the body of an upload for a note the caller does not own', async () => {
    const { stream, state } = countingBody(4096);
    const response = await streamingUpload('/v1/attachments/1/att1', USER_B, stream);

    expect(response.status).toBe(403);
    // Constructing the Request may prime the stream with a single chunk before the Worker ever
    // sees it. What matters is that the Worker never drains the rest: 4096 chunks were offered.
    expect(state.pulled).toBeLessThanOrEqual(1);
    expect(rpcNames()).toEqual(['precheck_note_attachment_put']);
  });

  it('reads the body once the caller does own the note', async () => {
    const { stream, state } = countingBody(2);
    const response = await streamingUpload('/v1/attachments/1/att-stream', USER_A, stream);

    expect(response.status).toBe(200);
    expect(state.pulled).toBe(2);
  });
});

/**
 * Retrying a PUT of an attachment that is already committed.
 *
 * The object key is derived from the attachment id, so a repeat PUT lands on the live object.
 * Compensation for this request's own failure must never reach bytes that a surviving metadata
 * row still points at, or a retry could permanently break an attachment that was fine.
 */
describe('PUT retry against a committed attachment', () => {
  const key = `owners/${USER_A}/notes/1/att1`;

  async function commitAttachment(body = 'original-bytes'): Promise<void> {
    const response = await upload('1', 'att1', USER_A, body);
    expect(response.status).toBe(200);
    expect(bucket.objects.has(key)).toBe(true);
  }

  it('treats a repeated PUT of a committed attachment as already uploaded', async () => {
    await commitAttachment();
    const originalBytes = bucket.objects.get(key)!.body;

    const response = await upload('1', 'att1', USER_A, 'different-bytes');

    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ objectKey: key, alreadyUploaded: true });
    // The committed object is left exactly as it was; an id is one immutable image.
    expect(bucket.objects.get(key)!.body).toEqual(originalBytes);
  });

  it('does not re-finalize metadata for an already committed attachment', async () => {
    await commitAttachment();
    const before = rpcNames().filter((name) => name === 'finalize_note_attachment_put').length;

    await upload('1', 'att1', USER_A, 'again');

    const after = rpcNames().filter((name) => name === 'finalize_note_attachment_put').length;
    expect(after).toBe(before);
  });

  it('keeps the committed blob when a repeat PUT hits a database outage', async () => {
    await commitAttachment();
    const originalBytes = bucket.objects.get(key)!.body;

    // Metadata row exists but the object vanished, so the retry genuinely re-uploads — and then
    // finalization fails. The regression: compensation deleted the object the live row needs.
    bucket.objects.delete(key);
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('finalize_note_attachment_put')) {
        return new Response('gateway timeout', { status: 504 });
      }
      return undefined;
    });
    attachmentRows.set(rowKey(USER_A, '1', 'att1'), {
      mimeType: 'image/png',
      sizeBytes: originalBytes.byteLength,
      deleted: false,
      deleteClaimed: false,
      objectDeleted: false,
    });

    const response = await upload('1', 'att1', USER_A, 'repair-bytes');

    expect(response.status).toBe(502);
    // Bytes uploaded for a still-live attachment are not this request's to destroy.
    expect(bucket.objects.has(key)).toBe(true);
  });

  it('still cleans up its own uncommitted blob when a fresh PUT fails to finalize', async () => {
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('finalize_note_attachment_put')) {
        return new Response('gateway timeout', { status: 504 });
      }
      return undefined;
    });

    const response = await upload('1', 'att-fresh', USER_A, 'never-committed');

    expect(response.status).toBe(502);
    // Nothing else referenced these bytes, so compensation is still correct here.
    expect(bucket.objects.has(`owners/${USER_A}/notes/1/att-fresh`)).toBe(false);
  });

  it('repairs a live attachment whose object is missing', async () => {
    await commitAttachment();
    bucket.objects.delete(key);

    const response = await upload('1', 'att1', USER_A, 'repaired');

    expect(response.status).toBe(200);
    expect(bucket.objects.has(key)).toBe(true);
  });

  it('does not let another owner reach a committed attachment by retrying its PUT', async () => {
    await commitAttachment();
    const originalBytes = bucket.objects.get(key)!.body;

    const response = await upload('1', 'att1', USER_B, 'intruder');

    expect(response.status).toBe(403);
    expect(bucket.objects.get(key)!.body).toEqual(originalBytes);
  });
});

/**
 * DELETE is claimed before a byte moves.
 *
 * The old order deleted the R2 object first and then asked the database to mark the metadata
 * deleted, without checking the answer — so a refused, failing, or timed-out finalization still
 * produced 200 {"deleted":true} over a live row whose object was gone. Recording the deletion
 * first turns every remaining failure into an orphaned object, which is recoverable, and lets a
 * retry converge from any point.
 */
describe('attachment DELETE protocol', () => {
  const key = `owners/${USER_A}/notes/1/att1`;

  async function commit(): Promise<void> {
    expect((await upload('1', 'att1', USER_A)).status).toBe(200);
    rpcLog = [];
  }

  function deleteAtt(token = USER_A, path = '/v1/attachments/1/att1') {
    return handleAttachmentRequest(request('DELETE', path, token), env);
  }

  it('claims the deletion before the object is touched', async () => {
    await commit();
    const deleteOrder: string[] = [];
    const realDelete = bucket.delete.bind(bucket);
    bucket.delete = async (k: string) => {
      deleteOrder.push('r2');
      return realDelete(k);
    };
    const response = await deleteAtt();

    expect(response.status).toBe(200);
    expect(rpcNames()).toEqual(['begin_note_attachment_delete', 'finalize_note_attachment_delete']);
    expect(deleteOrder).toEqual(['r2']);
    expect(await response.json()).toMatchObject({ deleted: true, confirmed: true });
    expect(bucket.objects.has(key)).toBe(false);
  });

  it('does not touch the object when authorization fails', async () => {
    await commit();
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('begin_note_attachment_delete')) {
        return new Response(JSON.stringify({ allowed: false }), { status: 200 });
      }
    });

    const response = await deleteAtt();
    expect(response.status).toBe(404);
    expect(bucket.objects.has(key)).toBe(true);
  });

  it('does not touch the object when the claim rejects the token', async () => {
    await commit();
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('begin_note_attachment_delete')) {
        return new Response('unauthorized', { status: 401 });
      }
    });

    const response = await deleteAtt();
    expect(response.status).toBe(401);
    expect(bucket.objects.has(key)).toBe(true);
  });

  it('does not touch the object when the claim upstream is unreachable', async () => {
    await commit();
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('begin_note_attachment_delete')) throw new Error('rpc unreachable');
    });

    const response = await deleteAtt();
    expect(response.status).toBe(503);
    expect(bucket.objects.has(key)).toBe(true);
  });

  it('never trusts an RPC object_key over the locally derived key on DELETE', async () => {
    bucket.objects.set(`owners/${USER_B}/notes/1/att1`, {
      body: new Uint8Array([9, 9, 9]),
      contentType: 'image/png',
    });

    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('/rest/v1/rpc/begin_note_attachment_delete')) {
        return new Response(
          JSON.stringify({
            allowed: true,
            object_key: `owners/${USER_B}/notes/1/att1`, // Malicious mismatch!
          }),
          { status: 200 },
        );
      }
    });

    const response = await deleteAtt();
    expect(response.status).toBe(403);
    // USER_B's object MUST NOT have been deleted
    expect(bucket.objects.has(`owners/${USER_B}/notes/1/att1`)).toBe(true);
  });

  it('reports an R2 delete failure as retryable, with the claim already recorded', async () => {
    await commit();
    bucket.delete = async () => {
      throw new Error('R2 delete error');
    };

    const response = await deleteAtt();

    expect(response.status).toBe(502);
    expect(rpcNames()).toEqual(['begin_note_attachment_delete']);
    // The claim survives, so the object is no longer referenced by live metadata and the sweep
    // can finish it even if this client never comes back.
    expect(attachmentRows.get(rowKey(USER_A, '1', 'att1'))).toMatchObject({
      deleted: true,
      deleteClaimed: true,
      objectDeleted: false,
    });
  });

  it('reports an R2 delete timeout the same way, without confirming the deletion', async () => {
    await commit();
    bucket.delete = async () => {
      throw Object.assign(new Error('The operation was aborted'), { name: 'TimeoutError' });
    };

    const response = await deleteAtt();

    expect(response.status).toBe(502);
    expect(rpcNames()).not.toContain('finalize_note_attachment_delete');
    expect(attachmentRows.get(rowKey(USER_A, '1', 'att1'))?.objectDeleted).toBe(false);
  });

  // Every one of these happens after the deletion has been recorded and the bytes are gone, so
  // the attachment really is deleted. Reporting failure would be the false answer; the response
  // says the confirmation stamp is missing, and the sweep picks the row up from its claim.
  for (const [label, failure] of [
    ['401 Unauthorized', () => new Response('unauthorized', { status: 401 })],
    ['403 Forbidden', () => new Response('forbidden', { status: 403 })],
    ['404 Not Found', () => new Response('not found', { status: 404 })],
    ['500 Internal Server Error', () => new Response('boom', { status: 500 })],
    ['a malformed body', () => new Response('<html>nope</html>', { status: 200 })],
  ] as const) {
    it(`still deletes the object when finalization answers ${label}`, async () => {
      await commit();
      mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
        if (url.includes('finalize_note_attachment_delete')) return failure();
        return undefined;
      });

      const response = await deleteAtt();

      expect(response.status).toBe(200);
      expect(await response.json()).toMatchObject({ deleted: true, confirmed: false });
      expect(bucket.objects.has(key)).toBe(false);
    });
  }

  it('still deletes the object when finalization times out', async () => {
    await commit();
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('finalize_note_attachment_delete')) {
        throw Object.assign(new Error('The operation was aborted'), { name: 'TimeoutError' });
      }
      return undefined;
    });

    const response = await deleteAtt();

    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ deleted: true, confirmed: false });
    expect(bucket.objects.has(key)).toBe(false);
  });

  it('converges when retried after the object was already deleted', async () => {
    await commit();
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('finalize_note_attachment_delete')) {
        return new Response('boom', { status: 500 });
      }
      return undefined;
    });
    expect((await deleteAtt()).status).toBe(200);
    expect(bucket.objects.has(key)).toBe(false);

    // The client retries; the object is already gone and the row is already claimed.
    mockSupabaseAuth({ [USER_A]: USER_A });
    const retry = await deleteAtt();

    expect(retry.status).toBe(200);
    expect(await retry.json()).toMatchObject({ deleted: true, confirmed: true });
    expect(attachmentRows.get(rowKey(USER_A, '1', 'att1'))?.objectDeleted).toBe(true);
  });

  it('converges when retried after metadata was already finalized', async () => {
    await commit();
    expect((await deleteAtt()).status).toBe(200);

    const retry = await deleteAtt();
    expect(retry.status).toBe(200);
    expect(await retry.json()).toMatchObject({ deleted: true, confirmed: true });
  });

  it('is idempotent across repeated DELETEs', async () => {
    await commit();
    const statuses: number[] = [];
    for (let attempt = 0; attempt < 3; attempt += 1) {
      statuses.push((await deleteAtt()).status);
    }
    expect(statuses).toEqual([200, 200, 200]);
    expect(bucket.objects.has(key)).toBe(false);
  });

  it('answers 404 for an attachment that has no metadata row at all', async () => {
    const response = await deleteAtt(USER_A, '/v1/attachments/1/never-existed');
    expect(response.status).toBe(404);
    expect(rpcNames()).toEqual(['begin_note_attachment_delete']);
  });

  it('does not let user A delete user B attachment', async () => {
    // B commits an attachment at the same note/attachment path in their own namespace.
    attachmentRows.set(rowKey(USER_B, '1', 'att1'), {
      mimeType: 'image/png',
      sizeBytes: 3,
      deleted: false,
      deleteClaimed: false,
      objectDeleted: false,
    });
    const bKey = `owners/${USER_B}/notes/1/att1`;
    bucket.objects.set(bKey, { body: new Uint8Array([9, 9, 9]), contentType: 'image/png' });

    const response = await deleteAtt(USER_A);

    expect(response.status).toBe(404);
    expect(bucket.objects.has(bKey)).toBe(true);
    expect(attachmentRows.get(rowKey(USER_B, '1', 'att1'))).toMatchObject({ deleted: false });
  });
});

/**
 * An attachment id is retired by its own deletion.
 *
 * The delete protocol makes a claimed deletion terminal, but the object key is derived from the
 * attachment id, so a PUT of that same id lands right back on the retired identity. The database
 * is the authority here — a DELETE can be claimed at any point after the Worker's preflight said
 * yes, including while the bytes are still uploading — and it answers `terminally_deleted` as a
 * value so the Worker can act on it without guessing.
 */
describe('PUT against a terminally deleted attachment identity', () => {
  const key = `owners/${USER_A}/notes/1/att1`;

  async function commitThenDelete(): Promise<void> {
    expect((await upload('1', 'att1', USER_A)).status).toBe(200);
    expect(
      (await handleAttachmentRequest(request('DELETE', '/v1/attachments/1/att1', USER_A), env))
        .status,
    ).toBe(200);
    expect(bucket.objects.has(key)).toBe(false);
    rpcLog = [];
  }

  it('cannot resurrect an id whose delete completed', async () => {
    await commitThenDelete();

    const response = await upload('1', 'att1', USER_A, 'replacement');

    expect(response.status).toBe(409);
    expect(bucket.objects.has(key)).toBe(false);
    expect(attachmentRows.get(rowKey(USER_A, '1', 'att1'))).toMatchObject({ deleted: true });
  });

  it('refuses at the precheck, without reading the body', async () => {
    await commitThenDelete();

    await upload('1', 'att1', USER_A, 'replacement');

    // Nothing past the precheck runs: no authorization, no finalization, no R2 write.
    expect(rpcNames()).toEqual(['precheck_note_attachment_put']);
  });

  it('cannot resurrect an id claimed but not yet confirmed deleted', async () => {
    expect((await upload('1', 'att1', USER_A)).status).toBe(200);
    // The claim has landed; the object delete and its confirmation have not.
    const row = attachmentRows.get(rowKey(USER_A, '1', 'att1'))!;
    row.deleted = true;
    row.deleteClaimed = true;
    row.objectDeleted = false;

    const response = await upload('1', 'att1', USER_A, 'replacement');

    expect(response.status).toBe(409);
    expect(attachmentRows.get(rowKey(USER_A, '1', 'att1'))).toMatchObject({
      deleted: true,
      deleteClaimed: true,
    });
  });

  it('still uploads normally under a new attachment id', async () => {
    await commitThenDelete();

    const response = await upload('1', 'att2', USER_A, 'the replacement image');

    expect(response.status).toBe(200);
    expect(bucket.objects.has(`owners/${USER_A}/notes/1/att2`)).toBe(true);
  });

  it('does not let another owner reach a retired identity', async () => {
    await commitThenDelete();

    // USER_B's own namespace is untouched by USER_A retiring an id, and USER_B still cannot
    // reach USER_A's note either way.
    const response = await upload('1', 'att1', USER_B, 'intruder');

    expect(response.status).toBe(403);
    expect(bucket.objects.has(key)).toBe(false);
  });

  /**
   * The interleaving that makes the authoritative answer necessary, and forced compensation with
   * it. A DELETE lands between this PUT's authorization and its R2 lookup, so the Worker sees a
   * live row, finds the object missing, and re-uploads to "repair" an attachment that has in fact
   * just been retired. `ownedByThisRequest` is false on that path, so ordinary compensation would
   * decline to remove the bytes — and nothing would ever collect them: the row's object deletion
   * is already confirmed, so the unconfirmed-delete sweep skips it, and its note is still live, so
   * the orphan sweep never looks at it either.
   */
  it('does not orphan replacement bytes when a DELETE races an already_live repair', async () => {
    expect((await upload('1', 'att1', USER_A)).status).toBe(200);
    rpcLog = [];

    let raced = false;
    const realHead = bucket.head.bind(bucket);
    bucket.head = async (k: string) => {
      if (!raced) {
        raced = true;
        // The whole DELETE completes here: claim, object delete, confirmation.
        const deleted = await handleAttachmentRequest(
          request('DELETE', '/v1/attachments/1/att1', USER_A),
          env,
        );
        expect(deleted.status).toBe(200);
      }
      return realHead(k);
    };

    const response = await upload('1', 'att1', USER_A, 'repair-bytes');

    expect(raced).toBe(true);
    expect(response.status).toBe(409);
    // The bytes this request wrote are gone again: the database said, unambiguously, that no row
    // can ever point at this key.
    expect(bucket.objects.has(key)).toBe(false);
    expect(attachmentRows.get(rowKey(USER_A, '1', 'att1'))).toMatchObject({
      deleted: true,
      deleteClaimed: true,
      objectDeleted: true,
    });
  });

  it('leaves a live attachment bytes alone when finalization is merely untrustworthy', async () => {
    // The conservative path must survive the new forced one. Same shape as the race above — live
    // row, missing object, re-upload — but finalization times out instead of answering, so the
    // Worker cannot know whether the row survived and must not destroy bytes it may still need.
    expect((await upload('1', 'att1', USER_A)).status).toBe(200);
    bucket.objects.delete(key);
    mockSupabaseAuth({ [USER_A]: USER_A }, (url) => {
      if (url.includes('finalize_note_attachment_put')) {
        throw Object.assign(new Error('The operation was aborted'), { name: 'TimeoutError' });
      }
      return undefined;
    });

    const response = await upload('1', 'att1', USER_A, 'repair-bytes');

    expect(response.status).toBe(503);
    expect(bucket.objects.has(key)).toBe(true);
  });
});
