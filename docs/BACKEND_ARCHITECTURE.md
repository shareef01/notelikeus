# Notelikeus backend architecture

Canonical backend as of 2026-09-06. Firebase is not part of the runtime.

## Auth

Supabase Auth is the only cloud identity provider.

- Web: `@supabase/supabase-js` PKCE session, email one-time-code sign-in, optional email/password in dev/e2e only.
- Android / Desktop: `SupabaseSessionManager` signing in with an emailed one-time code: `POST /auth/v1/otp` (`create_user: true`, so the first sign-in creates the account), then `POST /auth/v1/verify` (`type: email`) with the code the user types in. Session persistence is platform-specific (EncryptedSharedPreferences / DPAPI file).
- Guest mode remains local-only (`__guest__` namespace). No anonymous Supabase user is created.

Cloud ownership is `auth.uid()` (Supabase UUID). There is no Firebase UID mapping.

**Destructive actions need a recent sign-in** (AUD-005, `20261104000000`). Deleting the account and wiping its cloud data are refused unless the session signed in within the last ten minutes, read from the access token's `amr` claim, which GoTrue keeps across a refresh. The database answers NL004, and the Worker answers 403 `reauthentication_required`. Clients then ask for a new emailed code for the same account and retry. Account deletion is started only by the Worker: it verifies the token and its sign-in time, then calls `request_account_deletion_for(owner, signed_in_at)` with the service role, which checks the time again. `request_account_deletion()` is no longer granted to users (AUD-006).

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
- attachment RPCs: `register_note_attachment`, `delete_note_attachment`, `list_user_attachments`, `list_pending_deleted_attachments`, `purge_deleted_note_attachment`, `precheck_note_attachment_put`, `authorize_note_attachment_{put,get,delete}`, `finalize_note_attachment_put`, `begin_note_attachment_delete`, `finalize_note_attachment_delete`
- Hosted sweep only (`service_role`): `list_orphaned_deleted_attachments`, `claim_orphaned_attachment_for_delete`, `purge_orphaned_deleted_attachment`, `list_unconfirmed_attachment_deletes`, `confirm_attachment_object_deleted`

**Note limits** (AUD-004, `20261105000000`): an account holds at most 10 000 notes and 100 MiB of note text (title, content, labels and checklist). The `notes_account_usage` trigger keeps a running total per account in `account_note_usage`, from each row's own change, and refuses with NL005 a write that would take the account past a limit while growing it. Shrinking and deleting are always allowed, so an account already over a limit is never stuck. Per-note limits stay in `validate_note_payload`; attachments have their own 200 MB quota.

Row-level security is enabled on user-owned tables. Direct INSERT/UPDATE/DELETE of revision, owner, and tombstone rows is blocked by mutation guards. User attachment PUT/GET/DELETE stay on the caller’s bearer token.

## Sync protocol

The server owns a monotonic `sync_revision_seq`.

1. Client stores `last_remote_revision`.
2. Mutation sends the note plus `base_revision`.
3. Server detects conflicts / tombstones, assigns a new revision, and commits.
4. Pull returns notes and tombstones after revision N, paginated.

The full snapshot is read in pages too (AUD-008, `20261106000000`): `fetch_snapshot_page(kind, after, limit)` returns notes, or tombstones, by note id, with a notes page stopping at 4 MiB of text. Every page reports the account's state (newest revision, wipe generation, note and tombstone counts); clients accept the pages only when every one reported the same state, read again otherwise, and assemble the same payload `fetch_full_snapshot` returns. `fetch_full_snapshot` stays for older clients, and current clients fall back to it on a database without the paged function.

Failed cloud reads must not be treated as an empty database. A successful empty snapshot is distinct from auth, timeout, HTTP, or RPC failure. An empty snapshot also does not prove deletion: clients keep unsynced local notes and refuse to overwrite when known cloud ids are unexplained.

**Unexplained** is the operative word, and it is a tombstone question. A previously-known id that is missing from the snapshot *and* has no tombstone is evidence of a bad read; one that a tombstone accounts for is evidence of a deletion that really happened. Both clients subtract the explained ids before deciding, so an account whose notes were all genuinely deleted — the last note removed on another device, the trash emptied there — syncs normally instead of being refused. Comparing against the whole known set was a permanent failure rather than a cautious one: the known-id set is only rewritten at the end of a *successful* sync, so a client that refused on this could never get out of it. See `docs/AUDIT_2026.md` A-2.

