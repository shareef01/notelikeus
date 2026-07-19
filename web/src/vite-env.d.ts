/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_FIREBASE_API_KEY: string;
  readonly VITE_FIREBASE_AUTH_DOMAIN: string;
  readonly VITE_FIREBASE_PROJECT_ID: string;
  readonly VITE_FIREBASE_STORAGE_BUCKET: string;
  readonly VITE_FIREBASE_MESSAGING_SENDER_ID: string;
  readonly VITE_FIREBASE_APP_ID: string;
  readonly VITE_FIREBASE_GOOGLE_CLIENT_ID: string;
  /** Optional reCAPTCHA v3 site key for Firebase App Check. */
  readonly VITE_APPCHECK_RECAPTCHA_SITE_KEY?: string;
  /** Optional reCAPTCHA Enterprise site key (preferred when set). */
  readonly VITE_APPCHECK_RECAPTCHA_ENTERPRISE_SITE_KEY?: string;
  /** Dev-only: `true` to print a debug token, or a registered debug token string. */
  readonly VITE_APPCHECK_DEBUG_TOKEN?: string;
  /** Show email/password test login (also on when `import.meta.env.DEV`). */
  readonly VITE_ENABLE_TEST_LOGIN?: string;
  readonly VITE_TEST_LOGIN_EMAIL?: string;
  readonly VITE_TEST_LOGIN_PASSWORD?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
