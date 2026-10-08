# UI/UX Audit — Web and Compose (2026-10)

Baseline: `main` at `42bae9bd668178d6f359c647c54e80b5123d2d28`, working tree clean.
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

1. F2's filter/sort surface redesign on web, with screenshots.
2. The remaining web journeys (B–O) — note creation, checklists, rich text, pin/archive/delete,
   search and smart views, labels, attachments, reminders, bulk actions, drag-reorder, backup,
   offline and sync-failure states — each measured the same way, since only journeys A and parts of
   B/P have been walked here.
3. Android, on a machine with an AVD — or by extending CI to publish screenshots from its
   instrumented lanes, which is the only route that exists today.
