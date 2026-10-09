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

**The swatches were the exception, and no longer are.** They were left at 28px because a square
expansion would have made adjacent swatches eight pixels apart fight over the same pixels. Resolved
by changing the geometry rather than the target: swatches are now **32px with a 12px gap**, which puts
their centres exactly **44px apart** — so a 44px target touches its neighbour at a point and never
overlaps it. Measured at 390px: nine swatches, `44x44` targets, centre spacing `44.0px`, zero
overlapping pairs. The row overflows further as a result, which is what the scroll cue (F2) is for,
and the cue is still present.


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

### F9 — Journey E (search and smart views): audited, no defect found — GUARDED

The toolbar is where this branch made its first change, and every spec so far exercised it at rest.
Search puts different content in the same row, so it was swept at 320px and 390px, step by step:
typing a match, typing a miss, clearing, focusing with history present, and `Ctrl+K`.

| State | Result |
|---|---|
| Sort chip, no query | enabled, labelled "Sort: Manual. Choose sort order" |
| Sort chip, query present | disabled, labelled "Sort locked to relevance while searching" |
| Page overflow | 0px at every step, at both widths |
| Recent-searches panel | 124px wide inside a 142px field at 320px; 194px inside 212px at 390px |
| `Ctrl+K` | focuses the field, as the hint claims |

That last row is worth calling out: the footer hint was *verified* rather than assumed, and it is
true. The history panel being narrower than the field it belongs to is what keeps it from escaping a
320px viewport, and it is now asserted — an absolutely positioned list sized to its contents is a
standard way for this to break.

Guarded by `web/e2e/search-journey.spec.ts`, four tests on two browser projects.

### F10 — Journey D (the editor's formatting and the note's survival): audited, no defect — GUARDED

Journey D is the next item after B (creation) and C (checklists) in the objective's own enumeration.
The journey definitions themselves are not in this repository — they came from the mission brief and
the pre-existing `AUDIT_UI_UX_2026.md` holds only its eleven findings — so the mapping is stated
here rather than assumed silently.

Two claims I got wrong before measuring, both worth recording because the first pass produced each:

1. **"The web editor has no rich text."** Its body is a plain `<textarea aria-label="Note body">`
   with no `contenteditable` and no ProseMirror/TipTap — and then tabbing from the title landed on a
   **Bold** button. `components/editor/RichTextToolbar.tsx` exists and writes **markdown into the
   plain text**: Bold wraps the selection in `**…**`, and Italic nests to `**_…_**`. The absence of a
   rich-text engine is not the absence of the feature.
2. **"Guest notes do not survive a reload."** They do. After a reload the *sign-in screen* is shown
   again — the guest session does not persist — so a check that looks only at the notes list reads it
   as data loss. Re-entering guest mode shows the note intact, which matches the sign-in copy: "Notes
   are saved locally on this device."

Recorded as an observation, not a defect: a guest who reloads lands on the sign-in screen rather than
back in their notes. The copy is accurate about the data, and re-entering is one tap, but the
behaviour is worth a deliberate decision rather than an accident.

Guarded by `web/e2e/rich-text.spec.ts`, two tests on two browser projects, discovery verified.

### F11 — On a touch device, pin/archive/trash were unreachable: the way into selection mode was hover-only — FIXED

**Severity: high.** The entry point to selection mode is a checkbox on each note card — the code
calls it "UX-B" — and it was revealed by `group-hover` alone, starting from `opacity-0`. A touch
device has no hover, so on a phone it stayed at **opacity 0 forever**. Everything behind it — Pin,
Archive, Move to trash — was therefore unreachable at every touch width, while looking correct on a
desktop.

Found by asking how a phone user reaches those actions and then reading the code rather than guessing
at gestures: a long press also enters selection mode, but nothing tells a user that, and the visible
affordance that should have was hidden by a media query that never matches on touch.

