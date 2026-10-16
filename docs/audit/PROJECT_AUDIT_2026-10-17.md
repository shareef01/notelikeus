# Notelikeus project audit, 2026-10-17

| | |
|---|---|
| Commit audited | `78f547aa` (`main`, merge of PR #331, release 2.1.2) |
| Scope | The eight commits since the 2026-10-15 audit: email one-time-code sign-in on every client (`638d150b`), the hosted auth-config job (`fe47873c`), pull on reconnect (`9a5a89f5`), the production deploy gate (`0a9fc6cc`), and the a11y and test commits. Plus the items the earlier audits left open, and the live GitHub settings those changes depend on. |
| Builds on | `AUDIT_REPORT.md` (F-, FU-), `docs/audit/FINDINGS.md` (AU-) and `docs/audit/PROJECT_AUDIT_2026-10-15.md` (N-01 to N-10). Those are not repeated. |
| Method | Source reading, the repo's own test commands, and read-only `gh` queries. No code, setting or deploy was changed. |

## Summary

The sync engine, attachments and deploy pipeline are in the state the 10-15 audit left them: nothing in the new commits weakens them. The new risk is in **sign-in**. Moving every client to an emailed 6-digit code made the Supabase Auth settings part of the security boundary, and the hosted project still has defaults that were tuned for a different sign-in method.

1. **Password sign-up is still open on the hosted project** (S-01). The clients hide it outside debug builds, but `/auth/v1/signup` takes any email and password from anyone holding the public anon key. Whether an attacker can pre-register a victim's address and later share that account depends on one hosted setting nobody has checked.
2. **The whole project can send 30 sign-in emails an hour** (S-02). That limit is project-wide, so anyone can stop every user from signing in by requesting about 30 codes an hour.
3. **A sign-in code stays valid for an hour, with no CAPTCHA** (S-03).
4. **The production approval gate added in `0a9fc6cc` does nothing yet** (S-04): the `production` environment has no required reviewers and `main` is unprotected.

## Findings

Severity and confidence follow the 10-15 audit: P0 irreversible loss or cross-account breach, P1 high-impact, P2 material, P3 hardening. **Confirmed** means traced end to end. **Probable** means one step needs a runtime check.

### S-01 (P1 if the check fails, Probable): password sign-up on the hosted project can pre-claim someone else's email

- **Status:** Partly fixed. `supabase-auth.yml` now sets `mailer_autoconfirm: false` and prints it back, so a password sign-up stays unconfirmed. Still open: the hosted value must be read once (the check below), and a password set before the owner confirms by code may still work afterwards. Blocking password sign-in on the server is the remaining fix.

- **Where:** `supabase/config.toml:175,221` (`enable_signup = true`, email provider on). `.github/workflows/supabase-auth.yml` patches only the templates, OTP length, SMTP and email limit. It never sets `mailer_autoconfirm`, and its readback does not print it. The clients gate the password form (`SignInGate.kt:92` on `AppConfig.isDebug`, `testLoginFlag.ts` on `DEV`/`VITE_E2E`), but that is UI only. GoTrue still serves `POST /auth/v1/signup` and `grant_type=password` to any caller with the anon key, which ships in every client.
- **Sequence:** An attacker calls `/auth/v1/signup` with `victim@example.com` and a password they choose. Later the victim signs in with a code. GoTrue looks the user up by email (D29 relies on that for former Google users), so the victim lands in the account the attacker created.
  - If the hosted project **auto-confirms** emails (`mailer_autoconfirm = true`, the local value), the attacker's account is live immediately, and the attacker can sign in with the password and read everything the victim syncs. That is a cross-account read: P0-class.
  - If confirmations are **on** (the hosted default), the attacker's row stays unconfirmed. The victim's code confirms it. Whether the attacker's password still works after that is the one step to verify.
- **Check (read-only, 5 minutes, against your own test address):**
  1. In the dashboard, see Authentication > Providers > Email > "Confirm email". Off means the first case applies.
  2. If it is on: sign up with password for an address you control, do not confirm it, sign in to the web app with a code for that address, then try `grant_type=password` with the first password.
- **Fix:** password sign-in is not a product feature (D29), so refuse it on the server rather than hide it in the UI. Use a [Password Verification Attempt auth hook](https://supabase.com/docs/guides/auth/auth-hooks) that rejects every attempt on the hosted project. The local stack keeps passwords for tests and e2e. Also add `mailer_autoconfirm: false` to the `supabase-auth.yml` patch and to its readback.
- **Test:** a pgTAP or e2e case that `grant_type=password` is refused against a stack with the hook enabled.

### S-02 (P2, Confirmed): 30 emails an hour for the whole project lets anyone block all sign-ins

- **Status:** Open. CAPTCHA is a separate piece of work across three clients.

- **Where:** `supabase-auth.yml` sets `rate_limit_email_sent: 30` (applied in run 38921738705, 2026-10-16 15:10). That limit counts every email the project sends, not per user or per IP. Every sign-in now costs one email (D29).
- **Effect:** about 30 `/auth/v1/otp` calls an hour for random addresses, spread across a few IPs to stay under `sign_in_sign_ups` (30 per 5 minutes per IP), exhaust the hour's budget. Real users then get "Too many attempts" (`describeEmailCodeFailure`, `authErrors.ts`) until the hour rolls over. It also lets a script make the project send mail to arbitrary addresses, which can hurt the SMTP sender's reputation.
- **Fix:** CAPTCHA on the OTP request (S-03), then raise the limit to what the SMTP plan allows. Cloudflare Turnstile fits, since the web is already on Pages. Native clients need either a Turnstile widget in a WebView or a small Worker endpoint that fronts `/otp`.

### S-03 (P2, Confirmed in config): a 6-digit code is accepted for an hour, and nothing slows distributed guessing

- **Status:** Partly fixed. `supabase-auth.yml` sets `mailer_otp_exp: 600` and `config.toml` matches. It takes effect on the next run of the Supabase Auth Config workflow. CAPTCHA is still open (S-02).

- **Where:** `config.toml:232-234` (`otp_length = 6`, `otp_expiry = 3600`). The hosted job sets the length but not `mailer_otp_exp`, so hosted runs the default of 3600 s. `token_verifications` is 30 per 5 minutes **per IP**, with no per-account cap.
- **Effect:** one IP gets 360 guesses an hour against 10⁶ codes. An attacker with many IPs who knows the victim's address can request a code, then guess for an hour. Each request replaces the code, but also restarts the hour.
- **Fix:** set `mailer_otp_exp: 600` in the patch (10 minutes is plenty to read an email). Add CAPTCHA (S-02). Optionally go to 8 digits; `EMAIL_CODE_LENGTH` and `SignInGate` must change with it.

### S-04 (P3, Confirmed): the production approval gate is not switched on

- **Status:** Open. These are owner settings in GitHub.

- **Where:** `0a9fc6cc` moved the Pages production deploy into the `production` environment so that "required reviewers set on it make every deploy wait". `gh api …/environments/production` returns `protection_rules: []` and no branch policy. `main` has no branch protection (HTTP 404), so a direct or force push to `main` that passes Web CI goes to production with no approval.
- **Fix (owner settings, not code):** add yourself as a required reviewer on `production`, restrict it to `main`, and protect `main` (at minimum, block force pushes and require Web CI). The workflows already honor both.

### S-05 (P3, Confirmed, carried from N-10): a `note_deleted` refusal is still silent on Android and desktop

- **Status:** Fixed. A download that keeps a refused or deleted-elsewhere note as a copy now says so on Android and desktop (`takeEditsKeptFromDeletedNotes`, `edits_kept_from_deleted_notes`), tested in `NoteSyncEngineTest`.

- `SupabaseNoteTransport.putNotes` (`:220-250`) handles `status = conflict` with only an `AppLog.warn`, whatever the `error` is. The web handles `note_deleted` explicitly (`supabaseSyncEngine.ts:144`). Since 2.1.1 the purge keeps a never-synced row as a copy, so no text is lost. But the user is still not told that the cloud refused the note, which is what made N-10 hard to see.
- **Fix:** surface `note_deleted` as a sync event (the "deleted note came back" plumbing from AU-07 is the natural place).

## Checked and found sound

- **Kotlin code sign-in** (`SupabaseAuthApi.requestEmailOtp`, `verifyEmailOtp`). The JSON is escaped. Email and code are trimmed. Both Android and desktop run `onSignedIn()` after a code sign-in, so account isolation (`isolateIfAccountChanged`) and any owed cloud wipe run as before. Desktop email sign-in, which previously skipped `onSignedIn()`, now runs it too. GoTrue errors are mapped to user-facing text without echoing the raw body.
- **Web code sign-in** (`EmailCodeSignIn.tsx`). There is a resend cooldown matching GoTrue's 60 s gap. Input is restricted to digits. Errors go through `formatAuthError`. The CSP lost the Google hosts (`web/public/_headers`), and the parity check was updated.
- **Removed Google surface.** The desktop loopback OAuth flow and its shipped client secret are gone (the secret was in a generated source, not in git). `googleid` and Credential Manager are out of the dependency graph and `verification-metadata.xml`. The local Google provider is off with `skip_nonce_check = false`. **Owner action:** turn the Google provider off in the hosted dashboard and delete the OAuth client in Google Cloud, if that has not been done. The repo cannot show either.
- **Pull on reconnect** (`CloudSyncController.observeConnectivity`). It is cancelled when the network drops, debounced 5 s, waits out an in-flight sync, and respects auto-sync and sign-in. A pull that lands before a queued upload is safe, because the download forks an unsynced edit into a conflicted copy.
- **Production deploy trigger** (`cloudflare-pages.yml`). It needs `workflow_run` success on a `push` to `main`, rebuilds that exact SHA, and skips if `main` has moved on. A fork PR cannot reach it.
- **`supabase-auth.yml`.** It runs only on `main`, needs the typed project ref, masks `smtp_pass` and the template bodies in logs, and deletes `patch.json` and `response.json`. Secrets go through `jq --arg`, not shell interpolation.

## Tests run here

| Command | Result |
|---|---|
| `web: npx vitest run` | 131 files, **944 / 944** pass |
| `web: tsc -b --noEmit` | pass |
| `web, workers/attachments: npm audit --omit=dev` | 0 vulnerabilities |
| `workers/attachments: npx vitest run` | **139 / 139** pass |
| Kotlin `:composeApp:desktopTest`, `detekt` | **838 / 838** pass; detekt clean (against its baseline) |
| pgTAP, Playwright, Android instrumented | Not run here (no Docker or device). The Desktop CI, CodeQL and Pages runs on `78f547aa` are green. |

## Suggested order

1. **S-01 check today.** It is a five-minute dashboard look. If "Confirm email" is off, turn it on now, then add the password hook.
2. **S-03 and S-02:** shorten the code lifetime in `supabase-auth.yml` (one line), then CAPTCHA, then raise the email limit.
3. **S-04:** the two GitHub settings.
4. **S-05** when convenient.
