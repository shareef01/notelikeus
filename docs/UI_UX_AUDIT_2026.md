# Notelikeus UI/UX audit — 2026-11-23

Scope: **Android** (Compose, `androidApp/` + shared UI in `composeApp/src/commonMain`), **Windows native**
(Rust/Slint, `desktop-rust/crates/nlk-native/`) and **Web** (React/Tailwind/TipTap, `web/`). The
legacy Kotlin Windows app and the Tauri experiment are out of scope; shared Compose changes were kept
Android-only where behaviour differs (`AppConfig.isDesktop`).

Branch: `rust/native-ui` (from `670966b4`). Earlier passes (2026-11-06, -11, -17, -18) had already
fixed a lot: real Inter on every client, contract-tested theme colours, WCAG AA text roles, label chips at
12px, the narrow-card title row, touch targets and reflow on the web. This pass checks those findings
against the current code again and fixes what remains. It does not repeat them.

Severity: **P0** data loss or a feature that can't be used · **P1** a primary workflow is hard to read
or use · **P2** inconsistent or below the design system, with real but limited impact · **P3** polish.

---

## A. Issue register

"Confirmed" means reproduced in code or in a test run, "Suspected" means inferred but not reproduced,
and "Not a defect" means a suspected issue that turned out to be fine on inspection.

