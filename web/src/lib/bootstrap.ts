import { clearOwner } from '@/lib/local/notesLocalRepository';
import { isSupabaseBackendEnabled, loadSupabaseAnonKey, loadSupabaseUrl } from '@/lib/supabase/client';
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
 * User-owned data — cleared on sign-out / account switch. Settings/UI/filter prefs stay, since
 * those aren't per-account.
 */
const USER_DATA_STORAGE_KEYS = [
  LEGACY_NOTES_STORAGE_KEY,
  LAST_MERGED_USER_STORAGE_KEY,
  'notelikeus-label-registry',
  'notelikeus-deleted-notes',
  'notelikeus-lock-key',
  SESSION_HINT_STORAGE_KEY,
] as const;

const REHYDRATE_TIMEOUT_MS = 8_000;
const OPTIONAL_REHYDRATE_TIMEOUT_MS = 4_000;

function withTimeout(promise: Promise<void>, ms: number, label: string): Promise<void> {
  return Promise.race([
    promise,
    new Promise<void>((resolve) => {
      window.setTimeout(() => {
        console.warn(`[Notelikeus] ${label} timed out after ${ms}ms — continuing startup.`);
        resolve();
      }, ms);
    }),
  ]);
}

/** Fail closed on timeout so sync never runs against a half-empty tombstone/label store. */
function requireRehydrate(promise: Promise<void>, ms: number, label: string): Promise<void> {
  return Promise.race([
    promise,
    new Promise<void>((_, reject) => {
      window.setTimeout(() => {
        reject(new Error(`${label} timed out after ${ms}ms`));
      }, ms);
    }),
  ]);
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

export function clearPersistedAppData(): void {
  for (const key of STORAGE_KEYS) {
    try {
      localStorage.removeItem(key);
    } catch {
      // ignore
    }
  }
}

/** Clears in-memory UI state and persisted tombstones/labels. IndexedDB notes are intentionally preserved so offline edits can survive sign-out and re-login under the same account namespace. */
export function clearLocalUserData(): void {
  useNotesStore.getState().reset();
  useLabelRegistryStore.getState().reset();
  useTombstoneStore.getState().reset();
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
 * Account switch only: clear session state and wipe the prior account's IndexedDB namespace so
 * the next account cannot read it. Normal sign-out must NOT call this — unsynced offline edits
 * must remain in IndexedDB until the same account signs in again and syncs.
 */
export function clearLocalUserDataForAccountSwitch(previousOwnerId: string): void {
  clearLocalUserData();
  void clearOwner(previousOwnerId).catch((error: unknown) => {
    console.warn('[Notelikeus] Failed to clear IndexedDB owner namespace:', error);
  });
}

/** Runs once before the app shell renders. Never blocks forever. */
export async function bootstrapApp(): Promise<void> {
  try {
    await rehydrateStores();
  } catch (error) {
    console.error('[Notelikeus] Rehydrate failed, clearing persisted data:', error);
    clearPersistedAppData();
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
