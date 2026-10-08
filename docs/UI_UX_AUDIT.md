# UI/UX Audit — Web and Compose (2026-10)

Baseline: `main` at `42bae9bd668178d6f359c647c54e80b5123d2d28`, working tree clean.
Change-by-change record and regression checklist: [`UI_UX_IMPLEMENTATION_LOG.md`](UI_UX_IMPLEMENTATION_LOG.md).
Method: source reading **and** a real browser — Playwright driving the app in guest mode at the
widths the brief names, with measurements taken from the live DOM rather than inferred from classes.

What could not be inspected here, stated plainly: **no Android runtime**. This machine has the
emulator binary and `/dev/kvm`, but no AVD and no system images, and pulling one is a ~1 GB download
outside this task's scope — so every Android statement below is source-backed, not rendered. Web
findings are rendered except where marked otherwise.

## Baseline test status (before any change)

| Suite | Result |
|---|---|
| `web`: typecheck · lint | EXIT 0 · 0 errors, 76 pre-existing warnings |
| `web`: unit | 105 files, 785 tests, 0 failures |
| Kotlin `composeApp` unit + desktop, `androidApp` unit | 1322 tests, 0 failures |
| Screenshots | captured: entry + notes screen, ×5 widths ×3 themes (see below) |

Everything was already green, which matters: the findings below are design and correctness defects
that the existing suites cannot see, not regressions.

## Findings

### F1 — Web notes screen scrolled sideways on phones and tablets — CONFIRMED DEFECT — fixed

**Severity: high** (WCAG 1.4.10 Reflow; every 320–768px user). **Platform: web.**

Reproduction: guest mode → notes screen at 320, 360, 390 or 768px. Measured on `main`:

```
viewport 320  scrollWidth 424   (104px of sideways scroll)
viewport 390  scrollWidth 424   ( 34px)
viewport 768  scrollWidth 769   (  1px)
```

Traced, not guessed: the elements extending past the right edge were the header's toolbar row
(`div.flex.h-14.items-center`, measured 412px wide) and the search field's wrapper, both flex items
of the header's flex container carrying `min-width: auto` — so neither could shrink below its
content. The search field and its input already had `min-w-0`; the **rows** were the missing link,
which is why the defect only appeared once the field, the view toggle and the `Ctrl K` hint were all
present.

Treatment: `min-w-0 flex-1` on the toolbar row (`TopBar.tsx`) and on its selection-mode twin
(`SelectionBar.tsx`). After: `scrollWidth == clientWidth` at **320, 360, 390, 768, 1024, 1440**.

Guarded by `web/e2e/reflow.spec.ts`, which asserts the measurement at each width on both the entry
and notes screens — a class-name assertion would pass while the defect returned.

### F2 — Note colour filters were clipped on a phone with no cue, and unreachable by keyboard — FIXED

At 390px the filter row showed roughly six chips and cut the rest mid-chip. Two things were wrong,
and the second was worse than the clipping:

1. **No affordance.** The rows scroll with their scrollbar hidden (`overflow-x-auto scrollbar-none`
   in `FilterRow.tsx`), so nothing said more colours existed.
2. **Not keyboard-reachable.** An overflow container is not focusable by default, so every colour
   past the visible edge — and every label past the first few on a phone — could only be reached by
   swiping (WCAG 2.1.1).

Treatment: both rows get `tabIndex={0}` and an accessible name (`Filters`, `Label filter`), so they
can be focused and scrolled with the arrow keys, and a **"Scroll filters right"** button appears
over the right edge while there is genuinely more to the right.

The cue took two attempts, and the first one is worth recording because the mistake was instructive:
a gradient fade is the usual idiom, but on the dark and AMOLED themes it is a black fade over a black
surface — the affordance added to make hidden colours discoverable was itself undiscoverable
(screenshot: `filters-cue-390.png`). A button reads on every theme *and* does what the user wanted:
pressing it scrolls the row. It respects `prefers-reduced-motion`.

