# Staging handoff — Notelikeus Firebase → Supabase + R2

This document is written for a successor coding agent (or a human who wants the same context). Read it before changing code, deploying, or asking the owner to click anything.

Canonical tracker: [`docs/BACKEND_MIGRATION.md`](BACKEND_MIGRATION.md). That file is the phase checklist. This file is the **live staging + agent context dump**: what exists, what was proven, what is sitting in open PRs, what the owner still has to do, and how to continue without repeating work or leaking secrets.

**Do not print, commit, or paste secrets.** That includes JWTs, `sb_…` keys, database passwords, Cloudflare/Supabase tokens, Google client secrets, and the 20-character Supabase project ref. Use environment variable **names** only.

---

## 0. How to use this document

1. Confirm you are working on `github.com/shareef01/notelikeus`.
2. Fetch `origin/main` and read the **Live staging** section of `docs/BACKEND_MIGRATION.md` (it may have advanced after this file was written).
3. Treat **Phases 0–12 as implemented on `main`**. Do not re-implement the backend, worker, RLS, or dual-backend wiring.
4. Treat **production Firebase Auth / Firestore / Hosting (`notelike.web.app`) as still live**. Do not start cutover.
5. Check the open PRs listed in §8 before writing overlapping code.
6. If the user says “continue”, do the next **agent-implementable** item in §10. Do **not** invent a production cutover.

Hard stop phrases already in the tracker:

```
Do not start Firebase retirement in production.
Review docs/BACKEND_MIGRATION.md Live staging first.
```

---

## 1. Product and hard constraints

Notelikeus is a notes app with three clients:

| Client | Stack | Auth / remote today (production) |
| --- | --- | --- |
| Web | Vite + React + TypeScript | Firebase (`notelike.web.app`) |
| Android | Kotlin + Compose | Firebase |
| Desktop / Windows | Kotlin + Compose (same module as Android) | Firebase |

Migration goal: **Firebase → Supabase (Postgres + Auth) + Cloudflare R2 (attachments)**.

### Non-negotiable rules

- Never set `VITE_ALLOW_SUPABASE_PRODUCTION`.
- Never set `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION`.
- Never point production Hosting, Play, or desktop release builds at Supabase.
- Never retire Firebase Auth, Firestore, or Hosting from this agent session unless the owner explicitly approves production cutover **after** they have signed in with Google on staging and imported a backup.
- Never commit `web/.env.staging`, `local.properties`, or Wrangler state.
- Never echo tokens into chat, logs, or git.

Production Hosting stays on Firebase (`notelike.web.app`). Staging web is a **separate** Cloudflare Pages project.

---

## 2. Architecture (what talks to what)

```
                    ┌─────────────────────────────┐
                    │  Firebase (PRODUCTION)      │
                    │  Auth + Firestore + Hosting │
                    │  notelike.web.app           │
                    └─────────────▲───────────────┘
                                  │ default path
 Web / Android / Desktop ─────────┤
                                  │ only when debug + supabase flags
                    ┌─────────────▼───────────────┐
                    │  Supabase STAGING           │
                    │  Auth (Google + email)      │
                    │  Postgres + RLS             │
                    │  RPCs: upsert / list /      │
                    │        import_user_backup   │
                    └─────────────▲───────────────┘
                                  │
                    ┌─────────────▼───────────────┐
                    │  Cloudflare R2 STAGING      │
                    │  bucket: attachments-dev    │
                    │  Worker: attachments        │
                    └─────────────────────────────┘
```

### Dual-backend selection

**Web** (`web/src/lib/remote/supabaseBackend.ts`):

- `VITE_REMOTE_BACKEND=supabase`
- plus **one** of:
  - `VITE_ALLOW_SUPABASE_STAGING=true` **and** the page is served from `*.pages.dev` (runtime hostname gate), or
  - `VITE_ALLOW_SUPABASE_PRODUCTION=true` (forbidden until cutover)
- localhost Vite can use staging if `.env.staging` is loaded (`vite.staging.config.ts` / `npm run dev:staging`).

**Kotlin** (`composeApp/.../SupabaseBackendSelection.kt`):

