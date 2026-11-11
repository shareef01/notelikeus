import { clearOwner, countPendingPushes } from '@/lib/local/notesLocalRepository';
import {
  clearPendingAttachmentsForOwner,
  countPendingAttachmentsForOwner,
} from '@/lib/local/pendingAttachmentRepository';
import { isSupabaseBackendEnabled, loadSupabaseAnonKey, loadSupabaseUrl } from '@/lib/supabase/env';
import { isBrowserSafeSupabaseKey } from '@/lib/supabase/backendFlag';
import { LEGACY_NOTES_STORAGE_KEY } from '@/lib/notes/legacyLocalMigration';
import { LAST_MERGED_USER_STORAGE_KEY } from '@/lib/notes/lastMergedUser';
import { forgetSignedIn, SESSION_HINT_STORAGE_KEY } from '@/lib/auth/sessionHint';
import { ensureReminderSync } from '@/lib/reminders/reminderSync';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { useUiStore } from '@/store/uiStore';
import { clearGuestAdoptionIntent } from '@/lib/local/guestAdoptionIntent';
import { clearPendingAttachmentStore } from '@/lib/attachments/pendingAttachmentStore';
import { clearAttachmentThumbnailCache } from '@/lib/attachments/attachmentThumbnailCache';
import { clearAttachmentPreviewCache } from '@/lib/attachments/attachmentPreviewCache';
import {
  clearAllPendingDeletionStashes,
  stashPendingDeletions,
} from '@/lib/notes/pendingDeletionStash';

export type BootFailureCode = 'storage' | 'supabase-config' | 'unknown';

export class BootFailure extends Error {
  readonly code: BootFailureCode;
  override readonly cause?: unknown;

  constructor(message: string, code: BootFailureCode, cause?: unknown) {
    super(message);
    this.name = 'BootFailure';
    this.code = code;
    this.cause = cause;
  }
}

const STORAGE_KEYS = [
  LEGACY_NOTES_STORAGE_KEY,
  LAST_MERGED_USER_STORAGE_KEY,
  'notelikeus-note-filters',
  'notelikeus-settings',
  'notelikeus-ui',
  'notelikeus-label-registry',
  'notelikeus-deleted-notes',
  'notelikeus-lock-key',
  SESSION_HINT_STORAGE_KEY,
] as const;

/**
 * User-owned data — cleared on sign-out. Settings/UI/filter prefs stay, since
 * those aren't per-account. LAST_MERGED_USER_STORAGE_KEY is intentionally preserved
 * so that a subsequent sign-in by a different account can detect the switch and trigger
 * clearLocalUserDataForAccountSwitch.
 */
const USER_DATA_STORAGE_KEYS = [
  LEGACY_NOTES_STORAGE_KEY,
  'notelikeus-label-registry',
  'notelikeus-lock-key',
  SESSION_HINT_STORAGE_KEY,
] as const;

/** Tombstones. Cleared only alongside the notes themselves — see clearPendingDeletions. */
const DELETED_NOTES_STORAGE_KEY = 'notelikeus-deleted-notes';

const REHYDRATE_TIMEOUT_MS = 8_000;
const OPTIONAL_REHYDRATE_TIMEOUT_MS = 4_000;

function withTimeout(promise: Promise<void>, ms: number, label: string): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const timeoutId = window.setTimeout(() => {
        console.warn(`[Notelikeus] ${label} timed out after ${ms}ms — continuing startup.`);
        resolve();
    }, ms);
    promise.then(
      () => {
        window.clearTimeout(timeoutId);
        resolve();
      },
      (error: unknown) => {
        window.clearTimeout(timeoutId);
        reject(error);
      },
    );
  });
}

/** Fail closed on timeout so sync never runs against a half-empty tombstone/label store. */
function requireRehydrate(promise: Promise<void>, ms: number, label: string): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const timeoutId = window.setTimeout(
      () => reject(new Error(`${label} timed out after ${ms}ms`)),
      ms,
    );
    promise.then(
      () => {
        window.clearTimeout(timeoutId);
        resolve();
      },
      (error: unknown) => {
        window.clearTimeout(timeoutId);
        reject(error);
      },
    );
  });
}

