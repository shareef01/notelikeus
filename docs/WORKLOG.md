# Worklog

Running record of completed work sessions. Newest first. Granular history lives in git;
this file captures the *what and why* across a whole session.

## 2026-08-30 — Audit closure (Session 6)

Final pass after F44 (Bold/Italic toolbar) fix. Re-ran automated suites and pushed the F44 build to the physical Pixel 7.

### Verification this session
- **Web unit tests:** 32 files, **271/271** passing
- **KMP unit + desktop tests:** BUILD SUCCESSFUL
- **Android debug APK:** rebuilt with F44 fixes; in-place install on Pixel 7 (`31071FDH2007WT`) — success
- **Windows desktop:** prior session confirmed `:composeApp:run` launches Notelikeus window
- **Regression test:** `EditorViewModelTest.applyBold before an existing note loads keeps the formatted text` — guards F44 Android `contentEdited` path

### Audit verdict
**Ready with blocked external/hardware checks.** F28–F44 fixed and validated in automated suites. No open reproducible defects in audited flows. Blocked: live Google OAuth, biometrics, widget placement automation, exhaustive theme/viewport matrix, PWA offline update cycle.

### Manual follow-up for user
1. **Web:** reload dev server and tap Bold/Italic on mobile-width editor — markers should stay visible in the textarea
2. **Android (Pixel 7):** open any note, select text, tap Bold — body should show `**…**` without reverting
3. **Commit** when ready — all changes remain uncommitted on `main`

---

## 2026-08-30 — Full UI/UX audit continuation (Session 3)

Continuation of the comprehensive UI/UX audit. Built on Session 2 fixes (F28–F34) and extended
coverage with additional web defects, accessibility repairs, and full automated validation.

### Issues Found & Fixed (F35–F41)
1. **Privacy dialog stale copy (F35):** `PrivacyPolicyDialog.tsx` still claimed Google sign-in was required on web. Rewrote body to match `PRIVACY_POLICY.md` (offline-first, optional sync).
2. **Editor focus traps (F36):** Mobile and fullscreen editor overlays lacked focus trap and dialog semantics. Added `overlayPanelRef` with `role="dialog"` / `aria-modal`.
3. **Labels screen accessibility (F37):** `LabelsScreen` had no focus trap, scroll lock, or Escape. Added shared trap + dialog semantics.
4. **Nested Escape in options sheet (F38):** Delete confirm Escape dismissed entire sheet. Added `closeOnEscape` option to `useFocusTrap` / `ResponsiveSheet`; regression tests in `useFocusTrap.test.ts`.
5. **Dead sort chip during search (F39):** Sort chip cycled but order stayed relevance-ranked. Disabled chip, shows "Relevance" while search active (D14).
6. **Error Retry contrast (F40):** Hard-coded white retry button invisible in light theme. Theme-token styling.
7. **A11y labels (F41):** Per-item checklist remove labels; aria-labels on link URL and label inputs.

### Validation Matrix
- **TypeScript typecheck:** 0 errors
- **Web lint:** 0 errors (warnings only, pre-existing)
- **Web unit tests:** 32 files, 270/270 passing (added `useFocusTrap.test.ts`)
- **Web production build:** success
- **Web sync tests:** 9/9 passing
- **Playwright E2E:** 4/4 passing
- **Firestore rules:** 37/37 passing
- **KMP unit + desktop tests:** BUILD SUCCESSFUL
- **Android debug + release APK:** BUILD SUCCESSFUL

### Blocked (environment)
- **Browser visual QA (Cursor browser MCP):** MCP server unavailable this session; web flows verified via Playwright E2E against built bundle.
- **Android emulator:** `emulator-5554` reported offline; no on-device runtime verification.
- **Windows desktop visual QA:** Not launched this session (prior sessions verified packaged app).

---

## 2026-08-30 — Audit continuation: focus-visible + runtime checks (Session 4)

### Issues Found & Fixed (F43)
1. **Missing keyboard focus rings (F43):** Drawer, editor chrome, filter chips, settings rows, auth tabs, and markdown preview toggle lacked visible `focus-visible` styling. Extracted shared `CHROME_FOCUS` to `web/src/lib/ui/focusStyles.ts` and applied across components. Markdown preview button gained `aria-label="Edit note body"`.

