import { resolveAuthenticatedUserId, UpstreamServiceError, type WorkerEnv } from './auth';
import { ATTACHMENT_ALLOWED_METHODS, withAttachmentCors } from './cors';
import { sweepOrphanedDeletedAttachments, sweepUnconfirmedAttachmentDeletes } from './sweep';
import {
  AttachmentTooLargeError,
  declaredContentLength,
  isAllowedAttachmentMimeType,
  MAX_ATTACHMENT_BYTES,
  normalizeMimeType,
  readBodyWithinLimit,
} from './limits';
import { buildAttachmentObjectKey, parseAttachmentPath } from './objectKey';
import { rateLimitByClient, rateLimitByUser } from './rateLimit';

export type { WorkerEnv };

const SUPPORTED_METHODS = new Set(['GET', 'PUT', 'DELETE']);

/**
 * Request pipeline, cheapest first.
 *
 * Route and method shape, then the request's own headers, are decided locally. Only then is the
 * bearer token exchanged with Supabase, and only then is the resource authorized. A malformed
 * request such as `PATCH /invalid-path` with a garbage bearer therefore costs no outbound call.
 *
 * Nothing that depends on which notes or attachments exist moves earlier: every status returned
 * before authentication is a property of the request itself, so this ordering cannot be used to
 * probe for another user's notes or attachments.
 */
export async function handleAttachmentRequest(
  request: Request,
  env: WorkerEnv,
): Promise<Response> {
  try {
    const url = new URL(request.url);
    const parsed = parseAttachmentPath(url.pathname);
    if (!parsed) {
      return new Response('Not Found', { status: 404 });
    }

    if (!SUPPORTED_METHODS.has(request.method)) {
      return new Response('Method Not Allowed', {
        status: 405,
        headers: { Allow: ATTACHMENT_ALLOWED_METHODS },
      });
    }

    if (request.method === 'PUT') {
      const rejected = rejectUnacceptableUploadHeaders(request);
      if (rejected) return rejected;
    }

    // Throttle before the outbound auth call, so a flood cannot turn one anonymous request into
    // one Supabase Auth request. No-op unless the deployment binds a rate limiter.
    const throttledClient = await rateLimitByClient(request, env.ATTACHMENT_RATE_LIMITER);
    if (throttledClient) return throttledClient;

    const userId = await resolveAuthenticatedUserId(request, env);
    if (!userId) {
      return new Response('Unauthorized', { status: 401 });
    }

    const throttledUser = await rateLimitByUser(userId, env.ATTACHMENT_RATE_LIMITER);
    if (throttledUser) return throttledUser;

    const objectKey = buildAttachmentObjectKey(userId, parsed.noteId, parsed.attachmentId);

    switch (request.method) {
      case 'PUT':
        return await putAttachment(request, env, objectKey, parsed);
      case 'GET':
        return await getAttachment(request, env, objectKey, parsed);
      default:
        return await deleteAttachment(request, env, objectKey, parsed);
    }
  } catch (error) {
    if (error instanceof UpstreamServiceError) {
      return new Response(error.message, { status: error.status });
    }
    throw error;
  }
}

/**
 * Upload rejections that need nothing but the request's own headers.
 *
 * Returns null when the headers are acceptable. These say nothing about what exists on the
 * server, so running them before authentication leaks nothing.
 */
function rejectUnacceptableUploadHeaders(request: Request): Response | null {
  if (!isAllowedAttachmentMimeType(request.headers.get('Content-Type'))) {
    return new Response('Unsupported Media Type', { status: 415 });
  }
  const declared = declaredContentLength(request.headers.get('Content-Length'));
  if (declared != null && declared > MAX_ATTACHMENT_BYTES) {
    return new Response('Payload Too Large', { status: 413 });
  }
  return null;
}

interface AttachmentAuthz {
  allowed?: boolean;
  object_key?: string;
  attachment_id?: string;
  note_id?: string;
  max_bytes?: number;
  /** A live metadata row already exists for this owner/note/attachment. */
  already_live?: boolean;
  mime_type?: string;
  size_bytes?: number;
  /** Delete finalization: the object deletion is recorded against the metadata row. */
  confirmed?: boolean;
}

