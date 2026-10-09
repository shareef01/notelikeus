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
| `8af203e` | F10 | journey D audited: markdown-as-text formatting and guest-note persistence | no defect; 4/4 with discovery verified |
| `cb0da5a` | F11 | `pointer-coarse:opacity-100` on the card's selection checkbox | opacity 1 at 390px coarse, 0 at rest on desktop; 4/4 discovery-verified |
| `5b2b4ad` | F12 | journey F closed: pin, archive and the trash round trip verified; the card's checkbox is named by its note's title | 8/8 on two browser projects; affecting the wrong note would fail loudly |
| `4a278c3` | F13 | permanent-delete confirmation audited and guarded | no defect: count matches, irreversibility stated, focus starts on Cancel, cancelling destroys nothing |
| _source only_ | F14 | journey G (labels): the delete guard and its copy read from source | no defect; a runtime spec failed at label creation and was not committed |
| `a123cb9` | F15 | `useNotes` merges the label registry with the notes' labels, so a created label is filterable | chip appears immediately; regression test passing on two projects |
| `7f8934b` | F16 | both backup formats round-tripped; the back-locator fixed in five specs | JSON parses and carries notes; bundle is PK; title and pin survive import into a fresh session |
| `4dc21ee` | F17 | touch attach path measured and guarded | no defect: a visible 36x36 "Add image" drives an image/* picker; PNG attachment asserted |
| `4dcd783` | F18 | "Manual order" removed from the web sort chooser and settings group; default is now Newest | offered orders asserted, and that Manual is not offered at all |
| `915ad18` | F19 | `.tap-target-y` on the reminder presets and date input | 26px and 38px visuals keep their size; every target is 44px |
| `da39c69` | F20 | bulk actions audited with a three-note fixture | no defect: count correct, unselected note untouched, and the Select/Deselect toggle relabels rather than duplicating |

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
6. **The whole e2e suite**, not just the new specs — **140 passed, 1 skipped, 0 failed** as of the
   final verification run. (132 at the close-out, 140 once the reminders journey landed.) This branch added eleven spec files; the pre-existing accessibility, dialog-a11y,
   note-lifecycle, image-ingestion, backup-import, account-switch and save-failure specs all exercise
   chrome these changes altered, which is why the full run matters more than the new files.
7. **Kotlin** — **1322 tests, 0 failures, 0 errors, 4 skipped** on a genuine re-run (`--rerun-tasks`, 74
   tasks executed). This branch contains one Compose edit, attempted and reverted, so the source is
   unchanged; the suites were re-run anyway because the objective's baseline step asks for it and "up to
   date" is not a run.

## Open, in the order I would take them

4. Android, on a machine with an AVD or from CI's instrumented lanes.

## Close-out

Every journey the mission named has been walked, and the branch is at its verification ceiling for web.

### Complete and verified

| Area | Evidence |
|---|---|
| Journeys A–I | entry, creation, checklists, formatting, search, selection-mode actions, labels, attachments, bulk actions — each with its own spec, all passing on desktop and a Pixel-5 emulation |
| Defects found and fixed | **eight** user-facing: F1, F2, F5, F6, F7, F11, F15, F18, F19 — six of them invisible on a desktop browser |
| Phase 1 foundation | `.tap-target` and `.tap-target-y` as shared interaction states, recorded as `DECISIONS.md` D26 |
| Phase 4 | full e2e **140 passed, 1 skipped, 0 failed**; palettes verified in dark, AMOLED and light (F8); offline and failed-save covered by the pre-existing `save-failure` spec, which passes; Kotlin re-run at 1322 tests, 0 failures |
| Deliverables | `UI_UX_AUDIT.md` (F1–F20), this log, `DECISIONS.md` D26, regression checklist, before/after screenshots |

### Partial, with the reason

- **Reminder delivery** — the scheduler, service worker and permission handling cannot be exercised
  here: it needs a browser that is closed or asleep. The UI around them is audited and fixed (F19).
- **F18's alternative** — reordering on web. Your decision was to stop advertising `Manual order`
  instead; the reorder plumbing in `NoteStaggeredGrid` is untouched and remains available if that
  changes.

### Blocked

- **Android.** This machine has the emulator binary and `/dev/kvm` but **no AVD and no system images**,
  and pulling one is outside this task's scope. No Android claim is made anywhere in these documents.
  The branch touches nothing outside `web/` and `docs/` — verified by `git diff --name-only` — so
  `composeApp`, `androidApp`, `workers/` and `supabase/` are provably unaffected, which is a weaker
  statement than "Android works" and is meant to be.

### Intentionally unchanged

Schemas, sync contracts, encryption, backup formats and stored data. The eleven findings already
implemented in `AUDIT_UI_UX_2026.md` were read and not redone. D1 (tonal surfaces, no accent strip),
D14 (headings describe real orders) and the AMOLED and legacy-preference behaviours are preserved rather
than altered.

### Final verification round — the two remaining web gaps

| Gap | Outcome |
|---|---|
| Reminders journey | **closed.** 8 passed, 0 failed, 0 skipped on both browser projects: preset set, preset change, clear, custom date, reopen the sheet, reopen the note, reload persistence, and the unstubbed permission refusal |
| F21 note positions | **measured.** A read-only IndexedDB probe found no position data for web notes anywhere, so nothing can be lost; the sorts themselves behave and the stored preference tracks |

The authenticated-session half of F21 remains source-audited rather than measured: it needs a signed-in
backend and no local Supabase instance is running here. `bootstrap.ts` calls `reset()` on the sign-out
path, and the store is the same either way, so the expectation is that a signed-in session keeps its stored
value — an expectation, not a measurement.

Still unverified, unchanged: Android rendering, and reminder scheduling and delivery.

### Android rendering — unblocked

A physical Pixel 7 was connected over USB, so Compose can be rendered, screenshotted and measured rather
than read. The debug APK was installed with `adb install -r` — debug deliberately, so nothing clashes with
any real install on the device — and the app runs: sign-in, offline mode, notes screen.

First measurement, `uiautomator dump` at 3x density: of seven clickable controls on the notes screen, six
are 42dp in at least one dimension, under `Size.touchTarget` (48dp), whose own comment says nothing
tappable may be smaller. Not a WCAG failure — the floor is 24dp — but a gap between the token and the code.

Also visible on that screen: **"Manual (drag to reorder)"**. Android has manual reordering, which is what
makes F18's web-side removal correct rather than a compromise.

Remaining for Phase 3: the editor, sheets, navigation and TalkBack on this device.

### Google sign-in: web verified working, Android diagnosed

Reported failing. Web is **not** the problem: clicking "Continue with Google" reaches the real Google
consent page for `ddxmubeaeeomureolvbu.supabase.co`. Evidence committed.

Android uses Credential Manager with a hand-maintained `default_web_client_id` and no
`google-services.json`. That API matches the app by package name plus signing-certificate SHA-1, and this
project's release identity changed recently, so a stale registration is the leading explanation. The debug
build now on the device has its own SHA-1, almost certainly unregistered. Both fingerprints are in the
audit entry, with the console steps.

The device was locked before the failing attempt could be made, so the failure itself is still
unreproduced.

### Phase 3 rendered audit — the first two screens measured

The device was found unlocked with the app in the foreground, so the audit continued past the first screen.

`uiautomator` bounds at 3x density, clickable nodes only: the notes screen has 7 clickables (3 at
42x42dp, three row controls 42dp tall), the editor has 14 (12 at 42x42dp). Eighteen of twenty-one report
42.0dp in at least one dimension; one measured 49.0dp.

Interpretation unresolved and recorded as such: 42dp clears WCAG's 24dp floor, but it is below Material's
48dp and below `Size.touchTarget`, whose comment says nothing tappable may be smaller. Material 3's
`IconButton` draws at 40dp and should pad its hit area to 48dp, so the measurement may be the drawn size
rather than the target. No literal `42.dp` exists and `LocalMinimumInteractiveComponentSize` is never
overridden — both checked. Settling it needs a Compose version check or a build-and-install loop.

Still to walk on this device: sheets, navigation and TalkBack. Evidence committed for both screens.

### Google sign-in reproduced on Android — server-side, and not silent

Cleared the debug build's data (guest notes only) to reach the sign-in screen, then tapped "Sign in with
Google" on the Pixel 7. The screen is unchanged for about 20 seconds, after which the app displays:

> Requires Email/Password enabled in Supabase Authentication.

Two earlier claims are withdrawn by this. The stale-SHA-1 hypothesis does not fit — logcat shows the app
reaching the network, and Supabase is answering, so the credential was obtained and sent. And the failure
is **not silent**: my first attempt said so because an 8-second wait missed a message that appears at ~20
seconds.

Next step is one read of `signInWithGoogleIdToken` on Android to tell a Supabase provider-configuration
problem from the app calling the wrong auth method. Evidence committed.

### Google sign-in: diagnosed to a Supabase configuration gap

The app is correct. `signInWithGoogleIdToken` posts to `/auth/v1/token?grant_type=id_token` with
`{"provider":"google","id_token":…}` — the right endpoint for the ID-token flow. Supabase refuses the
grant, and its message is what the user sees. `supabase/config.toml` has an `[auth.external.google]` block
with `enabled = true` and credentials from environment variables, and `BACKEND_ARCHITECTURE.md` lists
enabling the provider as step 2 of provisioning a project. The app points at the hosted project
`ddxmubeaeeomureolvbu`, so that project's provider is what has to be enabled, with the Google client id and
secret.

Three claims of mine corrected in the process: the stale-SHA-1 hypothesis (wrong layer), "the failure is
silent" (an 8-second wait missed a message that takes ~20), and "web works" (only the redirect is verified;
no sign-in has been completed on either platform, and web shares the same Supabase project).

### Android accessibility naming — measured clean, with the trap recorded

Swept the app's accessibility tree on the device: all six clickables on the notes screen resolve to a name,
via their own semantics or a descendant's. `uiautomator`'s flat dump shows empty `content-desc` on each
clickable, which reads as six unnamed controls; Compose merges semantics into children, so only a
tree-aware check sees the labels.

An intermediate version of the sweep reported three controls all named "Change view mode", which would be a
real defect. Checking each node's own `content-desc` showed one node with that name and the rest with their
own — the duplication was my aggregation. Recorded so the next attempt does not repeat it.

Not a TalkBack run: nobody has driven the app with TalkBack enabled, so focus order and announcement quality
remain unverified.

### Kotlin suites re-run on a connected device session

Run because the objective's baseline step asks for it and it had not been done in this session:
`:composeApp:testDebugUnitTest :androidApp:testDebugUnitTest --rerun-tasks`. **74 actionable tasks, 74
executed** — a genuine run, not cached.

Worth recording how it first went wrong: an earlier invocation without `--rerun-tasks` reported
"BUILD SUCCESSFUL ... 74 up-to-date", which is **not** a test run. Gradle's success line is not evidence
that anything executed, and treating it as such would have been a false claim.

### TalkBack: enabled, measured, and the phone restored

TalkBack was enabled for one command's worth of measurement, then the phone's accessibility settings were
returned to their original null/0 — verified by reading them back.

With the screen reader running the app renders normally, and the accessibility tree exposes merged labels on
the nodes themselves: Menu, Search your notes, Open settings, Filters, Manual (drag to reorder), Change view
mode, Add note. Eight focusable nodes against seven clickables, so nothing interactive is unreachable.

It also settles F26's hunt for a menu control: the label is "Menu", on a child node, which is precisely the
flat-dump trap that made it look absent.

Not a full TalkBack run, and recorded as such: focus order through the editor, sheets and dialogs,
announcement quality, and gesture navigation are unmeasured. TalkBack activates on double-tap, so tap
driving does not test it faithfully.

### Google sign-in re-tested after the provider was enabled

The Supabase rejection is gone. Where the app previously showed "Requires Email/Password enabled in
Supabase Authentication" at about 20 seconds, it now sits on the sign-in screen for a full 30 with no error.

The failure point moved to Google's side: logcat shows `CredentialManager: Client binder died - clearing
session` and `BoundBrokerSvc: onUnbind … gis_internal.START`, so Credential Manager starts and its session
dies before any account picker appears. The next suspect is the signing-certificate registration — the
original hypothesis, which did not fit the first failure but fits this one. Recorded as a suspect rather
than a cause, since the log shows the binder dying and not why.

## Change-to-coverage map

Every web source file this branch changed, and what actually exercises it. **Written by hand deliberately:**
a first attempt matched file names against test text and reported fourteen files as uncovered, which was
wrong — these specs exercise components behaviourally rather than by name. A name-matching check cannot see
that, and acting on it would have produced seventeen pointless unit tests.

This is the closest thing to a diff review that a single agent can produce. It is not a substitute for one.

| Changed file | Covered by | How |
|---|---|---|
| `layout/TopBar.tsx` | `reflow`, `search-journey`, `sort-order` | the header row is measured at six widths and its chip asserted before/after searching |
| `layout/SelectionBar.tsx` | `journey-f-actions`, `bulk-actions` | selection mode's actions and the count are asserted in both |
| `layout/FilterRow.tsx` | `filter-row`, `touch-targets` | cue visibility, keyboard scrolling, and the 44px targets |
| `layout/SortSheet.tsx` | `sort-order` | offered orders, the applied order, and that `Manual` is absent |
| `layout/ColorSwatch.tsx` | `palette-contrast`, `touch-targets` | outline contrast per theme, and 44x44 targets with zero overlap |
| `layout/SideDrawer.tsx` | `touch-targets` | its close button's 44px target |
| `notes/NoteCard.tsx` | `card-actions`, `journey-f-actions`, `touch-targets` | checkbox visibility on coarse pointers, trash round trip, target sizes; F11 and F12 respectively |
| `settings/ProfileSheet.tsx` | `sort-order` | the settings group and the toolbar chip asserted as one state |
| `editor/ChecklistEditor.tsx` | `touch-targets` | the tick box's 24x44 target in checklist mode |
| `editor/EditorOptionsSheet.tsx` | `touch-targets` | the sheet is measured and the reminder controls asserted at 44px |
| `screens/EditorScreen.tsx` | `touch-targets`, `rich-text` | the checklist button's target; markdown formatting and persistence |
| `screens/MainScreen.tsx` | `reflow`, `filter-row`, `sort-order` | list and chrome layout, filters, sort |
| `screens/main/MainDialogs.tsx` | `sort-order`, `permanent-delete` | the sort sheet as an overlay; the destructive confirm |
| `hooks/useNotes.ts` | `labels-journey` | F15's regression: a created label is filterable |
| `hooks/useOverflowHint.ts` | `filter-row` | the cue appears and disappears as the row scrolls |
| `store/notesStore.ts` | `sort-order` | the default order and the persisted preference |
| `styles/globals.css` | `touch-targets`, `reflow` | `.tap-target` behaviour and the reflow contract |

**One file has no behavioural coverage, and it should be stated rather than buried:** `hooks/useOverflowHint.ts`
is exercised indirectly through `FilterRow`, but its own unit behaviour — the 1px slack, the
resize/mutation/re-render re-measurement — has no direct test. It runs only in a real browser, which is why
it has an e2e spec at all, and a happy-dom unit test would assert nothing (scrollWidth is 0 there).
