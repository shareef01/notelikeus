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

## Known limitations and remaining issues

See the register's open rows: U-08 (empty-view filter row), U-09 (tablet columns), U-10 (duplicate
checklist control), U-11 (Compose untitled card), U-12 (benchmark flake). Native rendering, screen
readers, Safari/Firefox and Supabase-backed journeys were not exercised here.
