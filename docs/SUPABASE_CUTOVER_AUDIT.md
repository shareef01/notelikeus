# Supabase cutover audit

**Date:** 2026-09-05  
**Branch:** `cutover/supabase-canonical`  
**HEAD:** `820e616` (`cutover/supabase-canonical`)  
**PR:** https://github.com/shareef01/notelikeus/pull/153  
**Mandate:** Supabase is the sole backend. Legacy Firebase users and Firestore data are abandoned.

## Verdict

**Ship with nits** for source merge. Staging schema, Pages, Worker, and Auth redirect URLs are live. **Google sign-in still needs a human Google account** (OAuth reaches `accounts.google.com` for `*.supabase.co`; this agent has no Google password). Do not cut a custom production domain until `ALLOWED_ORIGINS` and store-build keys are set.

The working tree is Supabase-only at runtime. Dual-backend flags, Firebase SDKs, Hosting, App Check, and the UID-bridge table/RPCs are gone. Remaining Firebase strings are historical names, archive, or stale docs — not a live second backend.

## Phase matrix (original BACKEND_MIGRATION.md claims)

| Phase | Claimed status | Source exists | Tests exist | Classification | Action |
|---|---|---|---|---|---|
| 0 — inventory | implemented | yes | n/a | VERIFIED | Historical |
| 1 — remote abstraction | implemented | yes (`RemoteNotesDataSource`, `CloudNoteTransport`) | yes | VERIFIED | Keep generic interfaces |
| 2 — IndexedDB local-first | implemented | yes | yes | VERIFIED | Keep |
| 3 — startup hydration | implemented | yes | yes | VERIFIED | Keep; empty-cloud guard retained |
| 4 — Supabase notes adapter | implemented | yes | yes | VERIFIED | Made unconditional |
| 5 — Supabase Auth | implemented | yes | yes | VERIFIED | Made unconditional |
| 6 — Firebase UID mapping / import | implemented | removed | pgTAP removal tests | OBSOLETE / REMOVE | Dropped table + RPCs via `20260905000000_remove_firebase_compatibility.sql` |
| 7 — Realtime | implemented | yes | publication pgTAP | VERIFIED | Keep as pull wake-up |
| 8 — R2 attachments | implemented | yes (Worker + metadata RPCs) | Worker + pgTAP | VERIFIED | Keep R2; do not add Supabase Storage |
| 9 — Kotlin attachment UI | implemented | yes | compile/tests | PARTIAL | Kept existing paths |
| 10 — Cloudflare Pages | implemented | yes | `pages:verify` | VERIFIED | Canonical hosting |
| 11 — Firebase retirement readiness | dual-backend flags | removed | n/a | OBSOLETE | Flags and fallbacks deleted |
| 12 — staging bootstrap | implemented | Pages/staging scripts | n/a | PARTIAL | Owner still deploys production |

## Findings

### Critical

None in source. Production **traffic** is still blocked on owner infra (see Owner actions).

### High

1. **Hosted schema pushed** on the staging project (`notelikeus-staging`). Only `20260905000000_remove_firebase_compatibility.sql` was pending; it applied. `firebase_uid_mappings` is gone.

2. **Android/Desktop release fail-closed** (`820e616`). Empty hosted config no longer falls back to `127.0.0.1:54321` in release; debug still uses CLI defaults. Store builds must still bake `NOTELIKEUS_SUPABASE_*`.

3. **Stale runbooks still describe Firebase as production.** `docs/CUTOVER_ROLLBACK.md` is bannered SUPERSEDED but still tells an operator to `firebase deploy --only hosting` and flip deleted allow-flags. `docs/BACKEND_MIGRATION.md` is a thousand-line dual-backend diary. Following either after merge is an ops incident.  
   Paths: `docs/CUTOVER_ROLLBACK.md`, `docs/BACKEND_MIGRATION.md`.

### Medium