| ID | Sev | Platform | Finding | Evidence | Status |
|---|---|---|---|---|---|
| UX26-01 | P1 | Android | Checklist rows on grid cards render at **11sp** (`labelSmall`): smaller than the 12sp timestamp beside them, though they are the note's content. The web renders the same rows at 15px. | `NoteCard.kt` checklist `Text` used `noteCardBody.copy(fontSize = labelSmall.fontSize…)` | **Fixed, device-verified** (Pixel 7): `AppType.noteCardChecklistItem` (15/22); "+N" uses `AppType.noteCardMeta` (12sp). Checklist rows now match the body preview (`android-pixel7-grid-checklist.png`) |
| UX26-02 | P2 | Android | The phone grid used the view mode's column count as is. At large font scales Grid 2 cards were too narrow for one title word per line. *Corrected on device:* the phone picker offers only List / Grid (2) / Compact, so Grid 3–5 (~54–101dp cards) is reachable on a phone only through a stored query or a desktop-made preference, not from the picker. | `MainScaffold.kt:385` used `state.viewMode.columns`, with no width or font-scale input | **Fixed**: `adaptivePhoneGridColumns` (min card width 120dp × font scale), unit-tested. Desktop is unchanged. On a Pixel 7 (~376dp, font scale 1.0) Grid 2 stays at 2 columns; the enlarged-font case is not device-verified (changing the phone's system font size was out of scope) |
| UX26-03 | P1 | Windows | **88 hard-coded `font-size` literals** across `app.slint`, with no scale. Ten sizes in use, the smallest **10px** ("Ctrl K" hint) and **11px** (card timestamps, brand subtitle, picture errors). | `grep font-size: *Npx` | **Fixed**: semantic `Type` global in `theme.slint` (11 roles). Every `font-size` now names a role; nothing is below 12px except the uppercase overline |
| UX26-04 | P2 | Windows | Editor title was **32px Bold**: the largest text on any client, 10px above Android's and 4px above the web's | `app.slint` title `TextInput` | **Fixed**: `Type.editor-title` 28px semibold, the same as web at ≥640px |
| UX26-05 | P2 | Windows | Card titles had **no length limit**, so a pasted paragraph as a title filled the whole card. Slint `Text` has no line clamp. | `controller.rs` passed `note.title.trim()` through | **Fixed**: `view::card_title` caps at 120 chars with "…" (unit-tested). The editor still shows the full title |
| UX26-06 | P2 | Windows | Narrator heard only title and preview on a card. Pinned, reminder and picture existed only as icons, while TalkBack and the web card name them. | `Card.accessible-description: card.preview` | **Fixed**: description begins with the status in words ("Pinned. Reminder 9:00 AM. …"). The accessible name stays the title. UI test with negative control |
| UX26-07 | P3 | Windows | Hovering a coloured card in light theme set a **white 20%** border, invisible on pastel notes. Hover shadow was an 18px glow, against the design system's restrained 3dp. | `Card` border-color / drop-shadow | **Fixed**: dark edge in light, light edge in dark; 8px / 2px-offset shadow |
| UX26-08 | P2 | Web | Editor typography was hard-coded (`text-[26px] sm:text-[32px]`, `.rich-note-body` 17/18px literals) and not in the token set the design docs map. The 32px title was 4px larger than any other client's. | `EditorScreen.tsx`, `globals.css` | **Fixed**: `--text-editor-title(-lg)` 26/28px semibold, `--text-editor-body(-lg)` 17/18px. Asserted in `typography.spec.ts` |
| UX26-09 | P3 | Web | Text below the 12px metadata size outside overlines: drawer subtitle 11px, attachment count 11px, `Ctrl K` hint 10px | grep `text-[1[01]px]` | **Fixed**: 12 / 12 / 11px. `typography.spec.ts` sets an 11px floor for all visible text on the notes screen and in the editor |
| UX26-10 | — | Android | "Label chips are too small to tap" (earlier finding). The chips draw 22–24dp tall. | `NoteCard.kt` chips use `clickable` | **Not a defect, device-verified** (Pixel 7): the chip draws ~20dp tall, but its touch bounds are 48dp (uiautomator reports `[93,1292][269,1430]`, 138px at 2.875 px/dp). A tap 12dp below the visible pill applied the label filter; a tap on the card beside the chip opened the note. Compose extends small touch targets in hit testing without enlarging layout |
| UX26-11 | P3 | Android | Compact (single-column dense) cards use 15sp titles and an 11sp timestamp. Documented in `DESIGN_SYSTEM.md` §3 ("11 in compact") | `NoteCard.kt` | **Kept**: a deliberate, documented density choice |
| UX26-12 | P2 | Windows | No Windows **high-contrast** theme handling: `Theme` follows only light/dark, so contrast themes get the app's own palette | `theme.slint` | **Fixed**: `src/contrast.rs` reads `SPI_GETHIGHCONTRAST` and `GetSysColor`. While a contrast theme is on, every `Theme` role maps to a system colour (window, window text, button face, highlight, hot-track), muted text and borders use the text colour, note colours stop tinting cards and the editor, and amber caution washes are dropped. The theme is re-read every 2 s, so switching takes effect without a restart. UI test with negative control |
| UX26-13 | P3 | Windows | No `prefers-reduced-motion` equivalent, but the Slint app has **no animations**, so nothing needs reducing | grep `animate` → none | Not a defect |
| UX26-14 | P2 | All | Editor body roles differ: Android 16sp, web and Windows 17px (web 18px at ≥640px) | type tables | **Intentional, documented**: platform reading conventions (§C.2) |

Checked again and still fine: the web audit suite (reflow at 320–1440px, dialog focus traps, palette
contrast, touch targets) passed 136/136 **before** any change, theme-colour contracts are unchanged,
and every client loads Inter (Compose resources, Slint `import "fonts/…"` + `default-font-family`,
`@fontsource/inter`).

---

## B. Changes made

