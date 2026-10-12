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

    // Claim before touching a byte. The listing is a snapshot, and a note can be restored
    // between building it and getting here — deleting the object on the strength of the
    // snapshot left live metadata pointing at an object that no longer existed. The claim
    // re-checks eligibility under a row lock, so a restore either wins outright or finds the
    // attachment already claimed and leaves it deleted.
    const claim = await rpc<{ claimed?: boolean; object_key?: string }>(
      env,
      'claim_orphaned_attachment_for_delete',
      { p_owner_id: ownerId, p_note_id: noteId, p_attachment_id: attachmentId },
    );
    if (!claim?.claimed) {
      skipped += 1;
      continue;
    }
    // Never trust a key handed back by the database over the one derived locally.
    if (claim.object_key !== expected) {
      skipped += 1;
      continue;
    }

    try {
      await env.ATTACHMENTS_BUCKET.delete(objectKey);
    } catch {
      // The claim persists, so the next sweep retries this same row rather than losing it.
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

/** Objects younger than this may belong to an upload still finalizing or still being retried. */
const UNREFERENCED_MIN_AGE_MS = 7 * 24 * 60 * 60 * 1000;
/** Pages of 1000 keys per run: bounds the run's cost, and the report says when it stopped short. */
const UNREFERENCED_MAX_PAGES = 5;

export interface UnreferencedReport {
  scanned: number;
  unreferenced: number;
  unreferencedBytes: number;
  /** True when the listing had more pages than one run reads, so the counts are a lower bound. */
  truncated: boolean;
}

/**
 * Counts R2 objects at least a week old that no attachment row references, and logs the total.
 *
 * These are bytes an upload left behind when finalization failed ambiguously and the client never
 * retried: since F-08 the Worker keeps them rather than risk deleting a committed attachment's
 * bytes. Report only — nothing is deleted. A deleting sweep would also have to keep a late retry of
 * the same upload working, which the clients' lack of 409 handling makes non-trivial; this measures
 * whether that is worth building.
 */
export async function reportUnreferencedAttachmentObjects(
  env: WorkerEnv,
  now: number = Date.now(),
): Promise<UnreferencedReport> {
  const report: UnreferencedReport = { scanned: 0, unreferenced: 0, unreferencedBytes: 0, truncated: false };
  if (!serviceRoleHeaders(env)) return report;

  let cursor: string | undefined;
  for (let page = 0; page < UNREFERENCED_MAX_PAGES; page++) {
    const listing = await env.ATTACHMENTS_BUCKET.list({ prefix: 'owners/', cursor, limit: 1000 });
    report.scanned += listing.objects.length;
    const old = listing.objects.filter(
      (object) => now - object.uploaded.getTime() >= UNREFERENCED_MIN_AGE_MS,
    );
    if (old.length > 0) {
      const missing = await rpc<string[]>(env, 'find_unreferenced_attachment_objects', {
        p_object_keys: old.map((object) => object.key),
      });
      const unreferenced = new Set(Array.isArray(missing) ? missing : []);
      for (const object of old) {
        if (!unreferenced.has(object.key)) continue;
        report.unreferenced += 1;
        report.unreferencedBytes += object.size;
      }
    }
    report.truncated = listing.truncated;
    if (!listing.truncated) break;
    cursor = listing.cursor;
  }

  console.log('[Sweep] Unreferenced attachment objects', report);
  return report;
}

/**
 * Claims provisional uploads that no note commit will ever promote (F-09).
 *
 * Which rows qualify is decided entirely by `claim_orphaned_provisional_attachments`, under row locks
 * and only on positive evidence that nothing will commit them. This pass deletes no bytes itself: a
 * claimed row is exactly what an owner's DELETE leaves behind, so
 * [sweepUnconfirmedAttachmentDeletes] removes the object on a later run, through the same
 * canonical-key checks as every other delete.
 */
export async function claimOrphanedProvisionalAttachments(
  env: WorkerEnv,
): Promise<{ claimed: number }> {
  if (!serviceRoleHeaders(env)) {
    return { claimed: 0 };
  }
  const rows = await rpc<OrphanRow[]>(env, 'claim_orphaned_provisional_attachments', {
    p_limit: SWEEP_LIMIT,
  });
  return { claimed: Array.isArray(rows) ? rows.length : 0 };
}

/**
 * Finishes attachment deletes that were claimed but never confirmed.
 *
 * A user DELETE marks the metadata deleted before it touches R2, so a request that dies in
 * between leaves bytes nothing references. The row records the claim, so this pass can pick it
 * up hours later, delete the object, and stamp the confirmation. It never removes metadata —
 * whether the row itself may go is the orphan sweep's decision, under its own retention rules.
 */
export async function sweepUnconfirmedAttachmentDeletes(env: WorkerEnv): Promise<SweepResult> {
  if (!serviceRoleHeaders(env)) {
    return { scanned: 0, deleted: 0, skipped: 0 };
  }

  const rows = await rpc<OrphanRow[]>(env, 'list_unconfirmed_attachment_deletes', {
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
    // Never trust a stored key over the one derived from the owner: the delete has to stay
    // inside the owner's namespace even if the column were somehow wrong.
    if (objectKey !== expected) {
      skipped += 1;
      continue;
    }

    try {
      await env.ATTACHMENTS_BUCKET.delete(objectKey);
    } catch {
      // The claim persists and stays unconfirmed, so the next sweep retries this same row.
      skipped += 1;
      continue;
    }

    const confirmed = await rpc<{ status?: string }>(env, 'confirm_attachment_object_deleted', {
      p_owner_id: ownerId,
      p_note_id: noteId,
      p_attachment_id: attachmentId,
    });
    if (confirmed?.status === 'applied') deleted += 1;
    else skipped += 1;
  }

  return { scanned: rows.length, deleted, skipped };
}
