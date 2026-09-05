# Notelikeus backend architecture

Canonical backend as of 2026-09-05. Firebase is not part of the runtime.

## Auth

Supabase Auth is the only cloud identity provider.

- Web: `@supabase/supabase-js` PKCE session, Google OAuth via `signInWithOAuth`, optional email/password in dev/e2e only.
- Android / Desktop: `SupabaseSessionManager` exchanging a Google ID token with GoTrue. Session persistence is platform-specific (EncryptedSharedPreferences / DPAPI file).
- Guest mode remains local-only (`__guest__` namespace). No anonymous Supabase user is created.

Cloud ownership is `auth.uid()` (Supabase UUID). There is no Firebase UID mapping.

## Local storage

| Client | Store |
|---|---|
| Web | IndexedDB (`notes` + owner meta) plus Zustand in memory |
| Android | Room + SQLCipher |
| Desktop | Room |

User action writes local state first, then remote sync. Guest notes never upload.

## Remote database

Supabase PostgreSQL. Authoritative note mutations go through RPCs, not direct table writes:

- `apply_note_change`
- `apply_note_delete`
- `pull_changes`
- `fetch_full_snapshot`
- `clear_note_tombstone` (explicit undo of a permanent delete)
- `delete_all_user_cloud_data`
- attachment RPCs (`register_note_attachment`, `delete_note_attachment`, `list_user_attachments`, …)

Row-level security is enabled on user-owned tables. Direct INSERT/UPDATE/DELETE of revision, owner, and tombstone rows is blocked by mutation guards.

## Sync protocol

The server owns a monotonic `sync_revision_seq`.

1. Client stores `last_remote_revision`.
2. Mutation sends the note plus `base_revision`.
3. Server detects conflicts / tombstones, assigns a new revision, and commits.
4. Pull returns notes and tombstones after revision N, paginated.

Failed cloud reads must not be treated as an empty database. A successful empty snapshot is distinct from auth, timeout, HTTP, or RPC failure.

Tombstones prevent resurrection of deleted notes. They remain required.

## Realtime

Supabase Realtime (`postgres_changes`) is a wake-up: subscribe after login, unsubscribe on logout/account switch, then perform an authoritative revision-aware pull. Manual / startup / reconnect pulls remain the source of truth.

## Attachments

- Metadata: `note_attachments` in Postgres.
- Blobs: Cloudflare R2.
- Authorization: Cloudflare Worker verifies the Supabase JWT and derives `owners/{userId}/notes/{noteId}/{attachmentId}`. Callers cannot supply an arbitrary object key.

## Web hosting

Cloudflare Pages serves the Vite PWA (`web/dist`). SPA fallback is `web/public/_redirects`. Security headers live in `web/public/_headers`. The PWA is not hosted on Supabase Edge Functions.

## Environment variables

Web:

- `VITE_SUPABASE_URL`
- `VITE_SUPABASE_ANON_KEY` (public anon JWT)
- `VITE_ATTACHMENTS_WORKER_URL` (optional)
- `VITE_E2E=1` (e2e production-mode builds only)

Kotlin (`local.properties` / env):

- `NOTELIKEUS_SUPABASE_URL` / `notelikeus.supabaseUrl`
- `NOTELIKEUS_SUPABASE_ANON_KEY` / `notelikeus.supabaseAnonKey`
- `NOTELIKEUS_ATTACHMENTS_WORKER_URL` / `notelikeus.attachmentsWorkerUrl`

Worker:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY` (secret)
- `ATTACHMENTS_BUCKET` (R2 binding)
- `ALLOWED_ORIGINS` (optional)

Never put `service_role`, database passwords, or OAuth client secrets in a client app.

## Local development

```bash
npm install
npm run supabase:start
npm run supabase:reset

cd web
npm install
cp .env.example .env
npm run dev
```

Gradle:

```bash
./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest
./gradlew :androidApp:assembleDebug
```

## Production deployment

Owner-operated (credentials required):

1. Create a fresh Supabase project, `supabase link`, `supabase db push`.
2. Enable Google provider; add redirect URLs for localhost, Pages preview, and the production domain.
3. Enable Realtime on `notes` and `note_tombstones` (already published by migration).
4. Create an R2 bucket, deploy `workers/attachments`.
5. Create a Cloudflare Pages project with build `cd web && npm ci && npm run build`, output `web/dist`.
6. Set Pages env vars listed above.
7. Attach a custom domain and add it to the Supabase Auth redirect allowlist.

See `docs/SUPABASE_CUTOVER_AUDIT.md` for remaining owner actions.
