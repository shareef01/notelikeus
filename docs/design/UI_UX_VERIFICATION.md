# UI/UX verification — 2026-10-29

Companion to [`UI_UX_AUDIT_2026.md`](UI_UX_AUDIT_2026.md). Baseline `e849cd92`, branch
`design/ui-ux-audit-2026-10-29`. Everything below ran on one Windows 11 machine.

## Tests executed

| Command / check | Baseline | After |
|---|---|---|
| `npm --prefix web run typecheck` | pass | pass |
| `npm --prefix web run lint` | exit 0, pre-existing warnings only | same |
| `npm --prefix web test` | 145 of 146 files, 1073 of 1074 tests. The failure: `performanceBenchmark.test.ts` wall-clock limit, while the screenshot run shared the CPU; passes alone (U-12) | **146 files, 1082 tests, all pass** (new: 5 in `TopBar.test.tsx`, 3 in `NoteCard.test.tsx`; the 5 TopBar tests fail on the baseline code) |
| `npm --prefix web run build` | fails: `Cannot pin CSP: VITE_SUPABASE_URL is not a pin-able origin (empty)` — no backend config on this machine | same failure, same cause. Bundling verified with `vite build --mode e2e` instead (pass) |
| Playwright, chromium + mobile-chrome, `dist-e2e`, guest-only specs touching the changed UI | — | **141 passed, 3 skipped, 0 failed** (13.6 min): `card-actions`, `card-thumbnails`, `filter-row`, `focus-after-removal`, `labels-journey`, `palette-contrast`, `reflow`, `rich-text`, `search-journey`, `touch-targets`, `dialog-a11y`, `bulk-actions`, `journey-f-actions`, `keyboard-scope`, `grid-scroll-stability`, `sort-order`. Built before the last edit (the "+N" text moved from `aria-label` to screen-reader-only text); that edit is covered by the unit suite only |
| Supabase-backed Playwright specs (`sync-status`, `save-failure`, `accessibility` sign-up tests, `account-switch`) | not run: Docker / local Supabase not running | not run — CI covers them |
| `gradlew :composeApp:desktopTest` | **blocked**: `java.io.IOException: Unable to establish loopback connection`, inside and outside the command sandbox (as recorded in `REMAINING_WORK.md` §3) | blocked — the Compose change (`NoteCard.kt`, one string) is compiled and tested only by CI |

## Screenshots

`docs/design/screenshots/2026-10-29/{before,after}/`, produced by
[`web/scripts/captureAuditScreens.mjs`](../../web/scripts/captureAuditScreens.mjs) against the dev
server in guest mode with synthetic notes. "Before" was taken with the source changes stashed,
"after" with them applied; same seed, same script.

| File | Shows |
|---|---|
| `notes-{1440,1280,768,390,320}-{light,dark}.png` | U-02 chip fill, U-03 sizes and casing, U-05 group spacing, U-06 untitled card |
| `notes-compact-1440-light.png`, `notes-list-1440-light.png` | Same at the other two densities |
| `editor-1440-light.png` | U-07 checklist glyph |
| `search-recent-tab-1440-light.png` | U-01: before, the panel has closed and focus is on Settings; after, the panel is open with the chip focused |

The `before/` folder also holds the baseline from the older `captureUiScreens.mjs`
(`notes-desktop-*`, `notes-tablet-*`, `notes-mobile-*`, `auth`, `empty`, `settings`, `search`,
`editor-390`). Those screens were not changed and have no "after".

All captures are web. **No Android or Windows screenshot was taken**; the Compose change is not
visually verified.

## Responsive coverage

Rendered: 320×568, 390×844, 768×1024, 1280×800, 1440×900 (light and dark); 360×740 and 768 in the
older baseline set. No horizontal scroll at any of them, with a 120-character unbroken URL, a long
title and seven labels. Not rendered this pass: 375, 430, 600, 1024 and 1920 widths, browser zoom
and 200 % text.

## Accessibility coverage

- Keyboard: recent-searches panel (Tab, Shift+Tab via focus moves, Enter, Escape, Clear) — unit
  tests and a real-browser capture.
