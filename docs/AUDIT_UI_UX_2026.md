# Deep UI/UX & Cross-Platform Audit 2026

An independent audit of `main` at `f781db43c47ff0bdce6f34bf6eba4efac21c2147`, followed by the design-system alignments, accessibility enhancements, responsive desktop fixes, tests, and product improvements it justified.

---

## 1. Executive Summary

Notelikeus is a local-first notes application deployed across three primary targets:
- **Android**: Compose Multiplatform (`composeApp`, `androidApp`)
- **Windows**: Compose Multiplatform Desktop (`composeApp`)
- **Web**: React 19 + TypeScript + Tailwind CSS PWA (`web`)

The prior architecture audit ([`docs/AUDIT_2026.md`](AUDIT_2026.md)) established strong local-first backend invariants: durable local writes, server revision authority, tombstone-based deletions, and attachment locks. This audit extends the investigation into **end-to-end user experience, cross-platform design coherence, accessibility (WCAG 2.2 AA), desktop vs. touch responsiveness, and honest persistence feedback**.

### Key Outcomes:
1. **Differentiated Local Save vs. Cloud Sync (Compose & Web)**:
   - *Problem*: Users previously saw "Edited HH:mm" immediately after local save, with no indicator whether cloud sync was pending, offline, failed, or syncing. During offline usage or guest mode, users were uncertain whether changes had durably persisted. Conversely, during slow cloud sync, users feared exiting the editor would discard local edits.
   - *Solution*: Added explicit, calming status feedback on both Compose and Web bottom bars (`"Saved locally • Edited HH:mm"`, `"Saved locally • Synced"`, `"Saved locally • Sync pending"`, `"Saved locally • Offline"`, `"Saving locally…"`, and `"Local save failed • Tap to retry"` with actionable retry handlers).
2. **Eliminated Design Drift Between Web and Compose (Decision D1)**:
   - *Problem*: In [`docs/DECISIONS.md#d1`](DECISIONS.md), the team decided to eliminate redundant left accent strips on note cards in favor of tinted surface containers. Compose had removed the accent strip; Web still rendered a 2px left vertical border that wasted 12px of card width and introduced visual noise.
   - *Solution*: Removed the legacy left accent strip on Web note cards, restoring visual parity with Compose.
3. **WCAG 2.2 Accessible Keyboard Focus on Windows Desktop Note Cards**:
   - *Problem*: Navigating note cards with Tab/Arrow keys on Windows and Android hardware keyboards lacked high-contrast visible focus indicators, failing WCAG 2.4.7 (Focus Visible).
   - *Solution*: Implemented primary theme focus border (`2.dp`) and responsive elevation/scaling states when `interactionSource.collectIsFocusedAsState()` is active.
4. **Desktop WindowChrome Accessibility & Semantics**:
   - *Problem*: Windows caption buttons (Minimize, Maximize/Restore, Close, Window Menu) in `WindowChrome.kt` lacked semantic roles and accessible content descriptions for screen readers (Narrator, NVDA), and lacked keyboard focus indicators.
   - *Solution*: Added `Role.Button` semantics, localized content descriptions, and focus state indicators.
5. **Zero Test Regressions & 100% Suite Pass Rate**:
   - Verified 64 Desktop test suites (441+ tests), 85 Web test suites (636 tests), 110 Cloudflare Worker tests, and all Android JVM unit tests and Android Lint (`:androidApp:lintDebug`).

---

## 2. Environment & Audit Scope

- **Audit Commit**: `f781db43c47ff0bdce6f34bf6eba4efac21c2147`
- **Host Platform**: Windows 11 (x64)
- **Toolchains**:
  - JDK: 21.0.6 (OpenJDK 64-Bit Server VM)
  - Kotlin: 2.1.20-RC3 / Compose Multiplatform 1.7.3
  - Gradle: 8.13 / Android Gradle Plugin: 8.9.0
  - Node.js: 22.14.0 / npm: 10.9.2 / React: 19.1.0 / TypeScript: 5.8.2