async function rehydrateStores(): Promise<void> {
  await Promise.all([
    requireRehydrate(
      Promise.resolve(useNotesStore.persist.rehydrate()),
      REHYDRATE_TIMEOUT_MS,
      'Note filters rehydrate',
    ),
    requireRehydrate(
      Promise.resolve(useTombstoneStore.persist.rehydrate()),
      REHYDRATE_TIMEOUT_MS,
      'Tombstone store rehydrate',
    ),
    requireRehydrate(
      Promise.resolve(useLabelRegistryStore.persist.rehydrate()),
      REHYDRATE_TIMEOUT_MS,
      'Label registry rehydrate',
    ),
  ]);

  await Promise.all([
    withTimeout(
      Promise.resolve(useSettingsStore.persist.rehydrate()),
      OPTIONAL_REHYDRATE_TIMEOUT_MS,
      'Settings store rehydrate',
    ),
    withTimeout(
      Promise.resolve(useUiStore.persist.rehydrate()),
      OPTIONAL_REHYDRATE_TIMEOUT_MS,
      'UI store rehydrate',
    ),
  ]);
}

/**
 * Removes the app's localStorage records. The explicit "clear local data" action passes nothing
 * and removes pending deletions with everything else. Automatic startup recovery passes
 * keepPendingDeletions (AUDIT-007): a store that failed to load is no reason to destroy the only
 * record of notes deleted offline, which would come back on the next sync.
 */
export function clearPersistedAppData(options: { keepPendingDeletions?: boolean } = {}): void {
  if (!options.keepPendingDeletions) clearAllPendingDeletionStashes();
  for (const key of STORAGE_KEYS) {
    if (options.keepPendingDeletions && key === DELETED_NOTES_STORAGE_KEY) continue;
    try {
      localStorage.removeItem(key);
    } catch {
      // ignore
    }
  }
}

/**
 * Deletion intent that has not reached the server yet.
 *
 * A note deleted while offline exists nowhere afterwards except as a tombstone: the local row is
 * already gone and the server copy is still live. Clearing this is therefore as destructive as
 * clearing the notes themselves, and belongs only where the notes are cleared too — an account
 * switch, or entering guest mode. See [clearLocalUserData], which deliberately does not.
 *
 * With the owner's id, the deletions are put aside under that account rather than destroyed
 * (AUDIT-004), and come back when it signs in again. They are discarded only when the owner is
 * unknown, or the stash could not be written: they must never be applied to another account.
 */
export function clearPendingDeletions(ownerId?: string | null): void {
  if (ownerId) {
    stashPendingDeletions(ownerId);
    return;
  }
  useTombstoneStore.getState().reset();
  try {
    localStorage.removeItem(DELETED_NOTES_STORAGE_KEY);
  } catch {
    // ignore
  }
}

/**
 * Clears in-memory UI state and persisted labels.
 *
 * IndexedDB notes are intentionally preserved so offline edits survive sign-out and re-login
 * under the same account namespace — and unsynced deletions are preserved for exactly the same
 * reason. Clearing tombstones here used to resurrect notes: deleting a note offline removes it
 * locally and records a tombstone, signing out destroyed that tombstone, and the next sign-in
 * hydrated the still-live server copy straight back.
 */
export function clearLocalUserData(): void {
  useNotesStore.getState().reset();
  useLabelRegistryStore.getState().reset();
  // The navigation drawer belongs to the session that opened it. Signing out from inside it left
  // it open across the switch, so the next account arrived at a notes list with a modal drawer
  // still covering it and swallowing taps.
  useUiStore.getState().setDrawerOpen(false);
  // Recent searches are words the user typed about this account's notes. They persist in
  // `notelikeus-ui` beside device preferences, so wiping the account's data did not reach them and
  // the next person on the browser saw them (NL-07; native does the same at every account
  // boundary, NL-02). The rest of that store — view size, editor layout — is the device's and stays.
  useUiStore.getState().clearRecentSearches();
  // Saved filters name this account's labels and searches; the next account must not inherit them
  // (native NL-02). Theme, columns and layout are the device's and stay.
  useUiStore.getState().clearSavedFilters();
  clearPendingAttachmentStore();
  // Card thumbnails and editor previews are copies of the account's pictures; they go with the rest of its data.
  clearAttachmentThumbnailCache();
  clearAttachmentPreviewCache();
  forgetSignedIn();
  for (const key of USER_DATA_STORAGE_KEYS) {
    try {
      localStorage.removeItem(key);
    } catch {
      // ignore
    }
  }
}

