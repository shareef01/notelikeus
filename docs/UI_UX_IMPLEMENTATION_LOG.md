# UI/UX Implementation Log

Branch `ui-ux-audit-and-reflow`, based on `main` at `42bae9bd668178d6f359c647c54e80b5123d2d28`.
Every entry below was verified in a real browser or by the suite named against it. Nothing here is
planned work described as done.

## How to read this

Findings and their rationale live in [`UI_UX_AUDIT.md`](UI_UX_AUDIT.md). This file is the reverse
view: what changed, in what order, and what evidence exists for each change. It exists because the
audit answers "what is wrong and why", and a reviewer checking the diff needs "what did this commit
touch, and how was it checked".

## Commits

| Commit | Finding | Change | Evidence |
|---|---|---|---|
| `a9c546b` | F1 | `min-w-0 flex-1` on the header's toolbar rows (`TopBar`, `SelectionBar`) | `scrollWidth == clientWidth` at 320/360/390/768/1024/1440 |
| `2c5bfca` | — | corrected `reflow.spec.ts` to use the documented guest-mode entry and a width-independent anchor | 26/26 |
| `c0cc4f5` | F2 | keyboard-reachable filter rows + a "Scroll filters right" cue keyed on `scrollLeft + clientWidth < scrollWidth` | visible at 390, absent at 1440, gone at the row's end, `ArrowRight` scrolls it |
| `171016a` | F5 | the toolbar's sort chip opens a chooser built on `ResponsiveSheet` instead of cycling | chooser lists all three, applies, closes; notes reorder between Oldest and Newest |
| `df05230` | F6 | shared `.tap-target` interaction state; applied to 6 controls | 44×44 computed hit areas; a click 8px above a 20px control activates it |
| `67508fa` | F5 counterpart | the settings sort row becomes an inline three-way choice; the cycle and its dead code removed | settings and toolbar agree on one state; 24/24 |
| `ba18d1f` | F7 | `.tap-target-y` for dense rows; the checklist tick box becomes 24×24 visual with a 24×44 target | nothing under 24px in any editor mode, checklist included |
| `48afd71` | F8 | palette check across dark, AMOLED and note colours | outlines 15.6:1 / 21.0:1 / 18.9:1; fills recorded, not asserted |
| `f585116` | F6 (swatches) | 32px swatches with a 12px gap so a 44px target cannot overlap its neighbour | centres 44.0px apart, zero overlapping pairs, 9 swatches at 44x44 |
| `074c755` | cue gutter | the scroll cue is a flex sibling of the scroller, not an overlay | cue at x=362 with the scroller ending at x=358; zero swatches covered |
| `7ae2b0c` | F9 | journey E swept and guarded | no defect; 8/8 across two browser projects |

**F7 — the checklist tick box, found by auditing journey C. Fixed.** A sweep of the editor at 390px —
empty, with text, in checklist mode, and with two items — turned up a 20×20 tick box beside an
editable text field. Two things came out of it: the box was below the 24px minimum, and the
touch-target suite written in the previous round **did not look at checklist mode at all**, which is
how a sub-minimum target survived that pass. Both are fixed: the box is 24px visually, and the test
now measures this mode.

A square hit area was the wrong answer here — it would have covered the text field and stopped it
being edited, turning a target-size fix into a usability regression. `.tap-target-y` grows the target
vertically to 44px and leaves the width to the control, which is what the row can give. The sweep
reported `20x20` for this box before the change, and the checker compares against 24, so the new test
fails on the old code rather than merely passing on the new.

## Findings, and what is true about each

**F1 — sideways scroll on phones. Fixed.** Traced to `min-width: auto` on a flex item, not guessed:
the toolbar row measured 412px inside a 320px shell.

**F2 — clipped filters with no cue, unreachable by keyboard. Fixed.** The first cue was a gradient,
which on the dark and AMOLED themes was a black fade over a black surface — the affordance meant to
solve undiscoverability was itself undiscoverable. Replaced with a button, which also does what the
user wanted. Its first version stayed visible after the row was scrolled to its end; caught by
checking behaviour rather than trusting the implementation.

**F3 — theme follows the stored preference, not `prefers-color-scheme`. Not a defect.** Deliberate
read-time migration (`DECISIONS.md` D2). Recorded so a later reviewer does not mistake it for one.

**F4 — Android was not rendered here. Not claimed.** No AVD and no system images on this machine;
every Android statement in the brief's Phase 3 remains unstarted rather than guessed from source.

**F5 — sort control hid its destination. Fixed, both surfaces.** Toolbar opens a chooser; settings
offers an inline three-way choice. One store, so the two cannot disagree — asserted, not assumed.

**F6 — touch targets. Fixed as foundation work.** One control below WCAG 2.5.8's 24px minimum
(`+ Add checklist`, 103×20) and a long tail under the 44px this system already set for chips. The
swatches deliberately keep their 28px box: a square expansion would make adjacent swatches 8px apart
fight over the same pixels, so the winner would be paint order.

## Regression checklist

For a reviewer, or for me after the next change. Each item names how it is checked, so "it looks
fine" is never the answer.

1. **No screen scrolls sideways** — `web/e2e/reflow.spec.ts` at six widths, entry and notes screens,
   desktop and Pixel-5 emulation.
2. **Clipped filter rows stay reachable** — `web/e2e/filter-row.spec.ts`: cue appears only while
   there is more to the right, disappears at the end, and returns; `ArrowRight` scrolls the row.
3. **Sort behaves identically on both surfaces** — `web/e2e/sort-order.spec.ts`, including that the
   notes themselves reorder and that settings and toolbar agree.
4. **Nothing drops below a 24px target** — `web/e2e/touch-targets.spec.ts`, measured against the
   effective target rather than the box, since `getBoundingClientRect` cannot see a pseudo-element.
5. **Unit and static suites** — `npm run typecheck`, `npm run lint` (0 errors; the 76 warnings are
   pre-existing), `npm run test` (785).
6. **The whole e2e suite**, not just the new specs — 78 passed, 1 skipped as of `df05230`. The
   accessibility, dialog-a11y, note-lifecycle, image-ingestion, backup-import, account-switch and
   save-failure specs all touch the chrome these changes altered.
7. **Kotlin** — untouched this pass; 1322 tests across `composeApp` unit/desktop and `androidApp`
   unit were green at baseline and nothing in this branch reaches them.

## Open, in the order I would take them

3. Journeys B–O, measured the same way as A.
4. Android, on a machine with an AVD or from CI's instrumented lanes.
