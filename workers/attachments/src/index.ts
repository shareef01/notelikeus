import { resolveAuthenticatedUserId, type WorkerEnv } from './auth';
import { withAttachmentCors } from './cors';
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
  const body = await request.arrayBuffer();
  const mimeType = request.headers.get('Content-Type')?.trim() || 'application/octet-stream';
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
