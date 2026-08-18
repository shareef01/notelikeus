# Worklog

Running record of completed work sessions. Newest first. Granular history lives in git;
this file captures the *what and why* across a whole session.

## 2026-08-18 — Data-loss hunt, then the visual pass

29 commits, `d743f89`..`b927c9a`. 304 tests (was 245). All four CI workflows green.

### The through-line
Seven mechanisms were built, documented, wired end to end — and never connected. That is
this codebase's characteristic failure, and it is worth grepping for before anything else:

| mechanism | consequence |
|---|---|
| `markRestored` | declared, tested, never called — a failed restore let the next sync re-delete the note |
| `saveNoteAndAwait` | documented for exactly this hazard, called from nowhere — the "+ button is broken" bug |
| `EditorContentMaxWidth` | 720dp, defeated by `fillMaxSize()` preceding `widthIn` — never applied |
| `cloudSyncedNoteCount` | threaded to `ProfileSheet`, never assigned — "Last sync: 0 notes", always |
| `CloudSyncEvent.SignedOut` | render branch and two strings, nothing emitted it |
| timestamp bump on 4 write paths | the rule is in `updateNotePositions`' own comment; pin/restore/AppFunctions ignored it |
| AppFunctions constraints | `app_metadata.xml` promised limits nothing enforced |

### Six ways notes could be lost — all fixed, each with a test that fails against the old code
1. **Editor saves blanked `serverUpdatedAt`.** `EditorState` has no such field, so
   `buildNoteFromState` defaulted it to null and Room's `@Update` rewrote the row.
   `cloudWinsConflict` reads "remote has a stamp, local has none" as the cloud holding the only
   confirmed revision, so **the next sync reverted every edit to an already-synced note**. This
   was the one actually eating notes day to day. Fixed in the repository, not the UI: a null
   stamp on an existing row now means "caller does not know", not "clear it".
2. **The editor's initial load wiped what was typed.** `loadSettingsAndNote` ended with
   `_state.value = EditorState(...)` — a whole-state replacement reached through a suspending
   settings read. The body is auto-focused, so typing immediately is the normal path. Both
   branches now merge, with *per-field* tracking (one flag blanked the title instead).
3. **The formatter threw on ordinary edits.** `SmartTextProcessor` sliced
   `previous.selection.start until current.selection.start`, assuming the caret only moves
   forward. Paste at an earlier point, IME commit, autocomplete and undo all threw
   `StringIndexOutOfBoundsException` *inside* `onValueChange`, discarding the edit and letting
   autosave persist older text.
4. **Save on close raced its own cancellation** (Android back, desktop window close).
5. **A failed restore let the next sync re-delete the note.**
6. **Sign-out swallowed a failed cloud-data delete** and reported success.

### Recovery
Three notes had been blanked by #1. Recovered their bodies from the SQLite WAL, using frame byte
offsets to establish write order, and restored them through the app's own editor. A scan of the
WAL and the main DB's freed pages found no other lost content — but the WAL only spans recent
activity, so anything lost earlier is gone.

### Also fixed
- **Sync could hang forever.** No timeout anywhere in the path; `ProfileSheet` gates its controls
  on `status != Syncing`, so an offline sync disabled the only buttons that could retry it.
  Observed on a real device. Both platform managers now bound it at 90s.
- **`FLAG_SECURE`** now follows App Lock — the lock previously came off by pressing recents.
- **Note contrast measured, not thresholded.** `luminance() > 0.45` was not the crossover; white
  and `#121212` tie near 0.19, so mid-tones got white at ~2.3:1. Built-in colours are polarised
  and unaffected; imported notes carry arbitrary ARGB and were not.
- **Cloud restore now confirms** — it is a merge that can delete local notes, on one tap before.
- **Colour swatches named** for screen readers (all eight announced identically), 48dp targets.

### Visual pass — four of five were bugs, not taste
- **Notes baked the theme background into their own colour** (`#F0F0F0` on every theme but OLED,
  persisted and synced). Notes made on Forest were permanently white cards on near-black; on
  Light they were *invisible*, being exactly the background colour. 5 of 9 notes carried it.
  Neutralised on read, so every client is fixed without a migration.
- **Reading measure** for the single-column list; the editor's own 720dp cap made to work.
- **Filter chips fold away on scroll** — but never while a filter is active.
- **Settings split** into Sync / Data / Account, with Sign out last rather than second.
- **Drawer identity hues** were Tailwind `-400`s: 1.46–2.72:1 on light surfaces, all five under
  the 3:1 WCAG minimum for graphics. Now measured light/dark pairs.

### Corrections to this file and to the skills
- The 1.0.1 entry claimed "No secret leaks (git history included)". Wrong — the pre-rotation
  desktop OAuth secret is in `a00cde6` and `b374fc5`, reachable from `main`. Dead, so exposure is
  closed, but the claim would have sent the next audit past the check.
- `run-app`: `APPDATA` isolation **cannot be trusted**. It silently failed twice while verifying
  the + button fix, surfacing the real signed-in account. Verify visually before every action.

### Unresolved
- **`App.kt`'s snackbar guard** is verified by reading, not by execution. The Compose mechanism it
  relies on *is* demonstrated (`PendingEventConsumptionTest`), but composing `App.kt` needs the
  whole DI graph, and reaching a `Failure` event on a real account needs a sync that fails *with*
  connectivity — offline, the button that would trigger it is disabled.
- The `-journal` line in `quarantineDatabaseFiles` is symmetry with the success path, not a
  reproduced failure. An attempt to test it was removed: the journal does not survive the
  migrator's two open attempts, so the scenario could not be constructed.
- Colour dots in the filter row are still the loudest thing after the cards. Desaturating them
  would misrepresent the actual note colours, so it was left as a deliberate call for the owner.
- Never audited: web React components (the store/actions layer was, and is clean).

## 2026-08-17/18 — Full audit, hardening, and the 1.0.1 release

### Audit (full repo, three passes: security, KMP code quality, web/CI)
Firestore rules, CI pinning, and crypto key management all verified sound. Findings below
were all actioned or explicitly declined.

> **Correction (2026-08-18):** this section originally read "No secret leaks (git history
> included)". That is wrong. The pre-rotation desktop OAuth client secret is in git history
> at `a00cde6` and `b374fc5` (`PlatformModule.kt:107`, both 2026-08-07, both reachable from
> `main`). The exposure is closed — that pair was rotated during this session and verified
> dead against Google's token endpoint — but the blobs are still there, and the original
> wording would have led the next audit to skip the check. Nothing tracked in the working
> tree contains a live secret.

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
