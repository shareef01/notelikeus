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
  readonly VITE_TEST_LOGIN_EMAIL?: string;
  readonly VITE_TEST_LOGIN_PASSWORD?: string;
  /** `host` or `host:firestorePort`. Redirects Auth and Firestore to local emulators (e2e/dev). */
  readonly VITE_FIREBASE_EMULATOR_HOST?: string;
  /** Auth emulator port; defaults to 9099. Only read when the host above is set. */
  readonly VITE_FIREBASE_AUTH_EMULATOR_PORT?: string;
  /** Set by the e2e build so an emulator host is honoured in an otherwise production build. */
  readonly VITE_E2E?: string;
  /** `supabase` selects the Supabase remote adapter. Production still defaults to Firebase. */
  readonly VITE_REMOTE_BACKEND?: string;
  readonly VITE_SUPABASE_URL?: string;
  readonly VITE_SUPABASE_ANON_KEY?: string;
  /** Explicit cutover flag. Production Supabase also requires a non-localhost URL. */
  readonly VITE_ALLOW_SUPABASE_PRODUCTION?: string;
  /**
   * Staging Pages only. Enables Supabase in a production Vite build when the
   * page is served from `*.pages.dev`. Never set this on Firebase Hosting.
   */
  readonly VITE_ALLOW_SUPABASE_STAGING?: string;
  /** Dev-only: Cloudflare Worker base URL for attachment blobs (Phase 8). */
  readonly VITE_ATTACHMENTS_WORKER_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
