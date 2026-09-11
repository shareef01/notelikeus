import { NOTES_DB_VERSION, PENDING_ATTACHMENTS_STORE } from '@/lib/local/constants';
import { getNotesDatabase } from '@/lib/local/idb';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import { loadRevisionState } from '@/lib/supabase/revisionStore';
import { isPendingAttachment } from '@/lib/attachments/attachmentPaths';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import {
  DIAGNOSTICS_SCHEMA_VERSION,
  ownerTag,
  type DiagnosticsReport,
  type ServiceWorkerState,
  type SyncErrorCategory,
} from '@/lib/diagnostics/diagnosticsReport';

/**
 * Sync outcomes recorded as they happen, so the report can say when the last one worked and what
 * kind of thing went wrong — without keeping the error object itself, which is where content
 * leaks (a revision conflict carries the remote note's title).
 */
let lastSuccessfulSyncAt: number | null = null;
let lastErrorCategory: SyncErrorCategory = 'none';
let realtimeState: DiagnosticsReport['sync']['realtime'] = 'unknown';

export function recordSyncSuccess(at: number = Date.now()): void {
  lastSuccessfulSyncAt = at;
  lastErrorCategory = 'none';
}

export function recordSyncFailure(category: SyncErrorCategory): void {
  lastErrorCategory = category;
}

export function recordRealtimeState(state: DiagnosticsReport['sync']['realtime']): void {
  realtimeState = state;
}

/** Test hook — clears the recorded outcomes between cases. */
export function resetDiagnosticsSignalsForTests(): void {
  lastSuccessfulSyncAt = null;
  lastErrorCategory = 'none';
  realtimeState = 'unknown';
}

async function readServiceWorkerState(): Promise<{
  state: ServiceWorkerState;
  updateAvailable: boolean;
}> {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
    return { state: 'unsupported', updateAvailable: false };
  }
  try {
    const registration = await navigator.serviceWorker.getRegistration();
    if (!registration) return { state: 'none', updateAvailable: false };
    if (registration.installing) return { state: 'installing', updateAvailable: true };
    if (registration.waiting) return { state: 'waiting', updateAvailable: true };
    if (registration.active) return { state: 'active', updateAvailable: false };
    return { state: 'unknown', updateAvailable: false };
  } catch {
    return { state: 'unknown', updateAvailable: false };
  }
}

async function readStagedAttachments(
  ownerId: string | null,
): Promise<{ count: number; bytes: number }> {
  if (!ownerId) return { count: 0, bytes: 0 };
  try {
    const db = await getNotesDatabase();
    return await new Promise((resolve) => {
      const tx = db.transaction(PENDING_ATTACHMENTS_STORE, 'readonly');
      const request = tx
        .objectStore(PENDING_ATTACHMENTS_STORE)
        .index('ownerId')
        .getAll(ownerId);
      request.onsuccess = () => {
        const rows = (request.result as { sizeBytes?: number }[] | undefined) ?? [];
        resolve({
          count: rows.length,
          bytes: rows.reduce((sum, row) => sum + (row.sizeBytes ?? 0), 0),
        });
      };
      // A diagnostics read must never be the thing that fails; an unreadable store simply
      // reports zero, which is itself a signal worth seeing in the report.
      tx.onerror = () => resolve({ count: 0, bytes: 0 });
      tx.onabort = () => resolve({ count: 0, bytes: 0 });
    });
  } catch {
    return { count: 0, bytes: 0 };
  }
}

/**
 * Assembles the current diagnostics report.
 *
 * Every read is defensive: this runs when something is already wrong, so a store that throws must
 * degrade the report rather than replace it with an error.
 */
export async function collectDiagnostics(
  appVersion: string,
  now: number = Date.now(),
): Promise<DiagnosticsReport> {
  const auth = useAuthStore.getState();
  const ownerId = resolveOwnerId();
  const notes = useNotesStore.getState().notes;
  const tombstones = useTombstoneStore.getState();

  const revision = ownerId
    ? await loadRevisionState(ownerId).catch(() => null)
    : null;

  const staged = await readStagedAttachments(ownerId);
  const serviceWorker = await readServiceWorkerState();

  const pendingUploads = notes.reduce(
    (count, note) =>
      count + note.attachments.filter((a) => isPendingAttachment(a.storagePath)).length,
    0,
  );

  // A note the server has never confirmed, or one edited since it last did. This is the number
  // behind "why does it still say pending".
  const knownCloudIds = new Set(revision?.knownCloudIds ?? []);
  const pendingMutations = notes.filter(
    (note) => note.serverUpdatedAt == null || !knownCloudIds.has(note.id),
  ).length;

  return {
    schemaVersion: DIAGNOSTICS_SCHEMA_VERSION,
    generatedAt: now,
    app: { version: appVersion, platform: 'web' },
    storage: {
      kind: 'IndexedDB',
      schemaVersion: NOTES_DB_VERSION,
      available: typeof indexedDB !== 'undefined',
      // Notes are AES-GCM sealed at rest (NLN1). Profile-at-rest only — not an XSS control.
      encryptedAtRest: true,
    },
    account: {
      state: auth.user ? 'signed-in' : auth.guestMode ? 'guest' : 'signed-out',
      ownerTag: ownerTag(ownerId),
    },
    notes: {
      total: notes.length,
      active: notes.filter((note) => !note.isArchived && !note.isTrashed).length,
      archived: notes.filter((note) => note.isArchived).length,
      trashed: notes.filter((note) => note.isTrashed).length,
      pinned: notes.filter((note) => note.isPinned).length,
      withReminder: notes.filter((note) => note.reminderTimestamp != null).length,
      withAttachments: notes.filter((note) => note.attachments.length > 0).length,
    },
    sync: {
      lastRemoteRevision: revision?.lastRemoteRevision ?? 0,
      knownCloudIdCount: knownCloudIds.size,
      trackedRevisionCount: Object.keys(revision?.noteRevisions ?? {}).length,
      pendingMutationCount: pendingMutations,
      tombstoneCount: Object.keys(tombstones.deletedAtById).length,
      pendingRestoreCount: tombstones.restoredIds.length,
      lastSuccessfulSyncAt,
      lastErrorCategory,
      realtime: realtimeState,
    },
    attachments: {
      stagedCount: staged.count,
      stagedBytes: staged.bytes,
      pendingUploadCount: pendingUploads,
      unresolvedCleanupCount: Math.max(0, staged.count - pendingUploads),
      // Pending IndexedDB blobs are AES-GCM sealed (NLA1).
      encryptedAtRest: true,
    },
    serviceWorker,
  };
}
