export interface FirebaseEnv {
  apiKey: string;
  authDomain: string;
  projectId: string;
  storageBucket: string;
  messagingSenderId: string;
  appId: string;
  googleClientId: string;
}

/** Bumped on each production build so asset hashes change after config fixes. */
export const APP_BUILD_ID: string = import.meta.env.VITE_APP_BUILD_ID ?? 'dev';

function requireEnv(key: keyof ImportMetaEnv): string {
  const value = import.meta.env[key];
  if (!value || typeof value !== 'string' || value.trim() === '') {
    throw new Error(`Missing required environment variable: ${key}`);
  }
  return value;
}

export function loadFirebaseEnv(): FirebaseEnv {
  return {
    apiKey: requireEnv('VITE_FIREBASE_API_KEY'),
    authDomain: requireEnv('VITE_FIREBASE_AUTH_DOMAIN'),
    projectId: requireEnv('VITE_FIREBASE_PROJECT_ID'),
    storageBucket: requireEnv('VITE_FIREBASE_STORAGE_BUCKET'),
    messagingSenderId: requireEnv('VITE_FIREBASE_MESSAGING_SENDER_ID'),
    appId: requireEnv('VITE_FIREBASE_APP_ID'),
    googleClientId: requireEnv('VITE_FIREBASE_GOOGLE_CLIENT_ID'),
  };
}

const PLACEHOLDER_PATTERN = /placeholder/i;

export function isFirebaseConfigured(): boolean {
  const appId = import.meta.env.VITE_FIREBASE_APP_ID;
  if (!appId || typeof appId !== 'string') return false;
  return !PLACEHOLDER_PATTERN.test(appId) && !appId.includes('your-');
}

export function isFirebaseEnvValid(): boolean {
  if (!isFirebaseConfigured()) return false;

  const apiKey = import.meta.env.VITE_FIREBASE_API_KEY;
  const authDomain = import.meta.env.VITE_FIREBASE_AUTH_DOMAIN;

  if (!apiKey || !authDomain || typeof apiKey !== 'string' || typeof authDomain !== 'string') {
    return false;
  }

  return (
    !PLACEHOLDER_PATTERN.test(apiKey) &&
    !PLACEHOLDER_PATTERN.test(authDomain) &&
    !authDomain.includes('your-project')
  );
}
