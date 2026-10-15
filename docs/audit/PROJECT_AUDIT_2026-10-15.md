# Notelikeus project audit, 2026-10-15

| | |
|---|---|
| Commit audited | `d3f97a1` (`main`, merge of PR #318) |
| Scope | All platforms (Android, Windows, web), Supabase migrations, attachments Worker, CI/CD and deploy config |
| Builds on | `AUDIT_REPORT.md` (F-01 to F-12, FU-1 to FU-6) and `docs/audit/FINDINGS.md` (AU-01 to AU-30). Those were not repeated; this pass re-checked their open items and looked at what they did not cover: release and deploy pipelines, environment separation, the cron sweeps now live in production, and the Kotlin fixes merged today. |
| Method | Source reading plus the repo's own test commands. Read-only: no code, branch or deploy was changed. |

## Summary

The sync, conflict and attachment code is in good shape. Every finding from the two earlier audits is fixed except two low-priority hardening items. The new risks are in operations rather than in the sync engine:

1. **The attachment sweep enabled today will permanently delete images that only survive in R2** (N-01). Android v2.0.0 strands some uploaded images as provisional rows, and the sweep reclaims exactly those rows. The first deletions can happen around **2026-10-20**. This needs a decision before then.
2. **CI-built releases ship without a backend address** (N-02). Tagged Android builds and the Windows MSI have no Supabase URL compiled in or available at runtime, so sign-in and sync are off in those builds, and nothing fails the build.
3. **The web goes to production on every push to main, before the e2e gate and ahead of the manual database and Worker deploys** (N-03).
4. **Staging and production share one Supabase project and probably one R2 bucket** (N-04).

## Findings

Severity: P0 is irreversible loss or a cross-account breach. P1 is high-impact reliability or security. P2 is a material issue. P3 is polish or hardening. Confidence is **Confirmed** (traced in source end to end) or **Probable** (one step needs runtime confirmation).

### N-01 (P1, Confirmed): the provisional-attachment sweep will delete the last copy of images stranded by Android v2.0.0
- **Status:** Fixed in `20261015000000_provisional_sweep_longer_grace.sql`: the claim's floor is 90 days in the database, whatever the caller asks (pgTAP F09-8b). Takes effect once the Supabase deploy is run; no Worker deploy needed. Recovery of already-stranded rows (option 3) not done.

- **Where:** `supabase/migrations/20261012000000_provisional_attachment_sweep.sql:46-61` claims a provisional row when its note was committed again after the upload and that commit is 14 days old. `workers/attachments/src/sweep.ts:201-211` runs it every 6 hours; `list_unconfirmed_attachment_deletes` (`20260908120000_attachment_delete_claim.sql`) then deletes the R2 object an hour later.
- **Why it matters now:** the bug fixed today in `e5c5762` and `a5c3047` is in the released v2.0.0 (tag `3b4a970`, 2026-10-08: it uses `/v2/` uploads and has the old `reconcileStagedAttachments` path). On those builds, a new note whose image uploads after the note's first push is never committed again. The row stays provisional, and the next download drops the `r2:` reference and releases the local bytes. **The R2 object is then the only copy of that image.**
- **Sequence:** v2.0.0 creates a note with an image. The note is pushed, the image uploads later as provisional, and the device drops it. Any later edit of that note, from any device, makes `notes.server_updated_at > note_attachments.created_at`. Fourteen days after that edit, the sweep claims the row, and about an hour later the bytes are deleted.
- **Timing:** the deferred protocol went live around 2026-10-06, and both the upload and the later commit must be 14 days old. So the earliest claim is about **2026-10-20**. Nothing has been deleted by this path yet. The sweep was enabled today (Worker run 135).
- **Fix options, decision for val:**
  1. Before 2026-10-20, stop the claim pass or lengthen its grace period. For example, have the Worker call `claim_orphaned_provisional_attachments` with `p_min_age => '90 days'`, or skip that pass until a fixed Android build is installed on every device that has v2.0.0.
  2. Measure the exposure first, read-only, in the Supabase SQL editor:
     ```sql
     select count(*), coalesce(sum(a.size_bytes), 0) as bytes
     from note_attachments a
     where a.committed_at is null and a.deleted_at is null
       and exists (select 1 from notes n where n.owner_id = a.owner_id
                   and n.note_id = a.note_id and n.server_updated_at > a.created_at);
     ```
  3. Optional recovery: promote these rows (`committed_at = created_at`) so hydration brings the images back. This also brings back genuine orphans, such as an upload whose conflicted copy re-uploaded it under a new id. That shows up as a duplicate image, which is recoverable, unlike a deletion.
- **Test:** a pgTAP case where a provisional row on a note that was committed again is *not* claimed while a guard (minimum age or feature flag) is set.

### N-02 (P1, Confirmed): CI release builds have no backend address, so sign-in and sync are silently off
- **Status:** Fixed. Desktop compiles the backend in (`CompiledBackendConfig`, preferred over a `local.properties` on disk); `verifyReleaseBackendConfig` fails `bundleRelease` and `packageMsi` without it; the tag and MSI jobs pass the Pages secrets.

- **Android:** `composeApp/build.gradle.kts:240-276` fills `NOTELIKEUS_SUPABASE_URL`, `_ANON_KEY` and `_ATTACHMENTS_WORKER_URL` from env or `local.properties` at compile time. `.github/workflows/android.yml` sets none of them for `assembleRelease` / `bundleRelease` on a `v*` tag. `BackendConfig.android.kt` then resolves to `""` (`SupabaseBackendSelection.kt:46-50`, `allowLocalFallback = false`), and sign-in fails with "Supabase URL is not set" (`requireConfiguredSupabaseUrl`). The v2.0.0 GitHub Release APK/AAB was built this way. Store builds made locally with `local.properties` (`scripts/bundle-release.ps1`) are unaffected.
- **Windows:** `BackendConfig.desktop.kt` reads only env vars and a `local.properties` found by walking up from the working directory (`util/LocalProperties.kt:14-33`). An installed MSI has neither, so its sync is off for everyone except a machine with those env vars set. The `msi-release` job in `desktop.yml` checks only that the app survives startup.
- **Side issue (P3):** for the same reason, a `local.properties` placed in any parent of the MSI's working directory redirects the app to another backend. Exploitability on a default Windows install is low.
- **Fix:** fail release builds when the URL or anon key is blank (Gradle `require` in the release `buildConfigField` block). Pass the values from repo secrets in the tag and MSI jobs. For desktop, compile them into a generated source the way `generateDesktopSecrets` already does for the OAuth secret.
- **Test:** a Gradle check, or an assertion on the generated BuildConfig, that release variants have a non-blank https URL.

### N-03 (P2, Confirmed): production web deploys before e2e and ahead of its backend
- **Status:** Fixed. Production deploys on a successful Web CI run for a push to main, rebuilds that commit, skips if main moved on, and refuses while a migration is unapplied (`scripts/ops/require-migrations-applied.sh`). Takes effect once merged: `workflow_run` reads the default branch's workflow file.

- `cloudflare-pages.yml` deploys `notelikeus` production on any push to `main` touching `web/**`. It needs only its own lint and unit job. The Playwright gate is in `web.yml`, a separate workflow, so a push straight to `main` reaches production whether e2e passes or not.
- Migrations (`supabase.yml`) and the Worker (`attachments-worker.yml`) are manual dispatches. A web change that needs a new RPC overload or Worker route is therefore live before its backend. The Worker workflow documents "schema first", but nothing enforces that order for the web.
- **Relevant now:** the force-push of the rewritten `main` that the "Tidy branches and contributors" thread is waiting on will trigger this production deploy. That is harmless if the tree is identical, but worth knowing.
- **Fix:** make the Pages deploy depend on Web CI (one workflow, or `workflow_run` on a successful Web CI). Before deploying, have the deploy check that the hosted database has every migration the build expects (`supabase migration list --linked`, or a version RPC).

### N-04 (P2, Confirmed in repo; production values need val to confirm): there is effectively one environment
- **Status:** Partly fixed. Staging Pages, Supabase and Worker deploys run only from main; the Worker bucket input has no default. A separate staging Supabase project and required reviewers on the `production` environment are owner settings, not code.

- `web/.env.staging` and `scripts/ops/deploy-staging-pages.mjs` point the "dev" site at the live project `ddxmubeaeeomureolvbu` (per project notes), and the Worker CORS allows `notelikeus-dev.pages.dev` (`workers/attachments/src/cors.ts:13`).
- The Worker deploy's default bucket is `notelikeus-attachments-dev` (`attachments-worker.yml:22`, `setup-staging.sh:31`). A dispatch that keeps the default points the production Worker at that bucket. Which bucket production actually uses can only be checked in Cloudflare.
- `cloudflare-pages.yml` `deploy-preview` and `supabase.yml` `deploy` both run on `workflow_dispatch` from **any ref**, and the `production` environment has no required reviewers. An unreviewed branch can therefore reach production data through the dev site, or reach the production schema directly.
- **Fix:** a separate Supabase project and R2 bucket for staging. Restrict both dispatch deploys to `main`, or add required reviewers on the `production` environment. Make the Worker's bucket input required, with no default.

### N-05 (P3, Probable): a note marked for recommit can stop all downloads on that device
- **Status:** Fixed. At the start of a download each marked note is pushed on its own and a failure keeps the mark (`DeferredUploadCommitTest`, fails without the fix).

- `NoteSyncEngine.kt:794-799` (new in `a5c3047`) pushes marked notes before every download. `SupabaseNoteTransport.putNotes` (`:209-219`) rethrows any non-conflict RPC failure, which ends the run.
- A *persistent* failure for one marked note therefore blocks every later download on that device: another device's edits never arrive, while uploads may still work. Server-side conflicts are handled and do not trigger this. A transient network failure retries correctly.
- **Needs:** confirm whether any non-conflict error from `apply_note_change` can repeat for the same payload, such as a validation limit or a malformed attachment-id list. If so, catch it per note in `pushPendingRecommits`, keep the mark, and continue with the download.

### N-06 (P3, Probable): clients ignore the Worker's 429
- **Status:** Fixed by raising the deploy default to 600 a minute (takes effect on the next Worker deploy). Client-side 429 back-off not added.

The Worker throttles at 120 requests per minute per user (`attachments-worker.yml` default, `rateLimit.ts`). Neither client handles 429 or `Retry-After`: the web throws a generic error (`r2AttachmentBlobStore.ts:70`), and Kotlin has no 429 path. A new device hydrating a few hundred images will see failed loads that only clear on later syncs. **Fix:** back off on 429, or raise the limit for GET.

### N-07 (P3, Confirmed): the unreferenced-object report never looks past the first 5,000 keys
- **Status:** Fixed. Each run starts at a random owner and wraps around.

`reportUnreferencedAttachmentObjects` (`sweep.ts:155-186`) restarts at the beginning of `owners/` on every run and stops after 5 pages. Owners whose keys sort later are never measured. The report sets `truncated` but cannot cover them. **Fix:** persist the cursor (for example in KV), or sample a random starting prefix.

### N-08 (P3, Probable): a background update can drop the last second of typing on the web
- **Status:** Fixed. The update waits until no editor has a debounced or running save (`pendingEditorSaves`).

`main.tsx:24-30` reloads the page as soon as a new service worker is waiting, and every push to main ships one. The editor flushes its 1 s debounce on `pagehide` (`useNoteEditor.ts:316-330`), but that flush is async: it encrypts with `crypto.subtle`, then writes to IndexedDB, and a reload does not wait for it. **Fix:** in `onNeedRefresh`, wait for the editor's pending save, or until the editor is closed or the tab is hidden, before calling `updateSW(true)`.

### N-09 (P3, Confirmed): release hygiene
- **Status:** Fixed. A tag without signing secrets or the pinned certificate fails; the script prints the current privacy URL.

- `android.yml` publishes a GitHub Release with **unsigned** APK/AAB when signing secrets are missing (as v2.0.0 did). The certificate pin is skipped when `ANDROID_SIGNING_CERT_SHA256` is unset. Prefer failing the tag build.
- `scripts/bundle-release.ps1:29` still prints the retired Firebase privacy URL (`notelike.web.app`). The store docs use `https://notelikeus.pages.dev/privacy.html`.

## Status of the earlier audits' items

| Item | Status now |
|---|---|
| F-01 to F-04, F-06 to F-12 | Fixed (#316), with regression tests. F-08 and F-09 are deployed: Supabase run 131, Worker run 135. |
| F-05 | Superseded by AU-07 (verified deletes): fixed on all clients. |
| FU-1, FU-2 | Fixed. FU-3 withdrawn. |
| FU-4 `begin_sync_mutation` granted to `authenticated` | **Still open** (P3, not exploitable). `20250904000000_mapping_and_attachment_guards.sql:511`. |
| FU-5 deprecated `security-crypto` | **Still open** (P3). `gradle/libs.versions.toml:33`. |
| FU-6 web refresh token in `localStorage` | Unchanged, accepted note. |
| AU-01 to AU-30 | Fixed and tested, except AU-14 (accepted risk) and AU-18 (withdrawn). |
| Not verified by #318 | Two-device conflict on real Android devices; a Gradle CVE scan; Android/desktop screen-reader and keyboard runs. All still open. |

Checked in this pass and found sound:
- The web counterpart of the `e5c5762` bug: the web writes the uploaded `r2:` version through `putLocalEdit` (`noteActions.ts:20-33, 102`), so its pending-push marker protects it until a commit names it.
- Committed secrets: only Supabase CLI demo keys.
- Worker auth and object-key derivation, CORS, the cron error isolation (`Promise.allSettled`), and the deploy workflows' confirmation inputs and secret handling.

## Tests run here

| Command | Result |
|---|---|
| `web: npx vitest run` | 126 files, **919 / 919** pass |
| `web: tsc -b --noEmit`, `oxlint` | pass (warnings only) |
| `workers/attachments: vitest run`, `tsc --noEmit` | **137 / 137** pass |
| Kotlin (`desktopTest`, detekt) | **Not run**: Maven Central answered HTTP 429 to this sandbox. CI on `d3f97a1` is green for all six workflows (Android, Desktop, Web incl. e2e, Pages, Worker, CodeQL). |
| pgTAP, Playwright, instrumented | Not run here (no Docker or device); green in CI on `d3f97a1` where CI runs them. |

## Suggested order

1. **N-01 before 2026-10-20:** run the count query, then pause or lengthen the provisional claim. Ship a fixed Android build.
2. **N-02:** make release builds fail without a backend URL, and wire the secrets into the tag and MSI jobs.
3. **N-03 and N-04:** gate the Pages deploy on Web CI, restrict dispatch deploys to `main`, and plan a separate staging project.
4. The P3 items as convenient.
