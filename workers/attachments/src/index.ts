import { resolveAuthenticatedUserId, type WorkerEnv } from './auth';
import { withAttachmentCors } from './cors';
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
      return putAttachment(request, env, objectKey, parsed, userId);
    case 'GET':
      return getAttachment(request, env, objectKey, parsed);
    case 'DELETE':
      return deleteAttachment(request, env, objectKey, parsed);
    default:
      return new Response('Method Not Allowed', { status: 405 });
  }
}

interface AttachmentAuthz {
  allowed?: boolean;
  object_key?: string;
  max_bytes?: number;
}

async function authorizeAttachment(
  request: Request,
  env: WorkerEnv,
  rpcName: string,
  body: Record<string, unknown>,
): Promise<AttachmentAuthz | null> {
  const authorization = request.headers.get('Authorization') ?? '';
  const response = await fetch(
    `${env.SUPABASE_URL.replace(/\/$/, '')}/rest/v1/rpc/${rpcName}`,
    {
      method: 'POST',
      headers: {
        apikey: env.SUPABASE_ANON_KEY,
        Authorization: authorization,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    },
  );
  if (response.status === 401) return null;
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

  // Refuse a declared oversize before reading a byte; the streaming read below is what catches a
  // caller that lies about, or omits, Content-Length.
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
  const authz = await authorizeAttachment(request, env, 'authorize_note_attachment_put', {
    p_note_id: parsed.noteId,
    p_attachment_id: parsed.attachmentId,
    p_mime_type: mimeType,
    p_size_bytes: body.byteLength,
  });
  if (!authz) return new Response('Unauthorized', { status: 401 });
  if (!authz.allowed) return new Response('Forbidden', { status: 403 });
  const canonicalKey = authz.object_key || objectKey;
  if (authz.max_bytes != null && body.byteLength > authz.max_bytes) {
    return new Response('Payload Too Large', { status: 413 });
  }
  await env.ATTACHMENTS_BUCKET.put(canonicalKey, body, {
    httpMetadata: { contentType: mimeType },
  });
  return Response.json({ objectKey: canonicalKey, sizeBytes: body.byteLength, mimeType });
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
  const canonicalKey = authz.object_key || objectKey;
  const object = await env.ATTACHMENTS_BUCKET.get(canonicalKey);
  if (!object) {
    return new Response('Not Found', { status: 404 });
  }
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set('etag', object.httpEtag);
  // Stored bytes are user-supplied. Never let a browser sniff them into something executable,
  // and never let one render in this Worker's origin.
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
  const authz = await authorizeAttachment(request, env, 'authorize_note_attachment_delete', {
    p_note_id: parsed.noteId,
    p_attachment_id: parsed.attachmentId,
  });
  if (!authz) return new Response('Unauthorized', { status: 401 });
  if (!authz.allowed) return new Response('Not Found', { status: 404 });
  const canonicalKey = authz.object_key || objectKey;
  await env.ATTACHMENTS_BUCKET.delete(canonicalKey);
  return Response.json({ deleted: true, objectKey: canonicalKey });
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
