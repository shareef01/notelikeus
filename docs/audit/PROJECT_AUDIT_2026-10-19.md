# Notelikeus project audit, 2026-10-19

| | |
|---|---|
| Commit audited | `377af16f` (`main`, merge of PR #343). `main` is now `65cf9c48` (release 2.2.1), which adds only version bumps and the changelog on top. |
| Scope | The commits since the 2026-10-17 audit: Turnstile CAPTCHA on every client (`486c40a4`, `0509dcc1`, `eabc0e44`, `e7e97b60`), the password sign-in hook (`43be46f5`), and the 2.2.0/2.2.1 UI polish. Plus the items the earlier audits left open, and the live GitHub security alerts, CI and settings. |
| Builds on | `AUDIT_REPORT.md` (F-, FU-), `docs/audit/FINDINGS.md` (AU-), `PROJECT_AUDIT_2026-10-15.md` (N-) and `PROJECT_AUDIT_2026-10-17.md` (S-). Those are not repeated. |
| Method | Source reading, the repo's own test commands, and read-only `gh` queries. No code, setting or deploy was changed. |

## Summary

The code is in good shape: every suite passes, dependency scans are clean, RLS is on for every table, and no `SECURITY DEFINER` function is left. The CAPTCHA return flow is careful: the host is fixed, the state is random and checked, and the Android WebView is held to its origin.

The open risks are not in the code:

1. **A Google OAuth client secret is in public git history** (R-01). GitHub's secret scanning has 4 open alerts. The 10-17 audit said the secret "was in a generated source, not in git", and that is wrong.
2. **CAPTCHA is still off on the hosted project** (R-02, the rest of S-02). Anyone can still use up the project's 30 sign-in emails an hour.
3. **7 CodeQL alerts are open**, all of them false positives on inspection (R-03). They hide real alerts.

## Findings

Severity follows the earlier audits: P0 irreversible loss or cross-account breach, P1 high-impact, P2 material, P3 hardening.

### R-01 (P1 until the client is deleted, Confirmed): Google OAuth client secret and API keys in public git history

- **Where:** secret scanning alerts #1 to #4, all `publicly_leaked = true`, validity unknown:
  - #4 Google OAuth Client Secret and #3 Client ID: `composeApp/src/desktopMain/kotlin/com/aus/notelikeus/di/PlatformModule.kt:106-107` in an older commit.
  - #2 Google API Key: `androidApp/google-services.json:31`, `app/google-services.json:31`, `PlatformModule.kt:108`.
  - #1 Google API Key: `web/.env.example:4`.
- **Current tree:** clean. No `google-services.json`, `.env`, keystore or key pattern (`AIza…`, `GOCSPX-`, `sb_secret_`, private keys) is tracked.
- **Effect:** the desktop OAuth client is unused since the move to email codes (D29), but while it exists anyone can use its secret to run consent screens under the app's name. Firebase API keys are meant to be public, but only with API and app restrictions.
- **Fix (owner, in consoles):** delete the desktop OAuth client in Google Cloud, which the 10-17 audit already asked for. Delete or restrict the API keys, or delete the Firebase project if nothing uses it. Then close the four alerts as "revoked". Rewriting history is not needed once the credentials are dead.
- **Correction:** `PROJECT_AUDIT_2026-10-17.md`, "Removed Google surface", says the secret "was in a generated source, not in git". The alert shows it was committed.
- **Status:** Done 2026-10-21. The owner deleted the desktop OAuth client and both Firebase API keys in Google Cloud project `notelikeus`, and removed the unused `SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID` and `_SECRET` repository secrets. Alerts #1 to #4 are resolved as revoked.

### R-02 (P2, Confirmed): hosted CAPTCHA is still not switched on

- **Where:** the last Supabase Auth Config run (2026-10-17 00:10, before the 2.2.0 release) logged `ENABLE_CAPTCHA: false` and `rate_limit_email_sent: 30`. No run since.
- **Effect:** S-02 is still live: about 30 code requests an hour from a few IPs stop every user from signing in until the hour rolls over. S-03's guessing window is shorter (10 minutes) but has no CAPTCHA.
- **Fix (owner):** 2.2.0 and later send a token on every client. Once enough installs have updated, run `supabase-auth.yml` with `enable_captcha` ticked. Installs older than 2.2.0 cannot request codes after that.

### R-03 (P3, Confirmed): 7 open CodeQL alerts, all false positives

| # | Rule | Where | Why it is not a bug |
|---|---|---|---|
| 7 | `java/android/implicit-pendingintents` (error) | `ReminderReceiver.kt:52` | The intent is explicit (`widgetMainActivityIntent`) and the `PendingIntent` is `FLAG_IMMUTABLE`. |
| 12 | `java/android/websettings-allow-content-access` | `AndroidCaptchaTokenProvider.kt:82` | `allowContentAccess = false` is set two lines below. |
| 2 | `js/missing-origin-check` | `web/src/sw.ts:159` | The handler checks the client's origin against `self.location.origin` first. |
| 1, 10 | `js/xss-through-dom` | `AttachmentImageStrip.tsx:66`, `NoteCardThumbnail.tsx:87` | `src` is a `blob:` URL the app created; React sets it as an attribute. |
| 3, 4 | `js/file-system-race` | `pinAttachmentsCspPlugin.ts:42`, `write-kotlin-staging-properties.mjs:129` | Build and ops scripts on the developer's machine, not shipped code. |

- **Fix (owner):** dismiss each one with its reason ("false positive" or "used in tests"), so a new alert stands out.
- **Status:** Done 2026-10-19. All 7 were dismissed as "false positive", each with the reason above in its comment. No code-scanning alerts are open.

### R-04 (P3, Confirmed): any local request can fail the desktop security check

- **Where:** `LoopbackCaptchaTokenProvider.handle` marks the listener as answered before it checks `state`.
- **Effect:** any `GET /` to the port before the browser's redirect fails the check: another local process, or a web page probing loopback ports. The user sees "didn't go through" and has to try again. The attacker cannot get a token in, since that needs the state. It is a nuisance, not a breach.
- **Fix:** answer a wrong state with 400 without ending the check, and keep the single use for the first matching request. If the concern is someone guessing the state, a cap of a few bad attempts is enough, because the state has 192 bits.
- **Status:** Fixed in #346. A wrong or missing state gets a 400 and the listener keeps waiting; the first matching request completes the check, and 5 bad attempts fail it.

### R-05 (P3, Confirmed): the service worker denylist also skips some query strings

- **Where:** `NAVIGATION_DENYLIST` in `web/src/lib/hosting/navigationDenylist.ts`. The "last segment with a dot" pattern runs on `pathname + search`, so a `/` inside the query counts as a segment. For example, `/?u=https://x.example/a.b` is denied.
- **Effect:** such a navigation goes to the network instead of the app shell, so it fails offline. Today the app has no share target or other entry point that puts URLs in the query, so nothing reaches it.
- **Fix:** test the dot pattern against `url.pathname` only, or stop it at `?`.
- **Status:** Fixed in #346. The pattern now stops at the first `?`, so only the pathname counts.

## Still open from earlier audits

| Item | State |
|---|---|
| F-05 web remote delete with no base revision | Design decision pending (rule DEL-6). |
| N-04 one environment | Partly fixed. A separate staging Supabase project is an owner decision. |
| S-02 / S-03 | Code done. Hosted switch-on is R-02. |
| S-04 | `enforce_admins` is still off, so an admin push can skip the required checks. |
| FU-5 `security-crypto` deprecated, FU-6 web session in `localStorage` | Accepted. |
| Hosted Google provider off | Not visible from the repo; check it with R-01. |

## Checked and found sound

- **CAPTCHA return, web page** (`captchaReturn.ts`, `captcha.ts`). The destination host is built from a fixed scheme or `127.0.0.1`, never from the query. The port is limited to 1024 to 65535, and the state to 16 to 128 URL-safe characters. Without a valid request the page shows a message and never renders the widget.
- **CAPTCHA return, Android** (`CaptchaActivity`). It is not exported. JavaScript runs only while the top frame is on the page's origin. Only `challenges.cloudflare.com` loads in a subframe. The return scheme is accepted only from the main frame, and only with this request's state. File and content access are off, and so are multiple windows. Back or dismiss cancels the check, and rotation reloads the same request.
- **CAPTCHA return, desktop.** The listener binds to `127.0.0.1` on a port the OS picks. It accepts one use and stops on timeout. The response has `default-src 'none'`, `no-store` and `no-referrer`.
- **Token use.** Web resets the widget after every request, accepted or not. Kotlin gets a fresh token for each `requestEmailOtp`.
- **`refuse_password_sign_in`** has `search_path = ''`, reads no tables, passes through everything except `password`, and is executable only by `supabase_auth_admin`.
- **Database.** All five tables have RLS enabled. The one `SECURITY DEFINER` function, `firebase_uid_proven_elsewhere`, was dropped in `20260905000000`.
- **CSP** (`web/public/_headers`). Only `challenges.cloudflare.com` was added, to `script-src` and `frame-src`. `frame-ancestors 'none'` is kept.
- **Android release build.** It is minified and resource-shrunk, has `allowBackup="false"`, and targets SDK 37. `CaptchaActivity` and the reminder receivers are not exported.
- **Live settings.** `main` blocks force pushes and requires `Web build` and `Web e2e`. CI on `65cf9c48` is green for Web, Android, Desktop, CodeQL and Pages. There are 0 open Dependabot alerts.

## Tests run here

| Command | Result |
|---|---|
| `web: tsc -b --noEmit` | pass |
| `web: oxlint` | warnings only (test and bench scripts) |
| `web: npx vitest run` | 134 files, **983 / 983** pass |
| `web, workers/attachments: npm audit --omit=dev` | 0 vulnerabilities |
| `workers/attachments: npx vitest run`, `tsc --noEmit` | **139 / 139** pass; typecheck clean |
| Kotlin `:composeApp:desktopTest`, `detekt` | **871 / 871** pass; detekt clean (against its baseline) |
| `:androidApp:lintDebug` | 1 warning (`RedundantLabel`), no errors |
| pgTAP, Playwright, Android instrumented | Not run here (no Docker or device). CI covers pgTAP and Playwright. |

## Suggested order

1. ~~**R-01:** delete the OAuth client and restrict or delete the keys, then close the alerts.~~ Done 2026-10-21.
2. **R-02:** switch on hosted CAPTCHA once 2.2.x adoption is high enough.
3. ~~**R-03:** dismiss the CodeQL false positives.~~ Done 2026-10-19.
4. ~~**R-04 and R-05:** small code fixes, whenever convenient.~~ Done in #346.
