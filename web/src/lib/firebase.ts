import { initializeApp, type FirebaseApp } from 'firebase/app';
import { initializeAppCheck, ReCaptchaEnterpriseProvider, ReCaptchaV3Provider } from 'firebase/app-check';
import { getAuth, type Auth } from 'firebase/auth';
import {
  initializeFirestore,
  memoryLocalCache,
  persistentLocalCache,
  persistentMultipleTabManager,
  type Firestore,
} from 'firebase/firestore';
import { getStorage, type FirebaseStorage } from 'firebase/storage';
import { loadFirebaseEnv } from './config';

let app: FirebaseApp | null = null;
let auth: Auth | null = null;
let db: Firestore | null = null;
let storage: FirebaseStorage | null = null;
let initError: Error | null = null;

function createFirestore(instance: FirebaseApp): Firestore {
  const canUseIndexedDb = typeof indexedDB !== 'undefined';

  if (!canUseIndexedDb) {
    return initializeFirestore(instance, { localCache: memoryLocalCache() });
  }

  try {
    return initializeFirestore(instance, {
      localCache: persistentLocalCache({
        tabManager: persistentMultipleTabManager(),
      }),
    });
  } catch (error) {
    console.warn('[Firebase] Persistent cache unavailable, using memory cache.', error);
    return initializeFirestore(instance, { localCache: memoryLocalCache() });
  }
}

/**
 * Optional App Check. Requires a reCAPTCHA site key from Firebase Console → App Check.
 * Without a key, Firebase still works until you turn on enforcement in the console.
 */
function initAppCheck(instance: FirebaseApp): void {
  const enterpriseKey = import.meta.env.VITE_APPCHECK_RECAPTCHA_ENTERPRISE_SITE_KEY?.trim();
  const v3Key = import.meta.env.VITE_APPCHECK_RECAPTCHA_SITE_KEY?.trim();
  const siteKey = enterpriseKey || v3Key;
  if (!siteKey) return;

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
 * Initializes Firebase once with Firestore offline persistence when available.
 * Falls back to memory cache if IndexedDB is blocked (private browsing, strict shields).
 */
export function initFirebase(): {
  app: FirebaseApp;
  auth: Auth;
  db: Firestore;
  storage: FirebaseStorage;
} {
  if (app && auth && db && storage) {
    return { app, auth, db, storage };
  }

  if (initError) {
    throw initError;
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
    storage = getStorage(app);

    return { app, auth, db, storage };
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

export function getFirebaseStorage(): FirebaseStorage {
  return initFirebase().storage;
}