| Platform | File | Change |
|---|---|---|
| Android | `composeApp/.../ui/theme/Type.kt` | New roles `AppType.noteCardChecklistItem`, `AppType.noteCardMeta` |
| Android | `composeApp/.../ui/components/NoteCard.kt` | Checklist preview rows and "+N" use the new roles |
| Android | `composeApp/.../ui/main/NoteGridColumns.kt` (new) | `adaptivePhoneGridColumns` |
| Android | `composeApp/.../ui/main/MainScaffold.kt` | Phone grid passes width and font scale through it; desktop path untouched |
| Android | `composeApp/src/commonTest/.../NoteGridColumnsTest.kt` (new) | 5 tests: everyday phones, wide modes, landscape/tablet, font scales 1.3–2.0, pass-through |
| Windows | `desktop-rust/crates/nlk-native/ui/theme.slint` | `export global Type` with 11 semantic roles |
| Windows | `desktop-rust/crates/nlk-native/ui/app.slint` | All 91 `font-size`s → `Type.*`; editor title 28px/600; card status description; hover edge and shadow |
| Windows | `desktop-rust/crates/nlk-native/src/view.rs`, `controller.rs` | `card_title` cap + unit test |
| Windows | `desktop-rust/crates/nlk-native/tests/ui.rs` | Asserts the card description ("Pinned. pack") and an unpinned one ("milk"); a contrast theme on/off scenario checks every mapped role |
| Windows | `desktop-rust/crates/nlk-native/src/contrast.rs` (new), `controller.rs`, `ui/theme.slint`, `ui/app.slint`, `Cargo.toml` | High-contrast support (UX26-12): system-colour palette, `Theme.high-contrast` / `note-tint` / `caution-fill`, a 2 s poll, `windows-sys` features `Win32_UI_Accessibility` and `Win32_Graphics_Gdi` |
| Web | `web/src/styles/globals.css` | `--text-editor-title(-lg)`, `--text-editor-body(-lg)` tokens; `.rich-note-body` uses them |
| Web | `web/src/screens/EditorScreen.tsx` | Title uses `text-editor-title sm:text-editor-title-lg` |
| Web | `SideDrawer.tsx`, `NoteCard.tsx`, `TopBar.tsx` | 11→12px, 11→12px, 10→11px |
| Web | `web/e2e/typography.spec.ts` (new), `package.json` | Text-size floor, editor roles at 375/1280px, unbroken-word reflow at 320/768/1440px; added to `test:audit-ui` |

Untouched on purpose: data, sync, storage, encryption, auth, backup formats, note identifiers, theme
preferences, TipTap schema and serialization, and the Kotlin desktop layout path.

---

## C. Cross-platform design specification

### C.1 Semantic type roles

One role list for all three clients. A size may differ by platform where noted, but the role and its
hierarchy may not.

| Role | Use | Web | Android (Compose) | Windows (Slint) |
|---|---|---|---|---|
| editor-title | Note title in the editor | `text-editor-title` 26 → `-lg` 28 (≥640px), 600 | `AppType.editorTitle` 22/28, 600 | `Type.editor-title` 28, 600 |
| editor-body | Writing canvas, checklist rows in editor | `--text-editor-body` 17 → 18 (≥640px), lh 1.75 | `AppType.editorBody` 16/24.8 | `Type.editor-body` 17 |
| title | Dialog titles, empty-state headline | `text-lg`/18 | `titleLarge` 20 | `Type.title` 18 |
| card-title | Note card title | `text-note-title` 18/28, 600, 2–3 lines | `AppType.noteCardTitle` 18/28 (compact 15/20) | `Type.card-title` 18 (compact 15), capped 120 chars |
| card-body | Card preview, card checklist rows | `text-note-body` 15/1.6 | `AppType.noteCardBody` 15/24; rows `noteCardChecklistItem` 15/22 | `Type.card-body` 15 (compact 14) |
| body | Nav items, buttons, menus, dialog text | 14 | `bodyMedium`/`labelLarge` 14 | `Type.body` 14 |
| body-small | Secondary descriptions | 13 | `bodySmall` 12–13 | `Type.body-small` 13 |
| meta | Timestamps, chips, counts, captions | 12 (dense chips 11) | `AppType.noteCardMeta`, chip style 12 (compact 11) | `Type.meta` 12 |
| overline | Uppercase section labels, tracked 0.08em | `text-overline` 11 | `AppType.chromeLabel` 11 | `Type.overline` 11 |

