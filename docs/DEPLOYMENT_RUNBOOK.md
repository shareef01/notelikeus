# Deployment runbook

How a change on `main` reaches users: what deploys by itself, what needs a manual run, the order
that keeps clients and backend compatible, and how to undo each step.

Rewritten on 2026-11-10 from the workflows in `.github/workflows/` as they stand. It describes the
process, not one release: the earlier version of this file was the plan for the October 2026 audit
release, and is in the git history if those specifics are ever needed. If a workflow and this file
disagree, the workflow is right and this file needs fixing.

---

## 1. What deploys, and how

| Piece | Source | How it ships | Approval |
|---|---|---|---|
| Database migrations | `supabase/migrations/` | Manual run of `supabase.yml` from `main` | Type the project ref; `production` environment |
| Attachments Worker | `workers/attachments/` | Manual run of `attachments-worker.yml` from `main` | Type the Worker name and bucket; `production` environment |
| Web app | `web/` | Automatic after `Web CI` passes on a push to `main` (`cloudflare-pages.yml`) | `production` environment |
| Android | `androidApp/`, `composeApp/` | Push a `v*` tag (`android.yml`) | None beyond the tag |
| Windows | `composeApp/` | Manual run of `desktop.yml` from `main`, naming an existing release tag | None beyond the dispatch |
| Sign-in emails, CAPTCHA | `supabase/templates/` | Manual run of `supabase-auth.yml` | Type the project ref; `production` environment |

The `production` environment has one required reviewer, so each job that names it waits in GitHub
Actions until it is approved.

Merging a pull request therefore ships **only the web app**. A merged migration or Worker change is
not live until its workflow is run by hand: a push to `main` runs their tests and skips their
`deploy` job.

Android is distributed only as the APK and AAB attached to the GitHub release. The app is not on
Google Play.

---

## 2. Order, and why

1. **Migrations**
2. **Attachments Worker**
3. **Web** (follows `main` once approved)
4. **Android**, then **Windows**

**Migrations first.** A client or Worker that calls a database function the hosted project does not
have yet fails for every user. The reverse is safe as long as a migration keeps the signatures that
released clients call, which is the rule migrations in this repository follow.

Two deploys enforce this themselves. Both the Worker deploy and the web deploy run
`scripts/ops/require-migrations-applied.sh` and stop if any committed migration is missing from the
hosted project. Nothing enforces it for Android and Windows: check it before tagging.

**Android before Windows.** The Android tag creates the GitHub release. The Windows job attaches its
installer to a release that already exists and fails if there is none.

---

## 3. Before starting

**`main` is green.** Twelve checks block a merge, including the database tests, the Worker's
checks and the account-deletion end-to-end test, so a merged commit has passed them.

**Secrets.** Each deploy job fails early with a message naming a missing value, so a missing secret
costs seconds, not a half-applied deploy.

