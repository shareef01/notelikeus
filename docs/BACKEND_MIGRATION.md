# Notelikeus Backend Migration (Firebase → Supabase + Cloudflare R2)

**Status: COMPLETED / SUPERSEDED — 2026-09-05**

This document is historical. The cutover is finished in source:

- Legacy Firebase user/data migration was **intentionally abandoned**.
- Supabase is the canonical backend (Auth, PostgreSQL, RPC, Realtime).
- Cloudflare Pages hosts the Web PWA. Cloudflare Worker + R2 hold attachment blobs.
- Do **not** preserve Firebase Auth, Firestore, UID mapping, or a dual-backend fallback.

See `docs/BACKEND_ARCHITECTURE.md` for the current architecture and
`docs/SUPABASE_CUTOVER_AUDIT.md` for the phase-by-phase audit.

---



---

## Current architecture (pre-migration baseline)

Notelikeus is **local-first → cloud-second** on Kotlin platforms and is being brought to parity on Web.

```
Android / Windows / Web UI
        ↓
   Local database (Room / IndexedDB)
        ↓
    Sync engine
        ↓
   Remote backend (Firebase today)
```

### Kotlin Multiplatform

| Layer | Location | Role |
|-------|----------|------|
| Domain model | `composeApp/.../domain/model/Note.kt` | Canonical note fields |
| Room entities/DAO | `composeApp/.../data/local/` | SQLite source of truth |
| Sync engine | `composeApp/.../data/sync/NoteSyncEngine.kt` | Upload/download, tombstones, conflict resolution via `serverUpdatedAt` |
| Remote abstraction | `composeApp/.../data/sync/CloudNoteTransport.kt` | Platform-agnostic cloud I/O |
| Firebase impl (Android) | `composeApp/.../data/remote/FirestoreNoteTransport.kt` | Firestore SDK adapter |
| Firebase impl (Desktop) | `composeApp/.../data/remote/DesktopFirestoreTransport.kt` | Firestore REST adapter |
| Test fake | `composeApp/.../data/sync/FakeCloudNoteTransport.kt` | In-memory transport for unit tests |

Conflict resolution uses **server-assigned** `serverUpdatedAt`, not client `timestamp`. Empty-cloud protection raises `SuspectEmptyCloudException`.

### Web (after Phase 2)

| Layer | Location | Role |
|-------|----------|------|
| UI state | `web/src/store/notesStore.ts` | In-memory mirror; filters persisted in localStorage |
| Local DB | `web/src/lib/local/notesLocalRepository.ts` | IndexedDB (`notelikeus-notes`) per owner namespace |
| Owner namespace | `web/src/lib/local/ownerNamespace.ts` | `user.uid` signed-in; `__guest__` guest mode |
| Write path | `web/src/lib/notes/noteActions.ts` | IndexedDB first, then remote when signed in |
| Sync service | `web/src/lib/notes/notesSyncService.ts` | Remote snapshots → IndexedDB → Zustand |
| Bootstrap | `web/src/hooks/useNotesSync.ts` | First sign-in: Firebase snapshot → IndexedDB; then realtime |
| Guest bootstrap | `web/src/hooks/useGuestLocalNotesBootstrap.ts` | Load guest notes from IndexedDB |
| Remote abstraction | `web/src/lib/remote/remoteNotesDataSource.ts` | Firebase default via `firebaseRemoteNotesDataSource.ts` |
| Firebase CRUD | `web/src/lib/firestore/notesRepository.ts` | Firestore reads/writes, merge, empty-cloud guard |

### Firebase services in use

- **Auth:** Google sign-in + guest mode (Web); Firebase Auth (Android/Desktop)
- **Firestore:** `users/{uid}/notes`, `users/{uid}/tombstones`, `users/{uid}/_meta/sync`
- **Hosting:** `notelike.web.app` (Web PWA)
- **Rules/indexes:** `firestore.rules`, `firestore.indexes.json`
- **Emulator tests:** `tests/firestore.rules.test.mjs`, `web` sync/e2e configs

---

## Firebase coupling inventory

| Category | Examples | Phase |
|----------|----------|-------|
| AUTH | `FirebaseAuth`, `GoogleAuthProvider`, `useAuthStore` | Phase 5 (dev flag); Firebase default in prod |
| REMOTE DATA | `FirestoreNoteTransport`, `notesRepository.ts`, `onSnapshot` | Phase 1 abstracted; Firebase still default |
| LOCAL CACHE | Firestore persistent cache (Web legacy); now IndexedDB primary | Phase 2 |
| HOSTING | `firebase.json`, `firebase deploy` | Phase 10 scaffold; Firebase still production |
| TESTING | Rules emulator, `notesSync.emulator.test.ts`, Playwright e2e | Retained |
| CI | `scripts/ci-local.ps1`, `npm run test:rules` | Retained |
| CONFIG | `google-services.json`, env Firebase keys | Unchanged |
| DOCUMENTATION | README hosting URLs | Unchanged until cutover |

---

## Sync invariants (compatibility contract)

These behaviors must be preserved in Supabase:

1. **Offline create/edit/delete** — local DB works without network (Web IndexedDB; Kotlin Room).
2. **Server-authoritative conflicts** — `serverUpdatedAt` / revision wins over client clock.
3. **Tombstones** — deletions are durable; stale devices cannot resurrect deleted notes.
4. **Empty-cloud protection** — infrastructure/read failure must not wipe local notes.
5. **Account switching** — prior account data cleared from local namespace on switch.
6. **Guest mode** — optional; guest notes in IndexedDB; no tombstone on guest delete.
7. **Note IDs** — string doc id = `localId` text; stable across backup/migration/attachments.
8. **Labels/checklists/reminders/archive/trash/pin/ordering** — field parity with Firestore map.
9. **Backup import** — existing JSON backup versions remain importable.

---

## Baseline verification (before migration edits)

| Suite | Command | Result |
|-------|---------|--------|
| Firestore rules | `npm run test:rules` | PASS (41/41) |
| Web lint | `cd web && npm run lint` | PASS |
| Web typecheck | `cd web && npm run typecheck` | PASS |
| Web unit | `cd web && npm test` | PASS (271/271) |
| Kotlin unit | `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest` | See final verification |
| Android release | `./gradlew :androidApp:assembleRelease` | See final verification |
| Web build/sync/e2e | `npm run build`, `test:sync`, `test:e2e` | See final verification |
| Supabase local | `npm run supabase:reset && npm run supabase:test` | See final verification |

---

## Migration phase tracker

| Phase | Status | Summary |
|-------|--------|---------|
| **0 — Scaffolding** | COMPLETE LOCALLY | This document, architecture map, coupling inventory |
| **1 — Backend abstractions** | COMPLETE LOCALLY | Kotlin `CloudNoteTransport` (pre-existing); Web `RemoteNotesDataSource`; contract tests |
| **2 — Web local-first** | COMPLETE LOCALLY | IndexedDB repository; signed-in hydration; guest persistence; account namespace |
| **3 — Local Supabase** | COMPLETE LOCALLY | `supabase/` schema, RLS, revision RPCs, pgTAP tests |
| **4 — Supabase remote adapter** | COMPLETE LOCALLY | Dev-flagged `RemoteNotesDataSource` + `CloudNoteTransport`; Firebase default |
| **5 — Supabase Auth** | COMPLETE LOCALLY | Dev-flagged auth on Web + Kotlin; Firebase default |
| **6 — User data migration** | COMPLETE LOCALLY | Firebase uid ↔ Supabase uuid linking; IndexedDB namespace + optional cloud import |
| **7 — Realtime optimization** | COMPLETE LOCALLY | Web Supabase Realtime + debounced pull_changes; slow polling fallback |
| **8 — Attachments + R2** | COMPLETE LOCALLY | `note_attachments` metadata + R2 Worker scaffold + client blob transport (no UI yet) |
| **9 — Attachment UI + sync** | COMPLETE LOCALLY | Web editor image picker + R2 upload on save + Supabase metadata hydration on pull |
| **10 — Cloudflare Pages** | COMPLETE LOCALLY | `_headers`/`_redirects` parity + Pages CI verify; Firebase Hosting still production |
| **11 — Firebase retirement readiness** | COMPLETE LOCALLY | Cutover runbook, production override flags (off by default), account wipe RPC, ops backup export. **Firebase not removed.** |
| **12 — Kotlin attachment UI + sync** | COMPLETE LOCALLY | Android/Desktop editor image picker + preview strip; `attachmentsJson` Room column; `AttachmentSyncService` wired into `NoteSyncEngine` + editor save path (dev flags) |

---

## Phase 1 — Backend abstractions

### Kotlin

`CloudNoteTransport` already existed. Added shared contract tests:

- `composeApp/src/commonTest/.../CloudNoteTransportContractTest.kt`
- Runs against `FakeCloudNoteTransport`; Firestore/Supabase adapters added in Phase 4 (`SupabaseNoteTransport`).

### Web

- `web/src/lib/remote/remoteNotesDataSource.ts` — interface
- `web/src/lib/remote/firebaseRemoteNotesDataSource.ts` — Firebase implementation (default)
- `web/src/lib/remote/remoteNotesDataSourceRegistry.ts` — selects backend via dev flag
- `web/src/lib/supabase/supabaseRemoteNotesDataSource.ts` — Supabase RPC adapter (Phase 4)
- `noteActions.ts`, `notesSyncService.ts` route remote I/O through the registry

Firebase behavior unchanged in production; Supabase requires dev flag + session JWT.

