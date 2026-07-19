import { isFirebaseConfigured } from '@/lib/config';
import { clearLockKey } from '@/lib/crypto/lockedNoteCrypto';
import { initFirebase } from '@/lib/firebase';
import { clearKnownCloudIds, KNOWN_CLOUD_IDS_STORAGE_KEY } from '@/lib/notes/knownCloudIds';
import { ensureReminderSync } from '@/lib/reminders/reminderSync';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { useUiStore } from '@/store/uiStore';

const STORAGE_KEYS = [
  'notelikeus-notes',
  'notelikeus-settings',
  'notelikeus-ui',
  'notelikeus-label-registry',
  'notelikeus-deleted-notes',
  'notelikeus-lock-key',
  KNOWN_CLOUD_IDS_STORAGE_KEY,
] as const;

/** User-owned note data — cleared on sign-out / account switch. Settings/UI prefs stay. */
const USER_DATA_STORAGE_KEYS = [
  'notelikeus-notes',
  'notelikeus-label-registry',
  'notelikeus-deleted-notes',
  'notelikeus-lock-key',
  KNOWN_CLOUD_IDS_STORAGE_KEY,
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

/** Fail closed on timeout so sync never runs against a half-empty notes store. */
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
  // Notes/tombstones/labels must finish — soft-success would let cloud merge race an empty store.
  await Promise.all([
    requireRehydrate(
      Promise.resolve(useNotesStore.persist.rehydrate()),
      REHYDRATE_TIMEOUT_MS,
      'Notes store rehydrate',
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

/** Clears in-memory + persisted notes/labels/tombstones so the next account cannot inherit them. */
export function clearLocalUserData(): void {
  useNotesStore.getState().reset();
  useLabelRegistryStore.getState().reset();
  useTombstoneStore.getState().reset();
  clearLockKey();
  clearKnownCloudIds();
  for (const key of USER_DATA_STORAGE_KEYS) {
    try {
      localStorage.removeItem(key);
    } catch {
      // ignore
    }
  }
}

/** Runs once before the app shell renders. Never blocks forever. */
export async function bootstrapApp(): Promise<void> {
  try {
    await rehydrateStores();
  } catch (error) {
    console.error('[Notelikeus] Rehydrate failed, clearing persisted data:', error);
    clearPersistedAppData();
    // Second attempt: if storage is corrupt/hung, start from empty in-memory state.
    try {
      await rehydrateStores();
    } catch (retryError) {
      console.error('[Notelikeus] Rehydrate retry failed; starting empty:', retryError);
      clearLocalUserData();
    }
  }

  ensureReminderSync();

  if (isFirebaseConfigured()) {
    if (!isFirebaseEnvValid()) {
      console.error('[Notelikeus] Firebase env looks invalid. Rebuild with web/.env or run scripts/setup-web-env.ps1');
      return;
    }

    try {
      initFirebase();
      await completeRedirectSignInIfNeeded();
    } catch (error) {
      console.error('[Notelikeus] Firebase init failed:', error);
    }
  }
}
