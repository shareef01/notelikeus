# Deployment runbook

How the audit's changes reach production, in the order they must go, with the verification and the
rollback for each step.

**Written on 2026-10-13, before 2.1.0.** The work it covers was rebased onto `main` on 2026-10-26.
Its three migrations were renumbered to `20261018000000` … `20261020000000` so they run after the
ones `main` gained in the meantime, and the release steps below (PR numbers, the version to tag)
need re-reading against the current state before use.

**Nothing in this file was executed during the audit.** No migration was pushed, no Worker was
deployed, no release was cut, and no pull request was merged. This is the plan for the owner to
execute, and every command in it was read out of the repository rather than recalled.

The findings behind these steps are in [AUDIT_REPORT.md](AUDIT_REPORT.md) §13.2; the evidence that
the code is ready to deploy is in §13.3.

---

## 0. What is being deployed

| Artifact | Source | Trigger |
|---|---|---|
| Three SQL migrations | `supabase/migrations/20261018000000_account_wipe_generation.sql` … `20261020000000_restore_revision_fence.sql` | Manual dispatch of `.github/workflows/supabase.yml` |
| Attachments Worker | `workers/attachments/` | Manual dispatch of `.github/workflows/attachments-worker.yml` |
| Web client | `web/` | Automatic on push to `main` (`.github/workflows/cloudflare-pages.yml`) |
| Android | `androidApp/` | Push a `v*` tag (`.github/workflows/android.yml`) |
| Windows | `composeApp/` | Manual dispatch of `.github/workflows/desktop.yml` from `main` |

---

## 1. Order, and why it is this order

