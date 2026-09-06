import type { WorkerEnv } from './auth';
import { buildAttachmentObjectKey } from './objectKey';

export interface SweepResult {
  scanned: number;
  deleted: number;
  skipped: number;
}

interface OrphanRow {
  owner_id?: string;
  note_id?: string;
  attachment_id?: string;
  object_key?: string;
}

const SWEEP_LIMIT = 50;

function serviceRoleHeaders(env: WorkerEnv): HeadersInit | null {
  const key = env.SUPABASE_SERVICE_ROLE_KEY?.trim();
  if (!key) return null;
  return {
    apikey: key,
    Authorization: `Bearer ${key}`,
    'Content-Type': 'application/json',
  };
}

async function rpc<T>(
  env: WorkerEnv,
  name: string,
  body: Record<string, unknown>,
): Promise<T | null> {
  const headers = serviceRoleHeaders(env);
  if (!headers) return null;
  let response: Response;
  try {
    response = await fetch(`${env.SUPABASE_URL.replace(/\/$/, '')}/rest/v1/rpc/${name}`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    console.error('[Sweep] Upstream RPC network failure', { rpc: name });
    throw new Error(`Sweep RPC network failure: ${name}`);
  }
  if (!response.ok) {
    console.error('[Sweep] Upstream RPC failed', { rpc: name, status: response.status });
    throw new Error(`Sweep RPC failed: ${name} (${response.status})`);
  }
  try {
    return (await response.json()) as T;
  } catch {
    console.error('[Sweep] Upstream RPC malformed response', { rpc: name });
    throw new Error(`Sweep RPC malformed JSON: ${name}`);
  }
}

/**
 * Deletes R2 objects whose Postgres metadata has been deleted for ≥24h, the
 * note is tombstoned, and the note is not live. Prefer orphan storage over
 * deleting a blob we cannot prove is dead.
 */
export async function sweepOrphanedDeletedAttachments(env: WorkerEnv): Promise<SweepResult> {
  if (!serviceRoleHeaders(env)) {
    return { scanned: 0, deleted: 0, skipped: 0 };
  }

  const rows = await rpc<OrphanRow[]>(env, 'list_orphaned_deleted_attachments', {
    p_limit: SWEEP_LIMIT,
  });
  if (!Array.isArray(rows)) {
    return { scanned: 0, deleted: 0, skipped: 0 };
  }

  let deleted = 0;
  let skipped = 0;
  for (const row of rows) {
    const ownerId = row.owner_id?.trim() ?? '';
    const noteId = row.note_id?.trim() ?? '';
    const attachmentId = row.attachment_id?.trim() ?? '';
    const objectKey = row.object_key?.trim() ?? '';
    let expected = '';
    try {
      expected = buildAttachmentObjectKey(ownerId, noteId, attachmentId);
    } catch {
      skipped += 1;
      continue;
    }
    if (objectKey !== expected) {
      skipped += 1;
      continue;
    }

    try {
      await env.ATTACHMENTS_BUCKET.delete(objectKey);
    } catch {
      skipped += 1;
      continue;
    }

    const purged = await rpc<{ status?: string }>(env, 'purge_orphaned_deleted_attachment', {
      p_owner_id: ownerId,
      p_note_id: noteId,
      p_attachment_id: attachmentId,
    });
    if (purged?.status === 'applied') deleted += 1;
    else skipped += 1;
  }

  return { scanned: rows.length, deleted, skipped };
}
