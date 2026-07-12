const RECOVERY_FLAG = 'notelikeus-sw-recovery-v1';

const PLACEHOLDER_PATTERN = /placeholder/i;

function envLooksInvalid(): boolean {
  const apiKey = import.meta.env.VITE_FIREBASE_API_KEY;
  const authDomain = import.meta.env.VITE_FIREBASE_AUTH_DOMAIN;
  const appId = import.meta.env.VITE_FIREBASE_APP_ID;

  if (!apiKey || !authDomain || !appId) return false;

  return (
    PLACEHOLDER_PATTERN.test(apiKey) ||
    PLACEHOLDER_PATTERN.test(authDomain) ||
    PLACEHOLDER_PATTERN.test(appId) ||
    authDomain.includes('your-project')
  );
}

async function clearServiceWorkerCaches(): Promise<void> {
  if ('serviceWorker' in navigator) {
    const registrations = await navigator.serviceWorker.getRegistrations();
    await Promise.all(registrations.map((registration) => registration.unregister()));
  }

  if ('caches' in window) {
    const keys = await caches.keys();
    await Promise.all(keys.map((key) => caches.delete(key)));
  }
}

/**
 * Recovers clients stuck on a stale service-worker bundle (e.g. placeholder Firebase env).
 * Runs once per tab session to avoid reload loops.
 */
export async function recoverFromStaleServiceWorkerIfNeeded(): Promise<void> {
  if (typeof window === 'undefined' || !envLooksInvalid()) return;
  if (sessionStorage.getItem(RECOVERY_FLAG) === '1') return;

  sessionStorage.setItem(RECOVERY_FLAG, '1');
  console.warn('[Notelikeus] Stale PWA bundle detected — clearing caches and reloading.');

  try {
    await clearServiceWorkerCaches();
  } catch (error) {
    console.error('[Notelikeus] Cache recovery failed:', error);
  }

  window.location.reload();
}
