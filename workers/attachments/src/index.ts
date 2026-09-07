import { resolveAuthenticatedUserId, UpstreamServiceError, type WorkerEnv } from './auth';
import { ATTACHMENT_ALLOWED_METHODS, withAttachmentCors } from './cors';
import { sweepOrphanedDeletedAttachments } from './sweep';
import {
  AttachmentTooLargeError,
  declaredContentLength,
  isAllowedAttachmentMimeType,
  MAX_ATTACHMENT_BYTES,
  normalizeMimeType,
  readBodyWithinLimit,
} from './limits';
import { buildAttachmentObjectKey, parseAttachmentPath } from './objectKey';

export type { WorkerEnv };

export async function handleAttachmentRequest(
  request: Request,
  env: WorkerEnv,
): Promise<Response> {
  try {
    const userId = await resolveAuthenticatedUserId(request, env);
    if (!userId) {
      return new Response('Unauthorized', { status: 401 });
    }

    const url = new URL(request.url);

    const parsed = parseAttachmentPath(url.pathname);
    if (!parsed) {
      return new Response('Not Found', { status: 404 });
    }

    const objectKey = buildAttachmentObjectKey(userId, parsed.noteId, parsed.attachmentId);

    switch (request.method) {
      case 'PUT':
        return await putAttachment(request, env, objectKey, parsed, userId);
      case 'GET':
        return await getAttachment(request, env, objectKey, parsed);
      case 'DELETE':
        return await deleteAttachment(request, env, objectKey, parsed);
      default:
        return new Response('Method Not Allowed', {
          status: 405,
          headers: { Allow: ATTACHMENT_ALLOWED_METHODS },
        });
    }
  } catch (error) {
    if (error instanceof UpstreamServiceError) {
      return new Response(error.message, { status: error.status });
    }
    throw error;
  }
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
}

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
    throw new UpstreamServiceError('Upstream database unreachable', 503);
  }

  if (response.status === 401) return null;
  if (response.status >= 500) {
    throw new UpstreamServiceError('Upstream database error', 502);
  }
  if (!response.ok) return { allowed: false };
  return (await response.json()) as AttachmentAuthz;
}

async function putAttachment(
  request: Request,
  env: WorkerEnv,
  objectKey: string,
  parsed: { noteId: string; attachmentId: string },
  _userId: string,
): Promise<Response> {
  const contentType = request.headers.get('Content-Type');
  if (!isAllowedAttachmentMimeType(contentType)) {
    return new Response('Unsupported Media Type', { status: 415 });
  }

  const declared = declaredContentLength(request.headers.get('Content-Length'));
  if (declared != null && declared > MAX_ATTACHMENT_BYTES) {
    return new Response('Payload Too Large', { status: 413 });
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

  const mimeType = normalizeMimeType(contentType);
  // Phase 1: Preflight authorization check (does NOT insert live metadata)
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

  // Phase 2: Write bytes to R2
  await env.ATTACHMENTS_BUCKET.put(objectKey, body, {
    httpMetadata: { contentType: mimeType },
  });

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

  // Phase 3: Metadata finalization in database
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

async function deleteAttachment(
  request: Request,
  env: WorkerEnv,
  objectKey: string,
  parsed: { noteId: string; attachmentId: string },
): Promise<Response> {
  // Phase 1: Preflight delete authorization
  const authz = await authorizeAttachment(request, env, 'authorize_note_attachment_delete', {
    p_note_id: parsed.noteId,
    p_attachment_id: parsed.attachmentId,
  });
  if (!authz) return new Response('Unauthorized', { status: 401 });
  if (!authz.allowed) return new Response('Not Found', { status: 404 });

  // Security boundary: NEVER trust returned object_key over locally derived key!
  if (!authz.object_key || authz.object_key !== objectKey) {
    return new Response('Forbidden', { status: 403 });
  }

  // Phase 2: Idempotent R2 delete
  await env.ATTACHMENTS_BUCKET.delete(objectKey);

  // Phase 3: Finalize metadata delete
  await authorizeAttachment(request, env, 'finalize_note_attachment_delete', {
    p_note_id: parsed.noteId,
    p_attachment_id: parsed.attachmentId,
  });

  return Response.json({ deleted: true, objectKey });
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
    ctx.waitUntil(sweepOrphanedDeletedAttachments(env));
  },
};