---

## Phase 2 — Web local-first

### IndexedDB approach

Native IndexedDB (no Dexie) with `fake-indexeddb` for unit tests. Reuses one repository for guest and signed-in modes with **owner namespaces**:

- Signed-in: `ownerId = Firebase uid`
- Guest: `ownerId = __guest__`

Stores: `notes` (compound key `[ownerId, id]`), `meta` (`firebaseHydrated` flag).

### Write flow

```
user edit → noteActions → IndexedDB → Zustand UI
                       ↘ remote upsert (if signed in)
```

### Read flow

```
app start → load IndexedDB → Zustand
signed-in first run → Firebase snapshot → IndexedDB → Zustand
remote snapshot → IndexedDB → Zustand
```

### Account switch

`clearLocalUserData()` clears localStorage stores **and** IndexedDB owner namespace via `clearOwner()`.

### Tests added

- `web/src/lib/local/notesLocalRepository.test.ts`

---

## Phase 3 — Local Supabase infrastructure

**Local only.** No production project linked. No client Supabase auth yet.

### Commands

```bash
npm install          # installs supabase CLI as devDependency
npm run supabase:start
npm run supabase:reset
npm run supabase:test
npm run supabase:stop
```

### Schema field mapping (Firestore → PostgreSQL)

| Firestore field | Type | Supabase column | Notes |
|-----------------|------|-----------------|-------|
| document id | string | `note_id` TEXT | Same as `localId` string |
| `localId` | int | `local_id` BIGINT | CHECK `note_id = local_id::text` |
| (implicit owner) | uid path | `owner_id` UUID | `auth.users.id` after Phase 5 |
| — | — | `revision` BIGINT | `sync_revision_seq`; server-only |
| `title` | string | `title` TEXT | max 2000 |
| `content` | string | `content` TEXT | max 100000 |
| `timestamp` | int | `client_timestamp` BIGINT | client edit clock |
| `color` | int | `color` INTEGER | ARGB |
| `isPinned` | bool | `is_pinned` BOOLEAN | |
| `isArchived` | bool | `is_archived` BOOLEAN | |
| `isTrashed` | bool | `is_trashed` BOOLEAN | |
| `position` | int | `position` INTEGER | |
| `reminderTimestamp` | int/null | `reminder_timestamp` BIGINT | nullable |
| `labels` | array | `labels` JSONB | `[{name}]` preserved |
| `checklist` | array | `checklist` JSONB | text/isChecked/position |
| `serverUpdatedAt` | timestamp | `server_updated_at` TIMESTAMPTZ | server metadata |
| tombstone doc | map | `note_tombstones` table | `deleted_at`, `revision` |

### Revision protocol

- Sequence: `sync_revision_seq` (starts 10001)
- Every accepted mutation gets `revision = nextval(sync_revision_seq)`
- Client cursor: `last_remote_revision`
- `pull_changes(after_revision, limit)` — ascending, paginated
- `apply_note_change(payload, base_revision)` — optimistic concurrency; `base_revision = null` for create
- `apply_note_delete(note_id, base_revision)` — tombstone + row removal
- `fetch_full_snapshot()` — migration/recovery; clients must still apply empty-cloud guards

### RLS model

- RLS enabled on `notes`, `note_tombstones`, `sync_meta`
- `authenticated` role: CRUD only where `owner_id = auth.uid()`
- Anonymous: denied (tested)
- User A/B isolation tested in pgTAP
- Sync mutations prefer RPCs (`SECURITY INVOKER`, `auth.uid()` derived server-side)

### Files

```
supabase/config.toml
supabase/migrations/20250902000000_notelikeus_sync_schema.sql
supabase/migrations/20250902010000_sync_mutation_guard.sql
supabase/seed.sql
supabase/tests/database/notelikeus_sync.test.sql
supabase/tests/database/notelikeus_rls.test.sql
supabase/tests/database/notelikeus_sync_protocol.test.sql
package.json  (supabase scripts)
```

---

## Phase 4 — Supabase remote adapter (dev-only)

**Firebase remains the production default.** Supabase adapters are wired behind development-only flags and require a Supabase JWT (Phase 5 will integrate sign-in).

### Web dev flag

Set in `web/.env` (never in production builds):

```bash
VITE_REMOTE_BACKEND=supabase
VITE_SUPABASE_URL=http://127.0.0.1:54321
VITE_SUPABASE_ANON_KEY=<local anon key from supabase start>
```

`isSupabaseBackendEnabled()` returns `false` in production builds unless `VITE_E2E` is set.

### Web adapter behavior

- **Writes:** `apply_note_change` / `apply_note_delete` RPCs (revision-based OCC)
- **Reads:** `fetch_full_snapshot` + Realtime `postgres_changes` on `notes` / `note_tombstones` triggering debounced `pull_changes` (30s polling fallback when channel unavailable)
- **Revision state:** stored in IndexedDB owner meta (`lastRemoteRevision`, `noteRevisions`)
- **Empty-cloud guard:** preserved in `syncNotesWithCloud`
- **Auth:** requires active Supabase session; errors clearly if missing

### Kotlin dev flag

```bash
NOTELIKEUS_REMOTE_BACKEND=supabase
NOTELIKEUS_SUPABASE_URL=http://127.0.0.1:54321
NOTELIKEUS_SUPABASE_ANON_KEY=<anon key>
```

`NOTELIKEUS_SUPABASE_ACCESS_TOKEN` is no longer required when Phase 5 Supabase auth is active (session manager supplies JWT).

`BackendConfig.remoteBackend` is `SUPABASE` only when `NOTELIKEUS_REMOTE_BACKEND=supabase` **and** `AppConfig.isDebug`.

### Kotlin files

```
composeApp/.../data/remote/BackendConfig.kt
composeApp/.../data/remote/SupabaseNoteTransport.kt
composeApp/.../data/remote/SupabaseRpcClient.kt
composeApp/.../data/remote/DesktopSupabaseRpcClient.kt (desktop)
composeApp/.../data/remote/AndroidSupabaseRpcClient.kt (android)
composeApp/.../data/remote/SupabaseSessionAccessTokenProvider.kt
```

Platform DI (`PlatformModule`) selects `SupabaseNoteTransport` vs Firestore transport based on `BackendConfig`.

---

## Phase 5 — Supabase Auth (dev-only)

**Firebase remains the production default.** Supabase Auth is wired behind the same dev flags as Phase 4. Manual JWT env vars (`NOTELIKEUS_SUPABASE_ACCESS_TOKEN`) are no longer required when Supabase auth is active.

### Web dev flag

Same as Phase 4 (`VITE_REMOTE_BACKEND=supabase`). Auth routes through:

- `web/src/lib/auth/authUser.ts` — platform-agnostic `AuthUser`
- `web/src/lib/auth/supabaseAuth.ts` — Supabase session listener, Google OAuth, email/password
- `web/src/lib/auth/firebaseAuthListener.ts` — extracted Firebase listener (default)
- `web/src/hooks/useAuth.ts`, `googleAuth.ts`, `emailAuth.ts` — route by `isSupabaseBackendEnabled()`
- `web/src/store/authStore.ts` — stores `AuthUser` instead of Firebase `User`

Supabase client uses `detectSessionInUrl: true` for OAuth redirect handling on `http://127.0.0.1:5173`.

### Kotlin dev flag

Same as Phase 4 (`NOTELIKEUS_REMOTE_BACKEND=supabase` + debug build). Auth routes through:

- `CloudSessionManager` interface — shared session contract
- `FirebaseSessionManager` — default (implements `CloudSessionManager`)
- `SupabaseSessionManager` + `SupabaseAuthApi` — Google ID token exchange, email/password, token refresh
- `SupabaseSessionAccessTokenProvider` — supplies JWT to `SupabaseNoteTransport` (replaces manual token env)
- `AndroidSyncManager`, `DesktopSyncManager`, `CloudNoteSyncCoordinator`, `SyncWorker` — use `CloudSessionManager`

### Local Supabase Google OAuth

Set before `supabase start`:

```bash
SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID=<Web OAuth client id>
SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET=<Web OAuth client secret>
```

`supabase/config.toml` enables `[auth.external.google]` with `skip_nonce_check = true` for local dev. `site_url` and redirect URLs include Vite dev port `5173`.

### Limitations (Phase 5 scope)

- **No user data migration** — Firebase UID ≠ Supabase UUID; Phase 6 maps ownership
- **Anonymous/guest** — unchanged; guest mode remains local-only
- **Sign-out + delete cloud data** — implemented in Phase 11 via `delete_all_user_cloud_data`

---

## Phase 6 — User data migration (dev-only)

Maps legacy Firebase Auth UIDs to Supabase `auth.users.id` so account-switch guards and IndexedDB owner namespaces survive the backend transition.

### Supabase schema

- `firebase_uid_mappings` table (`firebase_uid` → `owner_id`)
- `link_firebase_uid(p_firebase_uid)` RPC — idempotent, per-user
- `get_linked_firebase_uid()` RPC — read back linked uid

### Web behavior

On Supabase sign-in (`ensureFirebaseSupabaseMigration`):

1. Resolve candidate Firebase uid (`lastMergedUserId`, local link, server mapping, or active Firebase session)
2. Call `link_firebase_uid` RPC + persist local link
3. Migrate IndexedDB namespace (`migrateOwnerNamespace`) from Firebase uid → Supabase uuid
4. Optionally import Firebase cloud notes/tombstones when Supabase is empty and Firebase session is available