Tombstones prevent resurrection of deleted notes. They remain required. A restore marker survives process death so a stale tombstone snapshot cannot hide a note the user just brought back.

Clients persist `knownCloudIds` with the notes+cursor write. Only ids that were actually on the server are stored.

## Realtime

Supabase Realtime (`postgres_changes`) is a wake-up: subscribe after login, unsubscribe on logout/account switch, then perform an authoritative revision-aware pull. Manual / startup / reconnect pulls remain the source of truth.

## Attachments

- Metadata: `note_attachments` in Postgres.
- Blobs: Cloudflare R2.
- Authorization: Cloudflare Worker verifies the Supabase JWT and derives `owners/{userId}/notes/{noteId}/{attachmentId}`. Callers cannot supply an arbitrary object key. Unauthorized GET/DELETE are generic 404.
- Request pipeline order: route shape -> method -> upload headers -> rate limit -> authenticate -> per-user rate limit -> authorize the resource -> body/storage work. Everything before authentication is a property of the request alone, so the ordering cannot be used to probe for another user's notes. `PATCH /invalid-path` costs no outbound call.
- Upload order: `precheck_note_attachment_put` decides ownership before a single body byte is read, then the body is streamed under the cap, then `authorize_note_attachment_put` runs against the real byte count, then R2, then `finalize_note_attachment_put`. The precheck is advisory; size, MIME, both quotas, note liveness, and the canonical key are all still enforced authoritatively at finalization, under the per-owner advisory lock.
- Delete order (user-initiated): `begin_note_attachment_delete` takes a row lock, marks `deleted_at` and `delete_claimed_at`, and returns the canonical key; then the R2 object is deleted; then the Worker stamps `object_deleted_at` through the service role (`confirm_attachment_object_deleted`, for the owner it verified). Claiming first means every later failure leaves an orphaned object -- recoverable -- instead of live metadata pointing at bytes that are gone. A failure to stamp the confirmation is reported as `confirmed: false` in a 200 response, because the attachment really is deleted by then. Every phase is idempotent, so a repeated DELETE converges.
- Quota integrity (AUD-003, `20261103000000`): a row counts against the 200 MB quota until its object is confirmed deleted, and only the service role can confirm that — `finalize_note_attachment_delete`, which runs on the user's token, records the claim but no longer the confirmation. `finalize_cloud_wipe` and `purge_deleted_note_attachment` remove only confirmed rows; an unconfirmed one waits for the sweeps below, which delete the object before the row.
- A claimed attachment is never resurrected, by any path. `restore_note` skips rows carrying `delete_claimed_at` (the owner deleted it) or `purge_claimed_at` (a sweeper claimed it), and `finalize_note_attachment_put` refuses a claimed identity under that row's own lock, so a DELETE landing mid-upload wins over the upload rather than being undone by it. A `note_attachments` CHECK constraint makes it structural: no function can leave a live row carrying a claim. Attachments deleted only as a side effect of deleting the note carry neither stamp and still come back.
- Deleting an attachment retires its id. A PUT of a retired id is refused with `terminally_deleted` (Worker: 409) rather than reviving it; replacement content uses a **new** attachment id, as it always has. The refusal is a value, not an exception, precisely so the Worker can tell it apart from a timeout and safely delete bytes it has already written — on an ambiguous failure it still leaves them, because a live row may still need them.
- Delete order: authoritative note delete first, then R2. Prefer an orphan blob over destroying data. `apply_note_delete` sets `note_attachments.deleted_at` in the same transaction; `restore_note` clears it.
- Client sweep: `list_pending_deleted_attachments` + `purge_deleted_note_attachment` on the next snapshot/pull. Pending GC is persisted locally.
- Hosted sweep (hourly Worker cron, at minute 15): `service_role` lists metadata that is deleted, tombstoned, not live, and older than 24 hours, claims it under a row lock, then deletes the canonical R2 key and purges the row. It never lists R2 first. It needs `SUPABASE_SERVICE_ROLE_KEY`, which production requires anyway (account deletion and quota release depend on it); without the key the cron does nothing.
- Second cron pass: `list_unconfirmed_attachment_deletes` finds deletes claimed more than an hour ago whose object was never confirmed gone -- a client that died mid-DELETE -- deletes the object and stamps `confirm_attachment_object_deleted`. It never removes metadata; whether the row itself may go stays the orphan sweep's decision.
- Abuse controls: bind a Cloudflare rate limiter as `ATTACHMENT_RATE_LIMITER` (see `wrangler.toml.example`) and the Worker throttles by client IP before authenticating and by user id after. Counting happens at the edge, so the limit is not defeated by a different isolate. Zone-level WAF rules, bot protection, and platform request limits are configured on the Cloudflare zone and are **not** represented in this repository — operator checklist: [`CLOUDFLARE_ZONE_CONTROLS.md`](CLOUDFLARE_ZONE_CONTROLS.md).
- PUT retry: an attachment id is an immutable identity, and its object key is derived from it, so a repeated PUT of a committed attachment is a retry of work that already succeeded. `authorize_note_attachment_put` returns `already_live`, and the Worker then returns the committed object instead of rewriting it. Changing an image's content uses a **new** attachment id.
- Compensation scope: the compensating R2 delete after a failed finalization only removes bytes the failed request itself created. It never touches an object a surviving metadata row still points at — otherwise a retry that failed to finalize could destroy a previously committed blob.

