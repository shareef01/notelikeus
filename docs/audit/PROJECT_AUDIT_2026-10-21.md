# Notelikeus project audit, 2026-10-21

| | |
|---|---|
| Commit audited | `92a0aeb1` (`main`, merge of PR #357, release 2.3.0) |
| Scope | The code changes since the 2026-10-19 audit (`377af16f`): publishable-key support (`0a56ba24`), the keyboard, focus and Undo-toast fixes (`595d5520`, `6440a9db`), the web list performance work (`87aeab7e`), the Compose toolbar selection fix (`cb11b6bb`), and the sync coordinator test leak (`09d4379c`). Plus the live GitHub state and the items earlier audits left open. |
| Builds on | `AUDIT_REPORT.md` (F-, FU-), `docs/audit/FINDINGS.md` (AU-), `PROJECT_AUDIT_2026-10-15.md` (N-), `-10-17` (S-) and `-10-19` (R-). Those are not repeated. |
| Method | Source reading, the repo's own test commands, and read-only `gh` queries. No code, setting or deploy was changed. |

## Summary

The code is in good shape. Every suite that runs here passes, there are no open secret-scanning, code-scanning or Dependabot alerts, and CI on `92a0aeb1` is green for every workflow. No migration changed since the last audit.

The one finding worth acting on soon is T-01: the web Pages builds inline whatever `VITE_SUPABASE_ANON_KEY` holds into a public bundle, without the secret-key check that the Kotlin release build, the Worker deploy and the staging scripts all have. The other three are small.

## Findings

Severity follows the earlier audits: P0 irreversible loss or cross-account breach, P1 high-impact, P2 material, P3 hardening.

### T-01 (P2, Confirmed): a secret key in the Pages secret would be published in the web bundle

- **Where:** `.github/workflows/cloudflare-pages.yml`, the preview build (about line 91) and the production build (about line 178). Both only check that `VITE_SUPABASE_ANON_KEY` is non-empty. `web/package.json` `build` and `web/scripts/verifyPagesArtifacts.mjs` do not check the key either.
- **Compare:** `verifyReleaseBackendConfig` in `composeApp/build.gradle.kts`, the `case` in `attachments-worker.yml`, and `clientKeyProblem` in `scripts/ops/*.mjs` all refuse an `sb_secret_…` key before anything ships.
- **Effect:** Vite inlines `VITE_*` values into `dist/assets/*.js`, and that is deployed to a public URL. `bootstrap.ts` then refuses to *use* the key (`isBrowserSafeSupabaseKey`), but by then it is already readable by anyone. A secret key in the wrong GitHub secret is a realistic slip: `scripts/ops/setup-staging.sh` notes that a secret named `SUPABASE_ANON_KEY` "is often a new sb_secret_ key", and the D31 rollout has just swapped this secret by hand.
- **Fix:** refuse the deploy before it happens. The smallest place is `verifyPagesArtifacts.mjs --deployable`: fail when any file in `dist/` contains `sb_secret_`, or a JWT whose payload has `"role":"service_role"`. Scanning the output also catches a secret that reaches the bundle by another route. Alternatively, run `clientKeyProblem` from `scripts/ops/write-kotlin-staging-properties.mjs` on the env value in both workflow steps.
- **Status:** Fixed in #358. `verifyPagesArtifacts.mjs --deployable` scans every text file in `web/dist` (`web/scripts/bundleSecrets.mjs`) and fails on an `sb_secret_` key or a `service_role` JWT, printing the file and kind but never the value. The staging Pages job, which uploaded without running the verifier at all, now runs it too. Checked by building with each key kind: the secret key and the `service_role` JWT are refused, and the anon JWT and the publishable key pass.

### T-02 (P3, Confirmed): a routine Supabase Auth Config run drops the email limit to 30

- **Where:** `.github/workflows/supabase-auth.yml`. With SMTP secrets set, the patch always includes `rate_limit_email_sent: 30`, and raises it only when `enable_captcha` is ticked. `enable_captcha` defaults to `false`.
- **Effect:** hosted CAPTCHA has been on since 2026-10-21 (R-02). The next person who reruns the workflow for a template change, without knowing to tick the box, cuts the limit from 100 to 30 an hour. CAPTCHA stays on, so this is a capacity regression, not a reopening of S-02.
- **Fix:** now that CAPTCHA is on, default `enable_captcha` to `true`, or leave `rate_limit_email_sent` out of the patch when the box is unticked so the hosted value stays as it is. The header comment's reason for opt-in ("must not switch it on") no longer applies.
- **Status:** Fixed in #359. The patch no longer sets `rate_limit_email_sent` unless `enable_captcha` is ticked, so an unticked run leaves both the CAPTCHA setting and the limit as they are. `enable_captcha` stays opt-in.

### T-03 (P3, Confirmed): two of the four key checks accept a `service_role` JWT

- **Where:** `clientKeyProblem` in `scripts/ops/write-kotlin-staging-properties.mjs` and the `case` in `attachments-worker.yml` accept any `eyJ…` value. The web check (`supabaseClientKeyKind`) and the Gradle check decode the payload and refuse `role: service_role`. The `.mjs` doc comment says it "Mirrors `isBrowserSafeSupabaseKey`", which is no longer true.
- **Effect:** the legacy `service_role` JWT pasted into `web/.env.staging` would pass `deploy-staging-pages.mjs` and be written into the Kotlin `local.properties`. In the Worker the impact is small, since `auth.ts` always sends the caller's own bearer. Once legacy keys are disabled (D31 step 6) the JWT is dead anyway.
- **Fix:** add the same payload check to the `.mjs` helper (and reuse it from T-01's fix). Optional for the Worker.
- **Status:** Fixed in #359. `clientKeyProblem` and the Worker deploy check both decode the payload and refuse `role: service_role`, so all four checks now agree. T-01's bundle scan has its own copy of the check, since it runs on the output rather than the env.

### T-04 (P3, Confirmed): the web grid's handler cache never drops a note

- **Where:** `handlerCache` in `web/src/components/notes/NoteStaggeredGrid.tsx`.
- **Effect:** one small object per note id ever shown in that grid instance, kept until the grid unmounts. Deleted and filtered-out notes stay. Harmless at today's sizes; it grows over a long session with many imports or deletes.
- **Fix:** prune ids not in `notes` in an effect keyed on `notes`, or key the cache with a `WeakMap` on something that dies with the note.
- **Status:** Fixed in #359. An effect keyed on `notes` drops the handlers of every note that left the list.

### T-05 (P2, Confirmed, found after the audit): Android drops a just-picked image

- **Where:** `reconcileStagedAttachments` in `AttachmentSyncService.kt`, run by every download.
- **Effect:** found on a Pixel 7 while checking sync after the legacy keys were disabled. Returning from the photo picker resumes the app, and `autoSyncOnForeground()` starts a download (at most once per 30 s). Its reconciliation released every staged image no saved note referenced, including the one just picked, because the editor writes the note a second after staging. The next push then dropped the image ("staged bytes are gone"). Present since v2.0.0, and not caused by the key change.
- **Status:** Fixed in #360, shipped in 2.3.1. Unreferenced staged bytes are released only once they are older than an hour.

## Owner items (not code)

| Item | State |
|---|---|
| D31 step 6: disable the legacy JWT keys | **Done 2026-10-21**, ahead of the planned 2026-11-04. The legacy `service_role` JWT exposed on 2026-09-04 is dead. Checked the same night: the publishable key works, a legacy-shaped JWT gets 401, and Android 2.3.0 synced a note and uploaded and deleted an image. Installs older than 2.3.0 can no longer sync or sign in. |
| Probe user `nlu-audit-probe-…@notelikeus.dev` | **Done:** a search under Authentication → Users found no such user on 2026-10-21. |
| S-04 `enforce_admins` | **Done 2026-10-21:** on for `main`, so every push to `main`, an admin's included, needs `Web build` and `Web e2e`. |
| Secret scanning extras | **Dropped:** not offered for this repository. The API accepted the change and left both off, and the settings page shows only push protection under Secret Protection. Secret scanning and push protection are on. |
| F-05, N-04 | Unchanged: F-05 is a pending design decision, N-04 a separate staging project. |

## Checked and found sound

- **Publishable-key support.** A publishable key goes in `apikey` only; `clientKeyBearer` drops the `Authorization` header for it, while RPCs still send the user's own bearer. The Worker forwards the caller's `Authorization` and uses the client key only as `apikey`. The sweep uses the `sb_secret_` key in both headers, which the 2026-10-21 sweep confirmed works. `sb_publishable_` with nothing after it is refused everywhere.
- **Toasts.** The pause on hover and focus keeps the remaining time across pauses. Ctrl/Cmd+Z runs Undo only outside text fields and not while a dialog is open, so it never steals the editor's own undo. Both live regions stay mounted.
- **Keyboard reorder and focus return** in the web grid: bounds are checked, and focus is moved only when the card that held it has gone.
- **Formatting shortcuts.** Ctrl/Cmd+B and +I ignore Shift and Alt on both clients. The link dialog still runs URLs through `toSafeHref`/`SafeHref`, so `javascript:` links are rejected.
- **`BlurSelectionKeeper`.** It only refuses a change that leaves the text the same and collapses a selection to its end while unfocused, so it cannot drop typed text.
- **Undo after trashing from the web editor.** `useNoteEditor.trashNote` sets only `isTrashed`, so the snapshot read afterwards still holds the pin and the archive, and Undo puts the note back where it was.
- **`useDeferredValue` on filters.** Only the list trails; `filters` itself, and so the search box, update at once.
- **Live GitHub state.** 0 open secret-scanning, code-scanning and Dependabot alerts. Push protection is on. `main` blocks force pushes. No open pull requests.

## Tests run here

| Command | Result |
|---|---|
| `web: tsc -b --noEmit` | pass |
| `web: npx vitest run` | **1004 / 1004** pass |
| `web: oxlint` | 3 warnings (`no-array-sort`, 2× `no-map-spread`), no errors |
| `web, workers/attachments: npm audit --omit=dev` | 0 vulnerabilities |
| `workers/attachments: npx vitest run`, `tsc --noEmit` | **139 / 139** pass; typecheck clean |
| Kotlin `:composeApp:desktopTest`, `detekt`, `:androidApp:lintDebug` | **885 / 885** pass; detekt clean (against its baseline); lint 1 warning (`RedundantLabel`), no errors |
| pgTAP, Playwright, Android instrumented | Not run here (no Docker or device). CI covers pgTAP and Playwright, green on `92a0aeb1`. |

## Suggested order

1. **T-01:** add the secret scan to `verifyPagesArtifacts.mjs --deployable`. Small, and it guards the next hand edit of the secret.
2. **T-02:** flip the `enable_captcha` default, or stop writing the limit when it is unticked.
3. **T-03, T-04:** whenever convenient.
4. **Owner:** disable the legacy keys from about 2026-11-04, delete the probe user, and consider `enforce_admins` and the two secret-scanning extras.

All of the above is done (T-01 to T-04 in #358 and #359, the owner items on 2026-10-21), and T-05 is fixed in #360.