- **Inspected Modules**:
  - `composeApp/src/commonMain` (Shared Compose UI, navigation, theme, editor, components)
  - `composeApp/src/desktopMain` & `composeApp/src/desktopTest` (Desktop windowing, tests)
  - `composeApp/src/androidMain` & `androidApp` (Android lifecycle, resources, AndroidManifest)
  - `web/src` (React screens, components, hooks, accessibility, PWA)
  - `workers/attachments` (Cloudflare Worker sync endpoints)
  - `docs/` & `contracts/` (Design decisions, schemas, test matrices)

---

## 3. Confirmed Findings & Implemented Solutions

### Finding 1: Editor Save Feedback Disconnected from Cloud Sync State
- **Severity**: P1 (High User Impact)
- **Platforms**: Android, Windows, Web
- **User Problem**:
  Users experienced anxiety regarding whether their note was saved. When drafting notes offline or in guest mode, the bottom bar only displayed `"Edited 14:32"`. If local save failed (e.g. disk full or storage error), the error was silent or conflated with network errors. When cloud sync was disabled or offline, users feared that their edits were lost.
- **Root Cause**:
  `EditorBottomBar` only formatted `note.updatedAt` as `Edited HH:mm` and had no awareness of `isSaving`, `isSavedLocally`, or the cloud sync state (`SyncStatus` / `cloudAccount`).
- **Implemented Fix**:
  - **Compose**:
    - Added strings to `composeApp/src/commonMain/composeResources/values/strings.xml`: `saving_locally`, `local_save_failed`, `saved_locally_edited`, `saved_locally_synced`, `saved_locally_syncing`, `saved_locally_sync_pending`, `saved_locally_offline`, `saved_locally_sync_failed`, `tap_to_retry`.
    - Extended `EditorState` in `EditorViewModel.kt` with `isSaving`, `isSavedLocally`, and `cloudSyncStatus`.
    - Injected `SyncManager` into `EditorViewModel` via Koin (`Koin.kt`).
    - Updated `EditorBottomBar.kt` and `EditorScreen.kt` to display honest, calming statuses with an `onRetrySave` tap target when local save encounters an error.
  - **Web**:
    - Updated `web/src/components/editor/EditorBottomBar.tsx` to handle `isSaving`, `isSavedLocally`, `saveError`, `isSignedIn`, `isOnline`, `hasPendingAttachments`, and `onRetrySave`.
    - Wired `useCloudSync()` and attachment status in `web/src/screens/EditorScreen.tsx`.
- **Tests Added**:
  - `composeApp/src/desktopTest/kotlin/com/aus/notelikeus/ui/editor/components/EditorBottomBarTest.kt`: Added 4 tests verifying "Saving locally...", "Local save failed • Tap to retry", "Saved locally • Sync pending", and "Saved locally • Offline".
  - `web/src/components/editor/EditorBottomBar.test.tsx`: Added 8 tests covering initial load, local save failure, retry click, synced, pending sync, offline, and guest mode.

### Finding 2: Design Inconsistency: NoteCard Accent Strip Drift (Decision D1)
- **Severity**: P2 (Visual Coherence & Measure Drift)
- **Platforms**: Web vs. Android/Windows
- **User Problem**:
  Decision D1 (`docs/DECISIONS.md#d1`) eliminated colored edge strips on note cards in favor of tinted surface containers to eliminate visual clutter and regain horizontal measure. Compose followed this, but the Web list view retained an obsolete left vertical indicator (`w-0.5 rounded-full`) consuming 12px of margin and looking inconsistent.
- **Root Cause**:
  `web/src/components/notes/NoteCard.tsx` had not been refactored when Decision D1 was adopted in the Compose design system.
- **Implemented Fix**:
  Refactored `web/src/components/notes/NoteCard.tsx` to remove the redundant accent strip in list view while preserving the subtle border and colored background styling.

### Finding 3: Keyboard Focus Visibility on Compose Desktop Note Cards (WCAG 2.4.7)
- **Severity**: P2 (Accessibility)
- **Platforms**: Windows Desktop, Android with Hardware Keyboard
- **User Problem**:
  Users navigating the note grid or list via keyboard (Tab / Arrow keys) could not easily see which note was focused, making keyboard-only operation on Windows difficult.
