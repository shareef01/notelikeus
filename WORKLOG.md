# Worklog

Running record of completed work sessions. Newest first. Granular history lives in git;
this file captures the *what and why* across a whole session.

## 2026-08-17/18 — Full audit, hardening, and the 1.0.1 release

### Audit (full repo, three passes: security, KMP code quality, web/CI)
No secret leaks (git history included); Firestore rules, CI pinning, and crypto key
management all verified sound. Findings below were all actioned or explicitly declined.

### Shipped
- **Android:** encrypted DB opens on a background thread at startup (`AppStartup` gate in
  `NotelikeusApp`/`MainActivity`; `warmUp()` in `DatabaseBuilder.kt`). The Keystore decrypt
  and first-run `sqlcipher_export` no longer run during first composition.
- **R8 enabled** for release builds with conservative keep rules
  (`androidApp/proguard-rules.pro`). Verified on-emulator end to end: launch, sign-in gate,
  editor, note persistence, cold restart of the encrypted DB.
- **God classes decomposed:** `MainViewModel` → `CloudSyncController` +
  `NoteActionsController` + `MainState` + pure `filterAndSortNotes`; `MainScreen` →
  `MainDrawerContent` + `MainDialogs` + `MainScaffold`. Public API unchanged.
  `DatabaseKeyManager.kt` split one-file-per-class.
- **Tests backfilled** for `FirebaseSessionManager`, `ReminderScheduler` (Robolectric),
  `DesktopTokenStore` (desktop suite).
- **Web:** Tailwind v3 → v4 (CSS-first `@theme`, `tw-animate-css`, `@tailwindcss/vite`;
  config JS deleted). Custom utilities verified present in built CSS; e2e green.
- **oxlint** added to web (`correctness` as errors) and wired into CI — first JS/TS linter
  the repo has had.
- **`npm run deploy` is self-gating** (lint + tests + build before `firebase deploy`).
  The user deploys via CLI; a separate GitHub deploy workflow was added then removed as
  redundant. Production deploy done — notelike.web.app on Tailwind v4.
- **CI fixes:** `firebase.yml` npm cache now keys on both lockfiles; coroutines prod/test
  version refs split; unused `play-services-auth` dropped; dev-dep npm audit back to
  0 vulnerabilities (uuid + @opentelemetry overrides).
- **Desktop:** swallowed exceptions in `DesktopTokenStore` now log via new `AppLog` (JUL).
- **v1.0.1 released** — changelog written, versions bumped (Android vc3, web, AppConfig
  actuals), tagged. Found and fixed dead tag triggers (`on.push.branches` alone never matches
  tag pushes — added `tags: ['v*']` to android.yml); new `release` job attaches unsigned
  APK/AAB to the GitHub Release after all gates pass. Verified green end to end.
- `web/README.md` rewritten as real docs (was a stale "Step 1–4" build log).

### Ops / config (outside the repo)
- **Desktop Google sign-in fixed:** root cause was a rotated OAuth client secret — the pair
  was dead, verified directly against Google's token endpoint (`invalid_client`). New secret
  in `local.properties`; live-verified sign-in on the Windows app. ⚠ The compiled-in
  fallback in `DesktopSecrets.kt` still holds the old secret until the next build from this
  machine — fine locally, matters only for packaged MSI distribution.
- **Web API key referrer-restricted** via gcloud (`notelike.web.app`, firebaseapp.com,
  localhost/127.0.0.1 for dev+e2e). Verified from outside: no/evil referrer → 403, real
  referrer → 200. Android/desktop key intentionally left unrestricted (no restriction type
  can identify a desktop app; rules are owner-only; budget alert is the accepted backstop).
- **Sideloaded** debug 1.0.1 (vc3) onto the Pixel 7, in-place update, launch verified.

### Explicitly declined / deferred
- **App Check:** not wanted (personal/portfolio app, GitHub distribution). Consequence
  recorded: never enable enforcement in the Firebase console.
- **AGP flag removal** (`android.builtInKotlin`/`android.newDsl`): load-bearing with Kotlin
  2.1.21; rework must ride the next Kotlin upgrade, before AGP 10.
- **Signing CI release artifacts:** left unsigned by design for now; path documented in
  `signing.properties.example` if that changes.
- **Budget alert:** user action pending in GCP console (the one accepted mitigation for the
  unrestricted Android/desktop key).

### Session commits
`fe90bfc` → `1e7bc26` (13 commits), tag `v1.0.1`, all CI green, tree clean and pushed.