/**
 * Account switch only: clear session state and wipe the prior account's IndexedDB namespace and
 * pending attachments so the next account cannot read it. Normal sign-out must NOT call this —
 * unsynced offline edits must remain in IndexedDB until the same account signs in again and syncs.
 *
 * The same holds across a switch (FU-1): while the prior account still has edits the cloud has not
 * confirmed, or images that never uploaded (AUDIT-002), or when that cannot be checked (AUDIT-006),
 * its namespace is the only copy of them, so it is left in place — owner-scoped and
 * sealed, out of the new account's reach — and uploads the next time that account signs in here.
 * The in-memory and session state is still cleared either way.
 */
export async function clearLocalUserDataForAccountSwitch(previousOwnerId: string): Promise<void> {
  clearGuestAdoptionIntent();
  clearLocalUserData();
  // A different account must not inherit this one's pending deletions, and this one must not lose
  // them (AUDIT-004): they are put aside under its id until it signs in here again.
  clearPendingDeletions(previousOwnerId);
  clearPendingAttachmentStore();
  try {
    localStorage.removeItem(LAST_MERGED_USER_STORAGE_KEY);
  } catch {
    // ignore
  }
  // Staged images count as unsynced work too. The note text is pushed before its images upload, so a
  // note can be fully synced while an image it references failed to upload (quota full, Worker
  // unreachable) and exists nowhere but here (AUDIT-002).
  let unsyncedEdits: number;
  let stagedImages: number;
  try {
    [unsyncedEdits, stagedImages] = await Promise.all([
      countPendingPushes(previousOwnerId),
      countPendingAttachmentsForOwner(previousOwnerId),
    ]);
  } catch (error) {
    // A count that could not be read is not a count of zero: deleting on it could destroy the only
    // copy of the previous account's work (AUDIT-006). Keep the namespace; it is owner-scoped and
    // sealed, so the next account cannot read it.
    console.warn("[Notelikeus] Keeping the previous account's local data: could not check it for unsynced work", error);
    return;
  }
  if (unsyncedEdits > 0 || stagedImages > 0) {
    console.warn(
      `[Notelikeus] Keeping the previous account's local data: ${unsyncedEdits} edit(s) and ` +
        `${stagedImages} image(s) have not been uploaded yet`,
    );
    return;
  }
  await clearOwner(previousOwnerId);
  try {
    await clearPendingAttachmentsForOwner(previousOwnerId);
  } catch (error) {
    console.warn('[Notelikeus] Failed to clear pending attachments on account switch:', error);
  }
}

/** Runs once before the app shell renders. Never blocks forever. */
export async function bootstrapApp(): Promise<void> {
  try {
    await rehydrateStores();
  } catch (error) {
    console.error('[Notelikeus] Rehydrate failed, clearing persisted data:', error);
    clearPersistedAppData({ keepPendingDeletions: true });
    try {
      await rehydrateStores();
    } catch (retryError) {
      console.error('[Notelikeus] Rehydrate retry failed; starting empty:', retryError);
      clearLocalUserData();
      throw new BootFailure(
        'Local app data could not be restored. Clear local data and retry.',
        'storage',
        retryError,
      );
    }
  }

  ensureReminderSync();

  if (!isBrowserSafeSupabaseKey(loadSupabaseAnonKey()) || !loadSupabaseUrl().trim()) {
    throw new BootFailure(
      'Supabase is not configured for this web build.',
      'supabase-config',
    );
  }
  if (!isSupabaseBackendEnabled()) {
    throw new BootFailure(
      'This production build needs VITE_SUPABASE_URL pointing at a hosted Supabase project.',
      'supabase-config',
    );
  }
}