1. **Merge** the release pull request (PR #317 and the audit branch).
2. **Migrations** — manual dispatch.
3. **Attachments Worker** — manual dispatch.
4. **Web** — follows `main` automatically.
5. **Android and Windows** — tag and dispatch.

Two of these orderings are load-bearing rather than conventional:

**Migrations before the clients.** The new clients always send `p_wipe_generation`, which resolves
only to the post-migration function arities. Deploying a client first would make every write fail
with "function not found". The reverse is safe: the *currently deployed* clients send the legacy
arities, and the migrations keep those working — the legacy 14-argument `apply_note_change`,
`apply_note_delete` with two arguments and `restore_note` without a generation are all retained as
thin wrappers, and the pgTAP suite asserts they still apply. Generation `0` means "never wiped", so
an untouched account behaves exactly as before.

**Migrations before the Worker.** `.github/workflows/supabase.yml` says so itself: a Worker calling
an RPC its database does not have yet answers `503`.

---

## 2. Preconditions

The deploy jobs read these GitHub secrets. They are listed here as *names to confirm*, not as
secrets that are known to be present — the audit could not read the repository's secret store.

| Workflow | Secrets |
|---|---|
| `supabase.yml` | `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD`, `SUPABASE_PROJECT_REF` |
| `attachments-worker.yml` | `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY` |
| `cloudflare-pages.yml` | `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `VITE_ATTACHMENTS_WORKER_URL` |
| `android.yml` | `ANDROID_SIGNING_KEYSTORE_BASE`, `ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD` |

Each job fails fast with a message naming the missing variable, so a missing secret is a cheap
failure rather than a half-applied deploy.

This machine has no Supabase access token (`npx supabase projects list` answers "Access token not
provided"), so the CLI cannot reach the hosted project from here by design.

**Green locally before starting.** These are the commands the audit ran, and the results they
produced on the branch:

```bash
npx supabase db reset --yes && npx supabase test db      # 31 files, 350 assertions, PASS
cd web && npx vitest run && npx tsc -b --noEmit && npx oxlint
cd workers/attachments && npx vitest run && npx tsc --noEmit
./gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest :androidApp:testDebugUnitTest --no-daemon
```

---

## 3. Step 1 — merge

Re-check rather than trusting the audit's snapshot: `main` moves, and a green pull request can go
stale.

```bash
gh pr view 317 --json state,mergeable,mergeStateStatus,statusCheckRollup
```

At the time of the audit it was open, `MERGEABLE`, `CLEAN`, with 17 checks passing. The audit
branch (`fix/wipe-generation-and-hardening`) sits on top of it and has never been seen by CI.

---

## 4. Step 2 — Supabase migrations

The deploy job is gated on a manual dispatch (`if: github.event_name == 'workflow_dispatch'`), and
the dispatch requires typing the project ref into `confirm_project_ref`. A mismatch applies
nothing:

> `confirm_project_ref` does not match the `SUPABASE_PROJECT_REF` secret. Nothing was applied.

That typing step is deliberate — it is what makes this a decision rather than a misclick — so do not
"simplify" it.

**Before dispatching**, re-confirm that dropping the three public guard helpers is safe. This was
verified on the branch and is worth re-running after any rebase:

```bash
rg -n "begin_sync_mutation|end_sync_mutation|sync_mutation_guard" web/src composeApp/src \
  --glob '!*.test.*' --glob '!*Test.kt'
```

No client calls them — the migration moves them to `notelikeus_internal` and recreates all 16
callers inside the database, so only SQL callers are affected, and the pgTAP suite covers those.

**Dispatch**: Actions → `supabase` → *Run workflow* → branch `main` → `confirm_project_ref` = the
project ref.

**Verify**, from the workflow's own output:

- `preflight` proves the database password authenticates before anything is applied.
- `database-tests` resets a local database from the migrations and runs the pgTAP suite, so the
  migrations that are about to be pushed are the ones that just passed 350 assertions.
- `deploy` prints `migration list --linked` before **and** after `db push`; read the second table
  and confirm all 34 migrations show as applied on the remote.

**The five migrations**, in the order they apply:

| Migration | What it changes |
|---|---|
| `20261012000000_provisional_attachment_sweep.sql` | F-09: reclaims provisional attachment rows that no note commit will ever promote, which otherwise count against the owner's quota forever. |
| `20261012010000_unreferenced_attachment_report.sql` | Read-only: reports R2 objects no attachment row references, so the F-08 leak can be measured before anyone decides on a deleting sweep. |
| `20261018000000_account_wipe_generation.sql` | Per-account `wipe_generation`, enforced by triggers on `notes` and `note_tombstones`, published by the snapshot and pull RPCs, bumped by `delete_all_user_cloud_data()`. Adds 15- and 16-argument `apply_note_change`, 3-argument `apply_note_delete` and 15-argument `restore_note`; every older arity is retained. |
| `20261019000000_internal_guard_schema.sql` | Moves the sync guard helpers to the non-exposed `notelikeus_internal` schema, recreates the 17 in-database callers, and drops the three public wrappers. |
| `20261020000000_restore_revision_fence.sql` | Fences `restore_note` on the revision the caller presents (F-14). |

`supabase db lint --local --level warning --fail-on error` exits 0 on all of this, with three
advisories and no errors.

---

## 5. Step 3 — Attachments Worker

`workflow_dispatch` only, so this is always an explicit act. It writes `wrangler.toml` (gitignored —
it carries the account id and the environment's bucket), deploys, and then sets the secrets in a
separate step because `wrangler-action` uploads any secrets present at deploy time.

**Verify**: `npm --prefix workers/attachments test` (137 tests) and
`node --test scripts/ops/attachments-worker-deploy.test.mjs` (7 tests) pass on the branch. After the
deploy, the end-to-end check is: upload an image, delete the note, confirm the object is purged.

---

## 6. Step 4 — Web

`.github/workflows/cloudflare-pages.yml` runs on push to `main` as well as on pull requests, so
merging is what ships the web client. `npm run pages:verify` (and its security-header check) passes
on the branch's build.

The web client is the one that surfaces both new behaviours, so it is the fastest way to see them:
the wipe notice on an account that was wiped elsewhere, and the "changed on another device" message
when a restore is refused.

---

## 7. Step 5 — Android and Windows

**Android**: push a `v*` tag. `android.yml` triggers on tags as well as branches — the comment in
the workflow notes that a `branches` filter alone would make the tag conditions dead code, which was
a real bug — and builds `assembleRelease` and `bundleRelease` signed with the four signing secrets.
Ship it as a staged rollout.

**Windows**: packaging is manual by design. Dispatch `desktop.yml` from `main` with the release tag
as its input; it builds the installer, smoke-tests it, and attaches the MSI to the **already
existing** release. An asset of the same name is a hard stop unless the workflow was explicitly told
to replace it, so a re-cut needs the old asset removed first.

The Kotlin clients are what make two of the audit's fixes visible: the wipe notice with its
"Upload this device's notes" action, and the F-14 refusal. Until they ship, a wiped account's
Android or Windows device reports the wipe and uploads nothing, which is safe but inert.

---

## 8. Post-deploy verification

Four checks, each proving one of the audit's fixes is live rather than merely deployed. All four
need two clients signed into the same account.

1. **The wipe protocol.** Delete all cloud data on client A. On client B, let a sync run: B must
   report that the cloud data was deleted, upload nothing, and delete nothing locally. B's notes must
   still be there. Then choose "Upload my notes again" on B and confirm they reappear on A.
2. **The F-14 fence.** Delete a note on A, sync. Edit the same note on B, sync. Restore the deleted
   note on A: it must be refused with "changed on another device", and B's version must survive.
   Restore a note that nobody else edited: it must apply.
3. **The delete message (F-05).** Delete a note while offline on A. On reconnect, A must report that
   the delete could not be confirmed rather than silently claiming success.
4. **Attachments.** Upload an image on A. Confirm the Worker serves it. Delete the note and confirm
   the object is purged.

---

## 9. Rollback

| Step | Rollback |
|---|---|
| Migrations | **There is no down migration.** Each one is additive, so the forward path is the fix: write a new migration that reverses the specific change. Reverting the code without a migration leaves the schema ahead of the clients, which is the supported direction. |
| Attachments Worker | `wrangler rollback` to the previous version, or redeploy the previous commit's Worker. |
| Web | Roll back the Cloudflare Pages deployment to the previous build. |
| Android | Halt the staged rollout. |
| Windows | Replace or withdraw the release asset. |

The one migration with a real blast radius is `20261019000000` (it drops three public functions).
The check in §4 is what establishes that nothing outside the database called them. If that check
ever comes back non-empty, stop and do not dispatch.

---

## 10. What this runbook does not claim

- **That the secrets exist and are valid.** The audit read the names out of the workflows; it cannot
  read the repository's secret store. Every job fails with a named variable if one is missing.
- **That the remote database is in the state the migrations expect.** The deployment table in
  `AUDIT_REPORT.md` §13.1 is a read of the GitHub API, not of the hosted database: no credential for
  the remote was available, so remote state was never queried from here.
- **That the deployed clients behave as the tests say.** The instrumented suites pass on an emulator
  and the app was driven on a real device, but the two-client checks in §8 have not been performed
  end to end, because that needs two signed-in clients and an account.
