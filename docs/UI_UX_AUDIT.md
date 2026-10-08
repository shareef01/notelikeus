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

### F2 — Note colour filters are clipped on a phone, with no affordance — USABILITY ISSUE — recorded, not changed

At 390px the filter row shows roughly six chips and cuts the rest mid-chip
(`FilterRow.tsx`: a horizontally scrolling row inside `min-h-9 min-w-0`). The row does scroll once
reached, and F1's fix stops it dragging the *page* with it, so this is scoped as discoverability
rather than a defect: a user cannot tell that more colours exist. Suggested treatment, consistent
with the brief's own note about that file — an explicit sort/filter control with a scroll cue on the
row. Not implemented this pass; it is a redesign of the filter surface rather than a correction, and
it deserves its own change with before/after evidence.

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