/**
 * Calls an attachment RPC with the caller's own bearer token.
 *
 * Returns null when Supabase rejects the token, `{ allowed: false }` when the RPC refuses the
 * request, and throws {@link UpstreamServiceError} when the answer cannot be trusted at all —
 * unreachable, failing, missing, or malformed. Those three outcomes must stay distinct: treating
 * an unreadable answer as a refusal is how a Worker ends up acting on a decision nobody made.
 */
async function authorizeAttachment(
  request: Request,
  env: WorkerEnv,
  rpcName: string,
  body: Record<string, unknown>,
): Promise<AttachmentAuthz | null> {
  const authorization = request.headers.get('Authorization') ?? '';
  let response: Response;
  try {
    response = await fetch(
      `${env.SUPABASE_URL.replace(/\/$/, '')}/rest/v1/rpc/${rpcName}`,
      {
        method: 'POST',
        headers: {
          apikey: env.SUPABASE_ANON_KEY,
          Authorization: authorization,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(10_000),
      },
    );
  } catch {
    // Network failure, DNS failure, or the 10s timeout aborting the request.
    throw new UpstreamServiceError('Upstream database unreachable', 503);
  }

  let raw: string;
  try {
    raw = await response.text();
  } catch {
    // The status arrived but the body did not: a truncated or aborted response stream.
    throw new UpstreamServiceError('Upstream database unreachable', 503);
  }

  if (response.status === 401) return null;
  if (response.status >= 500) {
    throw new UpstreamServiceError('Upstream database error', 502);
  }
  // PostgREST answers 404/PGRST202 for an RPC it cannot find. That is a deployment that ran
  // ahead of its migrations, not a decision about this caller — reporting it as a refusal would
  // silently turn a schema mismatch into "you may not do that".
  if (response.status === 404 && raw.includes('PGRST202')) {
    throw new UpstreamServiceError('Upstream database schema out of date', 503);
  }
  if (!response.ok) return { allowed: false };

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new UpstreamServiceError('Upstream database response malformed', 502);
  }
  // A JSON `null`, array, or scalar is not an authorization decision. Letting `null` through
  // here would be read as "token rejected" and answer 401 to a perfectly valid request.
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new UpstreamServiceError('Upstream database response malformed', 502);
  }
  return parsed as AttachmentAuthz;
}

