/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_TEST_LOGIN_EMAIL?: string;
  readonly VITE_TEST_LOGIN_PASSWORD?: string;
  /** Set by the e2e build so localhost Supabase is allowed in an otherwise production bundle. */
  readonly VITE_E2E?: string;
  readonly VITE_SUPABASE_URL?: string;
  readonly VITE_SUPABASE_ANON_KEY?: string;
  /** Cloudflare Worker base URL for attachment blobs. */
  readonly VITE_ATTACHMENTS_WORKER_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
