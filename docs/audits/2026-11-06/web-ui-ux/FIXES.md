# UI/UX audit follow-up

The original [audit](REPORT.md) and its screenshots describe the pinned production revision. This follow-up addresses all twelve findings; the original evidence is preserved.

| Finding | Implementation and regression coverage |
| --- | --- |
| UX-01 | Mode conversion keeps a guarded before/after snapshot, including checked items. External documents start a fresh typing history. Keyboard Undo and visible Undo/Redo conversion actions restore the saved checklist and persist it after reopening. Later edits cannot be overwritten by an obsolete conversion snapshot. |
| UX-02 | Shared dialogs have a bounded, scrollable panel. Tests exercise long confirmations, actual conflict comparisons, and incomplete account backups at 320×568 and 720×450. |
| UX-03 | Link editing captures the selection and document, prefills the current URL, updates the full link mark, and offers Remove link. It refuses stale document selections and invalid URLs. Browser tests verify unchanged labels and exactly one resulting anchor. |
| UX-04 | Label inputs shrink within their flex row and the Create action retains its width. Browser tests measure the action inside a 320px viewport. |
| UX-05 | Shared dialogs use the visual viewport's height and offset, with safe-area padding. Reminder picking uses that shared scaffold; label management also follows the visible viewport. A simulated keyboard test asserts that the Link dialog and submit action remain above the keyboard boundary. |
| UX-06 | Signed-in destructive settings labels use separate light/dark red tokens. Isolated signed-in light/dark fixtures pass axe checks. |
| UX-07 | Adding an item returns its ID. Enter inserts after the active item and focuses that ID; Add focuses its own new item even when completed items sort last. |
| UX-08 | Opening the editor creates an in-app browser history entry. Back first dismisses nested focus traps, then uses the existing save guard before closing. A failed save keeps the editor open; Forward reopens the saved note, including a newly created note's allocated ID. |
| UX-09 | Save feedback compares the current persisted fields with the last successful revision immediately, including the debounce window and typing during a slow save. Viewing or changing the selection still does not edit the note date. |
| UX-10 | Create and Rename validate case/whitespace-normalized names across registered and note labels. Rejected duplicates retain the input and announce an associated inline error. |
| UX-11 | Title clearance is reserved for the selection circle on fine and coarse pointers. Browser tests measure zero overlap between the circle and first title character. |
| UX-12 | The cloud-wipe notice uses light/dark warning colors, stacks its message/actions on narrow screens, and wraps actions. The explicit reupload choice is unchanged; fixture clicks are intercepted. |

## Verification

- Full web unit suite: 174 files, 1,341 tests; ten tooling security tests also passed.
- Desktop/mobile formatting regression suite: 12 passed.
- Desktop/mobile editor, workspace, scrolling, date, and audit-fix browser checks passed. Focused checks additionally cover label rename rejection and card checkbox geometry.
- Isolated recovery surfaces: 20 passed, covering light/dark contrast and actual short-viewport conflict/backup states. Run `npx playwright test --config playwright.surfaces.config.ts` from `web/`; CI runs this separately from normal app E2E.
- The four-locale/time-zone accessibility, reflow, and touch-target matrix passed 135 cases initially. One case hit trace cleanup failure because another local suite cleaned its parent artifact directory; that case passed when rerun in an isolated output directory. No application assertion failed.
- Type checking passes. Lint reports warnings but no errors.

The keyboard boundary is simulated in Chromium; a physical Pixel/IME check remains outstanding. Real destructive account/cloud operations are intentionally replaced with no-op or intercepted fixture actions. These fixes do not turn the audit's documented device, browser, assistive-technology, or storage-pressure gaps into verified coverage.