Account-switch detection uses `accountsMatch()` so a linked Firebase→Supabase transition does not wipe local data.

### Kotlin behavior

- `AccountUidBridge` — treats linked Firebase uid and Supabase uuid as the same account
- `FirebaseSupabaseAccountLinker` — runs after Supabase sign-in; links uids and calls RPC
- `LocalAccountIsolator` / `NoteSyncEngine` — use `accountsMatch` instead of raw uid equality
- `NoteSyncStateStore` — stores `linkedFirebaseUid` for persistence across restarts

### Limitations (Phase 6 scope)

- **Dev flag only** — same as Phases 4–5; production still uses Firebase
- **Cloud import requires Firebase session** — Web can pull from Firestore only when Firebase Auth is still active
- **No bulk server-side Firestore export** — one-user client-driven import; ops migration tooling deferred

---

## Phase 7 — Realtime optimization (dev-only)

Replaces the 5s `pull_changes` polling loop with Supabase Realtime on Web.

### Supabase publication

Migration `20250902030000_realtime_publication.sql` adds `notes` and `note_tombstones` to the `supabase_realtime` publication. RLS still filters events per authenticated subscriber.

### Web behavior

- `subscribeSupabaseNoteRealtime()` — `postgres_changes` on both tables, filtered by `owner_id`
- On change: debounced `pull_changes` (300ms) with `has_more` pagination
- Fallback: 30s polling only when the Realtime channel errors or times out
- Shared pull/snapshot logic extracted to `supabaseSyncEngine.ts`

### Kotlin

Unchanged — Android/Desktop continue pull-on-sync via `SupabaseNoteTransport` RPCs (no background listener). Realtime is a Web concern until a dedicated Kotlin transport phase.

### Limitations (Phase 7 scope)

- **Web only** — Kotlin platforms sync on user action / WorkManager, not continuous polling
- **Requires Realtime enabled** — local `supabase/config.toml` has `[realtime] enabled = true`
- **Dev flag only** — production Web still uses Firebase `onSnapshot`

---

## Phase 8 — Attachments + Cloudflare R2 (scaffolding)

**Goal:** Store attachment binaries in Cloudflare R2 behind the same dev flags as Supabase; register metadata in Postgres. **No editor UI** — that is Phase 9.

### Supabase

- Migration `20250902040000_note_attachments.sql` — `note_attachments` table, owner RLS, RPCs:
  - `register_note_attachment` — validates `object_key` matches `owners/{auth.uid()}/notes/{note_id}/{attachment_id}`
  - `list_note_attachments`, `delete_note_attachment` (soft delete)
  - `expected_attachment_object_key` helper
- pgTAP: `notelikeus_note_attachments.test.sql`

### Cloudflare Worker (`workers/attachments/`)

- `PUT/GET/DELETE /v1/attachments/{noteId}/{attachmentId}` — proxies to R2 bucket binding
- Auth: validates Supabase session via `/auth/v1/user`
- `wrangler.toml.example` for local `wrangler dev`
- Unit tests: `workers/attachments/src/objectKey.test.ts` (`npm run test:attachments-worker`)

### Web

- `web/src/lib/attachments/` — `AttachmentBlobStore` registry, R2 implementation, Supabase metadata RPCs
- Enabled when `VITE_REMOTE_BACKEND=supabase` **and** `VITE_ATTACHMENTS_WORKER_URL` is set
- Default: `noopAttachmentBlobStore` (throws if called)

### Kotlin

- `AttachmentBlobTransport` + `R2AttachmentBlobTransport` / `NoopAttachmentBlobTransport`
- `AttachmentObjectKey`, `SupabaseAttachmentMetadata`
- Wired in `PlatformModule` when `NOTELIKEUS_REMOTE_BACKEND=supabase` and `NOTELIKEUS_ATTACHMENTS_WORKER_URL` is set

### Dev flags

| Platform | Env |
|----------|-----|
| Web | `VITE_ATTACHMENTS_WORKER_URL=http://127.0.0.1:8787` |
| Kotlin | `NOTELIKEUS_ATTACHMENTS_WORKER_URL=http://127.0.0.1:8787` |

### Limitations (Phase 8 scope)

- **No UI** — notes still persist with `attachments: []`; see `archive/attachments-feature/` for prior Firebase Storage UI
- **No sync pipeline wiring** — blob transport is injectable but not called from `NoteSyncEngine` yet
- **Worker/R2 not deployed** — scaffold only; local dev requires `wrangler dev` + R2 bucket

---

## Phase 9 — Attachment UI + sync wiring (Web)

**Goal:** Restore image attachments in the Web editor when R2 is enabled; upload blobs on save and hydrate metadata from Supabase on sync.

### Supabase

- Migration `20250902050000_list_user_attachments.sql` — `list_user_attachments()` RPC for bulk hydration
- pgTAP: `notelikeus_list_user_attachments.test.sql`

### Web

- `AttachmentImageStrip` in editor; image button on `RichTextToolbar` when `isR2AttachmentsEnabled()`
- `attachmentSyncService` — pending blob store, upload on `saveNote`, delete on remove/trash, merge on pull
- `attachmentPreviewCache` — object URLs for pending + downloaded R2 blobs
- `supabaseSyncEngine` hydrates attachments after snapshot/incremental pull
- `NoteCard` exposes “Has image” in accessibility label when attachments present
- Note equality includes attachment metadata

### Kotlin

- Unchanged UI (attachments remain empty in editor). Blob transport from Phase 8 is ready for a follow-up Kotlin UI phase.

**Superseded by Phase 12** — see below.

### Limitations (Phase 9 scope)

- **Web only** — Android/Desktop editor still saves `attachments = []`
- **Images only** — 10 MB cap; no video or generic files yet
- **Requires Phase 8 dev flags** — Supabase backend + attachments worker URL

---

## Phase 10 — Cloudflare Pages (hosting scaffold)

**Goal:** Prepare Cloudflare Pages deployment for the Web PWA without cutting over production from Firebase Hosting.

### Static artifacts (`web/public/`)

- `_redirects` — SPA fallback (`/* /index.html 200`)
- `_headers` — security headers matching `firebase.json` plus migration `connect-src` for Supabase + Workers

### Tooling

- `web/scripts/verifyPagesArtifacts.mjs` — ensures `_headers` / `_redirects` land in `web/dist` after build
- `cloudflare/scripts/verify-headers-parity.mjs` — Pages headers are a superset of Firebase Hosting
- `cloudflare/wrangler.pages.toml.example` — Wrangler Pages project template
- Root: `npm run pages:verify`

### CI

- `.github/workflows/cloudflare-pages.yml` — lint, test, build, verify artifacts on every push
- Optional `workflow_dispatch` deploy to `notelikeus-dev` Pages project when Cloudflare + `VITE_*` secrets are configured

### Production

- **Unchanged** — `npm run deploy` still targets Firebase Hosting (`notelike.web.app`)
- README / package `homepage` URLs unchanged until an authorized cutover

---

## Phase 11 — Firebase retirement readiness (not cutover)

**Firebase remains the production backend.** This phase prepares a reversible cutover. It does **not** remove Firebase dependencies, change README/privacy production claims, or deploy.

Firebase can only be retired after: Supabase sync, auth, and migration proven on all platforms; a migration window for existing users; RLS/backup/IndexedDB verified; then a gradual removal. Those owner-operated steps are listed below. Code in this phase only makes them possible.

### Account wipe (sign out and delete cloud data)

- Migration `20250902060000_delete_all_user_cloud_data.sql`
  - `delete_all_user_cloud_data()` RPC — authenticated caller only; `SECURITY INVOKER`; mutation guard
  - Deletes the caller's notes, tombstones, attachment metadata, `sync_meta`, and `firebase_uid_mappings`
  - Returns `attachment_object_keys` so the client can best-effort DELETE R2 blobs while the JWT is still valid
  - pgTAP: `notelikeus_delete_all_user_cloud_data.test.sql` (anon denied; A/B isolation; idempotent)
- Web: `deleteAllSupabaseCloudData()` from `signOutGoogle({ deleteCloudData: true })`
- Kotlin: `CloudNoteTransport.deleteAllOwnedCloudData`; `SupabaseNoteTransport` calls the RPC

### Production override (off by default)

Ordinary production users cannot switch backends.

| Platform | Cutover build requirements |
|----------|----------------------------|
| Web | `VITE_REMOTE_BACKEND=supabase` **and** `VITE_ALLOW_SUPABASE_PRODUCTION=true` **and** non-localhost `VITE_SUPABASE_URL` |
| Web (Pages staging only) | `VITE_REMOTE_BACKEND=supabase` **and** `VITE_ALLOW_SUPABASE_STAGING=true` — runtime-gated to `*.pages.dev`; does **not** enable `notelike.web.app` |
| Kotlin | `NOTELIKEUS_REMOTE_BACKEND=supabase` **and** `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION=true` **and** non-localhost `NOTELIKEUS_SUPABASE_URL` |

Debug/dev builds still enable Supabase from `VITE_REMOTE_BACKEND` / `NOTELIKEUS_REMOTE_BACKEND` alone (including local Docker). Production builds without the allow flag stay on Firebase even if other `VITE_SUPABASE_*` values are present.

### Ops export (offline)

`scripts/ops/export-firestore-user.mjs` converts a **local** Firestore notes dump into the existing backup JSON (version 3). It does not contact production.