async function putAttachment(
  request: Request,
  env: WorkerEnv,
  objectKey: string,
  parsed: { noteId: string; attachmentId: string },
): Promise<Response> {
  const mimeType = normalizeMimeType(request.headers.get('Content-Type'));
  const declared = declaredContentLength(request.headers.get('Content-Length'));

  // Phase 0: ownership preflight, before a single body byte is read. An authenticated caller who
  // does not own this note is refused here rather than after streaming 10 MB into Worker memory.
  // Advisory only — every authoritative check still happens below, against the real byte count.
  const precheck = await authorizeAttachment(request, env, 'precheck_note_attachment_put', {
    p_note_id: parsed.noteId,
    p_attachment_id: parsed.attachmentId,
    p_mime_type: mimeType,
    p_declared_size_bytes: declared,
  });
  if (!precheck) return new Response('Unauthorized', { status: 401 });
  if (!precheck.allowed) return new Response('Forbidden', { status: 403 });
  // Security boundary: NEVER trust a returned object_key over the locally derived key!
  if (!precheck.object_key || precheck.object_key !== objectKey) {
    return new Response('Forbidden', { status: 403 });
  }

  let body: Uint8Array;
  try {
    body = await readBodyWithinLimit(request.body, MAX_ATTACHMENT_BYTES);
  } catch (error) {
    if (error instanceof AttachmentTooLargeError) {
      return new Response('Payload Too Large', { status: 413 });
    }
    throw error;
  }

  // Phase 1: authoritative authorization against the size that actually arrived. Client-declared
  // Content-Length never decides anything here.
  const preflight = await authorizeAttachment(request, env, 'authorize_note_attachment_put', {
    p_note_id: parsed.noteId,
    p_attachment_id: parsed.attachmentId,
    p_mime_type: mimeType,
    p_size_bytes: body.byteLength,
  });
  if (!preflight) return new Response('Unauthorized', { status: 401 });
  if (!preflight.allowed) return new Response('Forbidden', { status: 403 });

  // Security boundary: NEVER trust preflight object_key over locally derived key!
  if (!preflight.object_key || preflight.object_key !== objectKey) {
    return new Response('Forbidden', { status: 403 });
  }
  if (preflight.max_bytes != null && body.byteLength > preflight.max_bytes) {
    return new Response('Payload Too Large', { status: 413 });
  }

  // An attachment id is an immutable identity, and its object key is derived from it, so a
  // repeated PUT of a committed attachment is a retry of work that already succeeded — usually a
  // client that never saw the first response. Report the committed state instead of rewriting the
  // object: overwriting it put a live blob at risk from this request's own failure paths.
  // Changing an image's content uses a new attachment id, which lands on the fresh path below.
  if (preflight.already_live) {
    const committed = await env.ATTACHMENTS_BUCKET.head(objectKey);
    if (committed) {
      return Response.json({
        objectKey,
        sizeBytes: preflight.size_bytes ?? committed.size,
        mimeType: preflight.mime_type ?? mimeType,
        alreadyUploaded: true,
      });
    }
    // Metadata says live but the object is gone. Re-upload to repair it; compensation below
    // still leaves the object alone, because deleting it cannot improve on already-missing.
  }

  // Compensation may only remove bytes this request is solely responsible for. When a live row
  // already referenced this key, the object is not ours to delete on failure.
  const ownedByThisRequest = preflight.already_live !== true;
  const compensate = async (reason: string) => {
    if (!ownedByThisRequest) return;
    try {
      await env.ATTACHMENTS_BUCKET.delete(objectKey);
    } catch {
      console.error(`[Worker] Compensating R2 delete failed after ${reason}`);
    }
  };

  // Phase 2: write bytes to R2
  try {
    await env.ATTACHMENTS_BUCKET.put(objectKey, body, {
      httpMetadata: { contentType: mimeType },
    });
  } catch {
    // A throwing put may still have landed bytes. No metadata will ever point at them, and no
    // sweeper can find an object with no row, so clean up rather than leak them permanently.
    await compensate('storage write failure');
    return new Response('Storage write failed', { status: 502 });
  }

  // Phase 3: metadata finalization in database
  let finalized: AttachmentAuthz | null;
  try {
    finalized = await authorizeAttachment(request, env, 'finalize_note_attachment_put', {
      p_note_id: parsed.noteId,
      p_attachment_id: parsed.attachmentId,
      p_object_key: objectKey,
      p_mime_type: mimeType,
      p_size_bytes: body.byteLength,
      p_attachment_type: 'image',
    });
  } catch (error) {
    await compensate('upstream finalization error');
    throw error;
  }

  if (!finalized || !finalized.attachment_id || finalized.object_key !== objectKey) {
    await compensate('finalization rejection');
    return new Response('Forbidden', { status: 403 });
  }

  return Response.json({ objectKey, sizeBytes: body.byteLength, mimeType });
}

async function getAttachment(
  request: Request,
  env: WorkerEnv,
  objectKey: string,
  parsed: { noteId: string; attachmentId: string },
): Promise<Response> {
  const authz = await authorizeAttachment(request, env, 'authorize_note_attachment_get', {
    p_note_id: parsed.noteId,
    p_attachment_id: parsed.attachmentId,
  });
  if (!authz) return new Response('Unauthorized', { status: 401 });
  if (!authz.allowed) return new Response('Not Found', { status: 404 });

  // Security boundary: NEVER trust returned object_key over locally derived key!
  if (!authz.object_key || authz.object_key !== objectKey) {
    return new Response('Forbidden', { status: 403 });
  }

  const object = await env.ATTACHMENTS_BUCKET.get(objectKey);
  if (!object) {
    return new Response('Not Found', { status: 404 });
  }
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set('etag', object.httpEtag);
  headers.set('X-Content-Type-Options', 'nosniff');
  headers.set('Content-Disposition', 'attachment');
  headers.set('Cache-Control', 'private, no-store');
  return new Response(object.body, { headers });
}