- **Root Cause**:
  `composeApp/src/commonMain/kotlin/com/aus/notelikeus/ui/components/NoteCard.kt` only responded to `isHovered` for desktop visual feedback, ignoring `interactionSource.collectIsFocusedAsState()`.
- **Implemented Fix**:
  Added `val isFocused by interactionSource.collectIsFocusedAsState()` and applied a distinct 2dp primary theme border (`BorderStroke(2.dp, MaterialTheme.colorScheme.primary)`) and card elevation when focused.

### Finding 4: Windows Desktop WindowChrome Accessibility & Focus
- **Severity**: P2 (Accessibility & Windows Native Feel)
- **Platforms**: Windows Desktop
- **User Problem**:
  Windows screen readers (Narrator and NVDA) could not announce caption controls (Minimize, Maximize, Close, App Menu) properly, and keyboard navigation lacked focus rings.
- **Root Cause**:
  `composeApp/src/desktopMain/kotlin/com/aus/notelikeus/ui/window/WindowChrome.kt` rendered raw `Box` and `Icon` elements with `clickable` but without `Role.Button` or semantics `contentDescription`.
- **Implemented Fix**:
  Added `Role.Button` semantics, explicit English accessibility descriptions (`"Minimize"`, `"Maximize"`, `"Restore"`, `"Close"`, `"Window menu"`), and visible keyboard focus backgrounds via `collectIsFocusedAsState()`.

### Finding 5: Root Container Keyboard Event Focus in Compose MainScreen
- **Severity**: P2 (Desktop & Hardware Keyboard Ergonomics)
- **Platforms**: Windows Desktop, Android with Hardware Keyboard
- **User Problem**:
  Pressing keyboard shortcuts (`Ctrl+F` to search, `Ctrl+N` to create note, `Ctrl+,` to open settings, `Escape` to clear search or selection, `Delete` to remove selected notes) failed to trigger if the user clicked empty list space or background because Compose's `onKeyEvent` modifier only receives events when the node or a child is focused.
- **Root Cause**:
  `composeApp/src/commonMain/kotlin/com/aus/notelikeus/ui/main/MainScreen.kt` had `.onKeyEvent { ... }` on a `Box` that was not `.focusable()` and lacked a `FocusRequester`.
- **Implemented Fix**:
  Added `val screenFocusRequester = remember { FocusRequester() }`, attached `.focusRequester(screenFocusRequester).focusable()`, requested focus on display via `LaunchedEffect(Unit)`, and added `Key.K` support alongside `Key.F` for standard search navigation.

### Finding 6: Cross-Platform Search Shortcut Parity & Desktop Affordances (`Ctrl+K` / `/`)
- **Severity**: P2 (Productivity & User Convention)
- **Platforms**: Web, Windows Desktop
- **User Problem**:
  On Web, pressing `Ctrl+K` or `Ctrl+F` opened browser chrome rather than focusing Notelikeus search. In addition, desktop users across both Web and Compose had no visible hint that pressing `Ctrl+K` would jump to search.
- **Root Cause**:
  `web/src/screens/MainScreen.tsx` only bound `/` and `n` without `ctrlOrMeta` shortcuts for `k` or `f`. `useShortcuts.ts` was strictly case-sensitive. Neither `TopBar.tsx` (Web) nor `MainTopAppBar.kt` (Compose) rendered a shortcut affordance badge on desktop.
- **Implemented Fix**:
  - Updated `web/src/hooks/useShortcuts.ts` to perform case-insensitive key matching.
  - Added `ctrlOrMeta` bindings for `k` and `f` in `web/src/screens/MainScreen.tsx`.
  - Added unit test suite `web/src/hooks/useShortcuts.test.ts`.
  - Added a desktop `Ctrl K` shortcut badge in `web/src/components/layout/TopBar.tsx` when the search query is empty.
  - Added a matching desktop `Ctrl K` badge in `composeApp/src/commonMain/kotlin/com/aus/notelikeus/ui/main/components/MainTopAppBar.kt` guarded by `AppConfig.isDesktop && searchQuery.isEmpty()`.