4. **Web Google OAuth PKCE double-exchange (fixed this audit).** `createClient({ detectSessionInUrl: true })` raced `completeSupabaseOAuthRedirect()` → `exchangeCodeForSession`. Both consume `?code=`. The second attempt fails and toasted an error after a successful sign-in.  
   Path: `web/src/lib/supabase/client.ts` (`detectSessionInUrl: false`).

5. **Attachments Worker CORS is Pages-preview-shaped.** Localhost and `*.pages.dev` are always allowed; a production custom domain must be set in `ALLOWED_ORIGINS`.  
   Path: `workers/attachments/src/cors.ts`.

6. **Unsigned CI release APKs bake empty Supabase fields.** `:androidApp:assembleRelease` in CI is correct as a minify/R8 gate, but it is not a store artifact. Store builds must inject hosted keys at compile time.  
   Path: `.github/workflows/android.yml`, `docs/ANDROID_STAGING.md`.

7. **`begin_sync_mutation` is EXECUTE-granted to `authenticated`.** Safe today because `set_config(..., is_local)` dies with the PostgREST transaction, so a client cannot open the guard and then forge a row in a later request. Still looks like a bypass in a grant audit; prefer keeping it that way and documenting, or folding `set_config` into the SECURITY INVOKER RPCs only.  
   Path: `supabase/migrations/20250904000000_mapping_and_attachment_guards.sql`.

8. **Operator-facing staging copy still said Firebase was the production default (fixed this audit).**  
   Paths: `scripts/ops/setup-staging.sh`, `scripts/ops/write-kotlin-staging-properties.mjs`.

### Low

9. Historical type/flag names kept for IndexedDB/backup compatibility: `firebaseHydrated`, `FirestoreNoteDocument`, `AccountUidBridge` (now equality-only).  
10. Unused version-catalog entry `play-services-base` (Play Services is still pulled transitively for Credential Manager / `GoogleApiAvailability`).  
11. Android CI release-job comment still says “Firebase-hosted web app”.  
12. Desktop CI comments still mention Firestore.  
13. `supabase/config.toml` unused `[auth.third_party.firebase]` template (commented).  
14. `web/.env` on disk still has leftover Firebase client keys (gitignored). Safe to delete locally.

## Remaining Firebase references

| Location | Class | Why it stays / action |
|---|---|---|
| `supabase/migrations/20250902020000_firebase_uid_mapping.sql`, `20250904010000_verified_firebase_uid_link.sql` | HISTORICAL | Required so a fresh `db reset` / `db push` can apply later `DROP`s |
| `supabase/migrations/20260905000000_remove_firebase_compatibility.sql` + `notelikeus_no_firebase_compat.test.sql` | RUNTIME (removal) | Proves the bridge is gone |
| `web/src/lib/local/notesLocalRepository.ts` `firebaseHydrated` / `firebaseCloudImported` | HISTORICAL | Existing IndexedDB meta; read as alias of `remoteHydrated` |
| `web/src/lib/mappers/noteCloudMapper.ts` `FirestoreNoteDocument` | HISTORICAL | Backup JSON field names |
| `composeApp/.../AccountUidBridge.kt` | FALSE POSITIVE | Account-switch equality; no Firebase UID mapping |
| `gradle/libs.versions.toml` Credential Manager / Play Services comments | FALSE POSITIVE | Google Sign-In, not Firebase Auth |
| `supabase/config.toml` `[auth.third_party.firebase]` | FALSE POSITIVE | Unused CLI template, commented |
| `archive/attachments-feature/src/FirebaseAttachmentSync.kt` | HISTORICAL | Archive |
| `docs/BACKEND_MIGRATION.md`, `docs/CUTOVER_ROLLBACK.md` (body), `docs/COMPREHENSIVE_AUDIT.md`, `docs/AUDIT_2026-09-02.md`, `CHANGELOG.md` | HISTORICAL | Do not follow for ops |
| `scripts/ops/fixtures/backup.rehearsal.example.json` `sourceUid` | HISTORICAL | Fixture label |
| `web/.env` Firebase `VITE_FIREBASE_*` | LOCAL DIRT | Gitignored; not loaded by current Vite app |