Treatment, one class: `pointer-coarse:opacity-100`, so the checkbox shows wherever the pointer is
coarse and desktop keeps its hover-reveal. Verified by measurement: at 390px with a coarse pointer the
checkbox is `opacity 1` at `24x24` (clear of WCAG 2.5.8's 24px minimum); at 1440px on a fine pointer it
is `opacity 0` at rest and `opacity 1` on hover, unchanged.

Guarded by `web/e2e/card-actions.spec.ts` — two tests, each with its own context because `hasTouch`
is a context option rather than something `emulateMedia` can fake. Discovery verified: 4 discovered,
4 passed. The touch test also asserts that tapping the checkbox actually surfaces Move to trash,
Archive and Pin, so the affordance is checked for what it leads to, not just for being visible.

### F12 — Journey F's data actions: pin, archive and trash all verified — CLOSED

**Closed.** With two notes on screen throughout, so "the wrong note was affected" would fail loudly:

| Action | Verified behaviour |
|---|---|
| Trash | soft; the note leaves the notes list, the unselected note is untouched, and it stays restorable in the trash view |
| Archive | the note leaves the notes view, the unselected note is untouched, and it appears in the archive |
| Pin | survives a reload, and the pinned marker is still shown afterwards |

Nothing here empties the trash, so no permanent path is exercised.

**Everything that went wrong on the way was instrumentation, not the product:**

- `getByRole('button', { name: 'Pin' })` matched `aria-label="Pink"` — Playwright's `name` is
  substring by default — so an early probe clicked a colour swatch and opened the editor.
- `page.locator('header').first()` always matched the **nav drawer's** header, because the drawer
  renders first in the DOM; scoping the bar's actions to it found nothing. The selection bar's own
  actions are unscoped now, with `.last()` only for `Archive`, which the drawer also has.
- The pin assertion named **"Unpin note"** — which exists in the DOM but belongs to the desktop
  quick-actions block and is `md:`-only, hence invisible at 390px. It read as a pin failure when the
  pin had persisted all along. The test now asserts the marker a phone user actually sees.

**The one product change this journey needed** was worth having regardless: the card's selection
checkbox is labelled by its note's title (`aria-labelledby` → the title's `id`), so a screen reader
hears which note a checkbox selects, and it is addressable by name rather than by DOM position.

**Still claims from inspection rather than test:** permanent deletion goes only through
`EmptyTrashDialog` and `BulkDeleteDialog`, and there is no undo on the trash transition itself.

Guarded by `web/e2e/journey-f-actions.spec.ts` — four tests, both browser projects, 8/8.


### F13 — The permanent-delete confirmation: audited, no defect — GUARDED

The only irreversible path in the product, and the one that most deserved checking. Trash is a view
(F12); `EmptyTrashDialog` and `BulkDeleteDialog` are where a note is actually destroyed.

Measured at runtime, with two notes trashed, so the count could be compared against reality:

| Property | Result |
|---|---|
| Count matches reality | "2 notes will be deleted permanently" for two trashed notes |
| Irreversibility stated | "This cannot be undone." |
| Focus starts on the safe option | **Cancel**, so a stray Enter destroys nothing |
| Destructive action is distinct | red fill against Cancel's muted grey |
| Dialog is named | via `aria-labelledby` → the `<h2>` title |
| Confirm when nothing to delete | disabled |

**A false alarm, recorded because the same mistake has now happened four times in this audit.** My
probe read `dialog.getAttribute('aria-label')`, got `null`, and I was one step from writing up a
missing accessible name. `ConfirmDialog` passes `ariaLabelledBy={titleId}` with `useId()`, and
`ModalDialog` deliberately sets `aria-label` to `undefined` when `aria-labelledby` is present — so
`null` was the correct value and the dialog was named all along. **The instrument was wrong, not the
code**, exactly as with the clipped swatch rect, the "0 placeholders" grep, and the `Pin`/`Pink`
substring match.

No change was made: the copy, the count, the focus placement and the styling were already right.

Guarded by `web/e2e/permanent-delete.spec.ts`. The test that matters most is the cancellation one —
cancelling must destroy nothing, and it is asserted rather than assumed. Both tests pass on both
browser projects.