The second attempt had its own bug, caught by verifying rather than assuming: keyed on "content is
wider than the box", the cue stayed visible after the row had been scrolled to its end, offering more
when there was none. It is now keyed on `scrollLeft + clientWidth < scrollWidth`, and the hook
measures on scroll, resize, element resize, child mutation and re-render.

Verified in a real browser: cue visible at 390px, hidden at 1440px where the row fits, hidden at the
end of the row, back when scrolled to the start, and `ArrowRight` on the focused row scrolls it
(0 → 40). Guarded by `web/e2e/filter-row.spec.ts` — four tests on two browser projects.

### F3 — Theme is not driven by `prefers-color-scheme` — OBSERVATION — expected, documented

Light and dark screenshots taken with `colorScheme: light`/`dark` are byte-identical: the app takes
its theme from the stored preference (`settingsStore`, with the legacy-shape migration described in
`docs/DECISIONS.md` D2), not the OS query. That is a deliberate read-time-migration choice, not a
defect — recording it here so a later reviewer does not mistake it for one. Verifying the light,
dark and AMOLED palettes therefore needs the preference seeded, not the media query; the harness
that does so is `/tmp/ui-shots2.mjs` in the working notes of this pass.

### F5 — Sort control made the destination visible before the action — FIXED

The sort chip cycled Manual → Newest → Oldest on every tap. A user who wanted "Oldest first" had to
press, read the toast, and press again if they had guessed wrong, and from the far side of the list
the control looked identical either way. It now opens a chooser listing all three destinations with
the active one marked.

Built on `ResponsiveSheet`, the codebase's existing pattern for a short list of choices — bottom
sheet on a phone, centred modal above `md` — so focus trapping and Escape come from that component
rather than a new menu primitive (`role="menu"` exists nowhere in this codebase, and inventing one
for a three-item list would have been the wrong trade).

Verified in a browser and in e2e: the chooser lists every destination with `aria-pressed` on the
active one; choosing applies it, closes the sheet and updates the chip; and the **order of the notes
themselves follows the choice** — two notes written in a known order swap positions between
"Oldest first" and "Newest first". That last assertion compares on-screen positions rather than card
markup, after a first version silently matched the "LIBRARY" heading instead of a note.

The settings sheet's sort row still cycles; see next steps.

### F6 — Touch targets: one control below the AA minimum, and a long tail under the design system's own — FIXED (foundation)

Measured at 390px across the three main screens, before any change:

| Screen | Controls | under 44px | under 24px (WCAG 2.5.8 AA failure) |
|---|---|---|---|
| Entry | 4 | 3 (the 42px primary buttons) | 0 |
| Notes | 23 | 15 (28px swatches, 40px chrome, 36px drawer close) | 0 |
| Editor | 35 | 23 | **1** — "+ Add checklist", 103x20 |

The design system already knew the number it wanted: `.filter-chip` sets `min-h-11`. Nothing
enforced it anywhere else.

Treatment, as Phase 1 foundation work rather than one-off patches: a shared `.tap-target`
interaction state in `globals.css` beside the existing component classes, which expands a control's
**hit area** to 44px via a pseudo-element and leaves the visual box alone — that is what a target
size guideline asks for, and it is the only way a 20px text button or a 40px icon button can comply
without changing how either reads. Applied to `+ Add checklist`, the four chrome icon buttons
(40px, 36px at `sm`) and the drawer's 36px close button.

Verified: every `.tap-target` control reports a 44x44 computed hit area; a real click 8px above the
20px button's box activates it; a click 40px above does not; a click on the visual box still works;
and clicking the title field beside it is not intercepted. `globals.css` already turned off
animations and transitions under `prefers-reduced-motion`, so nothing new was needed there.

