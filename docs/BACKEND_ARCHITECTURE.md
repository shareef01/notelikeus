# Notelikeus backend architecture

Canonical backend as of 2026-09-06. Firebase is not part of the runtime.

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

- `apply_note_change` / `apply_note_delete`
- `restore_note` (clears the tombstone and undeletes attachment metadata in the same transaction)
- `lookup_note_revision`
- `pull_changes` / `fetch_full_snapshot`
- `clear_note_tombstone` (explicit undo of a permanent delete)
- `delete_all_user_cloud_data`
- `validate_note_payload` (shared by apply + restore)
- attachment RPCs: `register_note_attachment`, `delete_note_attachment`, `list_user_attachments`, `list_pending_deleted_attachments`, `purge_deleted_note_attachment`, `authorize_note_attachment_{put,get,delete}`
- Hosted sweep only (`service_role`): `list_orphaned_deleted_attachments`, `purge_orphaned_deleted_attachment`

Row-level security is enabled on user-owned tables. Direct INSERT/UPDATE/DELETE of revision, owner, and tombstone rows is blocked by mutation guards. User attachment PUT/GET/DELETE stay on the caller’s bearer token.

## Sync protocol

The server owns a monotonic `sync_revision_seq`.

1. Client stores `last_remote_revision`.
2. Mutation sends the note plus `base_revision`.
3. Server detects conflicts / tombstones, assigns a new revision, and commits.
4. Pull returns notes and tombstones after revision N, paginated.

Failed cloud reads must not be treated as an empty database. A successful empty snapshot is distinct from auth, timeout, HTTP, or RPC failure. An empty snapshot also does not prove deletion: clients keep unsynced local notes and refuse to overwrite when known cloud ids are unexplained.

Tombstones prevent resurrection of deleted notes. They remain required. A restore marker survives process death so a stale tombstone snapshot cannot hide a note the user just brought back.

Clients persist `knownCloudIds` with the notes+cursor write. Only ids that were actually on the server are stored.

## Realtime

Supabase Realtime (`postgres_changes`) is a wake-up: subscribe after login, unsubscribe on logout/account switch, then perform an authoritative revision-aware pull. Manual / startup / reconnect pulls remain the source of truth.

## Attachments

- Metadata: `note_attachments` in Postgres.
- Blobs: Cloudflare R2.
- Authorization: Cloudflare Worker verifies the Supabase JWT and derives `owners/{userId}/notes/{noteId}/{attachmentId}`. Callers cannot supply an arbitrary object key. Unauthorized GET/DELETE are generic 404.
- Delete order: authoritative note delete first, then R2. Prefer an orphan blob over destroying data. `apply_note_delete` sets `note_attachments.deleted_at` in the same transaction; `restore_note` clears it.
- Client sweep: `list_pending_deleted_attachments` + `purge_deleted_note_attachment` on the next snapshot/pull. Pending GC is persisted locally.
- Hosted sweep (optional Worker cron every 6 hours): `service_role` lists metadata that is deleted, tombstoned, not live, and older than 24 hours, then deletes the canonical R2 key and purges the row. Without `SUPABASE_SERVICE_ROLE_KEY` the cron is a no-op. It never lists R2 first.
- PUT retry: an attachment id is an immutable identity, and its object key is derived from it, so a repeated PUT of a committed attachment is a retry of work that already succeeded. `authorize_note_attachment_put` returns `already_live`, and the Worker then returns the committed object instead of rewriting it. Changing an image's content uses a **new** attachment id.
- Compensation scope: the compensating R2 delete after a failed finalization only removes bytes the failed request itself created. It never touches an object a surviving metadata row still points at — otherwise a retry that failed to finalize could destroy a previously committed blob.

### Local attachment staging

Bytes that have not reached R2 are staged durably before the note references them, on every client:

- **Web**: the `pendingAttachments` IndexedDB store. The write is awaited; the attachment is not added to the note if it fails.
- **Android / Windows**: `pending-attachments/<ownerId>/<attachmentId>` under app storage, written through `AttachmentStagingStore`.

The invariant is the same on both: a locally persisted note never references attachment bytes that exist only in process memory, so an attachment cannot survive a restart as metadata pointing at nothing. Staging is namespaced per owner, so signing into a different account cannot read or upload the previous account's staged bytes. Staged bytes are released only when the upload is committed, the user removes the attachment, or reconciliation finds no note referencing them — never on age alone.

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
- `SUPABASE_SERVICE_ROLE_KEY` (optional secret; cron orphan sweep only)
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
4. Create an R2 bucket, deploy `workers/attachments`. Optional: `wrangler secret put SUPABASE_SERVICE_ROLE_KEY` so the orphan-sweep cron can run.
5. Create a Cloudflare Pages project with build `cd web && npm ci && npm run build`, output `web/dist`.
6. Set Pages env vars listed above.
7. Attach a custom domain and add it to the Supabase Auth redirect allowlist.

See `docs/SUPABASE_CUTOVER_AUDIT.md` for remaining owner actions.