### F14 — Journey G (labels): the delete guard is sound — audited from source, runtime not verified — OPEN

The destructive question here was worth asking: `deleteLabel` in `useLabelManagement` removes a label
from **every** note carrying it and rewrites each note with a fresh `timestamp`. That is a bulk,
irreversible mutation, so it needs a guard.

**There is one, and its copy is the reason no change is needed.** `DeleteLabelDialog` says:

> "X" will be removed from all notes. This cannot be undone.

It names the label, states the consequence for the notes rather than the label alone, and states
irreversibility — via the shared `ConfirmDialog`, so it inherits the measured properties from F13
(count where relevant, focus on Cancel, distinct destructive styling).

**What was verified how:**

| Claim | Evidence |
|---|---|
| Deleting a label is confirmed | read from `LabelsScreen.tsx` — `setLabelToDelete` → `DeleteLabelDialog` |
| The consequence is stated | read from `DeleteLabelDialog.tsx` |
| Notes are not deleted, only unlabelled | read from `useLabelManagement.ts` — the note object survives, its label array is filtered |

**Not verified, and not claimed:** the runtime journey. A spec covering create → assign → filter →
delete was written and failed at the creation step — the label row came back empty — and I could not
establish why within this round's budget. The input is inside a `<form onSubmit={handleCreate}>` with a
`Create` submit button, so Enter should submit implicitly; whether it does, and whether the following
`Close` returns to a re-rendered list, is unresolved. The spec was deleted rather than committed red.

**One observation, deliberately not a fix.** Rewriting each affected note's `timestamp` on a label
delete is a sync-visible mutation: those notes will surface at the top of "Newest first" and will be
uploaded. It may well be intentional — a label change *is* a content change and ought to sync — but
the mission's rules put sync contracts out of scope, so it is recorded here rather than changed.

### F15 — A created label never reached the filter row — CONFIRMED DEFECT, FIXED

A label created in the manager was listed there and nowhere else: no chip in the notes screen's filter
row, immediately or after a reload. Reproduced twice before being written up.

**Root cause, one trace.** `MainScreen` takes `labels` from `useNotes`, which had
`collectUniqueLabels(notes)` — derived from the notes alone. The label registry exists precisely for
the other case, and says so in its own comment: *"Labels created explicitly (e.g. from the labels
screen) before any note references them."* Nothing read it for the filter row, so a label created
before any note used it could not be filtered by. The fix is four lines in `useNotes`: merge the
registry's labels with the notes-derived ones, de-duplicated by name so a label both know about still
appears once.

**Why it is worth recording how nearly this was missed.** After the measurement I could not tell
whether the empty row was intended — a row listing only labels *in use* would be a defensible design —
and the audit entry said so rather than calling it a defect. Tracing `labels` to its source settled it
in one call, and the registry's own comment is what made the answer unambiguous. Five earlier
assumptions in this audit had been disproved by measurement; this was the first to be *confirmed* by
reading.

**Verified:** the chip now appears as soon as the label is created — `["All labels", "groceries"]` —
and `web/e2e/labels-journey.spec.ts` holds it, passing on both browser projects. That test is exactly
what would have caught this.

**One nuance, belonging to F10's territory rather than this finding.** The registry is persisted
(`persist` middleware, rehydrated in `bootstrap.ts`), but `bootstrap.ts` also calls `reset()` on the
sign-out path — so a *guest* who reloads loses created labels while their notes survive. That is the
same session-versus-data asymmetry F10 records, not a separate defect, and it is unchanged.


### F16 — Backup: both formats round-trip with notes intact — VERIFIED

Two export paths, and they are different formats:

| Control | Subtitle | Format |
|---|---|---|
| "Export backup with images" | "Download notes and images as one .nlkbak file" | zip bundle |
| "Export notes only" | "Download notes as JSON, without images" | JSON |

**Both verified end to end**, which is what F16 previously claimed for the bundle alone. Each export's
file is checked for the format it promises (JSON parses and carries a `notes` array; the bundle starts
with the zip magic `PK`), and the JSON export is imported into a **different** guest session — so
nothing can be satisfied by in-memory state — with the note's **title** and **pinned marker** asserted
after the import.

