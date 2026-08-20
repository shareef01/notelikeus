export interface FirebaseEnv {
  apiKey: string;
  authDomain: string;
  projectId: string;
  storageBucket: string;
  messagingSenderId: string;
  appId: string;
  googleClientId: string;
}

function requireEnv(value: string | undefined, key: string): string {
  if (!value || typeof value !== 'string' || value.trim() === '') {
    throw new Error(`Missing required environment variable: ${key}`);
  }
  return value;
}

export function loadFirebaseEnv(): FirebaseEnv {
  return {
    apiKey: requireEnv(import.meta.env.VITE_FIREBASE_API_KEY, 'VITE_FIREBASE_API_KEY'),
    authDomain: requireEnv(import.meta.env.VITE_FIREBASE_AUTH_DOMAIN, 'VITE_FIREBASE_AUTH_DOMAIN'),
    projectId: requireEnv(import.meta.env.VITE_FIREBASE_PROJECT_ID, 'VITE_FIREBASE_PROJECT_ID'),
    storageBucket: requireEnv(
      import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,
      'VITE_FIREBASE_STORAGE_BUCKET',
    ),
    messagingSenderId: requireEnv(
      import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
      'VITE_FIREBASE_MESSAGING_SENDER_ID',
    ),
    appId: requireEnv(import.meta.env.VITE_FIREBASE_APP_ID, 'VITE_FIREBASE_APP_ID'),
    googleClientId: requireEnv(
      import.meta.env.VITE_FIREBASE_GOOGLE_CLIENT_ID,
      'VITE_FIREBASE_GOOGLE_CLIENT_ID',
    ),
  };
}

export function isFirebaseConfigured(): boolean {
  return Boolean(import.meta.env.VITE_FIREBASE_APP_ID);
}