No `firebase.json`, `google-services` plugin, Firebase npm/Gradle dependency, App Check, Hosting deploy script, or UID-bridge client remains.

## Auth

| Client | Identity | Google | Guest |
|---|---|---|---|
| Web | `@supabase/supabase-js` PKCE | `signInWithOAuth({ provider: 'google' })` + manual `exchangeCodeForSession` | `__guest__` IndexedDB; no anonymous Supabase user |
| Android | `SupabaseSessionManager` | Credential Manager ID token → GoTrue `grant_type=id_token` | Offline/local Room; first sign-in may upload guest rows |
| Desktop | Same session manager | Loopback OAuth + nonce; helper saves session; `completeExternalSignIn` does not re-exchange | Same as Android |

Account isolation is Supabase UUID (`auth.uid()`). Web namespaces IndexedDB by that id. Kotlin `LocalAccountIsolator` wipes Room when `lastMergedUserId` differs. Guest deletes on web skip tombstones so they cannot suppress a later cloud note with the same numeric id.

## Sync

- Server-owned `sync_revision_seq`; `apply_note_change` / `apply_note_delete` / `pull_changes` / `fetch_full_snapshot`.
- Tombstones + anti-resurrection (`note_deleted` conflict). Explicit undo is `clear_note_tombstone`.
- Empty-cloud / failed-open guards on Web (`syncNotesWithCloud`, realtime apply) and Kotlin (`SuspectEmptyCloudException`).
- Realtime (`postgres_changes` on `notes` + `note_tombstones`) only wakes `pullIncrementalChanges`. Snapshot/pull RPCs are the truth.

## RLS / RPC

Read from SQL + pgTAP sources (Docker was not required for this audit; CI `.github/workflows/supabase.yml` still runs `supabase test db`).

- RLS on `notes`, `note_tombstones`, `sync_meta`, `note_attachments`.
- Mutation-guard trigger blocks direct INSERT/UPDATE/DELETE unless `notelikeus.sync_mutation = 1`.
- Mutating RPCs are `SECURITY INVOKER` and bind `owner_id` to `auth.uid()`.
- `anon` has no EXECUTE on sync/attachment RPCs (revoked in `20250904000000`).
- Firebase mapping table/RPCs dropped; pgTAP `notelikeus_no_firebase_compat.test.sql` asserts absence.

## Attachments

- Worker verifies the user JWT via `GET /auth/v1/user` with the **anon** key. No `FIREBASE_*`, no `SERVICE_ROLE` in `workers/attachments/`.
- Object key is derived: `owners/{jwtUserId}/notes/{noteId}/{attachmentId}`. Callers cannot pick another owner prefix.
- Metadata RPCs enforce the same key shape.
- Blobs stay in R2; note rows do not store attachment JSON (`supabaseNoteToNote` sets `attachments: []` then hydrates from `list_user_attachments`).

## Web IndexedDB startup

`bootstrapApp` rehydrates filter/tombstone/label stores (notes themselves are **not** in localStorage). `useNotesSync` then:

1. Account-switch wipe if `lastMergedUserId` differs.
2. `hydrateIndexedDbFromRemote` (idempotent via `remoteHydrated` / legacy `firebaseHydrated`).
3. `startNotesRealtimeSync` (snapshot + realtime wake-up).

Empty remote snapshots do not blank a populated local namespace. `putNotes` after realtime apply is fire-and-forget (pre-existing; a crashed tab can miss one mirror write and recover on next hydrate). IndexedDB open is a singleton; a failed first open is not retried until reload (low).

## Kotlin DI