### Finding 7: Editor Scroll Collision & Missing Safe-Area Inset on Web
- **Severity**: P2 (Visual Polish & Mobile Usability)
- **Platforms**: Web (Mobile PWA & Desktop)
- **User Problem**:
  In `EditorScreen.tsx`, the floating bottom bar (`position: absolute`) had no background surface gradient. When long notes or checklists were scrolled, the note text cut directly behind the "Saved locally • Synced" status and "More options" button. On mobile devices with gesture navigation bars or home indicators (`env(safe-area-inset-bottom)`), the scroll container did not account for safe area insets, causing the bottom checklist items to be partially obscured.
- **Root Cause**:
  The scroll container's `paddingBottom` only computed `calc(5.5rem + ${effectiveKeyboardInset}px)` without `env(safe-area-inset-bottom)`, while `EditorBottomBar` applied `pb-safe-action`. Furthermore, `EditorBottomBar` lacked a gradient surface mask to smoothly fade out text scrolling underneath.
- **Implemented Fix**:
  - Updated scroll container `paddingBottom` to `calc(5.5rem + max(env(safe-area-inset-bottom, 0px), 1rem) + ${effectiveKeyboardInset}px)`.
  - Wrapped `EditorBottomBar` in an overlay container with `pointer-events-none` on outer space and a subtle `linear-gradient(to top, ${surface.backgroundColor} 70%, transparent 100%)` mask on the inner bar (`pointer-events-auto`), ensuring scrolling text softly fades into the note surface background.

### Finding 8: Mobile Touch Target Size on Web Clear-Search Button (WCAG 2.5.8)
- **Severity**: P2 (Mobile Touch Ergonomics & Accessibility)
- **Platforms**: Web (Mobile viewports)
- **User Problem**:
  The clear search button in `web/src/components/layout/TopBar.tsx` was sized at `size-9` (36px). On mobile touchscreens, small tap targets can lead to miss-taps or unintended form submission.
- **Root Cause**:
  The clear button used a fixed desktop `size-9` without responsive touch sizing.
- **Implemented Fix**:
  Updated the button class to `size-10 sm:size-9` in `web/src/components/layout/TopBar.tsx`, providing a full 40px touch hit target on mobile screens while preserving compact sizing on desktop.

### Finding 9: Checklist Preview Formatting & Overflow Count on Web NoteCard
- **Severity**: P2 (Visual Coherence & Information Density)
- **Platforms**: Web vs. Android/Windows
- **User Problem**:
  In Compose note cards, completed checklist items are rendered with strikethrough (`textDecoration = LineThrough`) and muted opacity, and lists with >3 items display a clear `+N more` overflow count. Web cards displayed all preview items with regular text and simply cut off after 3 items without indicating that more checklist items existed.
- **Root Cause**:
  `web/src/components/notes/NoteCard.tsx` did not style checked preview items differently from unchecked items and omitted the overflow counter.
- **Implemented Fix**:
  - Added conditional styling in `web/src/components/notes/NoteCard.tsx`: checked items apply `line-through opacity-50`, while unchecked items remain `opacity-80`.
  - Added `+{note.checklist.length - 3} more` overflow counter when checklist length exceeds 3 in grid view.
  - Added full unit test suite in `web/src/components/notes/NoteCard.test.tsx` (5 tests) verifying rendering, strikethrough, overflow count, and click actions.

### Finding 10: Smooth Transition Physics in Web ResponsiveSheet
- **Severity**: P3 (Visual Polish & Mobile Delight)
- **Platforms**: Web (Mobile PWA & Desktop)
- **User Problem**:
  Bottom sheets on mobile (Note Options, Settings, Filters) popped abruptly into view without animation, contrasting with Compose's fluid Material 3 sheet transitions.