### Local attachment staging

Bytes that have not reached R2 are staged durably before the note references them, on every client:

- **Web**: the `pendingAttachments` IndexedDB store. The write is awaited; the attachment is not added to the note if it fails.
- **Android / Windows**: `pending-attachments/<ownerId>/<attachmentId>` under app storage, written through `AttachmentStagingStore`.

The invariant is the same on both: a locally persisted note never references attachment bytes that exist only in process memory, so an attachment cannot survive a restart as metadata pointing at nothing. Staging is namespaced per owner, so signing into a different account cannot read or upload the previous account's staged bytes. Staged bytes are released only when the upload is committed, the user removes the attachment, or reconciliation finds no note referencing them — never on age alone.

## Backup formats

Two, and the second contains the first.

- **v3 JSON** — note content, checklists, labels and reminders. Written and read by every client. Unchanged.
- **v4 `.nlkbak`** — a ZIP holding `manifest.json` plus `media/<attachmentId>` entries. `manifest.backup` is a v3 document **verbatim**, so a v4 reader hands it to the unchanged v3 importer, and anyone with only an older build can rename the file to `.zip` and lift the JSON out. Attachments are strictly additive: a corrupt, missing or unverifiable one is dropped with a reason and the note still imports.

Both are additive on import — new local ids, and for a bundle, freshly minted attachment ids — so importing the same file twice produces two independent copies. A bundle never carries tokens, keys, sessions, sync cursors, server revisions or an owner id. Entry names are validated (`^[A-Za-z0-9_-]{1,128}$` for an attachment id) and media is located by that validated id rather than by any path in the archive, which is what makes traversal structurally impossible rather than merely checked for.

Export reads only **locally staged** bytes. It never fetches from R2: a backup must work offline, and turning one into a cloud operation would break that. Images that live only in the cloud are reported as skipped.

The format is pinned by `contracts/backup/v4-bundle-manifest.json`. Bundle export and import are
implemented on Web, Android, and Windows Desktop (`BackupBundleCodec` / `BackupBundleTransfer` on
Kotlin; the web client’s ZIP path). All three read and write the same v4 layout.

## Diagnostics

Every client can render a local diagnostics report: counts, cursors, schema and app versions, sync
and realtime state, staged-attachment totals. It is shown before it can be copied and is never
transmitted.

It carries **no** access or refresh tokens, JWTs, passwords, note or checklist or label text,
attachment bytes or paths, email addresses, OAuth profile data, or the account id. A guard runs
over the rendered report and *throws* rather than scrubbing, so a field added later that leaks
fails a test instead of shipping. Sync failures are recorded as a category, never a message —
`apply_note_change`'s conflict error embeds the remote note's title.

The account appears as a redacted tag (FNV-1a over the id). Both clients derive it identically,
pinned by `contracts/diagnostics/owner-tag-vectors.json`, so two reports from one user can be
matched to each other without either identifying anyone.

## Cross-client contracts

`contracts/` holds canonical JSON read by **both** test suites — the Kotlin clients and the web
client are separate implementations of one backup format and one set of Supabase payloads, and
they can drift silently. A field renamed, defaulted differently, or coerced differently on one side
now fails a test on both. See `contracts/README.md`.

## Web hosting

Cloudflare Pages serves the Vite PWA (`web/dist`). SPA fallback is `web/public/_redirects`. Security headers live in `web/public/_headers`. The PWA is not hosted on Supabase Edge Functions.

## Environment variables

Web:

- `VITE_SUPABASE_URL`
- `VITE_SUPABASE_ANON_KEY` (public anon JWT)
- `VITE_ATTACHMENTS_WORKER_URL` (optional)
- `VITE_TURNSTILE_SITE_KEY` (public; hosted builds, D30)
- `VITE_E2E=1` (e2e production-mode builds only)

Kotlin (`local.properties` / env):

- `NOTELIKEUS_SUPABASE_URL` / `notelikeus.supabaseUrl`
- `NOTELIKEUS_SUPABASE_ANON_KEY` / `notelikeus.supabaseAnonKey`
- `NOTELIKEUS_ATTACHMENTS_WORKER_URL` / `notelikeus.attachmentsWorkerUrl`
- `NOTELIKEUS_CAPTCHA_PAGE_URL` / `notelikeus.captchaPageUrl` (the web app's `captcha.html`; required
  for release builds, D30)

Worker:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY` (secret)
- `SUPABASE_SERVICE_ROLE_KEY` (secret; required in production: account deletion, confirming attachment deletes so their quota is released, and the cron sweeps)
- `ATTACHMENTS_BUCKET` (R2 binding)
- `ALLOWED_ORIGINS` (optional)
- `ATTACHMENT_RATE_LIMITER` (optional Cloudflare rate-limit binding)

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

1. Create a fresh Supabase project, `supabase link`, `supabase db push`. Migrations go out **before** the Worker: a Worker calling an RPC its database does not have yet answers 503, and the older Worker's RPCs all still exist, so schema-first is the safe order in both directions.
2. Keep the Email provider enabled and set the **Magic Link** and **Confirm signup** email templates to send the code (`{{ .Token }}`) rather than a link — `supabase/templates/` has the local versions. Google and other OAuth providers stay off. Configure custom SMTP and raise the email rate limit: every sign-in sends an email. Add redirect URLs for localhost, Pages preview, and the production domain.
3. Enable Realtime on `notes` and `note_tombstones` (already published by migration).
4. Create an R2 bucket, deploy `workers/attachments`, and `wrangler secret put SUPABASE_SERVICE_ROLE_KEY`. Without it account deletion answers 503, deleted attachments never release their quota, and the cron sweeps do nothing.
5. Create a Cloudflare Pages project with build `cd web && npm ci && npm run build`, output `web/dist`.
6. Set Pages env vars listed above.
7. Attach a custom domain and add it to the Supabase Auth redirect allowlist.

### Applying migrations to an existing project

Once the project exists, later migrations go out through the `workflow_dispatch` deploy
job in `.github/workflows/supabase.yml` rather than by hand. It runs the pgTAP suite
against a fresh database first, then `supabase db push` against the hosted one, and asks
you to type the project ref so a dispatch cannot be a misclick. It needs three repository
secrets: `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD`, `SUPABASE_PROJECT_REF`.

Running it locally is equivalent and still supported:

```bash
npx supabase link --project-ref <ref>
npx supabase migration list --linked   # what is pending
npx supabase db push
```

Note that `db.<ref>.supabase.co` publishes AAAA records only, so an IPv4-only host
cannot reach it directly and needs the IPv4 pooler.

Every command that touches the hosted database runs through
`scripts/ops/retry-on-connect.sh`. Supavisor caches credentials per pooler node, so for
a window after a password reset one node can reject a password another accepts — the
same `migration list` has succeeded and then failed three minutes later inside one run.
Only connection and authentication failures are retried; a SQL error from a migration
fails on the first attempt, and `npm run test:retry-on-connect` holds that line.

### Deploying the attachments Worker

The `workflow_dispatch` deploy job in `.github/workflows/attachments-worker.yml` runs
lint, typecheck and the Worker suite first, then writes a `wrangler.toml` (gitignored,
so it is generated rather than committed) and deploys. It asks you to type the Worker
name, takes the R2 bucket and any extra CORS origins as inputs, and uploads
`SUPABASE_ANON_KEY` as a Worker secret rather than a var so it stays out of the log.

It needs `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`, and reuses the Pages
secrets `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` — the same project URL and
public anon JWT — rather than duplicating them under names that could drift.

**Apply the migrations first.** A Worker calling an RPC its database does not have yet
answers 503, while the older Worker's RPCs all still exist, so schema-first is safe in
both directions and Worker-first is not. The deploy job enforces it: it lists the hosted project's migrations and refuses
to deploy while any committed one is unapplied, the same check the Pages deploy runs. That
needs the three `supabase.yml` secrets as well.