- Assistive names: "+N" label overflow (web unit test; Compose source only).
- Focus ring on recent-search chips and Clear: rendered (`after/search-recent-tab-1440-light.png`).
- Contrast: chip text is the card's own text colour on a 14 % tint of that colour, so it stays above
  the card's text contrast floor; `palette-contrast.spec.ts` is in the e2e run above.
- Not done: axe run on the changed screens outside the e2e specs, any screen reader.

## Device / emulator coverage

None this pass. No emulator was started and the Windows desktop app could not be built locally.

## Performance observations

- Search, Node, unthrottled: 10,000 notes 163 ms for the first query, 31 ms per further keystroke.
- The card changes add no per-render work: the chip style is a string built from the card's
  existing text colour; the hidden-label list is a slice of the labels already in hand. `NoteCard`'s
  memo comparator is unchanged.

## Follow-up: U-08, U-09, U-10

Branch `design/ui-ux-audit-followups`, from `main` after #381 (which passed all 14 CI checks,
including the Compose build and tests that could not run here).

| Check | Result |
|---|---|
| typecheck, lint | pass |
| `npm --prefix web test` | 146 files, 1083 tests, all pass |
| Playwright, chromium + mobile-chrome, `dist-e2e` rebuilt | 18 guest specs: 145 passed, 3 skipped, 2 failed on the first run. Both failures were `search-journey` "searching does not push the page sideways" (chromium and mobile-chrome): the new seed note sat in a helper that test calls three times in one page. The seed moved into the one test that needs it; `search-journey` then passed 8 of 8 |
| Grid columns, measured in Chromium (6 notes) | 390: 1 · 600: 2 (280 px) · 768: 2 (256) · 840: 2 (292) · 1024: 2 (368) · 1280: 3 (320) · 1440 and 1920: 3 (357) |

Screenshots: `docs/design/screenshots/2026-10-29-followups/{before,after}/`, same script and seed,
plus `empty-{1280,390}-light.png` for a fresh workspace.

## Secondary pass: conflict review, reminders, settings, sign-in, editor layouts

Branch `design/ui-ux-audit-secondary`, from `main` after #382 (all 14 CI checks passed).

| Check | Result |
|---|---|
| typecheck, lint | pass |
| `npm --prefix web test` | 147 files, 1087 tests, all pass (new: `ReminderPickerDialog.test.tsx` ×2, `ConflictReviewDialog.test.tsx` +2) |
| Playwright, chromium + mobile-chrome, `dist-e2e` rebuilt | 16 guest specs: 129 passed, 3 skipped, 2 failed on the first run. Both failures were `grid-scroll-stability` "scroll position and card count stability" (chromium and mobile-chrome; both run at 1280 px): after pinning a note, `scrollY` moved 32 px against a 15 px limit. Bisected to the sticky sidebar (U-15). Measured the card in the middle of the screen: on `main` it **jumped 32 px** while `scrollY` held; on this branch it moves **0 px** and `scrollY` absorbs the new "Pinned" group (the browser's scroll anchoring now picks a card, not the stretched sidebar). The step now asserts the visible card stays within 5 px, as the same test's sync step already did; `grid-scroll-stability` then passed 8 of 8. The other 15 specs were not rerun after that spec-only change |
| Docked editor, 14 notes, 1280×800, measured | Before: More options at 2574 px, Back at −1500 px after scrolling. After: More options 744–784 px and Back 0–44 px, before and after scrolling 1500 px. Search field 43 → 211 px |

Screenshots: `docs/design/screenshots/2026-10-29-secondary/{before,after}/` from
[`web/scripts/captureSecondaryScreens.mjs`](../../web/scripts/captureSecondaryScreens.mjs):
sign-in, conflict review (light and dark, 1280 and 390), editor float/dock/full screen and the
docked editor after scrolling, the reminder picker with a past time, settings.

Not done in this pass: Android/Windows equivalents of these screens, a screen reader, a real
notification permission prompt (headless Chromium), a conflict created by two real devices (the
copy is seeded through import).

## Known limitations and remaining issues

See the register's open rows: U-11 (Compose untitled card), U-12 (benchmark flake). Native rendering, screen
readers, Safari/Firefox and Supabase-backed journeys were not exercised here.