/**
 * Deletes an attachment, claim first.
 *
 * The order is the correctness argument. Phase 1 commits the intent to delete: the row is marked
 * deleted and claimed, under a row lock, before a byte moves. Everything after it can fail, be
 * abandoned, or be retried, and the attachment is still deleted — the worst case is bytes left in
 * R2 that nothing references, which the cron sweep finishes. Deleting the object first, as this
 * used to, made the opposite failure possible: a live row pointing at an object that was gone,
 * with a 200 reported to the client either way.
 *
 * Every phase is idempotent, so a repeated DELETE is a no-op that returns the same answer.
 */
async function deleteAttachment(
  request: Request,
  env: WorkerEnv,
  objectKey: string,
  parsed: { noteId: string; attachmentId: string },
): Promise<Response> {
  // Phase 1: authorize and durably record the deletion.
  const claim = await authorizeAttachment(request, env, 'begin_note_attachment_delete', {
    p_note_id: parsed.noteId,
    p_attachment_id: parsed.attachmentId,
  });
  if (!claim) return new Response('Unauthorized', { status: 401 });
  if (!claim.allowed) return new Response('Not Found', { status: 404 });

  // Security boundary: NEVER trust returned object_key over locally derived key!
  if (!claim.object_key || claim.object_key !== objectKey) {
    return new Response('Forbidden', { status: 403 });
  }

  // Phase 2: idempotent R2 delete. A failure here leaves the attachment deleted and its bytes
  // orphaned, which the sweeper reclaims — so it is reported as retryable, not as data loss.
  try {
    await env.ATTACHMENTS_BUCKET.delete(objectKey);
  } catch {
    return new Response('Storage delete failed', { status: 502 });
  }

  // Phase 3: record that the object is gone. This is bookkeeping, not the deletion itself: the
  // metadata already says deleted and the bytes already are. A failure here is reported in the
  // body rather than as an error status, because telling the client the delete failed would be
  // the false answer — and the sweep picks the row up from its unconfirmed claim regardless.
  let confirmed = false;
  try {
    const result = await authorizeAttachment(request, env, 'finalize_note_attachment_delete', {
      p_note_id: parsed.noteId,
      p_attachment_id: parsed.attachmentId,
    });
    // `confirmed` says the stamp landed on a row; `allowed` is the fallback for a database that
    // predates it and did the work without reporting it.
    confirmed = result?.confirmed ?? result?.allowed === true;
  } catch (error) {
    if (!(error instanceof UpstreamServiceError)) throw error;
    console.error(`[Worker] Attachment delete left unconfirmed: ${error.message}`);
  }

  return Response.json({ deleted: true, objectKey, confirmed });
}

export default {
  async fetch(request: Request, env: WorkerEnv): Promise<Response> {
    if (request.method === 'OPTIONS') {
      return withAttachmentCors(request, new Response(null, { status: 204 }), env.ALLOWED_ORIGINS);
    }
    return withAttachmentCors(
      request,
      await handleAttachmentRequest(request, env),
      env.ALLOWED_ORIGINS,
    );
  },
  async scheduled(
    _controller: ScheduledController,
    env: WorkerEnv,
    ctx: ExecutionContext,
  ): Promise<void> {
    // Two independent passes: one reclaims blobs whose notes are long gone, the other finishes
    // deletes a client abandoned. Neither may hide the other's failure, so both always run and
    // the first rejection is rethrown for the runtime to log.
    ctx.waitUntil(
      (async () => {
        const results = await Promise.allSettled([
          sweepOrphanedDeletedAttachments(env),
          sweepUnconfirmedAttachmentDeletes(env),
        ]);
        const failed = results.find((result) => result.status === 'rejected');
        if (failed) throw (failed as PromiseRejectedResult).reason;
      })(),
    );
  },
};