Recorded rather than fixed: the colour swatches stay 28px. They clear AA's 24px, but a square
expansion would make adjacent 28px swatches eight pixels apart fight over the same pixels, so the
hit areas would overlap and the winner would be paint order. Fixing that properly means spacing the
swatches differently or giving them a taller row — a layout decision, not a token one.

**Closed as a gap:** the seeding method this entry described as missing now exists —
`notelikeus-settings` in `localStorage` before load — and the palette check below uses it to render
all three themes.

### F7 — The checklist tick box was 20×20, and the first touch-target pass never looked there — FIXED

A sweep of the editor in four states (empty, with text, in checklist mode, with two items) found a
20×20 tick box beside an editable text field: below WCAG 2.5.8's 24px minimum. More useful than the
defect was what it said about the check — the touch-target spec added the round before measured the
notes screen and an empty editor only, which is how a sub-minimum target survived that pass. Both are
fixed; the box is 24px visually with a 24×44 target via `.tap-target-y`, and the spec measures
checklist mode. A square target would have covered the text field and stopped it being edited.

### F8 — Palette check: dark, AMOLED and note colours — MEASURED, NO DEFECT

Rendered at 1440px in all three themes, with the theme seeded rather than guessed:

| Theme | Page surface | Active swatch outline vs page |
|---|---|---|
| dark | `rgb(28,28,28)` | 15.6:1 |
| AMOLED | `rgb(0,0,0)` | 21.0:1 |
| light | `rgb(255,255,255)` | 18.9:1 |

The fills measure 1.12:1 to 1.23:1 against the page — deliberately faint, because D1 makes a note
colour a tonal surface rather than a stripe. The outline is what identifies the swatch, and it clears
WCAG 1.4.11's 3:1 by a wide margin. An earlier version of the check asserted "every fill is under
3:1" and failed on the AMOLED and light themes: a test asserting the wrong thing about an intentional
design decision. There is no assertion on the fills; the measurement is recorded instead, and
`web/e2e/palette-contrast.spec.ts` guards the outlines.

### F4 — Android: not rendered, by tooling limits — REQUIRES VALIDATION

The Compose findings the brief anticipates (notes home spacing, long-press selection, filter sheets,
editor typography, IME and inset handling, TalkBack, font scaling) were **not** produced, because
nothing here can render the app. Claiming them from source alone would be exactly the kind of
inference this repository's own audits call out. Android remains covered by its 1296 unit tests on
this machine and by the instrumented lanes in CI.

## What was verified rather than assumed

- The reflow defect was found by measurement after the first screenshots showed the sign-in gate:
  the harness was extended to enter guest mode (`Continue without an account`) before any conclusion
  was drawn about the notes screen.
- Before/after screenshots exist for the notes screen at 5 widths × 3 themes, plus an "after" set.
  The before/after difference is the clipped chips and the page-level scrollbar, both visible.
- No claim in this document rests on a suite that was not run.

## Untouched by design

No schema, sync contract, encryption behaviour, backup format, theme preference storage or
cross-client contract was modified. Nothing in `composeApp`, `androidApp`, `workers/` or `supabase/`
was touched. No dependency was added. No analytics, tracking or remote asset was introduced.

## Next steps, in the order the brief sets out

1. The settings sheet's sort row still cycles on tap (F5's counterpart). That sheet is itself a
   `ResponsiveSheet`, so stacking a second one over it would put two focus traps and two backdrops on
   screen at once; the right fix is an inline three-way choice in that row, and it is its own change.
2. Phase 1 foundation: `.tap-target` is in place (F6). The remaining token work is spacing and\n   radius scale review, and the swatch spacing question F6 records.
3. The remaining web journeys (B–O) — note creation, checklists, rich text, pin/archive/delete,
   search and smart views, labels, attachments, reminders, bulk actions, drag-reorder, backup,
   offline and sync-failure states — each measured the same way, since only journeys A and parts of
   B/P have been walked here.
4. Android, on a machine with an AVD — or by extending CI to publish screenshots from its
   instrumented lanes, which is the only route that exists today.