| Workflow | Secrets it reads |
|---|---|
| `supabase.yml` | `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD`, `SUPABASE_PROJECT_REF` (and the `STAGING_` pair for a staging run) |
| `attachments-worker.yml` | `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD`, `SUPABASE_PROJECT_REF` |
| `cloudflare-pages.yml` | `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `VITE_ATTACHMENTS_WORKER_URL`, `VITE_TURNSTILE_SITE_KEY`, `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD`, `SUPABASE_PROJECT_REF` (and `STAGING_VITE_*` for previews) |
| `android.yml` | `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `VITE_ATTACHMENTS_WORKER_URL`, `NOTELIKEUS_CAPTCHA_PAGE_URL` |
| `desktop.yml` | `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `VITE_ATTACHMENTS_WORKER_URL`, `NOTELIKEUS_CAPTCHA_PAGE_URL` |
| `supabase-auth.yml` | `SUPABASE_ACCESS_TOKEN`, `SUPABASE_PROJECT_REF`, `TURNSTILE_SECRET_KEY`, and the `SMTP_*` set |

**There is no separate staging backend.** The `staging` target of `supabase.yml` and the staging
web settings reach the production project (`docs/audit/REMAINING_WORK.md`, ENV-1). Production is the
first real run of every migration, which is why the steps below lean on the local test suites.

---

## 4. Migrations

Run `supabase.yml` from `main` (Actions → *Supabase CI* → *Run workflow*) with:

- `confirm_project_ref`: the project ref, typed out. A mismatch applies nothing. The typing is what
  makes this a decision and not a misclick; do not remove it.
- `target`: `production`.

A run from any other branch runs the tests and stops before `deploy`.

What the run does, in order:

- `preflight` checks the secrets and that the database password authenticates.
- `database-tests` resets a local database from the committed migrations and runs the pgTAP suite,
  so what is about to be pushed is what just passed.
- `deploy` prints `migration list --linked`, runs `db push`, and prints the list again.

**Verify:** in the second list, every migration has a remote entry.

---

## 5. Attachments Worker

Run `attachments-worker.yml` from `main` (Actions → *Attachments Worker CI* → *Run workflow*) with:

- `confirm_worker_name`: `notelikeus-attachments`, typed out.
- `r2_bucket`: the production bucket name, typed out.
- `allowed_origins`: only for a custom domain. `localhost` and the `notelikeus` Pages projects are
  always allowed.
- `rate_limit_requests` / `rate_limit_period`: leave the defaults (600 per 60 seconds) unless
  changing the limit on purpose. `0` removes Worker-level throttling.

The job refuses to deploy if a committed migration is not applied. It then writes `wrangler.toml`
(gitignored: it carries the account id and bucket), deploys, uploads `SUPABASE_SERVICE_ROLE_KEY` as
a Worker secret, and checks that the hourly cleanup sweep is configured.

Without `SUPABASE_SERVICE_ROLE_KEY`, account deletion answers 503, deleted attachments never
release their quota and the sweeps do nothing. The job warns in its log when the secret is absent.

**Verify:** upload an image to a note, open the note on a second device, then delete the note and
confirm the image is gone after the next sweep.

---

## 6. Web

Nothing to run. After `Web CI` passes on a push to `main`, `cloudflare-pages.yml` starts a `deploy`
job that waits for approval on the `production` environment. Once approved it:

- skips if `main` has moved on since, so an older commit never replaces a newer deploy;
- stops if a committed migration is not applied;
- builds, checks the build output, and deploys to the `notelikeus` Pages project.

Several merges in a row queue several deploys. Approving the newest is enough; the older ones skip
themselves.

**Verify:** open the site and hard-reload. The service worker keeps serving the previous build
until a reload, so the first visit after a deploy still shows the old version.

---

## 7. Android

1. Open a release pull request that bumps the version in all of these, as `chore(release): X.Y.Z`:
   - `gradle.properties` (`notelikeus.versionName`)
   - `androidApp/build.gradle.kts` (`versionCode`, plus one)
   - `web/package.json` and `web/package-lock.json`
   - `CHANGELOG.md` and the version in `README.md`
2. Merge it, then confirm migrations and the Worker are deployed (§4, §5).
3. Tag the merged commit and push the tag:

   ```bash
   git tag vX.Y.Z
   ```

   ```bash
   git push origin vX.Y.Z
   ```

`android.yml` builds the signed APK and AAB, runs the unit and instrumented tests, and creates the
GitHub release `vX.Y.Z` with both files and generated notes.

**Verify:** the release exists with an `.apk` and an `.aab`; install the APK over the previous
version on a device and confirm notes are still there and a sync completes.

---

## 8. Windows

After the Android release exists, run `desktop.yml` from `main` (Actions → *Desktop CI* →
*Run workflow*) with:

- `release_tag`: the tag just released, for example `vX.Y.Z`.
- `replace_existing_asset`: leave unticked. Tick it only to replace an installer already attached
  to that release; without it, an existing installer of the same name stops the job.

The job builds the installer from the tagged source, not from the tip of `main`, and attaches the
MSI to the existing release.

**The MSI is not code-signed.** The v2.6.2 installer was checked on 2026-11-06 and Windows reported
`NotSigned`; the workflow has no signing step. Do not describe the installer as signed until a
trusted signing identity and a verify step exist. Android signing is separate and does verify.

**Verify:** install the MSI on Windows, sign in, and confirm a sync completes and the window stays
responsive while it runs.

---

## 9. Sign-in emails and CAPTCHA

Only when the email templates in `supabase/templates/`, the SMTP settings or the CAPTCHA setting
need to change. Run `supabase-auth.yml` with `confirm_project_ref` typed out.

Leave `enable_captcha` unticked on a routine run. Unticked, the run leaves the hosted CAPTCHA
setting and the hourly email limit as they are. Ticked, it turns CAPTCHA on for code requests,
which refuses requests from any installed Android or Windows build older than the CAPTCHA release.

`supabase config push` is deliberately not used: it would push the local stack's auth settings,
including local redirect URLs, over production's.

---

## 10. After a deploy

Use a throwaway account, and two clients signed into it where a check says so.

1. **Sync:** create a note on one client; it appears on the other.
2. **Attachments:** add an image on one client; it loads on the other.
3. **Cloud wipe:** delete all cloud data on one client. The other must say the cloud data was
   deleted, upload nothing and keep its local notes.
4. **Restore fence:** delete a note on one client, edit it on the other, then restore it on the
   first. The restore must be refused and the edit kept.
5. **Account deletion:** delete the throwaway account. Signing in again must not bring the notes
   back.

---

## 11. Rollback

| Piece | How to undo |
|---|---|
| Migrations | There are no down migrations. Write a new migration that reverses the specific change and deploy it the same way. Reverting the code alone leaves the schema ahead of the clients. |
| Attachments Worker | `wrangler rollback` to the previous version, or check out the previous commit and deploy that. |
| Web | Roll the Pages project back to the previous deployment in the Cloudflare dashboard, then revert the commit on `main` so the next deploy does not bring it back. |
| Android | Mark the GitHub release as a pre-release or delete it so it is no longer the latest, and release a fixed version. Devices that already installed it keep it. |
| Windows | Remove the MSI from the release, or replace it with `replace_existing_asset` ticked. |

Before a migration that drops or changes something released clients call, search the clients for
the name first, and do not deploy if anything still calls it:

```bash
git grep -n "name_of_function" -- web/src composeApp/src workers
```

---

## 12. What this file does not establish

- **That the secrets exist and are valid.** The names are read from the workflows; each job fails
  with the missing name if one is absent.
- **The current state of production.** For what is deployed right now, read the latest runs of each
  workflow and `docs/audit/REMAINING_WORK.md`.
- **That a deploy worked.** A green job means it ran. The checks in §10 are what show the behaviour
  is live.