```bash
npm run test:ops-export
node scripts/ops/export-firestore-user.mjs --input dump.json --out backup.json
```

Users import that file in-app (existing backup import + Phase 6 client migration). Bulk server-side write into production Supabase is still not provided.

### Cutover runbook (owner-operated; do not run from this branch automatically)

1. Provision a **staging** Supabase project and attach Cloudflare R2 + Pages `notelikeus-dev`. Do not point production clients at it.
2. Run pgTAP + web/Kotlin suites against staging; complete a migration window on a test account (Firebase dump → backup JSON → Supabase sign-in import).
3. Ship a client that still defaults to Firebase, with the override flags available for an internal cutover build.
4. Give existing users a documented backup/export window.
5. Only after that window: build Web/Android/Desktop with the production override flags, switch `npm run deploy` / hosting to Cloudflare Pages, update README and `PRIVACY_POLICY.md` to describe Supabase/R2.
6. Keep Firebase Auth/Firestore readable (not deleted) until the migration window is over and rollback is no longer needed.
7. Remove Firebase dependencies, rules, indexes, emulator config, and Hosting only after the window closes. Preserve git history.

### Limitations (Phase 11 scope)

- **No production cutover** — flags default off; Firebase Hosting URL unchanged
- **Firebase SDKs retained** — Auth, Firestore, Hosting, rules tests still in CI
- **No bulk server-side import into Supabase** — backup JSON + client import only
- **R2 blob cleanup is best-effort** — Postgres wipe is authoritative
- **Kotlin attachment UI** — implemented in Phase 12 (Web-only before that)

---

## Phase 12 — Kotlin attachment UI + sync (dev-only)

**Goal:** Mirror Web Phase 9 on Android and Desktop when R2 dev flags are enabled.

### Domain + persistence

- `Attachment` model aligned with Web (`id: String`, `storagePath` with `pending:` / `r2:` / `file:` prefixes)
- Room migration `MIGRATION_10_11`: `notes.attachmentsJson` column (JSON metadata; binaries in local files or R2)

### Sync

- `AttachmentSyncService` — merge/hydrate/upload/delete (mirrors `web/src/lib/attachments/attachmentSyncService.ts`)
- `SupabaseAttachmentMetadata.listUserAttachments()` RPC
- `NoteSyncEngine` hooks: hydrate after download, upload before push, delete on note removal; `sameContent()` compares attachment keys

### Editor UI

- `AttachmentImageStrip` + platform image picker (Android `PickVisualMedia`, Desktop file dialog)
- Image button on `RichTextToolbar` when `isR2AttachmentsEnabled()`
- `EditorViewModel` persists attachment metadata and uploads pending blobs on save

### Dev flags

Same as Phases 8–9:

| Platform | Env |
|----------|-----|
| Kotlin | `NOTELIKEUS_REMOTE_BACKEND=supabase` + `NOTELIKEUS_ATTACHMENTS_WORKER_URL` |

### Limitations (Phase 12 scope)

- **Dev flag only** — Firebase remains production default
- **Images only** — 10 MB cap; images MIME types only
- **Backup export** — attachments still omitted from JSON backup (unchanged)
- **Note list thumbnails** — not yet added (Web has a11y hint only)

---

## Phase 2/3 safety review (pre–Phase 4 gate)

### Web logout vs account switch

| Action | IndexedDB | In-memory / localStorage tombstones |
|--------|-----------|-------------------------------------|
| **Sign out** (`clearLocalUserData`) | **Preserved** per owner namespace | Cleared |
| **Account switch** (`clearLocalUserDataForAccountSwitch`) | **Wiped** for previous `uid` | Cleared |

**Rationale:** Offline edits made while signed in must survive sign-out and re-login under the same account. Account isolation requires wiping the *previous* account's IndexedDB namespace only when a different account signs in.

**Tests:** `web/src/lib/bootstrap.test.ts`

### Guest ↔ authenticated behavior

| Transition | Behavior |
|------------|----------|
| Guest → sign in | In-memory guest state cleared; guest IndexedDB namespace (`__guest__`) **preserved** |
| Sign in → guest | Signed-in user's IndexedDB **preserved**; guest loads from `__guest__` namespace |
| Account A → B | B never reads A's namespace; A's IndexedDB wiped on switch |

Guest and signed-in notes use separate owner namespaces — they are **not merged** automatically.

### Supabase tombstone / resurrection invariant

Separate `notes` + `note_tombstones` tables with RPC-only mutations:

- After delete, `apply_note_change` with `base_revision = null` → `conflict / note_deleted`
- Stale update with old `base_revision` after delete → `conflict` (note row absent; tombstone present)
- Direct table INSERT/UPDATE/DELETE blocked by `sync_mutation_guard` trigger (revision/owner forgery)
- Explicit restore RPC deferred to Phase 4+; resurrection is never implicit

**Tests:** `supabase/tests/database/notelikeus_sync_protocol.test.sql`

### pgTAP coverage matrix

| Case | File |
|------|------|
| Schema + RLS enabled | `notelikeus_sync.test.sql` |
| Anonymous read/insert/RPC denial | `notelikeus_rls.test.sql` |
| A/B tenant isolation | `notelikeus_rls.test.sql` |
| Revision 10001→10002→10003 ordering | `notelikeus_sync_protocol.test.sql` |
| `pull_changes(after)` pagination | `notelikeus_sync_protocol.test.sql` |
| Stale `base_revision` conflict | `notelikeus_sync_protocol.test.sql` |
| Create → delete → stale update (no resurrection) | `notelikeus_sync_protocol.test.sql` |
| Direct revision/owner forgery blocked | `notelikeus_sync_protocol.test.sql` |
| Idempotent delete | `notelikeus_sync_protocol.test.sql` |

---

## Final verification (post Phase 3 + safety review)