The import copy is asserted too, because it is unusually good: *"Import 1 note as new notes? They're
added alongside what's already here, so importing this file again will create another copy of each
note."* — count, consequence, and the duplicate warning, before anything happens.

**A latent bug in my own test helpers, found while writing this.** The editor-exit helper used
`getByRole('button', { name: /back/i })`, which matches a note titled "BACKUP note" — "back" as a
substring of "BACKUP". It had passed for several rounds only because every other note title happened to
avoid the sequence. Fixed with a word boundary in all five specs that carried it, and all five re-run.

**Minor, and the reason the locators are prefix-matched:** both controls' accessible names concatenate
the label with the subtitle inside the button ("Export notes onlyDownload notes as JSON, without
images"). Wordy but not wrong; an exact-name locator matches nothing, which is how two earlier hand
probes silently clicked nothing.

Guarded by `web/e2e/backup-roundtrip.spec.ts`, two tests on two browser projects.


### F17 — Attaching an image on touch: reachable, and now guarded — NO DEFECT

`image-ingestion.spec.ts` covers paste, drag-and-drop and text-paste — all desktop gestures. A phone
has none of them, so this is the same question F11 raised for the card's actions: is there a visible way
in? Measured at 390px with a coarse pointer, and the answer is yes.

| Finding | Measurement |
|---|---|
| "Add image" in the editor | visible at **both** 390px touch and 1440px, 36x36 |
| What it drives | `<input type="file" accept="image/*">`, so a touch user gets the camera or gallery picker |
| WCAG 2.5.8 | 36x36 clears the 24px minimum on its own |

**No change made.** 36x36 does not reach the 44px this system uses for chips, but it clears the
requirement and the system-wide check in `touch-targets.spec.ts` already asserts nothing here falls
below 24px — adding a `.tap-target` would be a change without a confirmed problem, which the mission
rules out.

**Two minor observations, neither a defect:**

1. The `<input type="file">` has **no accessible name** (`aria-label` is absent). It is hidden and
   driven programmatically by the button, and the repo's axe pass does not flag it, so it is recorded
   rather than changed — but an unnamed file control does reach the accessibility tree.
2. Two hidden file inputs exist in the editor: one `image/*` and one accepting
   `application/json,.json,.nlkbak,application/zip` — the latter being the **backup import** input,
   mounted on the editor screen. Harmless, but worth knowing when counting file inputs.

Guarded by `web/e2e/attach-touch.spec.ts`: the control must be visible on a coarse pointer, and
attaching a real PNG through the picker must be represented in the editor. Two browser projects.

### F18 — The web sort control offered "Manual order" the web client cannot produce — FIXED

**Measured:** zero reorder handles in the DOM at 390px touch and at 1440px desktop, in the default state
where the sort chip read `Manual` and the view was `Grid`.

**Confirmed by construction, not just by a missing control.** `MainScreen` renders `<NoteStaggeredGrid …>`
without a `reorder` prop — a search for `reorder={` found nothing — and the grid derives
`showReorderHandle: Boolean(reorder)`. No handler ever reached a card.

**Not a D12 violation:** D12 is a Compose-side decision, as its own text says, so web having no drag
handle is consistent with it. The defect was what the UI *offered* — `Manual order` beside the two
automatic orders, and `notesStore` defaulting to it, so a fresh session opened on an order the user had
no way to achieve.

**Resolved by the product decision to stop advertising it**, taken after both options were weighed:

- the sort chooser now offers `Newest first` and `Oldest first` only, and the `manual` hint text is left
  in place because a stored position is still *displayed* under it;
- the settings group's inline choice lost `manual` for the same reason;
- `notesStore`'s default is now `newest`, so a new session does not open in an order it cannot leave.

Guarded: `sort-order.spec.ts` asserts that `Manual` is **not offered** rather than merely unpressed, and
that the default arrives as `Newest`. `SortOrder` still carries `'manual'` for stored notes, so nothing
about existing data or positions changed.


### F19 — Every control in the reminder flow was under 44px tall — FIXED

Reminders are fully implemented on web — picker, scheduler, service worker, sync — and the flow reads
well: the options sheet offers **"Set reminder"** with three presets ("In 1 hour", "Tomorrow 9:00",
"Next week") plus an exact date/time input. Measured at 390px, the controls themselves were a problem:

| Control | Before | After |
|---|---|---|
| "In 1 hour" | 71x**26** | 71x26 visual, **44px target** |
| "Tomorrow 9:00" | 114x**26** | 114x26 visual, **44px target** |
| "Next week" | 85x**26** | 85x26 visual, **44px target** |
| Reminder date and time | 358x**38** | 358x38 visual, **44px target** |

They cleared WCAG 2.5.8's 24px floor, so this was never a conformance failure — but these are the
*most-tapped* controls in the flow, and presets exist precisely so a user can tap one and move on. 26px
is also short of the 44px this system already sets on `.filter-chip`; they were written as
`px-3 py-1 text-xs` rather than using the shared chip classes, which is where the drift came from.

**Fix:** the shared `.tap-target-y` variant — a 44px target, the chip's own size unchanged. That
variant exists for exactly this case (dense rows where a square target would swallow a neighbour).

**Worth crediting rather than changing:** the section says *"Web reminders are best-effort. They can be
delayed or missed if the browser is fully closed or inactive."* That is an unusually honest statement
of a platform limitation, and it is the sort of thing most products leave the user to discover.

**Not changed, and a design decision rather than a defect:** the presets could instead adopt
`.filter-chip` outright, which would give them the system's 44px height *visually*. That is a larger
look change than this finding justifies on its own.

### F20 — Bulk actions: audited, no defect — GUARDED, and an earlier suspicion resolved

Measured with **three** notes and only two selected, which is the fixture a bulk-operation audit needs:
a two-note list cannot show a bulk action touching the wrong notes.

| Property | Result |
|---|---|
| Selection count | "2 selected" for two selected |
| Bulk archive | both selected notes leave the notes view |
| The unselected note | untouched — the assertion that matters |
| Bulk trash | soft, the unselected note survives, and the trash view holds the selected ones |
| The bar's toggle | reads "Select all" with 2 of 3 selected, "Deselect all" with 3 of 3 |

**A question from an earlier round, resolved.** I had recorded that the bar showed both "Clear
selection" and "Deselect all" and wondered whether they did the same thing. They do not: "Deselect all"
is the *same control* as "Select all" with a state-dependent label —
`allFilteredSelected ? 'Deselect all' : 'Select all'` — and the earlier reading came from a bar in the
all-selected state. There was never a redundant pair, and the guard now pins the relabelling.

**Also confirmed in passing:** the list came back as `KEEP three, BULK two, BULK one`, which is F18's
`newest` default doing its job outside a test fixture.

Guarded by `web/e2e/bulk-actions.spec.ts` — three tests on two browser projects, each leaving one note
unselected and asserting it survives.

### F21 — A legacy stored `manual` sort preference is reset for guests — measured, mechanism identified

Follow-up to F18, prompted by the question "what happens to a user who already has a persisted manual
sort?".

**Measured, twice, with a valid persisted shape:** seeding `notelikeus-note-filters` with
`sortOrder: 'manual'` (and a real `filter: 'active'`) and loading the app leaves the chip reading
**"Sort: Newest"** and the stored value rewritten to `newest`.

**Mechanism identified, not guessed:** `bootstrap.ts` calls `useNotesStore.getState().reset()` on the
guest path, and `reset()` sets `filters: defaultFilters` and persists it. So this is F10's session-reset
behaviour rather than a new defect — the same reset that drops a guest's label registry on reload. F18
changed *what* the reset restores (`newest` rather than `manual`), not *whether* it happens. A
signed-in session does not take that path, so its stored `manual` is honoured.

**Verified against the rest of the follow-up question:**

| Question | Answer |
|---|---|
| Does the hidden option reappear? | No — the chooser offers Newest first and Oldest first only |
| Is either available order selectable? | Yes, and the choice is written to storage |
| Are stored preferences destructively rewritten? | For a **guest**, yes — by the same reset F10 records. For a signed-in session, no |

**Not verified:** whether stored *note positions* are touched. Positions live in IndexedDB, which this
probe did not read; the reset affects `filters` only, and `partialize` excludes notes, so the code says
they are untouched — that is a source claim, not a measurement.

**One instrument error worth recording.** The first version of this probe seeded `filter: 'notes'`, a
value that does not exist in `NoteFilter` (`'active' | 'archived' | 'trashed'`), and the store correctly
rejected the whole object. The reading looked like a defect and was my seed. It is the fifth time in
this audit that the measurement was wrong rather than the code, which is why every claim here names how
it was checked.

### F22 — Reminders: the permission boundary is verified; configuration under a granted permission is not

Chasing what looked like a defect — an "In 1 hour" preset that did nothing when clicked — produced the
clearest negative result of this audit.

**It is not a defect.** `setReminderTimestamp` refuses to save a reminder without notification
permission, with a comment saying why: *"a reminder saved without notification permission would
silently never fire."* A Playwright context denies notifications by default, so the app was correctly
refusing and my first probe never looked for the reason. The refusal is explained to the user rather
than silent.

**Verified in the browser, on both projects: the refusal path.** With notifications denied, clicking a
preset saves nothing, the state line still reads "No reminder set", and the user is told notifications
are needed. That is a boundary worth having a test for, and it is now `web/e2e/reminders.spec.ts`.

**Unverified, and marked rather than claimed:** reminder *configuration* with permission granted. Headless
Chromium cannot grant notifications at all, so `requestNotificationPermission()` returns false however the
context is configured and nothing is ever saved. Those two tests are `test.fixme` — skipped and counted,
never reported as passing. Running them needs a headed browser or a Chromium build with the permission
grantable.

**Still unverified, unchanged from the close-out:** reminder *scheduling*, and reminder *delivery*. A UI
test cannot show that a service worker fires later or that an operating-system notification appears; the
second needs a browser that is closed or asleep. The app is honest with users about this — "Web reminders
are best-effort. They can be delayed or missed if the browser is fully closed or inactive."

**Result of the run:** 2 passed, 4 skipped, 0 failed. The adjacent sort, filter-row and touch-target
specs: 28 passed.

### F23 — Compose: the two defect classes the web audit proved real are already handled — SOURCE-AUDITED, NO DEFECT

Phase 3's source level, checked against the two classes of defect this audit actually found on web rather
than against a general checklist.

**Class 1 — controls below the minimum touch target (F6, F7, F19).** Not present. `Size.touchTarget =
48.dp` lives in `theme/Spacing.kt` with a comment citing WCAG 2.5.8 and describing this decision's own
pattern: *"A control may look smaller — the colour swatches paint 26dp inside a 48dp target — but nothing
tappable may be smaller than this."* It is applied in eight places: `NoteColorSwatch` (the hit area around
the 26dp circle), `NoteCard`, `ChecklistUI`, `EditorBottomSheet`, `ThemePicker`, `MainDrawerContent`.

**Class 2 — affordances reachable only by hover (F11).** Not present. The Compose note card does read
`isHovered`, but only to drive elevation and a 1.01 scale — decorative feedback, never the sole path to an
action. Actions come from selection and long-press, which a touch device has.

**What this is and is not.** This is a **source audit**: the tokens and modifiers were read, not rendered.
The one thing that cannot be claimed from it is that any particular control *looks* right on a device —
that needs an AVD and is blocked as recorded. The claim here is narrower and supportable: neither defect
class exists in the Compose source, and the token that prevents the first is genuinely applied rather
than merely defined.

**A correction this produced.** `DECISIONS.md` D26 claimed the Android client had the same gap and that
its treatment "has **not** been applied there". That was written from a web-side assumption and is
untrue; D26 now carries the amendment and the evidence.

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
