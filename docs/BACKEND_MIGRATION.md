# Notelikeus Backend Migration (Firebase → Supabase + Cloudflare R2)

**Status:** Phases 0–3 checkpoint on branch `migration/supabase-r2` (Firebase remains production backend).  
**Last updated:** 2025-09-02 (safety review + git checkpoint)

This document tracks the phased migration away from Firebase. Only Phases 0–3 are implemented in this checkpoint; production cutover is **not** authorized yet.

**Git:** Work is committed on `migration/supabase-r2`, not `main`. `main` remains the known Firebase-only baseline until this branch is merged.

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
| AUTH | `FirebaseAuth`, `GoogleAuthProvider`, `useAuthStore` | Future Phase 5 |
| REMOTE DATA | `FirestoreNoteTransport`, `notesRepository.ts`, `onSnapshot` | Phase 1 abstracted; Firebase still default |
| LOCAL CACHE | Firestore persistent cache (Web legacy); now IndexedDB primary | Phase 2 |
| HOSTING | `firebase.json`, `firebase deploy` | Future Phase 10 |
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
| 4 — Supabase remote adapter | NOT STARTED | Behind dev flag; Firebase remains default |
| 5 — Supabase Auth | NOT STARTED | |
| 6 — User data migration | NOT STARTED | |
| 7 — Realtime optimization | NOT STARTED | |
| 8 — Attachments + R2 | NOT STARTED | See `archive/attachments-feature/` |
| 9–11 — UI, Pages, Firebase retirement | NOT STARTED | |

---

## Phase 1 — Backend abstractions

### Kotlin

`CloudNoteTransport` already existed. Added shared contract tests:

- `composeApp/src/commonTest/.../CloudNoteTransportContractTest.kt`
- Runs against `FakeCloudNoteTransport`; Firestore/Supabase adapters to be added in Phase 4.

### Web

- `web/src/lib/remote/remoteNotesDataSource.ts` — interface
- `web/src/lib/remote/firebaseRemoteNotesDataSource.ts` — Firebase implementation (default)
- `noteActions.ts`, `notesSyncService.ts` route remote I/O through the abstraction

Firebase behavior unchanged; tests updated.

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
| Web unit | `cd web && npm test` | **PASS** (276/276, includes `bootstrap.test.ts`) |
| Web build / sync / E2E | `npm run build`, `test:sync`, `test:e2e` | **PASS** |
| Supabase start | `npm run supabase:start` | **BLOCKED** — Docker Desktop installed but daemon not ready (first-run/WSL2 setup required) |
| Supabase db reset | `npm run supabase:reset` | **NOT EXECUTED** (blocked on Docker) |
| Supabase pgTAP | `npm run supabase:test` | **NOT EXECUTED** (blocked on Docker) |
| Supabase CI (GitHub Actions) | `.github/workflows/supabase.yml` | **PENDING** — runs on push/PR when `supabase/**` changes; use this when local Docker is unavailable |

**Phase 4 gate:** Supabase `reset` + `test` must be **green** (locally or via GitHub Actions) before starting Phase 4.

**Firebase remains the production backend.** No production Supabase/Cloudflare resources were created or modified.

---

## Risks / open questions

1. **Web offline + Firestore cache** — Firestore SDK cache still initialized; IndexedDB is now primary for UI. Phase 4 should evaluate whether to reduce Firestore persistence reliance.
2. **Revision vs serverUpdatedAt** — Kotlin/Web still use timestamp conflict model against Firebase; Supabase adapter must map revision protocol without weakening empty-cloud guards.
3. **Firebase UID ≠ Supabase UUID** — account migration (Phase 6) must map ownership explicitly.
4. **pgTAP / Docker** — local Supabase requires Docker; use **GitHub Actions** (`.github/workflows/supabase.yml`) when local virtualization is unavailable.
5. **Direct table RLS vs RPC-only writes** — Phase 4 adapter should prefer RPCs; consider tightening direct `UPDATE` on `revision` columns.

---

## Owner actions before Phase 4

1. Review this document and local Supabase schema.
2. Run pgTAP tests — either locally (Docker) or via GitHub Actions after pushing `migration/supabase-r2`:
   ```bash
   npm run supabase:start
   npm run supabase:reset
   npm run supabase:test
   ```
   Or push the branch and check the **Supabase CI** workflow in GitHub Actions.
3. Create a **staging** Supabase project (not production) when ready — **do not** link production clients yet.
4. Configure Google OAuth in Supabase dashboard (staging only) — production auth remains Firebase until Phase 5.
5. Approve Phase 4 scope: Supabase `RemoteNotesDataSource` / `CloudNoteTransport` behind a **development-only** feature flag.

### Continue command

```
Continue Notelikeus backend migration with Phase 4 only. Review docs/BACKEND_MIGRATION.md first and do not proceed to Phase 5.
```

---

## Rollback

- Phases 0–3 are additive; Firebase remains default.
- Revert Web local-first by restoring pre-Phase-2 sync paths (not recommended once users rely on IndexedDB).
- Delete `supabase/` directory to remove local backend scaffold (no production impact).