### Runtime verification this session
- **Windows desktop:** `:composeApp:run` launched successfully — two `java` processes with `MainWindowTitle = Notelikeus` confirmed.
- **Playwright E2E:** Re-ran after `npx playwright install chromium` — **4/4 passing**.
- **Web unit tests:** **270/270** passing after focus-style changes.
- **Android emulator:** Cold start attempted (`Medium_Phone_API_36.1`); prior `emulator-5554` remained offline. Device QA still blocked.

---

## 2026-08-30 — On-device Android verification (Session 5)

Physical **Pixel 7** (`31071FDH2007WT`, Android 16) connected after adb restart.

### Verified on device
- In-place debug **1.0.2** install (`install -r`) — success; `firstInstallTime` unchanged (Aug 17), library preserved.
- Cold launch — `MainActivity` top resumed, no FATAL in logcat.
- Notes list — search bar, Filters, Manual reorder handles, FAB, signed-in profile chip, note cards render.
- Read-only search — typed `test2`, list filtered (verified via screencap in temp; not committed — contains personal notes).
- **Instrumentation:** `:composeApp:connectedDebugAndroidTest` — **4/4 passed** on Pixel 7 (offline emulator skipped).

### Still not exercised on device (per PIXEL_QA_CHECKLIST / safety rules)
- Biometric app lock (do not fake)
- Google OAuth sign-in flow (do not fake)
- Sign-out (destructive to local session)
- Widget home-screen placement (automation unsolved)

---

## 2026-08-30 — Web React Component Layer Audit (Session 2)

Continuation of the UI/UX + functional audit. This session completed the one gap the
previous audit explicitly flagged: **"Never audited: web React components."**

### What was audited
- All 4 web screens (`MainScreen`, `EditorScreen`, `LabelsScreen`, `AuthScreen`)
- All layout components (`SideDrawer`, `TopBar`, `FilterRow`, `SelectionBar`, `ViewModeToggle`, `InstallPrompt`, `OfflineBanner`, `ResponsiveSheet`)
- All note components (`NoteCard`, `NoteStaggeredGrid`, `SwipeableNoteCard`, `NoteStaggeredGrid`, section headers)
- All editor components (`ChecklistEditor`, `EditorBottomBar`, `EditorOptionsSheet`, `LinkDialog`, `MarkdownPreview`, `ReminderPickerDialog`, `RichTextToolbar`)
- All settings components (`ProfileSheet`, `ThemePicker`, `PrivacyPolicyDialog`, `SignOutDialog`)
- All stores (`uiStore`, `settingsStore`, `notesStore`, `authStore`, `toastStore`, `tombstoneStore`, `labelRegistryStore`)
- All hooks (`useNoteEditor`, `useNotes`, `useNoteActions`, `useAccountActions`, `useShortcuts`, `useAuth`, `useCloudSync`, `useLongPress`, `useFocusTrap`, `useBodyScrollLock`, `useMediaQuery`, `useVisualViewportBottomInset`, `useEffectiveColumns`)

### Issues Found & Fixed (F32–F34)
1. **TypeScript errors in SideDrawer (F32):** `SideDrawer.tsx` destructured `iconClass` and `barClass` that don't exist on `NAV_ITEMS` type, and referenced an undefined `MANAGE_ITEMS` constant, producing 8 TS2339/TS2304/TS2322 typecheck errors. `npm run typecheck` was exiting 1. Fixed by removing the stale bindings from all three `NavButton` call sites.
2. **D15 violation in NoteCard (F33):** `NoteCard.tsx` used `note.title || 'Untitled'` as the displayed `<h2>` heading across all density modes, showing "Untitled" in the card when the note has body text but no title. Decision D15 mandates the text "Untitled" is not composed when the title is empty. Fixed: empty-title notes now either omit the `<h2>` (with the body acting as the visual lead) or show the first body line prominently. Accessibility label uses `title || firstBodyLine || 'Untitled'` (only shows "Untitled" when truly blank).
3. **Missing `type="button"` and `aria-label` in LabelsScreen (F34):** Label edit and delete buttons lacked `type="button"` (default is `type="submit"`); inline edit input had no `aria-label`. Fixed.

