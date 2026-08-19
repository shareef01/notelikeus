import { initializeApp, type FirebaseApp } from 'firebase/app';
import { initializeAppCheck, ReCaptchaEnterpriseProvider, ReCaptchaV3Provider } from 'firebase/app-check';
import { connectAuthEmulator, getAuth, type Auth } from 'firebase/auth';
import {
  connectFirestoreEmulator,
  clearIndexedDbPersistence,
  initializeFirestore,
  memoryLocalCache,
  persistentLocalCache,
  persistentMultipleTabManager,
  terminate,
  type Firestore,
} from 'firebase/firestore';
import { loadFirebaseEnv } from './config';

let app: FirebaseApp | null = null;
let auth: Auth | null = null;
let db: Firestore | null = null;
let initError: Error | null = null;
/** True when IndexedDB persistence is unavailable (private browsing, strict shields). */
let firestoreUsesMemoryCache = false;

function createFirestore(instance: FirebaseApp): Firestore {
  const canUseIndexedDb = typeof indexedDB !== 'undefined';

  if (!canUseIndexedDb) {
    firestoreUsesMemoryCache = true;
    return initializeFirestore(instance, { localCache: memoryLocalCache() });
  }

  try {
    firestoreUsesMemoryCache = false;
    return initializeFirestore(instance, {
      localCache: persistentLocalCache({
        tabManager: persistentMultipleTabManager(),
      }),
    });
  } catch (error) {
    console.warn('[Firebase] Persistent cache unavailable, using memory cache.', error);
    firestoreUsesMemoryCache = true;
    return initializeFirestore(instance, { localCache: memoryLocalCache() });
  }
}

/**
 * Optional App Check. Requires a reCAPTCHA site key from Firebase Console → App Check.
 * Without a key, Firebase still works until you turn on enforcement in the console.
 * Production builds log a warning when the key is missing so enforcement is not flipped on
 * against an unprotected web client by accident.
 */
function initAppCheck(instance: FirebaseApp): void {
  const enterpriseKey = import.meta.env.VITE_APPCHECK_RECAPTCHA_ENTERPRISE_SITE_KEY?.trim();
  const v3Key = import.meta.env.VITE_APPCHECK_RECAPTCHA_SITE_KEY?.trim();
  const siteKey = enterpriseKey || v3Key;
  if (!siteKey) {
    if (import.meta.env.PROD) {
      console.warn(
        '[Firebase] App Check site key missing. Do not enable Console enforcement until web sends tokens.',
      );
    }
    return;
  }

  if (import.meta.env.DEV) {
    const debug = import.meta.env.VITE_APPCHECK_DEBUG_TOKEN?.trim();
    // `true` prints a debug token in the console; otherwise use a registered token string.
    (
      globalThis as typeof globalThis & { FIREBASE_APPCHECK_DEBUG_TOKEN?: boolean | string }
    ).FIREBASE_APPCHECK_DEBUG_TOKEN = debug === 'true' || debug === '1' ? true : debug || true;
  }

  try {
    initializeAppCheck(instance, {
      provider: enterpriseKey
        ? new ReCaptchaEnterpriseProvider(enterpriseKey)
        : new ReCaptchaV3Provider(siteKey),
      isTokenAutoRefreshEnabled: true,
    });
  } catch (error) {
    console.warn('[Firebase] App Check init skipped:', error);
  }
}

/**
 * Points Auth and Firestore at local emulators when `VITE_FIREBASE_EMULATOR_HOST` is set.
 *
 * For the end-to-end suite and local development only. The variable is never set in a production
 * build, and is ignored outside DEV/test builds regardless, so a stray value in a deployed
 * environment cannot silently redirect a real user's data to a host that isn't there.
 */
function connectEmulatorsIfConfigured(authInstance: Auth, dbInstance: Firestore): void {
  const host = import.meta.env.VITE_FIREBASE_EMULATOR_HOST?.trim();
  if (!host) return;
  if (import.meta.env.PROD && !import.meta.env.VITE_E2E) {
    console.warn('[Firebase] Ignoring VITE_FIREBASE_EMULATOR_HOST in a production build.');
    return;
  }

  const [hostname, firestorePort] = host.split(':');
  try {
    connectFirestoreEmulator(dbInstance, hostname, Number(firestorePort || 8080));
    const authPort = import.meta.env.VITE_FIREBASE_AUTH_EMULATOR_PORT?.trim() || '9099';
    connectAuthEmulator(authInstance, `http://${hostname}:${authPort}`, { disableWarnings: true });
    console.info(`[Firebase] Using emulators at ${hostname}`);
  } catch (error) {
    console.warn('[Firebase] Emulator connection failed:', error);
  }
}

/**
 * Initializes Firebase once with Firestore offline persistence when available.
 * Falls back to memory cache if IndexedDB is blocked (private browsing, strict shields).
 */
export function initFirebase(): {
  app: FirebaseApp;
  auth: Auth;
  db: Firestore;
} {
  if (app && auth && db) {
    return { app, auth, db };
  }

  if (initError) {
    throw initError;
  }

  if (app && auth) {
    db = createFirestore(app);
    connectEmulatorsIfConfigured(auth, db);
    return { app, auth, db };
  }

  try {
    const env = loadFirebaseEnv();
    app = initializeApp({
      apiKey: env.apiKey,
      authDomain: env.authDomain,
      projectId: env.projectId,
      storageBucket: env.storageBucket,
      messagingSenderId: env.messagingSenderId,
      appId: env.appId,
    });

    initAppCheck(app);
    auth = getAuth(app);
    db = createFirestore(app);
    connectEmulatorsIfConfigured(auth, db);

    return { app, auth, db };
  } catch (error) {
    initError = error instanceof Error ? error : new Error(String(error));
    throw initError;
  }
}

export function getFirebaseAuth(): Auth {
  return initFirebase().auth;
}

export function getFirestoreDb(): Firestore {
  return initFirebase().db;
}

/** True when offline edits may be lost on tab close (no IndexedDB persistence). */
export function isFirestoreMemoryCache(): boolean {
  initFirebase();
  return firestoreUsesMemoryCache;
}

/** Best-effort purge for explicit sign-out; memory-cache sessions have nothing persistent to clear. */
export async function purgeFirestoreCache(): Promise<void> {
  if (!db || firestoreUsesMemoryCache) return;

  const currentDb = db;
  let terminated = false;
  try {
    await terminate(currentDb);
    terminated = true;
    db = null;
    await clearIndexedDbPersistence(currentDb);
  } catch (error) {
    if (terminated && db === currentDb) {
      db = null;
    }
    console.warn('[Firebase] Firestore cache purge skipped:', error);
  }
}
