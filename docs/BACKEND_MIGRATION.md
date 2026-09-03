# Notelikeus Backend Migration (Firebase → Supabase + Cloudflare R2)

**Status:** Phases 0–12 on `main`; staging bootstrap live. Firebase remains the production backend.  
**Last updated:** 2026-09-03 (Pages staging live; backup import uses Supabase; OAuth probe passed)

This document tracks the phased migration away from Firebase. Phases 0–12 are on `main`. Production cutover is **not** authorized. Firebase Auth, Firestore, and Firebase Hosting (`notelike.web.app`) remain the live backend.

**Git:** Merged via #145 / #146. Staging ops continue on `cursor/run-staging-setup-2354` ([PR #147](https://github.com/shareef01/notelikeus/pull/147)).

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

Bootstrap: `npm run setup:staging` (`scripts/ops/setup-staging.sh`).

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
   - **Owner:** sign in with Google on https://notelikeus-dev.pages.dev/, then Profile → Import backup with the example JSON. Repeat on Android/Desktop staging builds when convenient.
6. Approve flipping `VITE_ALLOW_SUPABASE_PRODUCTION` / `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION` for a dedicated cutover build.
7. After the user migration window: update README, privacy policy, and `npm run deploy` target. Only then remove Firebase.

### Continue command

Remaining work: sign in with Google on https://notelikeus-dev.pages.dev/ and import `scripts/ops/fixtures/backup.rehearsal.example.json` (Profile → Import backup). Repeat on Android/Desktop when you have those staging builds. Do not start production Firebase retirement.

```
Do not start Firebase retirement in production. Review docs/BACKEND_MIGRATION.md Live staging first.
```

---

## Rollback

- Phases 0–11 are additive; Firebase remains default until a cutover build sets the explicit allow flags.
- Revert Web local-first by restoring pre-Phase-2 sync paths (not recommended once users rely on IndexedDB).
- Delete `supabase/` directory to remove local backend scaffold (no production impact).
- A cutover build is rolled back by shipping a Firebase-default client (omit the allow flags). Do not delete Firebase until rollback is no longer needed.