### Validation Matrix
- **TypeScript typecheck:** 0 errors (`npm run typecheck` exits 0).
- **Web unit tests:** 31 test files, 268/268 passing (`npm test`).
- **Firestore rules:** 37/37 passing (`npm run test:rules`).
- **KMP unit + desktop tests:** BUILD SUCCESSFUL, all UP-TO-DATE (`:composeApp:testDebugUnitTest :composeApp:desktopTest`).
- **Android debug APK:** BUILD SUCCESSFUL (`:androidApp:assembleDebug`).
- **Web production build:** `npm run build` exits 0, bundle generated.
- **Lint:** 0 errors (warnings only, pre-existing).

### No regressions introduced
All 268 unit tests green after every fix. TypeScript clean. Build clean.



- **Playwright E2E Escape Shortcut Fix (F28)**: Added `Escape` shortcut handler with `allowInInputs: true` inside `web/src/screens/EditorScreen.tsx` calling `handleBack()`, ensuring `editor.flushSave()` resolves before closing the route.
- **Section Headers Harmonization (F29)**: Aligned `buildBoardItems` in `web/src/components/notes/NoteStaggeredGrid.tsx` with Decision D14; date headers now only appear when sorting by date (`newest`/`oldest`).
- **Privacy Policy Update (F30)**: Updated `PRIVACY_POLICY.md` to document accountless, local-first offline operation across Android, Windows Desktop, and Web.
- **Web Note Opening Fix (F31)**: Fixed existing notes opening as blank / new notes by making `useNoteEditor` synchronously initialize state from `useNotesStore` on first render and adding explicit `key={noteId}` to all `<EditorScreen />` render sites in `MainScreen.tsx` and `App.tsx`.
- **Multiplatform Test Suite Pass**: All 31 web test files (268 unit tests), 9 sync emulator tests, 4 browser E2E tests, 37 Firestore security rules tests, and 200+ KMP/Desktop unit tests passed at 100%.
- **Windows Desktop Distribution**: Packaged native MSI installer (`Notelikeus-1.0.0.msi`) with bundled modular JLink JRE via WiX Toolset.
- **Android Production Packaging**: Built Google Play App Bundle (`androidApp-release.aab`) and minified release APK with full R8 shrinking.
- **Live Firebase Deployment**: Deployed production web bundle and Firestore security rules to Firebase live cloud (`https://notelike.web.app`).

### Key Issues Found & Resolved
1. **Web Editor `Escape` / Save Race in Playwright E2E Tests (F28):**
   `Escape` key pressed inside active editor inputs was either dropped or closed the editor before flushing pending debounced saves, causing edit data loss upon subsequent browser reload. Resolved by binding `Escape` with `allowInInputs: true` inside `EditorScreen.tsx` to `handleBack()` (which flushes `editor.flushSave()`), ensuring 100% pass on all 4 Playwright browser E2E test suites.
2. **Section Heading Parity with Decision D14 (F29):**
   Web `NoteStaggeredGrid.tsx` was generating date headers across manual and search views. Aligned with `NoteSections.kt` and Decision D14: suppress headers on search relevance, only show `Pinned`/`Others` under manual sort when pinned notes exist, and group by date under date-sorted modes.
3. **Privacy Disclosures Alignment (F30):**
   Updated `PRIVACY_POLICY.md` to accurately disclose offline-first, accountless guest storage on Web, aligning documentation with the actual implementation.
4. **Web Editor Blank Note on Card Click (F31):**
   `useNoteEditor` initialized `useState<EditorState>(createBlankEditorState())` on mount, causing an initial render pass with a blank note before `useEffect` ran, while `<EditorScreen />` lacked `key={noteId}` in `MainScreen.tsx` and `App.tsx`, causing React component reuse issues when transitioning between routes. Resolved with synchronous state initialization from store and explicit `key` props.

### Validation Matrix
- **KMP Unit & Desktop Tests:** All JVM and Desktop unit tests passing (`:composeApp:testDebugUnitTest :composeApp:desktopTest`).
- **Android APK Assembly:** Debug & Release APKs compiled and verified with R8 minification and resource shrinking.
- **Firestore Rules:** 37/37 tests passing against emulator (`npm run test:rules`).
- **Web Unit Tests:** 31 test files, 268/268 tests passing (`npm test`).
- **Web Sync Tests:** 9/9 sync invariant tests passing against emulator (`npm run test:sync`).
- **Web E2E Tests:** 4/4 Playwright browser tests passing (`npm run test:e2e`).

---

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
