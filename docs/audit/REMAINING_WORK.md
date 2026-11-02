# Notelikeus — Remaining Work After the Audit

State as of 2026-11-02. Findings are defined in `AUDIT_REPORT.md`. Items are ordered by what blocks users first.

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
| Supabase migrations | **Deployed** through `20261102010000` (runs 41386086762 and 41403546359, 2026-11-02) |
| Attachments Worker | **Deployed** from `98aa3833` (run 41404055682, 2026-11-02): account deletion route and the hourly sweep of 100 |
| Web app (Cloudflare Pages) | The deploy of `98aa3833`, with account deletion, (run 41405805972) is **waiting for production approval** |
| Android / Windows | Latest release **v2.4.1** (2026-10-30). **12 commits on `main` are unreleased**, including #403 and #404 |
| Dependabot alerts | None open. The 11 build-classpath alerts were dismissed on 2026-10-31 (not used in shipped apps; the Kotlin Gradle plugin one as tolerable risk) |

---

## 1. Decisions and actions only you can take

### 1.1 Approve the web deploy
Pages run 41405805972 waits for manual production approval in GitHub Actions. After it goes live, the service worker keeps serving the previous build until a reload.

### 1.2 Release Android and Windows after v2.4.1
Account deletion reaches Android and Windows users only with the next release (Android: push a `v*` tag; Windows: dispatch `desktop.yml` from `main`). Devices on v2.4.1 do not know the `NL003` "account deleted" answer: after their account is deleted elsewhere their syncs fail and the local copy stays until they update.

### 1.3 Google Play account deletion
Play Console's Data safety form needs a web link where a user can ask for their account to be deleted without installing the app. Settings → Delete account on the web app is the candidate; it requires signing in, so check it meets Play's requirements before submitting.

### 1.4 First deletion in production
Delete a throwaway account on each client. It is also the first proof that the Worker's `SUPABASE_SERVICE_ROLE_KEY` is the current `sb_secret_` key: with a wrong key the deletion answers pending and the cron sweep cannot finish it.

### 1.5 Required checks
Only `Web build` and `Web e2e` block a merge. `database-tests`, the Worker's `verify` and the deletion `e2e` can fail without blocking one; add them in branch protection.

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

1. Approve the web deploy (§1.1) and delete a throwaway account (§1.4).
2. Release Android and Windows (§1.2), then fill in Play's deletion link (§1.3).
3. Add the required checks (§1.5).
4. Rewrite the release steps in `docs/DEPLOYMENT_RUNBOOK.md` (§1.6).
5. Device verification (§3) as scheduled work.
