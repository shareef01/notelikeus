/**
 * A privacy-preserving snapshot of sync state, for troubleshooting.
 *
 * The problems this exists for — "my note did not appear on my phone", "it says pending forever",
 * "the images are gone" — are all questions about *counts and cursors*, not about content. So the
 * report carries only counts and cursors, and {@link assertNoSensitiveValues} is what keeps it
 * that way: it runs on every report before it can be shown or copied, so a field added later
 * cannot quietly start leaking.
 *
 * Deliberately absent, and enforced below: note titles and bodies, checklist and label text,
 * attachment bytes, access and refresh tokens, raw JWTs, email addresses, OAuth profile data, and
 * the owner id in unredacted form.
 */

import { formatUnknownError } from '@/lib/errors/formatUnknownError';

export type SyncErrorCategory =
  | 'none'
  | 'offline'
  | 'auth'
  | 'permission'
  | 'conflict'
  | 'empty-cloud-refused'
  | 'storage'
  | 'upstream'
  | 'unknown';

export type ServiceWorkerState =
  | 'unsupported'
  | 'none'
  | 'installing'
  | 'waiting'
  | 'active'
  | 'unknown';

export interface DiagnosticsReport {
  schemaVersion: number;
  generatedAt: number;
  app: { version: string; platform: string };
  storage: {
    kind: string;
    schemaVersion: number;
    available: boolean;
    /** Whether the notes at rest are encrypted by the app. */
    encryptedAtRest: boolean;
  };
  account: {
    /** 'guest' | 'signed-in' | 'signed-out'. Never an email or a provider profile. */
    state: 'guest' | 'signed-in' | 'signed-out';
    /** A stable, non-reversible tag for the owner namespace. Never the owner id itself. */
    ownerTag: string;
  };
  notes: {
    total: number;
    active: number;
    archived: number;
    trashed: number;
    pinned: number;
    withReminder: number;
    withAttachments: number;
  };
  sync: {
    lastRemoteRevision: number;
    knownCloudIdCount: number;
    trackedRevisionCount: number;
    pendingMutationCount: number;
    tombstoneCount: number;
    pendingRestoreCount: number;
    lastSuccessfulSyncAt: number | null;
    lastErrorCategory: SyncErrorCategory;
    realtime: 'subscribed' | 'unsubscribed' | 'error' | 'unknown';
  };
  attachments: {
    stagedCount: number;
    stagedBytes: number;
    pendingUploadCount: number;
    unresolvedCleanupCount: number;
    /** Whether staged pending blobs are sealed by the app (profile-at-rest only). */
    encryptedAtRest: boolean;
  };
  serviceWorker: { state: ServiceWorkerState; updateAvailable: boolean };
}

export const DIAGNOSTICS_SCHEMA_VERSION = 1;

/**
 * A short, stable, non-reversible tag for an owner id.
 *
 * Enough to tell "these two reports are the same account" and "this report is a different account
 * from that one" — which is the only question support actually needs — without carrying a value
 * that identifies anyone or that could be replayed anywhere.
 *
 * Not a cryptographic commitment and not trying to be: the input is a UUID the user themselves
 * cannot enumerate, and the point is redaction, not secrecy against the account holder.
 */
export function ownerTag(ownerId: string | null | undefined): string {
  if (!ownerId) return 'none';
  if (ownerId === '__guest__') return 'guest';
  let hash = 0x811c9dc5;
  for (let index = 0; index < ownerId.length; index++) {
    hash ^= ownerId.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return `acct-${hash.toString(16).padStart(8, '0')}`;
}

/**
 * Maps a thrown error onto a stable category.
 *
 * A category rather than the message, because the message is where content leaks: a conflict
 * error from `apply_note_change` embeds the remote note's *title*, and a storage failure can
 * carry a key. The category is what a maintainer can act on anyway.
 */
export function categorizeSyncError(error: unknown): SyncErrorCategory {
  if (error == null) return 'none';
  const message = formatUnknownError(error, '').toLowerCase();
  if (!message) return 'unknown';
  if (message.includes('refusing to overwrite') || message.includes('refusing to delete')) {
    return 'empty-cloud-refused';
  }
  if (message.includes('session missing') || message.includes('jwt') || message.includes('401')) {
    return 'auth';
  }
  if (message.includes('row-level security') || message.includes('403') || message.includes('permission')) {
    return 'permission';
  }
  if (message.includes('revision conflict') || message.includes('was deleted in the cloud')) {
    return 'conflict';
  }
  if (message.includes('indexeddb') || message.includes('quota')) return 'storage';
  if (
    message.includes('failed to fetch') ||
    message.includes('networkerror') ||
    message.includes('offline')
  ) {
    return 'offline';
  }
  if (message.includes('upstream') || message.includes('502') || message.includes('503')) {
    return 'upstream';
  }
  return 'unknown';
}

/**
 * Every substring a report must never contain, as a lowercase needle.
 *
 * Checked against the *serialized* report rather than field by field, so a nested object added
 * later is covered without anyone remembering to extend a list of paths.
 */
const FORBIDDEN_SUBSTRINGS = [
  'access_token',
  'refresh_token',
  'accesstoken',
  'refreshtoken',
  'id_token',
  'idtoken',
  'apikey',
  'api_key',
  'authorization',
  'bearer ',
  'password',
  'passphrase',
  'secret',
  'private_key',
  'privatekey',
  '@', // an email address, an OAuth profile, or an object key with an owner in it
  // The R2 object-key prefix. Caught by its structure rather than only by the owner id inside
  // it, so a truncated or rewritten key is refused too.
  'owners/',
  // Local storage paths for staged bytes, on every client.
  'pending:',
  'r2:',
  'pending-attachments/',
];

/** A JWT in any of its usual shapes. */
const JWT_PATTERN = /eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\./;
/** A bare UUID — the shape of an owner id, a note id in the cloud, or an attachment id. */
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

export class DiagnosticsLeakError extends Error {}

/**
 * Throws if [report] carries anything it must not.
 *
 * A hard failure rather than a scrub: silently removing a leaked field would leave the code that
 * put it there in place, and the next field it adds would be the one nobody checks. The report is
 * small and fully under this repository's control, so failing loudly is affordable.
 */
export function assertNoSensitiveValues(report: DiagnosticsReport): void {
  const serialized = JSON.stringify(report);
  const lowered = serialized.toLowerCase();
  for (const needle of FORBIDDEN_SUBSTRINGS) {
    if (lowered.includes(needle)) {
      throw new DiagnosticsLeakError(`Diagnostics report contains "${needle}"`);
    }
  }
  if (JWT_PATTERN.test(serialized)) {
    throw new DiagnosticsLeakError('Diagnostics report contains what looks like a JWT');
  }
  if (UUID_PATTERN.test(serialized)) {
    throw new DiagnosticsLeakError('Diagnostics report contains a raw identifier');
  }
}

/** Serializes a report for the clipboard or a file, after checking it is safe to hand over. */
export function formatDiagnosticsReport(report: DiagnosticsReport): string {
  assertNoSensitiveValues(report);
  return JSON.stringify(report, null, 2);
}
