import { isFirebaseConfigured } from '@/lib/config';
import { initFirebase } from '@/lib/firebase';
import { ensureReminderSync } from '@/lib/reminders/reminderSync';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import { useUiStore } from '@/store/uiStore';

const STORAGE_KEYS = ['notelikeus-notes', 'notelikeus-settings', 'notelikeus-ui'] as const;
const REHYDRATE_TIMEOUT_MS = 4_000;

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

async function rehydrateStores(): Promise<void> {
  await Promise.all([
    withTimeout(Promise.resolve(useNotesStore.persist.rehydrate()), REHYDRATE_TIMEOUT_MS, 'Notes store rehydrate'),
    withTimeout(Promise.resolve(useSettingsStore.persist.rehydrate()), REHYDRATE_TIMEOUT_MS, 'Settings store rehydrate'),
    withTimeout(Promise.resolve(useUiStore.persist.rehydrate()), REHYDRATE_TIMEOUT_MS, 'UI store rehydrate'),
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

/** Runs once before the app shell renders. Never blocks forever. */
export async function bootstrapApp(): Promise<void> {
  try {
    await rehydrateStores();
  } catch (error) {
    console.error('[Notelikeus] Rehydrate failed, clearing persisted data:', error);
    clearPersistedAppData();
    await rehydrateStores();
  }

  ensureReminderSync();

  if (isFirebaseConfigured()) {
    try {
      initFirebase();
    } catch (error) {
      console.error('[Notelikeus] Firebase init failed:', error);
    }
  }
}