Rules: nothing below 11px; 11px only for uppercase overlines and compact-density chips/timestamps. The
editor body is always larger than the card body (the editor is for reading and writing, the card for
scanning). Titles clamp on cards and never in the editor.

### C.2 Justified platform differences

- **Editor title, Android 22sp vs 26–28px elsewhere.** A phone editor shares its screen with the soft
  keyboard. A 28sp title would take two lines for an ordinary title on a 360dp screen.
- **Editor body, Android 16sp vs 17–18px.** It follows Material's `bodyLarge`, and Android users scale
  it through system font size, which the app honours. Web and Windows have no system text scale for
  the canvas, so they ship the larger reading size.
- **Grid density.** Desktop clients size columns by width (`card-min-width` 300px Windows/desktop
  Compose, CSS grid on web). Phones keep the user's chosen count, reduced only when cards would fall
  below 120dp × font scale.

### C.3 Spacing, colour, components, states

Unchanged from `docs/design/DESIGN_SYSTEM.md` §2, §4, §5 and `contracts/design/theme-colors.json`.
This pass changed no colour token. The only colour decision is UX26-07: hover edges on coloured
cards are drawn in contrast to the note colour (dark edge in light theme, light edge in dark), never
white in both themes.

### C.4 Consistency matrix

| Feature | Must be consistent | Visual treatment consistent | Platform-native |
|---|---|---|---|
| Notes grid/list | Order, pin grouping, sort, what a card shows | Card radius 18, tinted colour, 18/15 type | Column strategy (§C.2), swipe on Android only |
| Note card | Status words for screen readers (pin, reminder, picture) | Icons + 12px meta | Context actions: long-press (Android), hover actions (web), Space/Enter (Windows) |
| Editor | Save semantics, TipTap/Markdown content, checklist model | Title > body > meta hierarchy | Back button / Esc / Ctrl shortcuts; keyboard insets on Android |
| Labels | Names as typed, filter on tap | Chips at meta size, 14% tint | Chip hit-testing (Compose touch extension; 32px pointer on web) |
| Search & filters | Operators and saved filters | Chip rows | Ctrl K (web/Windows), search bar (Android) |
| Reminders | Time semantics, overdue state | Bell icon; overdue in danger colour **and** words | OS notification channel |
| Archive / trash | Undo for reversible deletes, confirmation for permanent | Same empty-state copy | Snackbar vs toast |
| Settings / theme | 7 themes from the contract | Contract colours | Picker layout |
| Account & sync | "Saved on this device" ≠ "Synced" (DESIGN_SYSTEM §5.1) | Same vocabulary | Banner vs status line |
| Dialogs | Destructive confirmation wording | Title/body roles | Focus trap and restore (web), modal window (Windows), M3 dialog (Android) |
| Empty / error states | Copy | `title` + `body-small` roles | — |

---

## D. Verification report

Commands run in this session on Windows 11 (results as observed):