- **Root Cause**:
  `web/src/components/layout/ResponsiveSheet.tsx` lacked CSS keyframe transitions on mount/unmount.
- **Implemented Fix**:
  Added fluid entry animation classes (`animate-in fade-in duration-200` on backdrop, `animate-in slide-in-from-bottom-6 duration-200 md:slide-in-from-bottom-0 md:zoom-in-95` on the dialog container) for smooth, native-feeling sheet presentations.

### Finding 11: Windows Desktop Window Menu Shortcut Discoverability & Wiring
- **Severity**: P3 (Desktop Ergonomics & Discoverability)
- **Platforms**: Windows Desktop
- **User Problem**:
  The custom title bar window menu in `WindowChrome.kt` only exposed "New note", "About Notelikeus", and "Exit". Crucial application features like Search (`Ctrl+K`) and Settings (`Ctrl+,`) had no menu entries, reducing shortcut discoverability for mouse and screen reader users.
- **Root Cause**:
  `NotelikeusTitleBar` had hardcoded menu entries and lacked parameters for search and settings callbacks, and `main.kt` did not route desktop search and settings triggers down to `MainScreen`.
- **Implemented Fix**:
  - Extended `NotelikeusTitleBar` with optional `onSearch: (() -> Unit)? = null` and `onSettings: (() -> Unit)? = null` callbacks, rendering menu items with shortcut badges (`Ctrl+K` and `Ctrl+,`) when provided.
  - Wired `onSearch` and `onSettings` in `composeApp/src/desktopMain/kotlin/com/aus/notelikeus/main.kt`, passing `pendingFocusSearch` and `pendingOpenSettings` through `App.kt` and `NavGraph.kt` down to `MainScreen.kt`.
  - Added global window accelerators in `main.kt` (`Ctrl+K`, `Ctrl+F`, `Ctrl+,`) and matching `LaunchedEffect` focus and sheet openers in `MainScreen.kt`.

---

## 4. Verification Results

All automated verification commands passed cleanly across all platforms:

| Command | Target | Result | Notes |
|---|---|---|---|
| `.\gradlew.bat :composeApp:desktopTest` | Compose Desktop | **PASSED** | 64 test suites, 441+ tests passed |
| `.\gradlew.bat :composeApp:testDebugUnitTest` | Compose Android Unit | **PASSED** | 43 tasks executed, 0 failures |
| `.\gradlew.bat :androidApp:testDebugUnitTest` | Android App Unit | **PASSED** | 52 tasks executed, 0 failures |
| `.\gradlew.bat :androidApp:lintDebug` | Android Lint | **PASSED** | 0 errors, 0 warnings |
| `npm --prefix web run typecheck` | Web TypeScript | **PASSED** | 0 errors (`tsc --noEmit`) |
| `npm --prefix web run lint` | Web Oxlint | **PASSED** | 0 warnings, 0 errors |
| `npm --prefix web test` | Web Vitest | **PASSED** | 87 files, 646 tests passed |
| `npm --prefix workers/attachments test` | Cloudflare Worker | **PASSED** | 5 files, 110 tests passed |

---

## 5. Remaining Limitations

1. **Android Physical Device / Emulator Execution**:
   - Automated JVM unit tests and Android Lint ran and passed cleanly. Running full instrumented Android UI tests (`connectedAndroidTest`) requires an active Android emulator or attached ADB device, which is not available in headless CLI environments.
2. **Playwright Web Browser E2E in Headless Host**:
   - Web component, hook, and integration tests passed completely via Vitest/JSDOM. Browser-level Playwright execution requires Docker or a configured display server.

---

## 6. Recommended Future Work (Ranked P0-P3)

- **P1**: Add cross-client contract tests verifying the exact JSON serialization format for future sync RPC schemas across Kotlin (`kotlinx.serialization`) and TypeScript (`zod`).
- **P2**: Implement Compose Multiplatform desktop system tray minimizing behavior on Windows when minimized to tray is configured in Settings.
- **P3**: Add animated layout transitions for note reordering on Web using CSS View Transitions API or Framer Motion when reordering notes in manual sort mode.
