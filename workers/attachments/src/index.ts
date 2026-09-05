import { resolveAuthenticatedUserId, type WorkerEnv } from './auth';
import { withAttachmentCors } from './cors';
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
      return putAttachment(request, env, objectKey);
    case 'GET':
      return getAttachment(env, objectKey);
    case 'DELETE':
      return deleteAttachment(env, objectKey);
    default:
      return new Response('Method Not Allowed', { status: 405 });
  }
}

async function putAttachment(
  request: Request,
  env: WorkerEnv,
  objectKey: string,
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
  await env.ATTACHMENTS_BUCKET.put(objectKey, body, {
    httpMetadata: { contentType: mimeType },
  });
  return Response.json({ objectKey, sizeBytes: body.byteLength, mimeType });
}

async function getAttachment(env: WorkerEnv, objectKey: string): Promise<Response> {
  const object = await env.ATTACHMENTS_BUCKET.get(objectKey);
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

async function deleteAttachment(env: WorkerEnv, objectKey: string): Promise<Response> {
  await env.ATTACHMENTS_BUCKET.delete(objectKey);
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
};