| Check | Command | Result |
|---|---|---|
| Rust format | `cargo fmt --all -- --check` (desktop-rust) | **Pass** |
| Rust tests | `cargo test -p nlk-native` | **Pass**: 17 unit tests (incl. new `card_titles_are_trimmed_and_capped`), `live.rs` 1, `ui.rs` end-to-end 1. The Slint UI compiled with the `Type` global |
| Rust negative control | Changed the new `ui.rs` expectation to `Some("pack")` and re-ran | **Failed as intended** (`left: Some("Pinned. pack")`, recompiled first), then restored |
| Web lint / typecheck (baseline) | `npm run lint`, `npm run typecheck` | **Pass** |
| Web audit UI (baseline, before changes) | `npm run test:audit-ui` | **136 passed** |
| Web lint / typecheck (after) | `npm run lint`, `npm run typecheck` | **Pass** (re-run after the edits, see below) |
| Web unit tests (after) | `npm test` (tooling + vitest) | **Pass**: 189 files, 1536 tests |
| Web typography spec (after, new) | `playwright test typography.spec.ts --config playwright.audit.config.ts` | **28 passed** (7 tests × 4 locale/viewport projects). A first run failed 8× on a click that hit the card's overlay button; the spec was fixed and re-run in full |
| Web typography negative control | Raised the floor to 13px (absolute-path edit, confirmed changed) and re-ran | **Failed as intended** (`Received: 11` — the overline), then restored. An earlier control attempt silently edited nothing (relative path); it was discarded, not counted |
| Web evidence screenshots | Playwright, guest mode, e2e build | `docs/audits/2026-11-23/web-{notes,editor}-{phone-375,desktop-1280}.png` |
| Android unit test (new) | `gradlew :composeApp:testDebugUnitTest --tests …NoteGridColumnsTest` | **Pass**: 5/5 |
| Android build | `gradlew :androidApp:assembleDebug` | **Pass** |
| Shared desktop compile | `gradlew :composeApp:compileKotlinDesktop` | **Pass** (shared Compose changes still compile for the legacy desktop target) |
| Detekt | `gradlew detekt` | **Pass** (`:composeApp:detekt`, `:androidApp:detekt`) |
| Shared note-card UI tests | `gradlew :composeApp:desktopTest --tests "com.aus.notelikeus.ui.components.*"` | **Pass**: 62 tests incl. `NoteCardAccessibilityTest`, `NoteCardKeyboardSelectionTest`, `NoteCardTitleWrapTest`, `NoteCardTimestampContrastTest` |
| Android on device | Debug build with a temporary `.uxcheck` package suffix (reverted, never committed), installed beside the release app on a Pixel 7 (Android, density 460 ≈ 376dp wide), guest mode only | Checklist preview rows at body size; Grid 2 keeps 2 columns at font scale 1.0; label chip touch target 48dp with chip-vs-card routing as intended. Screenshots `docs/audits/2026-11-23/android-pixel7-*.png`. The release app (v2.7.0) and its data were not touched |

### Not verified here (manual checklist)

- **Windows runtime rendering** at 800×600 / 1024×768 / 1280×720 / 1920×1080 and 100–200% scaling.
  No screen-capture tool was available in this session. The Slint changes are compile- and
  testing-backend-verified (layout, accessibility tree), but not inspected visually. Check: card
  timestamps (now 12px) don't crowd the pin/bell icons; editor title (28px) wraps cleanly; Ctrl K hint
  fits its 22px pill at 200%.
- **Narrator** reading of the new card description on a real Windows session.
- **Android at enlarged system font size**: Settings → Display → Font size at maximum on the Pixel 7;
  Grid 2 should show 1 column (cards stay readable) and return to 2 at the default size. Also TalkBack
  on the checklist rows. Both need a system setting changed, which this session did not do.
- **Windows contrast themes on screen**: the UI test sets the system palette directly; the live
  `SPI_GETHIGHCONTRAST` path ran (returning "off") on every window opened by the tests, but no
  contrast theme was switched on in this session.
- **Web at real browser zoom 200%** and OS text scaling. Playwright covers widths, not zoom.

---

## E. Remaining issues and follow-ups

1. **Windows high-contrast themes on a real desktop** (UX26-12 is implemented): turn on each built-in
   contrast theme (Aquatic, Desert, Dusk, Night sky) and check focus rings, selected nav rows, the
   picture viewer (it keeps its own dark overlay on purpose) and that a coloured note is still
   identifiable by its label and not by its tint.
2. **Android editor title vs web/Windows**: keep it under review with real-device screenshots; the
   difference is justified (§C.2), but should be confirmed with users.
3. **Rust visual regression**: Slint's testing backend can render to a buffer (`i-slint-backend-testing`
   with the software renderer). A screenshot test at 800×600 and 1920×1080 would make UX26-03/04/07
   checkable in CI.
4. **Phone grid setting copy**: when Grid 3–5 is reduced to 2 columns on a phone, the view picker
   still shows the chosen mode. A hint ("shows 2 columns at this width") would make it explainable.