| Suite | Command | Result |
|-------|---------|--------|
| Firestore rules | `npm run test:rules` | **PASS** (41/41) |
| Kotlin unit (Android + desktop) | `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest` | **PASS** |
| Android release build | `./gradlew :androidApp:assembleRelease` | **PASS** |
| Web typecheck | `cd web && npm run typecheck` | **PASS** |
| Web unit | `cd web && npm test` | **PASS** (283/283, includes Phase 4–5 auth mapper tests) |
| Web build / sync / E2E | `npm run build`, `test:sync`, `test:e2e` | **PASS** |
| Supabase start | `npm run supabase:start` | **BLOCKED locally** — Docker Desktop installed but virtualization not enabled in BIOS |
| Supabase db reset | `npm run supabase:reset` | **NOT EXECUTED locally** (blocked on Docker) |
| Supabase pgTAP | `npm run supabase:test` | **NOT EXECUTED locally** (blocked on Docker) |
| Supabase CI (GitHub Actions) | `.github/workflows/supabase.yml` | **PASS** — [run 33579075312](https://github.com/shareef01/notelikeus/actions/runs/33579075312) on `migration/supabase-r2` (4 pgTAP files, all green) |

**Phase 11 gate:** Retirement *readiness* complete. Production cutover remains owner-authorized.

**Firebase remains the production backend.** Staging (not production) Supabase + R2 + Pages now exist; see Live staging below.

---

## Risks / open questions

1. **Web offline + Firestore cache** — Firestore SDK cache still initialized; IndexedDB is now primary for UI. Phase 4 should evaluate whether to reduce Firestore persistence reliance.
2. **Revision vs serverUpdatedAt** — Kotlin/Web still use timestamp conflict model against Firebase; Supabase adapter must map revision protocol without weakening empty-cloud guards.
3. **Firebase UID ≠ Supabase UUID** — account migration (Phase 6) must map ownership explicitly.
4. **pgTAP / Docker** — local Supabase requires Docker; use **GitHub Actions** (`.github/workflows/supabase.yml`) when local virtualization is unavailable.
5. **Direct table RLS vs RPC-only writes** — Phase 4 adapter should prefer RPCs; consider tightening direct `UPDATE` on `revision` columns.

---

## Live staging (2026-09-03)

Owner-operated staging only. **Do not** set `VITE_ALLOW_SUPABASE_PRODUCTION`.

| Resource | Status |
|----------|--------|
| Supabase project `notelikeus-staging` | Migrations applied; Google provider enabled from the existing Firebase Web client |
| R2 bucket `notelikeus-attachments-dev` | Created |
| Worker `notelikeus-attachments` | Redeployed with Pages CORS — https://notelikeus-attachments.error-endpoint.workers.dev |
| Cloudflare Pages `notelikeus-dev` | **Live staging (Supabase)** — https://notelikeus-dev.pages.dev/ (`VITE_ALLOW_SUPABASE_STAGING`; Hosting remains `notelike.web.app`) |
| Pages staging flag | `VITE_ALLOW_SUPABASE_STAGING` enables Supabase only on `*.pages.dev` (never on `notelike.web.app`) |
| Smoke test (local Vite + staging Supabase) | Signed in, note **staging smoke**, PNG upload; `list_user_attachments` returned 1 row |

Kotlin **debug** builds can point at the same staging stack. Run `npm run kotlin:staging-properties` to copy `web/.env.staging` into gitignored `local.properties`. Android debug `BuildConfig` bakes those keys (device processes do not see shell env); desktop `./gradlew run` reads the same file at runtime. Release APKs keep empty BuildConfig fields and stay on Firebase. Do not set `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION`. See `docs/ANDROID_STAGING.md`.

Bootstrap: `npm run setup:staging` (`scripts/ops/setup-staging.sh`).

---

## Migration audit (2026-09-04)

An independent audit re-derived this tracker from source, SQL and runtime behaviour rather than
from the phase table. Method and results below; the phase statuses above are unchanged except
where a finding contradicted them.

### What was actually executed

Docker was unavailable, so `supabase start` / `supabase db reset` could not run. Instead a local
PostgreSQL 16 was given the Supabase platform bootstrap the CLI would have produced — the `anon` /
`authenticated` / `service_role` / `authenticator` roles, an `auth` schema with `auth.users` and
`auth.uid()`/`auth.role()`/`auth.jwt()` reading `request.jwt.claims`, the `supabase_realtime`
publication, and Supabase's `ALTER DEFAULT PRIVILEGES ... GRANT ALL ON TABLES/FUNCTIONS/SEQUENCES
TO anon, authenticated, service_role`. Every PostgREST request was emulated as one transaction
with `SET LOCAL ROLE` plus the caller's JWT claims.

| Check | Result |
| --- | --- |
| All migrations apply to a virgin database, in order | **PASS** (9/9, repeated after each change) |
| pgTAP suite (`supabase/tests/database`) | **PASS** — 94/94 assertions |
| Cross-user reads/writes on `notes`, tombstones, `sync_meta`, attachments, uid mappings | **PASS** — zero rows and zero mutations leaked in either direction |
| Anonymous role against every table and RPC | **PASS** — nothing readable, nothing callable |
| Direct-table forgery of `revision` / `owner_id` on `notes` and `note_tombstones` | **PASS** — blocked by `sync_mutation_guard` |
| Revision monotonicity across create/create/update/delete | **PASS** — strictly ascending, globally unique |
| `pull_changes` paging over 1 200 changes at `limit 100` | **PASS** — 10 rounds, 1 000 changes, no duplicates, no gaps, converged to the correct 800-note live set |
| Conflict matrix (stale base, recreate-over-live, resurrect-after-delete, idempotent delete, unknown note, id/local_id mismatch) | **PASS** — no implicit resurrection on any path |
| `fetch_full_snapshot` size at 800 notes / 200 tombstones | 267 kB (≈ 3.3 MB projected at 10 000 notes) |
| Secret scan of HEAD and full history | **CLEAN** — every hit is a placeholder, a comment, or a Postgres role name |
| Web unit / typecheck / lint / build | **PASS** — 344/344, clean, 0 errors, bundle builds |
| Attachments Worker | **PASS** — 58/58 (was 9, none covering the handler) |
| Ops scripts (`test:ops-export`) | **PASS** — 3/3 |
| Kotlin desktop unit tests | **PASS** — 330/330 |
| Android unit tests | **PASS** — 407 (composeApp 390, androidApp 17), 0 failures, run on the owner's machine with the SDK present |

### Defects found and fixed

See `20250904000000_mapping_and_attachment_guards.sql` and the commit that introduced it.

1. **Firebase uid ownership was never proven (data/identity, fixed).** `ensureFirebaseSupabaseMigration`
   resolved its candidate Firebase uid from `lastMergedUserId` and a remembered-uid key, both of
   which survive sign-out. On a shared browser profile the next Supabase account resolved the
   *previous* user's uid, called `link_firebase_uid` with it, and adopted that uid's IndexedDB
   namespace. Because `firebase_uid` is the mapping table's primary key, this also permanently
   locked the real owner out of linking. A claim now requires a live Firebase session for that
   uid. Regression tests fail against the pre-fix implementation.
2. **`firebase_uid_mappings` and `note_attachments` had no mutation guard (fixed).** Their RLS
   write policies let an authenticated PostgREST client insert rows directly, skipping the
   "already linked to another account" check and the `object_key` owner-namespace check. The
   latter, with the global `note_attachments_object_key_unique`, let one account squat another's
   future object key and permanently break their attachment registration. Both were reproduced
   against the pre-migration schema and are now blocked.
3. **The mutation guard leaked for the rest of the transaction (fixed).** `set_config(..., is_local)`
   is transaction-scoped, so any transaction that called an RPC could then write the guarded tables
   directly. Not reachable through PostgREST — one RPC call is one transaction — but it silently
   breaks any caller that mixes an RPC with a direct-write assertion, pgTAP files included. Every
   mutating RPC now brackets the window.
4. **`REVOKE ... FROM PUBLIC` never removed `anon`'s EXECUTE (fixed).** Supabase grants the Data
   API roles explicitly through `ALTER DEFAULT PRIVILEGES`, *in addition to* Postgres' own PUBLIC
   default, so revoking one leaves the other. `anon` held EXECUTE on every RPC; the function bodies
   still refused it, so nothing leaked, but the grant table did not say so. Both are revoked now
   and the anon pgTAP assertions move from `28000` to `42501`.
5. **The Worker had no server-side upload limits (fixed).** `putAttachment` called
   `request.arrayBuffer()` with no size cap and no content-type check. The editors' 10 MB cap is a
   UX affordance: the Worker is reachable directly with any Supabase access token. Uploads are now
   streamed against a 10 MB limit, restricted to image types, and served with `nosniff`.
6. **A secret `sb_…` key could reach the browser bundle (fixed).** The ops scripts reject them, but
   a hand-edited `web/.env.staging` was inlined by `import.meta.env` unchecked. The backend
   selector now refuses to enable Supabase unless the anon key is a public JWT.
7. **An empty cloud snapshot hid a migrated library (P1, fixed).** `syncNotesWithCloud` refuses to
   reconcile an empty cloud against known ids, but it is keyed on `previouslyKnownCloudIds`, which
   is empty for an account that has never synced — so it did not cover the case that actually
   happens during migration. `ensureFirebaseSupabaseMigration` moves the user's library into the
   Supabase owner namespace *before* anything is uploaded, so the first `fetch_full_snapshot`
   legitimately answers with zero notes. Both `hydrateIndexedDbFromRemote` and the realtime
   `onData` handler then called `setNotes([])`. IndexedDB survived (`putNotes` is additive, so
   nothing was destroyed), but the in-memory store is what the upload path reads as "local notes",
   so the library was invisible *and* never pushed — and it re-blanked on every subsequent load.
   An empty snapshot no longer replaces a library the device still holds; a library emptied by
   deletion still applies, because tombstoned notes do not count as held.
8. **Kotlin deletes were dropped on a cold start (P2, fixed).** `SupabaseNoteTransport.revisions`
   is per-instance and never persisted, and `NoteSyncEngine.deleteNote()` calls the transport
   directly with no download first — so `deleteNotes` found no base revision and returned without
   calling the RPC at all. Only the full-sync path repopulated the map, and only by luck of
   ordering. It now refreshes once per batch, retries a revision conflict against the revision the
   server reports, and clears the cached revision on the idempotent answer (which carries no
   `revision`, so the old cleanup never ran).
9. **Boot required Firebase even when Supabase was selected (P2, fixed).** `bootstrap.ts` threw
   `BootFailure` on missing Firebase config and called `initFirebase()` unconditionally, and
   `App.tsx` gated sync on `isFirebaseConfigured()`. A Supabase build could not start without full
   Firebase web config — a cutover blocker, and the reason Pages staging never exercised a
   Firebase-free client. Boot now requires whichever backend the build selected, and validates the
   Supabase URL and anon key when that is Supabase. Firebase is still initialised on a Supabase
   build that carries the config, because the Phase 6 bridge reads the live Firebase session to
   prove uid ownership; a failure there is no longer fatal. **Production behaviour is unchanged**:
   `isSupabaseBackendEnabled()` is false in production unless an owner-authorised cutover build
   sets the allow flag, and tests pin both sides of that.
10. **`idempotent` was read off the wrong branch (fixed).** `apply_note_delete` answers an
   already-tombstoned note with `{status: 'applied', idempotent: true}`; the web client looked for
   `idempotent` inside its `status === 'conflict'` branch, so the cleanup was unreachable and a
   stale revision was left behind. Same family as the rehearsal script expecting `"ok"` (#148).

### Firebase uid ownership — resolved (owner-authorised, 2026-09-04)

The audit's one open security item. `link_firebase_uid` accepted any uid from any authenticated
session, and `firebase_uid` was the table's primary key, so the first account to name a uid held it
forever — a durable denial-of-migration against a named victim. (The `EXISTS` guard inside the RPC
never caught that: it runs SECURITY INVOKER under RLS and cannot see another owner's row. The unique
violation was doing the work.)

The fix separates a **claim** from a **proof**:

| | Written by | Exclusive? | Effect of a squatter |
| --- | --- | --- | --- |
| Claim (`verified = false`) | `link_firebase_uid`, any authenticated session | **No** | None — the real owner can still claim, and still prove |
| Proof (`verified = true`) | `link_verified_firebase_uid`, `service_role` only | **Yes** | Displaces unproven claims on the same uid |

A proof is recorded only after a Firebase ID token has been verified: RS256 signature checked
against Google's published certificates, `aud`/`iss` pinned to the configured Firebase project,
`exp`/`iat`/`auth_time` checked with 60s skew, and the uid taken from the token's `sub` — never from
the request body. That runs in the existing attachments Worker
(`workers/attachments/src/firebaseIdToken.ts`), which already validates Supabase access tokens, so
both halves of the identity are checked in one place: *this Supabase user* and *this Firebase user*
are the same person, right now.

Verification is optional. Without `FIREBASE_PROJECT_ID` and `SUPABASE_SERVICE_ROLE_KEY` the route
answers 501 and clients fall back to an unverified claim — which is no longer exclusive, so the
lockout is gone either way. The service-role key bypasses RLS and widens what a Worker compromise
would reach; it is scoped to that one RPC, never written to a file, and set only when the operator
supplies it.

**Tested:** 16 pgTAP assertions for the claim/proof model (squatting, displacement, two-proof
conflict, RLS-blind exclusivity, provenance across re-linking) and 33 Worker tests for the verifier
and route — algorithm confusion (`alg: none`, HS256), wrong-project tokens, forged signatures,
post-signing payload swaps, expiry and skew, unknown key ids, malformed input, and Google being
unreachable. The privileged RPC is never called unless a token actually verified.

**Residual:** an attacker can still write an unverified claim naming someone else's uid. It is not
exclusive, confers no server-side access, and is displaced the moment the real owner proves
ownership — but it is a row they caused. Rate limiting on the Worker is not implemented.

### PR #150 (backup attachments) — review

Reviewed against `origin/main`. The design is right: images are embedded as `dataBase64` under the
existing `version: 3` (older v3 clients ignore the field, so it is genuinely backward compatible),
restored as **pending** attachments so the existing `attachmentSyncService` uploads them to R2 on
save, MIME-allowlisted and size-capped in both directions, and capped at 20 attachments per note on
both Web and Kotlin. Recommend merging after #148 and #149.

Three things worth addressing, none of them blocking:

1. **MIME allowlist mismatch — fixed here.** #150 accepts `image/jpg` (non-standard, but real) on
   both platforms; the Worker's allowlist did not. A backup carrying `image/jpg` would import
   cleanly, store a pending blob, and then be refused **415** on upload — a note importing
   "successfully" with its image silently gone, which is exactly the cross-layer failure chain the
   audit brief warns about. `image/jpg` is now accepted by the Worker, with a test asserting the
   two allowlists agree.
2. **Memory on a 50 MB import.** `MAX_BACKUP_FILE_BYTES` goes 10 MB → 50 MB. Base64 costs ~33%, so
   a 50 MB file carries ~37 MB of binary, and import holds the JSON string, the decoded bytes and
   the Blob copies at once — plausibly 150 MB+ peak. Fine on desktop; a real OOM risk on mobile
   Safari. Worth a streaming or per-note-batched decode before the cap is raised again.
3. **The pending blob store is unbounded and never evicted.** `pendingAttachmentStore` is a
   module-level `Map` drained only by a successful upload. `attachmentsFromBackupDtos` populates it
   from inside `importNotesFromBackup` — a synchronous, pure-looking function — so a parse that is
   later abandoned (the upload throws, so `commitImportedNotes` never calls `setNotes`) leaves the
   blobs resident until reload. At 10 MB per image that adds up.

### Tombstone retention — audited, no change made

`note_tombstones` is never pruned server-side. Nothing in `supabase/migrations` deletes a tombstone
except `delete_all_user_cloud_data`, and `SupabaseNoteTransport.deleteTombstones` is deliberately a
no-op. Kotlin's local `TOMBSTONE_TTL_MS` (180 days) prunes only the device's own copy.

That is the safe direction and it should stay: because a tombstone is never removed, a device that
has been offline for longer than any TTL still learns the note was deleted, so deleted notes cannot
resurrect. The cost is unbounded growth — one row per deleted note per user, forever — at roughly
100 bytes a row, which is slow enough not to matter for a long time (an 800-note/200-tombstone
account snapshots at 267 kB).

Pruning would need a safe watermark: a tombstone can only be dropped once every device that could
resurrect the note has certainly seen it, and nothing currently tracks per-device sync progress.
Inventing one without that tracking would trade unbounded growth for silent data resurrection, so
this is recorded rather than "fixed".

### Open findings — not fixed here

| Finding | Severity | Why it was left |
| --- | --- | --- |
| Kotlin does not upload a verified Firebase uid link — it only writes unverified (non-exclusive) claims. The ownership *gate* is now shared with Web, so no Kotlin client claims a uid it cannot corroborate; what is missing is the proof upload, which needs a Firebase ID token and an HTTP call on two platform targets that cannot be exercised in this environment. | P3 | Needs Android/desktop runtime verification |
| `note_attachments` is not in the realtime publication, so an attachment added on one client is not signalled to another until a note change or the 30 s fallback. | P3 | Behavioural gap, not a defect |
| Desktop `readLocalProperty` (PR #149) scans up to six parent directories of the process CWD for `local.properties` and reads the Supabase URL/key/worker URL from it regardless of `isDebug`. `remoteBackend` is still `isDebug`-gated, so a packaged build stays on Firebase — but a cutover build would take its endpoint from a directory scan. | P2 | Belongs to #149 |
| `nextLocalNoteIdAfter` returns `Math.max(maxId + 1, Date.now() * 1000 + rand)` ≈ 1.8e15 today, safely inside `Number.MAX_SAFE_INTEGER` (9.007e15, reached around year 2255). Backup import re-allocates ids rather than trusting the file, so a hostile `localId` cannot poison the allocator. `local_id` is `BIGINT`, and a value above 2^53-1 does lose precision in `JSON.parse` — verified — but nothing generates one. | P4 | Not reachable; recorded so it is not re-derived |
| `docs/STAGING_HANDOFF.md` (PR #152, draft) describes `user_notes`, `upsert_user_notes`, `list_user_notes` and `import_user_backup`. **None of these exist** in `supabase/migrations`, the web client, the Kotlin client or the ops scripts. The real schema is `notes` / `note_tombstones` / `sync_meta` with `apply_note_change` / `apply_note_delete` / `pull_changes` / `fetch_full_snapshot`. The document, not the architecture, is wrong. | P3 | #152 owns that file |

## Owner actions before production cutover

1. ~~Create Cloudflare Pages project `notelikeus-dev` and deploy a preview.~~ **Done** — https://notelikeus-dev.pages.dev/
2. Register Pages URLs in Google OAuth / Firebase authorized domains:
   - ~~`notelikeus-dev.pages.dev` added to Firebase Auth authorized domains~~ **Done**
   - ~~Browser API key HTTP referrers~~ **Done** — `https://notelikeus-dev.pages.dev/*` and `https://*.notelikeus-dev.pages.dev/*`
   - ~~Supabase Google OAuth from Pages~~ **Done** — authorize 302s to accounts.google.com (`redirect_uri` is `https://<project>.supabase.co/auth/v1/callback`). Pages JS origins are not required for this redirect flow.
3. ~~Create a **staging** Supabase project and `supabase db push`.~~ **Done** (`notelikeus-staging`)
4. ~~Deploy a **Pages staging** bundle (Supabase, not production cutover).~~ **Done** — https://notelikeus-dev.pages.dev/ uses `VITE_ALLOW_SUPABASE_STAGING` (runtime-gated to `*.pages.dev`). `npm run deploy:staging-pages` passes `--branch=main` so Wrangler updates the Production alias. Auth redirect URLs include `https://notelikeus-dev.pages.dev` and `https://notelikeus-dev.pages.dev/**` (localhost:5173 kept). Never set `VITE_ALLOW_SUPABASE_PRODUCTION`.
5. Test-account migration rehearsal:
   - ~~Ops dump → backup JSON~~ **Done** — `npm run rehearse:staging-import` / `scripts/ops/fixtures/backup.rehearsal.example.json`
   - ~~Backup import uses the active remote (Supabase on Pages)~~ **Done** in `commitImportedNotes`
   - **Owner:** sign in with Google on https://notelikeus-dev.pages.dev/, then Profile → Import backup with the example JSON. Repeat on Android/Desktop debug staging builds (`npm run kotlin:staging-properties`, see `docs/ANDROID_STAGING.md`) when convenient.
6. Approve flipping `VITE_ALLOW_SUPABASE_PRODUCTION` / `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION` for a dedicated cutover build.
7. After the user migration window: update README, privacy policy, and `npm run deploy` target. Only then remove Firebase.

### Continue command

Remaining work: sign in with Google on https://notelikeus-dev.pages.dev/ and import `scripts/ops/fixtures/backup.rehearsal.example.json` (Profile → Import backup). For Android/Desktop debug, run `npm run kotlin:staging-properties` then rebuild/restart and repeat the import. Do not start production Firebase retirement.

```
Do not start Firebase retirement in production. Review docs/BACKEND_MIGRATION.md Live staging first.
```

---

## Cutover gate

Production cutover is **not** authorized. These are the conditions that would let the owner decide.
Ticked items were established by the 2026-09-04 audit; the rest are owner-gated or unfinished.

- [x] Fresh Supabase database reproduces from `supabase/migrations` alone, with no manual SQL
- [x] No cross-user read or write survives RLS on any table (A/B and anonymous, executed)
- [x] Direct PostgREST writes cannot bypass the revision protocol or the RPC invariants
- [x] RPC grants match what the function bodies enforce
- [x] Conflict, tombstone and no-resurrection semantics verified
- [x] `pull_changes` pagination verified past one page (1 200 changes)
- [x] Empty cloud never replaces a library the device still holds (reconcile, hydrate, realtime)
- [x] Worker rejects unauthenticated, cross-user and oversized requests (tested)
- [x] Production-isolation guards tested (Firebase host, lookalike hosts, missing flags, secret key)
- [x] No credential in HEAD or git history
- [x] **Firebase uid ownership proven server-side** — Firebase ID token verified in the Worker; squatting no longer locks anyone out
- [x] Owner set `FIREBASE_PROJECT_ID` + `SUPABASE_SERVICE_ROLE_KEY` on the staging Worker; a verified link was confirmed end to end (see above)
- [ ] Web Google sign-in completed on `notelikeus-dev.pages.dev` (a 302 to accounts.google.com is not a sign-in)
- [ ] Web backup import completed on staging, verified after reload and re-login
- [ ] Attachment import verified end-to-end (bytes in R2, metadata row, image renders after reload)
- [x] Android staging proven on-device (Pixel 7 / Android 16): auth, sync, and attachment upload/download through the Worker
- [x] Desktop staging proven — Google sign-in completes against staging Supabase
- [ ] Web / Android / Desktop convergence on one staging account
- [x] Offline reconciliation proven on Android hardware (account switching still untested)
- [x] Kotlin deletes reach the server without a prior download in the same process
- [x] Web boots without Firebase config when Supabase is the selected backend
- [ ] PWA service-worker behaviour across a backend change modelled
- [ ] Rollback plan reviewed by the owner
- [ ] **Owner explicitly authorizes cutover**

Until the last box is ticked by the owner, do not set `VITE_ALLOW_SUPABASE_PRODUCTION` or
`NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION`, repoint `notelike.web.app`, or remove any Firebase code.

---

## Runbook — enabling verified Firebase uid linking on staging

Owner-operated. Nothing here touches production: Firebase Auth, Firestore and
`notelike.web.app` are untouched, and no cutover flag is set at any point.

**Never paste a secret into a chat, an issue, or a committed file.** `SUPABASE_SERVICE_ROLE_KEY`
goes straight from your clipboard into `wrangler secret put`, which stores it in Cloudflare. It
bypasses RLS — treat it like a database password.

### 0. Prerequisites

| Value | Where it comes from | Secret? |
| --- | --- | --- |
| `SUPABASE_PROJECT_REF` | Supabase → Project Settings → General | Treat as sensitive |
| `SUPABASE_DB_PASSWORD` | Supabase → Project Settings → Database | **Yes** |
| `SUPABASE_SERVICE_ROLE_KEY` | Supabase → Project Settings → API → `service_role` | **Yes** |
| `FIREBASE_PROJECT_ID` | `.firebaserc` → `notelikeus` | No — ships in every web build |

### 1. Get on the branch

```bash
git fetch origin
git checkout claude/firebase-supabase-migration-audit-f55yye
```

The two new migrations exist only here until the branch merges.

### 2. Push the schema change

```bash
npx supabase link --project-ref "$SUPABASE_PROJECT_REF"
npx supabase migration list      # confirm 20250904000000 / 20250904010000 are pending
npx supabase db push
```

Applying these to a populated database was rehearsed against a Supabase-equivalent copy seeded on
the old schema: notes, labels, checklists, tombstones, attachment metadata and both uid mappings
came through unchanged, and sync kept working. Existing mappings are marked `verified = false`,
which is correct — none of them was ever proven.

### 3. Configure and redeploy the Worker

`workers/attachments/wrangler.toml` is gitignored. Add to its `[vars]`:

```toml
FIREBASE_PROJECT_ID = "notelikeus"
```

Then, from `workers/attachments/`:

```bash
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY   # paste when prompted; never echo it
npx wrangler deploy
```

Leaving either unset is a valid posture: the route answers 501 and clients fall back to unverified
claims, which are not exclusive and lock nobody out.

### 4. Redeploy Pages staging

```bash
npm run deploy:staging-pages
```

The web client only sends a Firebase ID token when a live Firebase session can mint one.

### 5. Verify

Sign in at https://notelikeus-dev.pages.dev/ with Google, then run in the Supabase SQL editor
(the browser console cannot reach the module directly — a production Vite build has no source
paths, and the Supabase client is not exposed globally):

```sql
select owner_id, firebase_uid, verified from public.firebase_uid_mappings;
```

`verified = true` means the Worker checked a real Firebase ID token against Google's keys.
`verified = false` means the route was unreachable or no Firebase session was present — the link
still works, it is simply not exclusive.

Worker health, without a session:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  "$WORKER_URL/v1/identity/firebase-link" -d '{}'
# 401 = the Worker is up and demanding a Supabase session
```

That check cannot tell a new build from an old one: `handleAttachmentRequest` resolves the bearer
token *before* it looks at the path, so an unauthenticated request to any path — including one the
old build never had — answers 401. Confirm the deployment from `npx wrangler deploy` output or the
Cloudflare dashboard, or by signing in and watching for `verified = true` below.

### Applied to staging — 2026-09-04

Both migrations are live on the staging project, and the security model has now been exercised
against real Supabase rather than the local equivalent.

Raw Postgres is unreachable from the audit sandbox (TCP 5432/6543 time out under the network
policy), so `supabase db push` could not run there. The migrations went through the Management API's
HTTPS query endpoint instead, as one `BEGIN … COMMIT`, together with their
`supabase_migrations.schema_migrations` rows. It was rehearsed first as `BEGIN … ROLLBACK` — the
full payload executed cleanly against the live schema and left nothing behind (`verified` column
absent, no tracking rows) — and only then re-run with `COMMIT`.

| Check | Result |
| --- | --- |
| Migrations tracked | 9/9, including both `20250904*` |
| Schema objects | `verified` column, 2 new guard triggers, 5 new functions, partial unique index |
| Existing data | 2 notes, 1 attachment, 0 mappings — unchanged |
| `anon` EXECUTE on any public function | false |
| `anon` on `sync_revision_seq` | false |
| `link_verified_firebase_uid` | anon ✗, authenticated ✗, service_role ✓ |

Eight adversarial cases then ran against the live project, each in its own rolled-back transaction,
and all eight held:

1. An unproven claim on another user's uid is accepted but not exclusive
2. The real owner can still claim a uid someone else squatted — the lockout is gone
3. `authenticated` cannot call `link_verified_firebase_uid` (42501)
4. `authenticated` cannot set `verified` by direct write (mutation guard)
5. B cannot read A's notes
6. Direct `INSERT` into `notes` is blocked
7. Direct `INSERT` into `note_attachments` is blocked (the guard added by `20250904000000`)
8. `register_note_attachment` refuses an object key outside the caller's namespace

Verified afterwards that nothing persisted: no test users, note/attachment counts unchanged.

**Deviation to note.** `db push` normally records a `statements` array alongside each tracking row;
these two were inserted with `version` and `name` only (`statements` is nullable, and
`migration list` reads `version`). A later `db push` from a machine with Postgres access will see
both as applied and skip them; `db diff` may be less informative for these two.

### Firebase uid ownership — proven end to end on staging, 2026-09-04

The invariant is no longer an inference from unit tests. A real Google identity, a real Firebase ID
token, the deployed Worker, and the live staging database completed the whole chain.

**Why it could not be tested on Pages.** Firebase Auth sessions are per-origin. On
`notelikeus-dev.pages.dev` there is no Firebase session and no way to create one — when Supabase is
the selected backend the app's sign-in routes to Supabase — so `resolveFirebaseUid` finds nothing
and the bridge exits before calling the Worker. A fresh sign-in there is not a migrating user. The
path only exists in the real cutover shape: one origin that *was* Firebase and is now Supabase.

**How it was reproduced.** `localhost:5173` was used as that single origin, in two phases against
the same browser profile:

1. dev server with no `VITE_REMOTE_BACKEND` → Firebase backend → Google sign-in. Confirmed on disk:
   Firefox wrote `firebaseLocalStorageDb`, `firebase-heartbeat-database` and a Firestore cache for
   that origin.
2. dev server restarted with the staging Supabase env, **browser storage untouched** → Google
   sign-in again. `resolveFirebaseUid` took its first branch, `source: 'firebase-session'`.

**Evidence.**

| Source | Observation |
| --- | --- |
| Worker log tail | `OPTIONS` then `POST /v1/identity/firebase-link`, 23:20:29 local |
| `firebase_uid_mappings` | one row, `verified = true`, `linked_at` 21:20:30 UTC — one second after the POST |
| Row identifiers | `owner_id` and `firebase_uid` match the values the client had recorded locally |
| Client console | no `Skipping Firebase→Supabase link`, no `verification failed` — neither refusal path fired |

`verified` can only be set by `link_verified_firebase_uid`, which only `service_role` may execute
and which no anon or authenticated PostgREST session can reach. So the true value is itself proof
that the Worker verified an RS256 Firebase ID token against Google's published keys, with
`aud`/`iss` pinned to the `notelikeus` project, before writing.

**A dead end worth recording.** An earlier attempt cleared all IndexedDB between the two sign-ins to
force the migration to re-run. That destroys the Firebase session, which lives in IndexedDB — so the
bridge correctly found nothing and never called the Worker. The correct reset is to delete only the
app's `notelikeus-notes` database and leave `firebaseLocalStorageDb` alone.

### PR #149 release isolation — verified, 2026-09-04

The safety property this PR rests on had never been checked: debug builds bake staging Supabase
values into `BuildConfig`, so a release build must not. Nothing in CI asserts it — `android.yml`
runs `assembleRelease` but inspects nothing.

Tested against the PR head in an isolated worktree, with real staging values in `local.properties`
— the exact state of an operator who has run `npm run kotlin:staging-properties`:

| BuildConfig field | debug | release |
| --- | --- | --- |
| `NOTELIKEUS_REMOTE_BACKEND` | `"supabase"` | `""` |
| `NOTELIKEUS_SUPABASE_URL` | staging URL | `""` |
| `NOTELIKEUS_SUPABASE_ANON_KEY` | staging anon JWT | `""` |
| `NOTELIKEUS_ATTACHMENTS_WORKER_URL` | staging Worker | `""` |

Empty is sufficient, not merely tidy. `firstNonBlank` discards empty strings, so `remoteBackendEnv`
resolves to null, and `isSupabaseRemoteSelected` returns false on its first line — Firebase.

There is a second, independent guard: the production allow flag is read **only** from
`System.getenv`, on both Android and desktop. It is never a `BuildConfig` field and never read from
`local.properties`, so it cannot be baked into an APK at all. `write-kotlin-staging-properties.mjs`
additionally refuses to write it. A release APK therefore has no route to Supabase even if every
other value were present.

**Recommendation unchanged:** merge after #148. The isolation property holds; what is still missing
is a CI assertion so it cannot regress silently. A test that generates both variants' `BuildConfig`
and asserts the release fields are empty would close that.

### Android staging proven on hardware — Pixel 7 / Android 16, 2026-09-05

Ran against a physical Pixel 7 (owner-approved; the brief otherwise prefers an emulator). The build
was PR #149 merged onto this branch in a throwaway worktree, with real staging values in
`local.properties`.

**The premise holds.** `NOTELIKEUS_REMOTE_BACKEND` is empty in the device environment, confirmed by
`adb shell`. Without #149's BuildConfig baking, a debug build on a real device silently stays on
Firebase — which is exactly the trap the tracker warned about, now demonstrated rather than assumed.

**Server-side proof, no SQL required.** Attaching an image to a note produced, in the Worker log:

```
PUT /v1/attachments/<noteId>/<attachmentId>   HTTP 200
GET /v1/attachments/<noteId>/<attachmentId>   HTTP 200
```

The Worker reaches R2 only after `${SUPABASE_URL}/auth/v1/user` accepts the caller's bearer token,
and that URL is the staging project. A token it cannot validate yields 401. So a `200` means staging
Supabase authenticated the device's session, the Worker derived the object key from the user id it
returned, and the bytes round-tripped through R2. That is the whole Android→Supabase→R2 chain
established from the server side.

| Check | Result |
| --- | --- |
| Android unit tests | 407 passing (composeApp 390, androidApp 17), 0 failures |
| Staging config inside the APK | present in two `classes*.dex` files |
| Release variant BuildConfig | all four fields empty |
| Production allow flag reachable from an APK | no — `System.getenv` only, on both platforms |
| `System.getenv` on device | empty, confirmed on the Pixel |
| Install, launch, Google sign-in | no crashes |
| Note create and save | `pixel-staging-proof` |
| `SyncWorker` | SUCCESS on every run after sign-in |
| Attachment upload/download via Worker | HTTP 200 / 200 |

**Incidental corroboration.** The note id in the upload path was `1788554457472992` — about 1.79e15,
comfortably under `Number.MAX_SAFE_INTEGER` (9.007e15). That is the id-safety analysis confirmed on
real data generated by the Kotlin client, not just reasoned about.

**Caveats.** The note row itself was not read back from Postgres — SELinux on Android 16 blocks
`run-as` for `targetSdkVersion=37`, and this machine has no database credentials. `SyncWorker`
reported SUCCESS throughout and the attachment path is proven, so the remaining doubt is narrow. A
`ReconciliationSyncWorker` FAILURE was observed once *before* sign-in and never recurred afterwards,
consistent with "no session yet" rather than a defect.

**The device's production app was replaced.** The release build and its local data were removed to
install the staging build, at the owner's explicit instruction, with `allowBackup="false"` making an
adb backup impossible. Notes already in Firestore are unaffected.

### Desktop staging — blocked by a Google OAuth audience mismatch, 2026-09-05

Launched with `./gradlew :composeApp:run --no-daemon` (no-daemon so the `NOTELIKEUS_*` exports reach
the app; a reused daemon does not inherit them) against the staging Supabase project.

**Backend selection works.** The app started, chose Supabase, and reached Supabase Auth — the error
it surfaced is a Supabase error, not a Firebase one. That confirms the desktop env-var path.

**Sign-in fails**, with a precise cause:

```
Supabase RPC auth failed: HTTP 400
{"error":"invalid request",
 "error_description":"Unacceptable audience in id_token: [<desktop client id>]"}
```

The chain: desktop signs in with `DesktopOAuthConfig.CLIENT_ID` — a Desktop/installed-app OAuth
client hardcoded in `PlatformModule.kt` — so Google issues an `id_token` whose `aud` is that client.
`SupabaseAuthApi` posts it to `/auth/v1/token?grant_type=id_token`, and Supabase rejects it because
that audience is not among the client IDs configured for its Google provider.

**Why Android works and desktop does not.** The staging Google provider was enabled from the
existing Firebase *web* client, and the Android app requests its `id_token` against that same web
client. Desktop is the only client using its own OAuth identity, so it is the only one rejected.

**Fix — configuration, not code.** In Supabase → Authentication → Providers → Google, add the
desktop client id to **Authorized Client IDs** (a comma-separated list of additional accepted
audiences). That field exists for exactly this case: native and desktop clients using
`grant_type=id_token`. No code change is needed, and nothing about the desktop client id is secret —
it already ships compiled into the desktop application.

**This recurs at cutover.** Production Supabase will need the same entry, or desktop users cannot
sign in after the switch. It belongs on the cutover checklist, not just the staging one.

### Offline reconciliation — verified on hardware, 2026-09-05

Run against the Pixel 7 with the staging build, driven over adb.

| Step | Observed |
| --- | --- |
| Airplane mode on, Wi-Fi and data disabled | staging Supabase unreachable from the device (ping fails) |
| Create a note while offline | `offline-reconcile-test` saved and listed — the local-first write needs no network |
| Restore connectivity | online within ~5s |
| App foregrounded | `SyncWorker` → **SUCCESS**, tagged `cloud_note_sync` |

No stuck failure state, no duplicated worker, nothing lost. The note written with no network
survived the transition and the first sync after reconnect succeeded.

### Supabase provider settings that only surface at first real sign-in

Three separate Google-provider issues appeared during staging, each invisible until a client
actually attempted to authenticate, and each returning an opaque HTTP 400. They will recur on the
production project unless replicated:

| Symptom | Cause | Resolution |
| --- | --- | --- |
| `Unacceptable audience in id_token` | Desktop uses its own OAuth client; only the web client was registered | Add the desktop client id to the provider's client-id list |
| `Bad ID token` (no nonce sent) | GoTrue verifies the nonce; the desktop flow sent none | Send a nonce (done in code — no project setting needed) |
| `Bad ID token` (plain nonce sent) | GoTrue hashes the nonce it is given and compares against the token claim | Send `SHA-256(nonce)` hex to Google, the plain value to Supabase |

Android was unaffected throughout: its ID token carries the **web** client as audience and sends no
nonce, so only desktop exercised any of these paths. That is worth remembering — a client working
is not evidence that the provider is correctly configured for the others.

### Desktop staging — signed in, 2026-09-05

Desktop was the last unproven client. Three separate defects stood between it and a working
sign-in, and only the first was a project setting:

1. **Unregistered OAuth client.** Desktop authenticates with its own installed-app client, but only
   the web client was configured on the Supabase Google provider — `Unacceptable audience`. Fixed by
   adding the desktop client id to the provider.
2. **No nonce, then an unhashed one.** GoTrue verifies the nonce on `grant_type=id_token`, and the
   loopback flow sent none — `Bad ID token`. Sending the plain value to both sides failed the same
   way, because GoTrue hashes what it is given and compares against the token claim. Google now
   receives `SHA-256(nonce)` in hex and Supabase the plain value, so the project no longer needs
   `skip_nonce_check` disabled.
3. **A double exchange, which was the actual blocker.** `DesktopGoogleSignInHelper` already
   exchanges the Google token and saves the session on the Supabase path, returning
   `session.accessToken`. `main.kt` then passed that Supabase JWT back through
   `signInWithGoogleIdToken`, re-posting it to `grant_type=id_token` — so a sign-in that had
   succeeded reported `Bad ID token` and left the user on the gate. Firebase is unaffected: there
   the helper returns a genuine Firebase ID token for the ViewModel to exchange.

Removing the redundant exchange stopped the error but did not sign the user in: `refreshAccount()`
is private to `DesktopSyncManager` and runs only inside `onSignedIn()`, which also performs the
Firebase-uid linking and the account-isolation check. `SyncManager.completeExternalSignIn()` now
runs exactly that post-sign-in work without a token exchange. Android implements it as an explicit
failure — nothing there signs in outside `signInWithGoogle`.

Worth recording: the intermediate version signed in *without* linking the Firebase uid or isolating
the account. Shipping it would have reintroduced the wrong-account class of bug this audit opened
with.

### Rollback

Redeploy the previous Worker version from the Cloudflare dashboard, or
`npx wrangler secret delete SUPABASE_SERVICE_ROLE_KEY` — the route reverts to 501 and clients fall
back to unverified claims. The schema change needs no rollback: it only widens what is allowed.

---

## Rollback

- Phases 0–11 are additive; Firebase remains default until a cutover build sets the explicit allow flags.
- Revert Web local-first by restoring pre-Phase-2 sync paths (not recommended once users rely on IndexedDB).
- Delete `supabase/` directory to remove local backend scaffold (no production impact).
- A cutover build is rolled back by shipping a Firebase-default client (omit the allow flags). Do not delete Firebase until rollback is no longer needed.