- `NOTELIKEUS_REMOTE_BACKEND=supabase`
- debug builds: that is enough (release still requires the production-allow flag **and** a non-localhost URL).
- Android **debug** `BuildConfig` fields must be baked at compile time. Device `System.getenv` is empty. That wiring **landed in #149** (`548a3b8` on `main`). Run `npm run kotlin:staging-properties` then rebuild debug. See `docs/ANDROID_STAGING.md`.
- Desktop `./gradlew run` reads the same `local.properties` at runtime (`LocalProperties.kt`, also #149).

If those flags are missing, clients stay on Firebase.

### Data path (Supabase selected)

1. Notes JSON lives in Postgres (`user_notes`), updated through RPCs (`upsert_user_notes`, `list_user_notes`, `import_user_backup`).
2. Image bytes live in R2. Clients upload through the attachments Worker (`POST` with the Supabase access token). Postgres stores `r2:` pointers via `list_user_attachments` / related RPCs.
3. Backup JSON is the **owner-gated rehearsal vehicle**. Import on web goes through `commitImportedNotes` → `getRemoteNotesDataSource().uploadAllNotes` (Supabase on Pages). Open PR #150 also syncs embedded image bytes before that upload.

---

## 3. Phase status on `origin/main`

`origin/main` tip when this file was last updated: **`548a3b8`** (merge of PR **#149**, which itself sat on `92c750a` / #147).

| Phase | Status | Meaning |
| --- | --- | --- |
| 0 Inventory | done | Dual-backend seams exist |
| 1 Supabase project | done | Staging project linked; migrations applied |
| 2 Schema + RLS | done | Applied on staging |
| 3 Auth adapter | done | Google + email on staging |
| 4 Notes CRUD | done | RPCs used by web/Kotlin |
| 5 Attachments Worker + R2 | done | Staging Worker live |
| 6 Kotlin/Android adapter | done on `main` | Debug `BuildConfig` from `local.properties` (#149) |
| 7 Web adapter | done | Vite + Pages staging gate |
| 8 Desktop adapter | done on `main` | `local.properties` at runtime (#149) |
| 9 Backup import | done | Web Profile → Import backup; fixture exists |
| 10 Staging cutover | **in progress** | Pages + Worker + project live; **owner Google rehearsal still open** |
| 11 Production cutover | **not started** | Forbidden |
| 12 Firebase retirement | **not started** | Forbidden |

---

## 4. Live staging inventory

These resources already exist. Recreating them is waste. Linking/updating is fine.

### 4.1 Supabase

| Item | Value / note |
| --- | --- |
| Project name | `notelikeus-staging` |
| Region | `eu-west-1` |
| Project ref | 20-char ref in env `SUPABASE_PROJECT_REF` (do not print) |
| API URL | `https://<ref>.supabase.co` |
| Auth providers | **Google** enabled; **email** enabled |
| Email confirm | `mailer_autoconfirm` was **false**. Admin-created users must set `email_confirm: true` or they cannot sign in. |
| Migrations | Applied from `supabase/migrations/` |
| Google Web client | Same public client already in `google-services.json` client_type 3 (see §6) |
| Google redirect | Owner added `https://<ref>.supabase.co/auth/v1/callback` on the Google Cloud Web client |

OAuth probe that already succeeded (no secrets in the URL):

`GET https://<ref>.supabase.co/auth/v1/authorize?provider=google` → **302** to `accounts.google.com`.

### 4.2 Cloudflare R2 + Worker

| Item | Value / note |
| --- | --- |
| Account | `CLOUDFLARE_ACCOUNT_ID` |
| R2 bucket | `notelikeus-attachments-dev` (Standard). Owner had to enable R2 once; API was 10042 until then. |
| Worker name | `notelikeus-attachments` |
| Worker URL | `https://notelikeus-attachments.error-endpoint.workers.dev` |
| CORS allow | `localhost`, `127.0.0.1`, `[::1]`, `*.pages.dev` |
| Auth | Client sends Supabase access token; Worker talks to R2 |

Android native HTTP does **not** need CORS. Browser (Vite + Pages) does.

### 4.3 Cloudflare Pages (staging web)

| Item | Value / note |
| --- | --- |
| Project | `notelikeus-dev` |
| Production URL | https://notelikeus-dev.pages.dev/ |
| Deploy command | `npm run deploy:staging-pages` |
| Important flag | Wrangler is invoked with `--branch=main` so the **Production** alias updates (not a preview URL). |
| Required env | `VITE_REMOTE_BACKEND=supabase`, `VITE_ALLOW_SUPABASE_STAGING=true`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY` (**JWT**, not `sb_…`), `VITE_ATTACHMENTS_WORKER_URL`, plus public Firebase web config (`VITE_FIREBASE_APP_ID` still required by `App.tsx` `isFirebaseConfigured`) |
| Runtime gate | Staging allow flag only works on `*.pages.dev`. It will **not** activate on `notelike.web.app`. |

Last recorded Production deployment (from PR #148 notes): Pages alias `fd7dcfba`. Re-check with `npx wrangler pages deployment list --project-name=notelikeus-dev` if you need the current SHA.

Firebase Hosting production (`npm run deploy` → `notelike.web.app`) is untouched and must stay Firebase.

### 4.4 Google / GCP

| Item | Value / note |
| --- | --- |
| GCP project | `notelikeus` / number `404285880902` |
| Public Web client ID | `404285880902-cpiu3nj2itndnh0kkouvn374ec5ecl9v.apps.googleusercontent.com` |
| Secret source | Firebase Identity Toolkit `defaultSupportedIdpConfigs/google.com` (not creatable via `gcloud iap oauth-clients`) |
| Owner Google account used for gcloud | `shareef2189@gmail.com` |
| Repo owner email | `shareef.mansour@stud.uni-due.de` |

`gcloud iap oauth-clients` **cannot** manage Google Auth Platform Web clients or add redirect URIs. The owner already added the Supabase callback on the existing Web client.

Management API calls need:

- `User-Agent` (Cloudflare/Google return 403/1010 without one)
- `x-goog-user-project: notelikeus` for Google APIs

---

## 5. Secrets and env (names only)

### 5.1 Cloud Agent / operator env

Typically injected (values must stay out of git and chat):

- `SUPABASE_ACCESS_TOKEN`
- `SUPABASE_PROJECT_REF`
- `SUPABASE_ANON_KEY` — **often a secret `sb_…` key**. Browsers reject it (`Forbidden use of secret API key in browser`).
- `SUPABASE_DB_PASSWORD`
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

### 5.2 Browser anon key (the #1 staging footgun)

`scripts/ops/setup-staging.sh` calls `resolve_browser_anon_key()` and fetches the Management API **anon public JWT** (`eyJ…`, `name=anon`).

Rules:

- Web + Android/Desktop **clients** must use the JWT (`eyJ…`).
- Never put an `sb_…` key in `VITE_SUPABASE_ANON_KEY` or `NOTELIKEUS_SUPABASE_ANON_KEY`.
- `scripts/ops/write-kotlin-staging-properties.mjs` (on `main` via #149) refuses `sb_…` keys and production-allow flags.
- Never print the JWT.

### 5.3 Gitignored generated files

| File | Who writes it | Purpose |
| --- | --- | --- |
| `web/.env.staging` | `npm run setup:staging` | Vite staging + Pages deploy |
| `workers/attachments/wrangler.toml` | same | Worker deploy |
| `local.properties` | `setup-staging.sh` and/or `npm run kotlin:staging-properties` | Android `BuildConfig` + desktop runtime |

`web/.env` / `.env.local` are also local. Restart Vite after changing them.

### 5.4 Web staging flags (names)

```
VITE_REMOTE_BACKEND=supabase
VITE_ALLOW_SUPABASE_STAGING=true
VITE_SUPABASE_URL=https://<ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<anon JWT>
VITE_ATTACHMENTS_WORKER_URL=https://notelikeus-attachments.error-endpoint.workers.dev
VITE_FIREBASE_APP_ID=1:404285880902:web:6a84257413e7552c996847
```

Public Firebase web config may stay in the staging env so `isFirebaseConfigured` passes. Remote calls still go to Supabase when the staging flags are set.

### 5.5 Kotlin staging flags (names)

```
NOTELIKEUS_REMOTE_BACKEND=supabase
NOTELIKEUS_SUPABASE_URL=https://<ref>.supabase.co
NOTELIKEUS_SUPABASE_ANON_KEY=<anon JWT>
NOTELIKEUS_ATTACHMENTS_WORKER_URL=https://notelikeus-attachments.error-endpoint.workers.dev
```

Never `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION`.

---

## 6. What was already proven on staging

Do not redo these as if they failed.

| Check | Result | Notes |
| --- | --- | --- |
| Link staging project + apply migrations | pass | `setup-staging.sh` |
| Create R2 bucket | pass | After owner enabled R2 |
| Deploy attachments Worker | pass | URL above |
| Enable Google provider on Supabase | pass | Management API + existing Web client secret |
| Owner added Google redirect URI | pass | Owner action, already done |
| `GET /auth/v1/authorize?provider=google` | 302 to Google | |
| Local Vite Google button | reached Google email chooser | Cloud Agent VM **cannot** complete Google password / 2FA |
| Admin-created email user + Playwright | pass | Note titled **staging smoke**; PNG upload; `list_user_attachments` = 1 row |
| Pages project `notelikeus-dev` | live | https://notelikeus-dev.pages.dev/ |
| `npm run rehearse:staging-import` | pass on later agent | Imported 1 note for the **email** user (not the owner’s Google account). PR #148 accepts RPC status `applied`. |
| Owner Google sign-in on Pages | **not done** | Blocker for calling staging “rehearsed” |
| Owner Profile → Import backup on Pages | **not done** | Same |
| Android / desktop staging debug Google + import | **not done** | #149 is on `main`. Owner still needs `npm run kotlin:staging-properties`, a debug rebuild, Google sign-in, and import. |

Rehearsal fixture (safe to commit, no secrets):

`scripts/ops/fixtures/backup.rehearsal.example.json`

Commands:

```bash
npm run setup:staging
npm run deploy:staging-pages
npm run rehearse:staging-import
npm run kotlin:staging-properties   # on main via #149
```

---

## 7. What this Cloud Agent already shipped (code)

This agent id: `bc-e9906a91-59df-4bbb-8bd0-a48c480d2354`  
Run URL: https://cursor.com/agents/bc-e9906a91-59df-4bbb-8bd0-a48c480d2354

Preferred PR branch template: `cursor/<descriptive-name>-2354`. Base: `origin/main`. Preferred PR base: `main`.

### 7.1 Merged — PR #147

https://github.com/shareef01/notelikeus/pull/147 — merge commit `92c750a`.

Included:

- `scripts/ops/setup-staging.sh` bootstrap (link, migrate, R2, Worker, `.env.staging`)
- Token redaction in scripts
- R2 10042 error messaging
- Parse `workers.dev` URL from `wrangler deploy`
- `resolve_browser_anon_key()`
- Worker CORS + OPTIONS
- Tracker **Live staging** section

### 7.2 Merged — PR #149 Android / desktop staging properties

https://github.com/shareef01/notelikeus/pull/149 — merge commit `548a3b8` (2026-09-04).  
Branch: `cursor/android-staging-buildconfig-2354` (merged).

What landed on `main`:

- Android **debug** `BuildConfig` fields from gitignored `local.properties` / `NOTELIKEUS_*` at **compile time**.
- Release `BuildConfig` fields stay empty → Firebase.
- Desktop `./gradlew run` reads the same `local.properties` via `LocalProperties.kt`.
- `npm run kotlin:staging-properties` → `scripts/ops/write-kotlin-staging-properties.mjs` copies from `web/.env.staging`.
- `setup-staging.sh` also merges Kotlin keys into `local.properties`.
- Docs: `docs/ANDROID_STAGING.md` (now on `main`).
- Helper: `firstNonBlank` in `SupabaseBackendSelection.kt`.

After this merge, #148 / #150 / #151 went dirty against `main`. **#150 and #151 were rebased onto `548a3b8`.** #148 (other agent) is still CONFLICTING.

### 7.3 Open — PR #150 backup attachments

https://github.com/shareef01/notelikeus/pull/150  
Branch: `cursor/backup-attachments-2354`  
Tip: `6f1bf84` (rebased onto `548a3b8`).  
Status: **ready for review**, MERGEABLE after rebase. CI will re-run on the new tip.

What it does:

- Backup format **version 3** embeds readable image bytes as `dataBase64` (pending / `file:` / downloaded `r2:`).
- Version 2 still ignores legacy attachments (safe).
- Import restores pending / `file:` blobs.
- Web `commitImportedNotes` runs `syncNoteAttachments` **before** `uploadAllNotes`.
- Caps: backup 50 MB, per-image 10 MB, max 20 images / note.
- Key files: `BackupAttachments.kt`, `web/src/lib/backup/backupAttachments.ts`.

This is the missing piece if the owner’s rehearsal backup includes images.

### 7.4 Open — PR #151 note list thumbnails

https://github.com/shareef01/notelikeus/pull/151  
Branch: `cursor/note-list-thumbnails-2354`  
Tip: `801f764` (rebased onto `548a3b8`; previous green tip was `0c7d3c2`).  
Status: **ready for review**, MERGEABLE after rebase. CI will re-run on the new tip.

What it does:

- First-image thumbnails on Web + Kotlin list/grid cards.
- Web: `NoteCardThumbnail` + `useAttachmentPreviewUrl` (does **not** revoke object URLs on unmount; uses `attachmentPreviewCache`).
- Kotlin: `LocalAttachmentPreviewLoader` from `MainScaffold` via `koinInject<AttachmentSyncService>()`; `NoteCardThumbnail.kt`.
- `firstImageAttachment()` in Web/Kotlin `attachmentPaths`.
- A11y: Web already said “Has image”; Kotlin adds `cd_has_image`.

Nice-to-have for staging UX; **not** a cutover blocker.

### 7.5 Other agent — PR #148 Pages / RPC rehearsal

https://github.com/shareef01/notelikeus/pull/148  
Branch: `cursor/redeploy-staging-import-3683`  
Other agent: `bc-476ec657-c1f8-445f-ab72-3c578c4c3683`  
Tip: `9d8c486`  
OPEN, **CONFLICTING** with `main` after #149. Other agent owns this branch.

What it does:

- `rehearse-staging-import` accepts RPC status `applied`.
- Require `VITE_ATTACHMENTS_WORKER_URL` for Pages deploy.
- Ignore Wrangler local state.
- Docs: recorded Pages redeploy + email-user RPC rehearsal.

Independent feature-wise of #150–#151. #150 and #151 were rebased onto `548a3b8`. This branch still needs a rebase (other agent owns it).

---

## 8. File map (where to look)

### Ops / staging

| Path | Role |
| --- | --- |
| `docs/BACKEND_MIGRATION.md` | Phase tracker + Live staging |
| `docs/STAGING_HANDOFF.md` | This file |
| `docs/ANDROID_STAGING.md` | Android / desktop debug staging steps (on `main` via #149) |
| `scripts/ops/setup-staging.sh` | Bootstrap staging |
| `scripts/ops/deploy-staging-pages.sh` | Pages production alias |
| `scripts/ops/rehearse-staging-import.mjs` | RPC import smoke |
| `scripts/ops/write-kotlin-staging-properties.mjs` | copies `.env.staging` → `local.properties` |
| `scripts/ops/fixtures/backup.rehearsal.example.json` | Owner import fixture |
| `supabase/migrations/` | Schema / RLS / RPCs |
| `workers/attachments/` | R2 Worker |

### Web remote / backup

| Path | Role |
| --- | --- |
| `web/src/lib/remote/supabaseBackend.ts` | Staging / production gates |
| `web/src/lib/remote/supabaseNotes.ts` | Notes RPCs |
| `web/src/lib/sync/remoteNotes.ts` | `getRemoteNotesDataSource()` |
| `web/src/lib/backup/` | Export / import / attachments (#150) |
| `web/src/components/NoteCardThumbnail.tsx` | List thumbnails (#151) |
| `web/vite.staging.config.ts` | `npm run dev:staging` |

### Kotlin remote / backup

| Path | Role |
| --- | --- |
| `composeApp/.../data/remote/SupabaseBackendSelection.kt` | Flag gate + `firstNonBlank` |
| `composeApp/.../data/remote/LocalProperties.kt` | Desktop runtime (`local.properties`) |
| `composeApp/.../data/backup/BackupAttachments.kt` | Version 3 blobs (#150) |
| `composeApp/.../ui/notes/NoteCardThumbnail.kt` | List thumbnails (#151) |

### Package scripts (`package.json`)

- `setup:staging`
- `deploy:staging-pages`
- `rehearse:staging-import`
- `dev:staging`
- `kotlin:staging-properties`

---

## 9. Owner-gated remaining work (cannot finish in a Cloud Agent)

A Cloud Agent VM cannot complete Google password / 2FA. Do not pretend Playwright logged in as the owner.

### 9.1 Must happen before anyone calls staging “rehearsed”

1. Open https://notelikeus-dev.pages.dev/
2. Sign in with **Google** (the owner’s real account).
3. Profile → **Import backup**.
4. Use `scripts/ops/fixtures/backup.rehearsal.example.json` (or a real export).
5. Confirm the note(s) appear and, if #150 is merged and the backup has images, that images survive.

### 9.2 Android / desktop (wiring is on `main` via #149)

1. `npm run setup:staging` (or ensure `web/.env.staging` exists).
2. `npm run kotlin:staging-properties` (writes gitignored `local.properties`).
3. Rebuild a **debug** Android APK / run desktop `./gradlew run`.
4. Sign in with Google.
5. Repeat import.

Full steps: `docs/ANDROID_STAGING.md`.

Release / Play builds must stay on Firebase.

### 9.3 Later — production cutover (explicit owner approval only)

Only after 9.1 (and ideally 9.2):

1. Owner sets `VITE_ALLOW_SUPABASE_PRODUCTION` / `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION` **on purpose**.
2. Retarget README, `PRIVACY_POLICY.md`, `npm run deploy` (Firebase Hosting).
3. Only then consider Firebase retirement.

**Do not start this from an agent “continue” unless the owner said the words.**

---

## 10. What a successor agent can implement (safe backlog)

Priority order. Stop if the user only asked for a status doc.

1. **#150 and #151 are rebased onto `548a3b8` and mergeable.** Wait for CI on the new tips (`6f1bf84`, `801f764`). #148 (other agent) is still CONFLICTING — leave that branch unless asked to rebase it.
2. **#151 is ready** (was green on `0c7d3c2`; rebased to `801f764`).
3. Keep `docs/ANDROID_STAGING.md` on `main`; do not duplicate it here except as a pointer.
4. If the owner reports a Pages / import bug: fix on a **new** `cursor/<name>-2354` branch from latest `origin/main`.
5. Small hardening that is still in-scope:
   - Keep worker CORS current if a new staging hostname appears.
   - Keep `resolve_browser_anon_key()` as the only way scripts pick a browser key.
   - Do not add production-allow flags to setup scripts.
6. **Do not** flip production flags, rewrite README/privacy for Supabase, or delete Firebase config.

Out of scope unless asked: new product features unrelated to staging (editors, sync UX, etc.). #151 was an exception because list cards hid the fact that notes had images during rehearsal.

---

## 11. How to run staging locally (agent or laptop)

```bash
# from repo root, with the env names in §5.1 already injected
npm run setup:staging
# writes web/.env.staging + workers/attachments/wrangler.toml
# also writes Kotlin keys into local.properties

# web
npm run dev:staging
# open the printed localhost URL; Google will work in a real browser

# pages (updates notelikeus-dev.pages.dev Production alias)
npm run deploy:staging-pages

# RPC import smoke (email user / service role — not owner Google)
npm run rehearse:staging-import
```

Android / desktop:

```bash
npm run kotlin:staging-properties
# then rebuild debug
```

Vite **must be restarted** after `.env` changes.

Desktop add-note control: `aria-label="New note"` (the FAB is `md:hidden` on large screens).

---

## 12. Known pitfalls

1. **`SUPABASE_ANON_KEY` is often `sb_…`.** Using it in the browser fails closed. Always resolve the anon JWT.
2. **`isFirebaseConfigured` still requires `VITE_FIREBASE_APP_ID`.** Staging env can include public Firebase config while `VITE_REMOTE_BACKEND=supabase`.
3. **Pages staging allow is hostname-gated** to `*.pages.dev`. Copying the same flag onto Firebase Hosting will not switch production to Supabase (good). Forgetting the flag on Pages will leave the staging site on Firebase (bad for rehearsal).
4. **Android device env is empty.** Debug APKs only see staging if `local.properties` was present at compile time (`npm run kotlin:staging-properties`). See `docs/ANDROID_STAGING.md`.
5. **Release Kotlin fields must stay empty.** That is what keeps Play/desktop release on Firebase.
6. **Email users need `email_confirm: true`** if `mailer_autoconfirm` is false.
7. **Worker CORS** must include the browser origin. Native Android does not care.
8. **`npm run deploy:staging-pages` must use `--branch=main`** or you will update a preview URL and think Production is stale.
9. **Do not use `gcloud iap oauth-clients`** to add the Supabase redirect. Use the Google Cloud Console on the existing Web client (already done).
10. **Cloud Agent Google login cannot finish.** Use an admin email user + Playwright for smoke; leave real Google to the owner.
11. **Handoff of a Cloud Agent chat does not copy VM `.env`.** Desktop Cursor opens the conversation, not the pod secrets. Re-run `setup:staging` in the new environment.
12. **`BACKEND_MIGRATION.md` is a conflict magnet.** #150 and #151 were rebased after #149; #148 is still dirty. This handoff file should stay a single-purpose doc so later PRs can link it instead of rewriting the same paragraph.

---

## 13. Decision log (why things look this way)

- **Reuse the existing Google Web client** rather than creating a new one. The client id is already in Android `google-services.json`. A second client would break “same account” rehearsal.
- **Pages instead of Firebase Hosting for staging.** Hosting is production. A second Hosting channel would risk shipping `VITE_ALLOW_SUPABASE_STAGING` to the same Firebase project. Pages is isolated and hostname-gated.
- **Debug-only Kotlin wiring.** Release binaries in the wild must keep using Firebase until the owner flips production flags.
- **Backup version 3 + embedded bytes.** Staging rehearsal is worthless for attachments if export/import drops images. Version 2 remains readable and ignores unknown attachment payloads.
- **Thumbnails on the note list.** Rehearsal users could not see that an imported note had an image without opening it.
- **RPC status `applied`.** Newer `import_user_backup` returns `applied`; the original rehearsal script only treated `ok` as success (#148).

---

## 14. Suggested first messages for a new agent

If the user says **continue the migration**:

```
Do not start Firebase retirement in production.
Read docs/STAGING_HANDOFF.md and docs/BACKEND_MIGRATION.md Live staging.
Check open PRs #148 #150 #151 before overlapping them (#149 already merged).
#150 and #151 are rebased onto 548a3b8. #148 still conflicts. Next implementable work is wait for their CI or fix whatever the owner reports from https://notelikeus-dev.pages.dev/ Google + Import backup.
Android/desktop debug staging: npm run kotlin:staging-properties + rebuild; see docs/ANDROID_STAGING.md.
Never set VITE_ALLOW_SUPABASE_PRODUCTION or NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION.
```

If the user says **I signed in on staging**:

```
Update docs/BACKEND_MIGRATION.md Live staging with the owner Google rehearsal result.
Do not start production cutover unless they explicitly asked.
```

If the user says **cut over production**:

```
Stop. Confirm they completed Google + Import backup on notelikeus-dev.pages.dev.
Only then follow Phase 11 in BACKEND_MIGRATION.md. Still do not delete Firebase until Phase 12 is explicit.
```

---

## 15. Document maintenance

When you change live staging (new Worker URL, new Pages project, new PR, owner completed Google import):

1. Update **Live staging** in `docs/BACKEND_MIGRATION.md` (short).
2. Update this file (detail).
3. Do not paste secrets.

Last updated from Cloud Agent `bc-e9906a91-59df-4bbb-8bd0-a48c480d2354` against `origin/main` `548a3b8` (#149 merged). Open PRs: #148 (still CONFLICTING), #150 (`6f1bf84`, rebased), #151 (`801f764`, rebased).
