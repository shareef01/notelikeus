import { isFirebaseConfigured, isFirebaseEnvValid } from '@/lib/config';
import { completeRedirectSignInIfNeeded } from '@/lib/auth/googleAuth';
import { initFirebase } from '@/lib/firebase';
import { recoverFromStaleServiceWorkerIfNeeded } from '@/lib/swRecovery';
import { ensureReminderSync } from '@/lib/reminders/reminderSync';
import { migrateEncryptedStorage } from '@/lib/crypto/migrate';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import { useUiStore } from '@/store/uiStore';

const STORAGE_KEYS = ['notelikeus-notes', 'notelikeus-settings', 'notelikeus-ui'] as const;

async function rehydrateStores(): Promise<void> {
  await Promise.all([
    useNotesStore.persist.rehydrate(),
    useSettingsStore.persist.rehydrate(),
    useUiStore.persist.rehydrate(),
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
  await recoverFromStaleServiceWorkerIfNeeded();

  // Migrate any data left behind by the previous encrypted storage
  await migrateEncryptedStorage();

  await rehydrateStores();

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