- `CloudSessionManager` → `SupabaseSessionManager` on Android and Desktop.
- `CloudNoteTransport` → `SupabaseNoteTransport`.
- No `FirebaseSessionManager`, Firestore transport, or `google-services` plugin.
- Play Services remains only for Google Sign-In (`credentials-play-services-auth` + `GoogleApiAvailability`).

## Hosting / CI / docs

- Canonical host: Cloudflare Pages. `web/public/_redirects` is SPA `/* → /index.html 200`. CSP in `web/public/_headers` allows `*.supabase.co`, `wss://*.supabase.co`, `*.workers.dev`, Google accounts; CI forbids `firebaseio.com` / `firebaseapp.com` / App Check / reCAPTCHA.
- Root npm scripts are Supabase/Pages/Worker only. No `firebase deploy`.
- `CONTRIBUTING.md` and `README.md` match the current stack.
- `docs/BACKEND_ARCHITECTURE.md` is the live architecture doc. Treat `BACKEND_MIGRATION.md` as a diary.

## Secrets (values not printed)

| Type | Path | Tracked | Rotate |
|---|---|---|---|
| Local Supabase demo anon JWT (public CLI fixture) | `web/.env.example`, `web/.env.e2e` | yes | no |
| Leftover Firebase web client config | `web/.env` | no (gitignored) | optional; unused by current Vite app |
| Hosted Supabase project URL | `workers/attachments/wrangler.toml` (local) | no (gitignored) | n/a (identifier, not a secret) |
| Staging anon JWT / worker URL | `web/.env.staging` | no | only if leaked outside this machine |
| Desktop OAuth client secret | `NOTELIKEUS_OAUTH_CLIENT_SECRET` / `local.properties` | no | if ever committed or pasted |
| CI secrets | GitHub `VITE_*`, `CLOUDFLARE_*` | no | standard |

No `service_role`, `sb_secret_`, or private key is tracked.

## Tests this audit ran

| Suite | Result |
|---|---|
| `cd web && npm test` | **PASS** (48 files, 321 tests) |
| `npm run test:attachments-worker` | **PASS** (3 files, 25 tests) |
| `npm run supabase:test` | **BLOCKED** — Docker is not installed on this machine |
| Kotlin / Android / Playwright | **NOT RUN** this session; PR #153 CI was already green on `22bc939` |

## What this audit changed

- `web/src/lib/supabase/client.ts` — `detectSessionInUrl: false` so Google redirect cannot double-spend the PKCE code.
- `scripts/ops/setup-staging.sh` — success line no longer says Firebase is the production default.
- `scripts/ops/write-kotlin-staging-properties.mjs` — comment no longer claims a Firebase default.

## Owner actions

**Done**
1. Staging Supabase project linked; `db push` applied the Firebase-compat drop.
2. Google redirect URLs for `https://notelikeus-dev.pages.dev/**` and `http://localhost:5173/**` (OAuth 302s to Google; no `redirect_uri_mismatch`).
3. Pages staging + R2 Worker deployed.
4. Native release fail-closed (`820e616`).

**Still required**
1. Complete **Sign in with Google** on https://notelikeus-dev.pages.dev/ with a real Google account (agent cannot enter the password).
2. Bake hosted keys into any Play/MSI build (`NOTELIKEUS_SUPABASE_*` / `local.properties`).
3. Set Worker `ALLOWED_ORIGINS` before a custom domain.
4. Optional GitHub secrets so Actions can deploy Pages.
5. Delete leftover `VITE_FIREBASE_*` from local `web/.env`.
6. Merge [PR #153](https://github.com/shareef01/notelikeus/pull/153) when ready (CI green, not a draft).

## Preserved correctness (still true)

- Server-controlled revision sequence
- Tombstones + anti-resurrection
- Explicit undo via `clear_note_tombstone`
- Empty-cloud / failed-read guards
- Guest namespace `__guest__`
- Account isolation by Supabase UUID
- IndexedDB / Room local-first
