import { getOwnerMeta, updateOwnerMeta } from '@/lib/local/notesLocalRepository';

/**
 * The account's cloud data was deleted from another device, and this browser has not caught up.
 *
 * Server-side, `delete_all_user_cloud_data()` increments the account's durable wipe generation and
 * every note or tombstone write must present it (see
 * `supabase/migrations/20261013000000_account_wipe_generation.sql`). These are the SQLSTATEs the
 * guard raises, and the whole reason a stale device cannot put the deleted notes back.
 */
export const WIPE_GENERATION_REQUIRED = 'NL001';
export const WIPE_GENERATION_STALE = 'NL002';

/** Why a write was refused: no generation at all, or one older than the account's. */
export type WipeGenerationRefusal = 'required' | 'stale';

/**
 * A push was refused because the account was wiped elsewhere.
 *
 * Never retried automatically: retrying with the same generation cannot succeed, and retrying with
 * a fresh one is exactly the silent re-upload this protocol exists to prevent. The user decides.
 */
export class CloudWipedElsewhereError extends Error {
  readonly refusal: WipeGenerationRefusal;

  constructor(refusal: WipeGenerationRefusal, message?: string) {
    super(
      message ??
        'This account’s cloud notes were deleted from another device. Nothing was uploaded from ' +
          'this browser — your notes here are untouched.',
    );
    this.name = 'CloudWipedElsewhereError';
    this.refusal = refusal;
  }
}

/**
 * Reads the refusal out of a PostgREST error.
 *
 * The code is the SQLSTATE from the trigger's `RAISE`, which PostgREST passes through verbatim.
 * The message prefix is checked too, so a proxy that rewrites the body does not turn a definite
 * refusal into a silent failure.
 */
export function wipeGenerationRefusalOf(error: unknown): WipeGenerationRefusal | null {
  const code = (error as { code?: unknown } | null)?.code;
  if (code === WIPE_GENERATION_REQUIRED) return 'required';
  if (code === WIPE_GENERATION_STALE) return 'stale';
  const message = (error as { message?: unknown } | null)?.message;
  if (typeof message === 'string') {
    if (message.includes('account_wipe_generation_required')) return 'required';
    if (message.includes('account_wipe_generation_stale')) return 'stale';
  }
  return null;
}

/** The generation this browser last learned from the server. 0 for an account never wiped. */
export async function readWipeGeneration(ownerId: string): Promise<number> {
  const meta = await getOwnerMeta(ownerId);
  return meta?.wipeGeneration ?? 0;
}

/** Whether a push is currently refused until the user decides. */
export async function pushesBlockedByWipe(ownerId: string): Promise<boolean> {
  const meta = await getOwnerMeta(ownerId);
  return meta?.wipeBlockedAt != null;
}

/**
 * Records a generation the server published. Monotonic, and never on its own enough to lift a
 * block: learning the new generation is not the same as being allowed to upload, which is a
 * decision the user makes.
 */
export async function recordWipeGeneration(ownerId: string, generation: number): Promise<void> {
  await updateOwnerMeta(ownerId, (meta) => {
    if (!Number.isFinite(generation) || generation < 0) return null;
    if ((meta.wipeGeneration ?? 0) >= generation) return null;
    return { ...meta, wipeGeneration: generation };
  });
}

/**
 * The generation to present on a push, or a throw when this browser is not allowed to push.
 *
 * Called before every note mutation, because the server refuses a write that presents nothing and
 * this is the client-side half of the same rule: while the block is set, nothing is attempted, so
 * a wipe cannot be reversed by a retry loop.
 */
export async function wipeGenerationForPush(ownerId: string): Promise<number> {
  const meta = await getOwnerMeta(ownerId);
  if (meta?.wipeBlockedAt != null) {
    throw new CloudWipedElsewhereError(
      'stale',
      'This account’s cloud notes were deleted from another device and this browser has not ' +
        'caught up. Nothing was uploaded — your notes here are untouched.',
    );
  }
  return meta?.wipeGeneration ?? 0;
}

/** Records a refusal so no further push is attempted until the user decides. */
export async function blockPushesAfterWipe(ownerId: string): Promise<void> {
  await updateOwnerMeta(ownerId, (meta) =>
    meta.wipeBlockedAt != null ? null : { ...meta, wipeBlockedAt: Date.now() },
  );
}

/**
 * Lifts the block and lets this browser push its notes up as new ones — the user's explicit
 * "upload my notes again".
 *
 * Clearing `knownCloudIds`/`noteRevisions` is the load-bearing part. Their absence from the cloud
 * is what a wipe creates, and while they are recorded as known, the next sync reads that absence
 * as "deleted elsewhere" and would delete the local copies instead of uploading them. Forgetting
 * them makes these notes local-only, which is exactly what they now are, and the next sync
 * uploads each one as a note the cloud has never seen.
 *
 * Only ever called from that user action: the server cannot tell this apart from a stale push
 * arriving after a wipe, so the decision has to come from the person who asked for the wipe.
 */
export async function reuploadLocalNotesAfterWipe(
  ownerId: string,
  generation: number,
): Promise<void> {
  await updateOwnerMeta(ownerId, (meta) => ({
    ...meta,
    wipeGeneration: Math.max(meta.wipeGeneration ?? 0, generation),
    wipeBlockedAt: undefined,
    knownCloudIds: [],
    noteRevisions: {},
    lastRemoteRevision: 0,
  }));
}

/**
 * Turns a mutation error into a typed [CloudWipedElsewhereError], recording the block first.
 *
 * Anything else is rethrown untouched: only the two wipe SQLSTATEs are this protocol's.
 */
export async function asWipeRefusal(ownerId: string, error: unknown): Promise<never> {
  const refusal = wipeGenerationRefusalOf(error);
  if (!refusal) throw error;
  await blockPushesAfterWipe(ownerId);
  throw new CloudWipedElsewhereError(refusal);
}
