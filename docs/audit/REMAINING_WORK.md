# Notelikeus — Remaining Work After the Audit

State as of 2026-11-17 (deploy, release, alert and required-check rows re-checked against GitHub that day; the F-/FU- rows as of 2026-11-02). Findings F-/FU- are defined in `AUDIT_REPORT.md`; the later audits have their own reports under [`docs/audits/`](../audits/). Items are ordered by what blocks users first.

## Where things stand

| Area | State |
|---|---|
| F-01 to F-12, FU-1, FU-2 | Fixed, merged in [#316](https://github.com/shareef01/notelikeus/pull/316) |
| Cloud-wipe gap (web kept its local copy after "Delete cloud data") | Fixed, merged in [#317](https://github.com/shareef01/notelikeus/pull/317) on 2026-10-15 |
| Cloud wipe and other devices | Fixed, merged in [#370](https://github.com/shareef01/notelikeus/pull/370) on 2026-10-26: a server-side wipe generation every write must present, so a stale device cannot upload its copy back |
| F-14 (restore overwrote newer edits), FU-4, FU-7, FU-8 | Fixed. F-14, FU-4 and FU-8 in #370; FU-7 (`dependency-submission` in `android.yml`) earlier |
| FU-5 | Already implemented; the finding was corrected in `AUDIT_REPORT.md` |
| Revision order and stored-byte quota (audit of 2026-11-02) | Fixed, merged in [#403](https://github.com/shareef01/notelikeus/pull/403): revisions are ordered per owner, and quota counts bytes still in storage |
| No way to delete an account | Fixed, merged in [#404](https://github.com/shareef01/notelikeus/pull/404) on web, Android and Windows. [#405](https://github.com/shareef01/notelikeus/pull/405) runs it end to end in CI (`account-deletion-e2e.yml`) against local Auth, Postgres and R2 |
| Expanded repository audit of 2026-11-05 (AUD-E01 to E13) | Its report records all thirteen as fixed, and the fixes are on `main` (`e9e85932`, `9c3dcc33`); not re-checked item by item here. See [`docs/audits/2026-11-05/REPORT.md`](../audits/2026-11-05/REPORT.md) |
| UI/UX audit of 2026-11-11 (NL-01 to NL-12) | Fixed, merged in #463 to #473. Its unfinished verification is listed in [`docs/audits/2026-11-11/FIXES.md`](../audits/2026-11-11/FIXES.md) |
| Code audit of 2026-11-17 (AUD-G01 to G04) | Fixed, merged in [#486](https://github.com/shareef01/notelikeus/pull/486); see [`docs/audits/2026-11-17/FIXES.md`](../audits/2026-11-17/FIXES.md) |
| Audit and polish pass of 2026-11-18 (AUD-H01 to H05) | Fixed, merged in [#494](https://github.com/shareef01/notelikeus/pull/494) on 2026-11-18: a sync fix (an unconfirmed local note could overwrite a confirmed cloud edit), guest mode remembered across reloads, a lighter filter row and a new README. Its web deploy waits for production approval like any other. See [`docs/audits/2026-11-18/REPORT.md`](../audits/2026-11-18/REPORT.md) |
| Supabase migrations | **Deployed** through `20261115000000` (manual run 43678236436 from `50eb484f`, 2026-11-15; its `deploy` job succeeded). No migration has merged since. A push to `main` only tests migrations; deploying is a manual dispatch of `supabase.yml` |
| Attachments Worker | **Deployed** from `8000a368` (manual run 44207720652, 2026-11-18), which includes [#433](https://github.com/shareef01/notelikeus/pull/433) and [#492](https://github.com/shareef01/notelikeus/pull/492). Nothing is waiting. Pushes to `main` only run the Worker's `verify` job; deploying is a manual dispatch of `attachments-worker.yml`, and since 2026-11-18 its `deploy` job waits for approval on the `production` environment. The run confirmed every migration is applied, uploaded the service-role secret and enabled the orphan sweep. Account deletion through the live Worker was proven afterwards (§1.4). The bucket it binds is named `notelikeus-attachments-dev`, as in every deploy since 2026-10-21: that is where production attachments are, despite the name |
| Web app (Cloudflare Pages) | **Deployed** from `aebe6291` (run 44009987376, 2026-11-17), which includes #486. The deploy of the release commit `8ca83383` is waiting for production approval; it changes only the version number the web app reports. The deploy of each later commit on `main` waits for production approval in turn |
| Android / Windows | Latest release **[v2.7.0](https://github.com/shareef01/notelikeus/releases/tag/v2.7.0)** (2026-11-17), from `8ca83383` ([#489](https://github.com/shareef01/notelikeus/pull/489)), with the signed APK and AAB and the Windows MSI. First release built with Kotlin 2.4 and Compose Multiplatform 1.12, and the first to migrate the local database to schema 12. Upgrade from 2.6.2 **checked on an Android emulator** (local data only); a signed-in upgrade on a Pixel 7 **syncs** afterwards (2026-11-19); the Windows upgrade is **not yet checked**: see §1.3 |
| Dependabot alerts | None open (re-checked 2026-11-17). The 11 build-classpath alerts were dismissed on 2026-10-31 (not used in shipped apps; the Kotlin Gradle plugin one as tolerable risk) |

---

## 1. Decisions and actions only you can take

### 1.1 Approve web deploys as they queue
Each push to `main` queues a Pages deploy that waits for manual production approval in GitHub Actions. After one goes live, the service worker keeps serving the previous build until a reload.

### 1.2 Deploy the Attachments Worker
Done on 2026-11-17 and again on 2026-11-18 for #492 (see the table). For the next one: dispatch `attachments-worker.yml` from `main` and type the bucket the previous deploy used. A different bucket name leaves the existing attachments behind.

### 1.3 Release Android and Windows
v2.7.0 was released on 2026-11-17 (see the table).

**Checked on 2026-11-17 on an Android 16 emulator** (`google_apis` x86_64, fresh data): the released v2.6.2 APK was installed and used signed out to create two notes, one green with a gallery picture and a new label, one pinned checklist with one item ticked. The released v2.7.0 APK, signed with the same certificate, was installed over it without clearing data. After the upgrade both notes, their order, pin, colour, checklist ticks and label were unchanged, the picture loaded in the editor from the encrypted on-device copy, an edit saved by 2.7.0 survived a force-stop and relaunch, and the log showed no crash or database error. This exercises the local database migration to schema 12.

**Checked on 2026-11-19 on a Pixel 7** (real data, signed in): v2.7.0 had been installed over the earlier release on 2026-11-18 without clearing data (first install 2026-11-05). The installed APK is byte-identical to the release APK (versionCode 21). The account was still signed in, the notes were all present, and a manual **Sync to cloud** finished "Up to date" with the account row showing "Up to date · 7 notes". Which release it upgraded from is not recorded on the device.

Still to do from the runbook: the MSI over 2.6.2 on Windows.

For the next release, Android: push a `v*` tag; the APK and AAB are attached to the GitHub release, which is the only distribution channel (the app is not on Google Play). Windows: dispatch `desktop.yml` from `main`. Devices older than v2.5.0 do not know the `NL003` "account deleted" answer: after their account is deleted elsewhere their syncs fail and the local copy stays until they update.

### 1.4 First deletion in production
**Done on the web on 2026-11-18, after a fix.** The first attempt deleted the throwaway account's notes but not its sign-in user: signing in again with the same address landed in the deleted account, which refused to sync. The web app reports a finished and a pending deletion the same way, so only that second sign-in showed it. Cause: the Worker sent its `sb_secret_` key as `Authorization: Bearer` as well as `apikey`. PostgREST accepted that, so the request and the purge ran, but Supabase Auth verifies a bearer token as a JWT and refused the admin delete, and the sweep's retries failed the same way. Every in-app deletion since the key changed on 2026-10-21 would have ended like this. [#492](https://github.com/shareef01/notelikeus/pull/492) sends a non-JWT key on `apikey` only and logs which step leaves a deletion pending. After deploying it, deleting the same account again removed the sign-in user: signing in with the address created a new, empty account whose note synced.

Left: the same check from Android and Windows, each with a throwaway address, and deleting the fresh test account the web check created. Production sign-in has a Turnstile check, which only a person can complete.

### 1.5 Required checks
Done on 2026-11-10. Twelve checks block a merge: `Web build`, `Web e2e`, `Android build`, `instrumented (30)`, `instrumented (36)`, `Desktop build`, `package`, `windows-crypto`, `Analyze (java-kotlin)`, `database-tests`, `Worker verify` and `Account deletion e2e`. The last three come from workflows that now start on every pull request and skip their work when nothing under their paths changed ([#451](https://github.com/shareef01/notelikeus/pull/451)). Since [#486](https://github.com/shareef01/notelikeus/pull/486) those three names belong to gate jobs that always run and fail if the `changes` job failed; the jobs that do the work are named `… (run)`.

### 1.6 `docs/DEPLOYMENT_RUNBOOK.md`
Done on 2026-11-10: rewritten from the current workflows as a description of the process, no longer the plan for one release.

---

## 2. Open findings (P3: not release blockers)

| ID | Platform | Issue | Suggested fix | Size |
|---|---|---|---|---|
| FU-6 | Web | The Supabase refresh token is in `localStorage` | No change unless the CSP is ever loosened | none |
| ENV-1 | Ops | There is no staging environment: `web/.env.staging`, `setup-staging.sh` and the `staging` target of `supabase.yml` all reach the production project and Worker | A second Supabase project, Worker and bucket, if production should ever stop being the first real run | medium |

---

## 3. Verification never done (needs a device, emulator or real data)

- **Screen reader and font scaling** on Windows (Narrator, 200% scale).
- **Performance with a large library**: thousands of notes, recomposition counts.
- **F-07 on a real device**: import a large backup (hundreds of MB of images) on a low-memory Android device. The streaming path is unit-tested only.
- **Concurrent writers** against the per-owner lock of #403: two devices saving the same account at once. pgTAP checks the lock is held until commit, not two real sessions.
- **Account deletion edge cases**: the request row being forgotten two hours after the auth user goes, and an upload racing the deletion. The e2e places a late object directly in R2.
- **The required-check gates' skipped path** (#486): so far the gates have only run with their tests executing. The first pull request that touches nothing under `supabase/` or `workers/` should still show `database-tests`, `Worker verify` and `Account deletion e2e` green. The case they exist for, a failing `changes` job, has not been forced.
- **The 2026-11-11 UI/UX audit's open checks**: Windows reminder banner while hidden, 200% scaling, multiple monitors, contrast themes and screen readers. See `docs/audits/2026-11-11/FIXES.md`.
- **Parts of `2864aabc..59ef4fed` the 2026-11-17 audit did not read**: web UI components, the query parser and saved filters, native UI files, `NoteAppFunctions`, and the Kotlin and Compose upgrades. See the Coverage section of its report.
- **Local test runs on this machine**: Gradle cannot open a loopback connection here and Docker was not running, so the Kotlin and database tests only run in CI.

`AUDIT_REPORT.md` records what the third pass did verify on a Pixel 7 and an emulator (cold start, persistence, large font, the instrumented suites).

---

## 4. Suggested order

1. Delete a throwaway account from Android and Windows (§1.4); the web is done. No Worker or web deploy is waiting.
2. Install the v2.7.0 MSI over 2.6.2 on Windows and check a sync completes (§1.3). Android is done: the local migration on an emulator, a signed-in upgrade and sync on a Pixel 7.
3. Device verification (§3) as scheduled work.
