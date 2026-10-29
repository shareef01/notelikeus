# UI/UX audit — notes screen, search and cards (2026-10-29)

Baseline: `main` at `e849cd92` (v2.4.0 + #378, #379). Branch: `design/ui-ux-audit-2026-10-29`.

This is the fifth UI/UX pass. The earlier ones ([`../AUDIT_UI_UX_2026.md`](../AUDIT_UI_UX_2026.md),
[`../UI_UX_AUDIT.md`](../UI_UX_AUDIT.md), [`UI_UX_REDESIGN.md`](UI_UX_REDESIGN.md),
[`../UI_UX_AUDIT_2026-10-21.md`](../UI_UX_AUDIT_2026-10-21.md)) settled the design system, reflow,
touch targets, contrast, the save/sync vocabulary, toasts, dialog focus and keyboard formatting.
This pass re-checked those against the current code first and only then looked for what is left.
It changes no data-layer, sync, storage, backup or schema code.

## Method

- **Source reading** of the notes screen, top bar, filter row, note card, grid, editor, toolbar,
  bottom bar and sync-status code on web and the matching Compose card.
- **Rendered web app.** Vite dev server, guest mode, headless Chromium (Playwright) and the in-app
  browser. Synthetic data only: [`web/scripts/captureAuditScreens.mjs`](../../web/scripts/captureAuditScreens.mjs)
  imports ten notes as a JSON backup (pinned and coloured notes, seven labels including mixed-case
  and long names, an untitled note, a checklist, a conflict copy, a long unbroken URL, a long title).
- **Before/after captures** at 1440, 1280, 768, 390 and 320 px, light and dark, plus compact and
  list densities, the editor and the recent-searches panel after Tab.
  The older [`captureUiScreens.mjs`](../../web/scripts/captureUiScreens.mjs) set was also run on the
  baseline (`screenshots/2026-10-29/before/*-desktop-*`, `*-tablet-*`, `*-mobile-*`, `auth`, `empty`,
  `settings`, `search`).
- **Not done**: Android emulator/device and the Windows app were not run (Gradle cannot open a
  loopback connection on this machine, inside or outside the command sandbox), no screen reader,
  no Safari/Firefox, no Supabase-backed flows (Docker/local Supabase not running).

Legend for *Evidence*: **R** reproduced in a real browser, **T** reproduced by an automated test,
**S** source inspection only.

## Already right — verified, not changed

| Area | What was checked | Result |
|---|---|---|
| Editor toolbar activation | `RichTextToolbar.tsx`: action on `click`; `pointerdown`/`mousedown` cancelled to keep the body's focus and selection; Ctrl/Cmd+B/I in the body; roving tabindex with arrow keys (A15 of 2026-10-21 is now implemented) | Correct (S). Existing `RichTextToolbar.test.tsx` and `rich-text.spec.ts` pass |
| Save & sync wording | `noteSyncStatus.ts`: "Synced" only when the note has no unconfirmed edit; save failure is a red status with Retry | Matches DESIGN_SYSTEM §5.1 (S) |
| Toasts / Undo | 8 s with an action, paused on hover/focus, Ctrl+Z | Unchanged since 2026-10-21 (S) |
| Reflow | No horizontal scroll at 320–1440 px with long titles, a long unbroken URL and seven labels | R (captures); `reflow.spec.ts` |
| Long unbroken text on cards | Wraps inside the card (`break-words`) | R |
| Search speed | 10,000 notes: 163 ms cold, 31 ms per further keystroke (`performanceBenchmark.test.ts`, Node, unthrottled) | Measured |

## Issue register

| ID | Platform | Screen / component | Sev | Kind | Evidence | User impact | Root cause | Fix | Status | Acceptance / verification |
|---|---|---|---|---|---|---|---|---|---|---|
| U-01 | Web | Top bar — recent searches | **P1** | Confirmed defect | **R**: focus the empty search field, Tab → "Clear" is focused, 200 ms later the panel unmounts and focus is on `<body>` (`before/search-recent-tab-1440-light.png`: panel gone, second Tab lands on Settings). **T**: 5 new tests fail on the old code | Recent searches cannot be used from the keyboard; focus is lost (WCAG 2.1.1, 2.4.3) | Panel visibility followed the input's own focus, cleared by a 200 ms timer on blur | Visibility follows focus within the whole search form (`relatedTarget` containment); Escape closes the panel and keeps focus in the field; "Clear" returns focus to the field; chips and "Clear" get the 2 px focus ring; chips 13 px, 32 px tall | Fixed | Tab reaches every chip with the panel open; Enter runs it; Escape closes; focus never falls to `<body>`. `TopBar.test.tsx` (5 new), `after/search-recent-tab-1440-light.png` |
| U-02 | Web | Note card — label chips | P2 | Confirmed defect | **R** (`before/notes-1440-light.png`): chips show no fill on any card | Chips read as stray, indented words; nothing marks them as tappable filters | Fill was white at 12 % (invisible on white cards) or the note's own colour at 10 % (invisible on that note) | Fill is the card's text colour at 14 %, the value Compose already uses (`NoteEmphasis.Decorative`) | Fixed | Chips visible on default and coloured cards in light and dark. `after/notes-*` |
| U-03 | Web, Android, Windows | Note card — labels, timestamp, small metadata | P2 | Confirmed (readability) | **R** web; **S** Compose. Labels 9–10 px/sp uppercase with wide tracking; timestamps 10–11; checklist progress 11 | Custom names lose their casing ("iPhone backup" → "IPHONE BACKUP", "ToDo" → "TODO"); metadata below the 12 px token of DESIGN_SYSTEM §3 | Pre-token sizes | Labels 12 (11 in compact) in the user's casing, medium weight, long names truncate with the full name as tooltip; timestamps 12 (11 compact); checklist count and "+N more" 12. Same sizes in Compose | Fixed (web verified; Compose not built locally) | No card text below 11 px; labels match what the user typed. `NoteCard.test.tsx`, captures |
| U-04 | Web, Android, Windows | Note card — "+N" label overflow | P2 | Confirmed (a11y) | **S** | "+2" told a screen-reader or mouse user nothing about which labels | Plain text | Web: screen-reader text and `title` "2 more labels: Q4, garden". Compose: `contentDescription` with the same text (`cd_more_labels`) | Fixed (web tested; Compose not built locally) | `NoteCard.test.tsx` |
| U-05 | Web | Notes grid — section headers | P2 | Confirmed defect | **R** (`before/notes-1440-light.png`): "Today" 8 px under the pinned cards | Pinned and dated groups run together | Header had `first:pt-0`, but it is always its wrapper's first child, so no header ever got top space | Space above every group but the first, on the wrapper | Fixed | `after/notes-1440-*`, `after/notes-390-*` |
| U-06 | Web | Note card — untitled notes (grid, compact) | P2 | Confirmed (layout) | **R**: an untitled card started with an empty row holding only the time | Looked like a missing title; wasted a line per card | Status column needed a flex sibling; the sibling was an empty span | The preview takes the title's place, level with the time (D15) | Fixed | `NoteCard.test.tsx`; `after/notes-1440-light.png` ("Call the dentist") |
| U-07 | Web | Editor toolbar — Checklist button | P2 | Confirmed (recognition) | **R**: the glyph was Material "article" (a page of lines) | Reads as "document", not "checklist"; differs from Compose, which uses `Icons.Default.Checklist` | Wrong path | Material "checklist" path | Fixed | `after/editor-1440-light.png` |
| U-08 | Web | Empty notes view | P2 | Opportunity | **R** (`before/empty-1280-light.png`) | Sort and nine colour swatches sit above "Notes you add appear here" on first run | Filter row is unconditional | The row is hidden when the current view has no notes and no filter or search is set; kept while loading so nothing shifts | Fixed (follow-up) | `TopBar.test.tsx`; `filter-row`, `sort-order`, `search-journey`, `labels-journey`, `palette-contrast` specs now add a note first; `2026-10-29-followups/*/empty-*` |
| U-09 | Web | Notes grid at 600–1023 px | P3 | Opportunity | **R**: measured one column up to 1023 px, cards up to 600 px wide at 840 | Tablets and half-screen desktop windows showed a single column of very wide cards | 300 px column minimum | Grid columns at least 256 px, at most three; the loading skeleton now shares the same values | Fixed (follow-up) | Measured: 600–1024 px two columns (256–368 px), 1280–1920 px three columns as before, 390 px one. `2026-10-29-followups/*/notes-768-*` |
| U-10 | Web | Editor — "Convert to checklist" text button | P3 | Opportunity | **R**: with a labels row it sat below the fold, under the footer's gradient | Duplicated the toolbar's Checklist button | Placed after a `min-h-[60vh]` body | Shown only on an empty note, as "+ Add checklist"; a note with text converts through the toolbar | Fixed (follow-up) | `palette-contrast` spec measures "+ Add checklist"; `touch-targets`, `accessibility` unchanged |
| U-11 | Compose | Untitled grid card | P3 | Suspected | **S** (`NoteCard.kt`: "The row itself stays: it carries the timestamp") | Same as U-06 | Same | Same as U-06 | Not done — needs a device to check | — |
| U-12 | Web tests | `performanceBenchmark.test.ts` | P3 | Test flake | **T**: failed once while the screenshot run shared the CPU; passes alone (163 ms / 31 ms against 300 / 80 ms limits) | CI noise | Wall-clock thresholds | Leave, or move to a benchmark job | Not done | — |
| U-13 | Web | Editor, dock layout | **P1** | Confirmed defect | **R**: 14 notes, dock a note at 1280×800: More options and the save status at y = 2574 px; scrolling to them puts Back and the layout switch at y = −1500 (`2026-10-29-secondary/before/editor-dock-scrolled-1280-light.png`: no status line) | Colour, labels, delete and the save status out of reach; the note list and the editor scroll as one page | The dock pane was a plain flex item, as tall as the list beside it | Pane is sticky and one viewport tall (`h-dvh`), so the editor scrolls inside itself | Fixed (secondary pass) | Measured after: footer 744–784 px and Back 0–44 px before and after scrolling the list 1500 px |
| U-14 | Web | Top bar beside a docked editor | P2 | Confirmed defect | **R**: search field 43 px wide, placeholder cut to "S" | Search unusable while docked | View-size switch and Ctrl K hint kept their space in a ~395 px column | Top bar `narrow` mode while docked drops the view-size switch and hint | Fixed (secondary pass) | Search field 211 px at 1280 |
| U-15 | Web | Sidebar (≥ 768 px) | P2 | Confirmed defect | **R**: with a list longer than the window, Notes/Archive/Trash scroll away; Collapse and Sign in sit at the bottom of the page (absent from every 10-note capture of the first pass) | Navigation and Sign in out of reach on desktop | `md:static` sidebar stretched to the page height | `md:sticky md:top-0 md:h-dvh`. Side effect, measured: pinning a note while scrolled down no longer shifts the card being read (was 32 px) | Fixed (secondary pass) | `after/editor-dock-scrolled-1280-light.png`: sidebar complete after scrolling; `grid-scroll-stability` now asserts the visible card stays put |
| U-16 | Web | Reminder picker | P2 | Confirmed defect | **R**: a past date leaves Set enabled (`before/reminder-past-*`); the editor then rejects it in a toast after the dialog closed (`useNoteEditor.ts`) | The typed date and time are lost; the error appears where it cannot be fixed | Validation only after the dialog closed | Set disabled for a past time; inline "That time has passed. Choose a time in the future." with `aria-invalid` | Fixed (secondary pass) | `ReminderPickerDialog.test.tsx` (new, 2) |
| U-17 | Web | Reminders — notifications blocked | P2 | Usability | **S**: toast said "Enable notifications to use reminders" | Once denied, the browser never asks again; the message gave nothing to press | — | Says the site is blocked and where to allow it (browser site settings) | Fixed (secondary pass) | Source |
| U-18 | Web | Conflict review — Keep current note / Keep conflicted copy | P2 | Confirmed (a11y) | **S** then **T**: no focus style and no disabled style, unlike the dialog's other buttons | Keyboard focus invisible on the two choices that move a version to Trash (WCAG 2.4.7) | Local class without `CHROME_FOCUS` | Shared focus ring and `disabled:opacity-40` | Fixed (secondary pass) | `ConflictReviewDialog.test.tsx` |
| U-19 | Web | Conflict review — comparison | P3 | Usability | **R**: amber highlight with no key on screen; pictures not mentioned | A sighted user has to guess what the highlight means; a version whose only difference is a picture looks identical | — | One-line key under the versions; "N pictures" under each version's date | Fixed (secondary pass) | `ConflictReviewDialog.test.tsx`; `after/conflict-review-*` |

Checked in the secondary pass and **not** changed: sign-in screen (guest path is first-class and explains local storage; the "Test login" panel is compiled only into dev and e2e builds), Settings at 390 and 1280 px, float and full-screen editor layouts at 1280 px, the phone editor at 390 px, conflict review stacking the two versions on phones.

No P0 found. Nothing here touched persistence, sync, encryption, backup format or attachments.

## Design-system changes

Recorded in [`DESIGN_SYSTEM.md`](DESIGN_SYSTEM.md) §3 and §5:

- Metadata (timestamps, label chips, checklist progress, "+N") is 12 px/sp, 11 in compact; never
  uppercase for user-entered text. Uppercase stays for fixed chrome overlines only.
- Label chip fill = the card's text colour at 14 % on both clients.
- "+N" overflow names the hidden labels to assistive technology.

## Remaining roadmap (impact ÷ effort)

1. ~~Compose tests for U-03/U-04~~ — passed in CI on #381; still to be looked at on a device.
2. ~~U-08, U-09, U-10~~ — fixed in the follow-up branch `design/ui-ux-audit-followups`.
3. ~~Conflict review, reminders, settings, sign-in, editor layouts~~ — audited in the secondary pass (U-13 to U-19).
4. U-11 untitled Compose card, on a device.
5. Screen-reader pass (NVDA, TalkBack, Narrator) — still never done.
6. Compose: whether Android/Windows have an equivalent of U-16 (past reminder) — not checked.
