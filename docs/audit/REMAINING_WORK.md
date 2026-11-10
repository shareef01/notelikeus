# Notelikeus — Remaining Work After the Audit

State as of 2026-11-09 (deploy and release rows re-checked against GitHub Actions and the release list; the rest as of 2026-11-02). Findings are defined in `AUDIT_REPORT.md`. Items are ordered by what blocks users first.

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
| Supabase migrations | **Deployed** through `20261106000000` (manual run 41795580879 from `809cfe51`, 2026-11-04). No migration has merged since. A push to `main` only tests migrations; deploying is a manual dispatch of `supabase.yml` |
| Attachments Worker | **Deployed** from `809cfe51` (manual run 41796111261, 2026-11-04). **One change is not deployed**: [#433](https://github.com/shareef01/notelikeus/pull/433) (`d9dd614b`, 2026-11-05) changed `workers/attachments/src/index.ts` after that run, and pushes to `main` only run the Worker's `verify` job |
| Web app (Cloudflare Pages) | **Deployed** from `fb4d1078` (run 42705042879, 2026-11-09). The deploy of each later commit on `main` waits for production approval in turn |
| Android / Windows | Latest release **v2.6.2** (2026-11-05). **18 commits on `main` were unreleased** at `fb4d1078`, most of them web-only UI work |
| Dependabot alerts | None open. The 11 build-classpath alerts were dismissed on 2026-10-31 (not used in shipped apps; the Kotlin Gradle plugin one as tolerable risk) |

---

## 1. Decisions and actions only you can take

### 1.1 Approve web deploys as they queue
Each push to `main` queues a Pages deploy that waits for manual production approval in GitHub Actions. After one goes live, the service worker keeps serving the previous build until a reload.

### 1.2 Deploy the Attachments Worker
#433 changed the Worker after its last deploy (see the table). Dispatch `attachments-worker.yml` from `main` to ship it.

### 1.3 Release Android and Windows when native changes land
Android: push a `v*` tag; the APK and AAB are attached to the GitHub release, which is the only distribution channel (the app is not on Google Play). Windows: dispatch `desktop.yml` from `main`. Devices older than v2.5.0 do not know the `NL003` "account deleted" answer: after their account is deleted elsewhere their syncs fail and the local copy stays until they update.

### 1.4 First deletion in production
Delete a throwaway account on each client. It is also the first proof that the Worker's `SUPABASE_SERVICE_ROLE_KEY` is the current `sb_secret_` key: with a wrong key the deletion answers pending and the cron sweep cannot finish it.

### 1.5 Required checks
Done on 2026-11-10. Twelve checks block a merge: `Web build`, `Web e2e`, `Android build`, `instrumented (30)`, `instrumented (36)`, `Desktop build`, `package`, `windows-crypto`, `Analyze (java-kotlin)`, `database-tests`, `Worker verify` and `Account deletion e2e`. The last three come from workflows that now start on every pull request and skip their work when nothing under their paths changed ([#451](https://github.com/shareef01/notelikeus/pull/451)).

### 1.6 `docs/DEPLOYMENT_RUNBOOK.md`
Written before 2.1.0. Its header now says so, but its release steps (PR numbers, the version to tag) need rewriting before anyone follows them again.

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
- **Local test runs on this machine**: Gradle cannot open a loopback connection here and Docker was not running, so the Kotlin and database tests only run in CI.

`AUDIT_REPORT.md` records what the third pass did verify on a Pixel 7 and an emulator (cold start, persistence, large font, the instrumented suites).

---

## 4. Suggested order

1. Deploy the Attachments Worker (§1.2), approve queued web deploys (§1.1) and delete a throwaway account (§1.4).
2. Release Android and Windows when native changes are waiting (§1.3).
3. Rewrite the release steps in `docs/DEPLOYMENT_RUNBOOK.md` (§1.6).
4. Device verification (§3) as scheduled work.
