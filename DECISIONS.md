# Decisions

Design and engineering calls made during the UI/UX + smart-filtering overhaul, with the
reasoning behind each, so they can be overruled on their merits rather than re-derived.

Each entry records **what was decided**, **why**, and **what it would cost to reverse**.

---

## D1 — Tinted surface carries the note colour. No accent bar.

**Decided:** a coloured note renders as a tinted container (`container` + `onContainer` tonal
roles). The 2dp strip in List view is removed. Cards with no colour keep the existing hairline
outline so they still read as cards.

**Why:** the strip only existed in List view, and it was drawn from `contentColor`/`outline` —
it was never rendering the note's colour, so the two layouts disagreed about what it meant. It
also cost 12dp of horizontal measure in the densest layout. The defect it appeared to
compensate for — low-contrast body text on the maroon and dark green — is a palette problem,
fixed by tonal role sets rather than by adding a second, redundant colour signal.

**Cost to reverse:** low. Reinstating the strip is a `Box` in the List branch of `NoteCard`,
and the tonal roles give it a legible colour to use this time.

---

## D2 — Theme migration is read-time only. Stored preferences are never rewritten.

**Decided:** the six-value `AppTheme` enum collapses to `System / Light / Dark` plus an
independent AMOLED toggle and an accent choice. The stored preference string is mapped to the
new state **on read**. DataStore keeps its existing value (`FOREST`, `MIDNIGHT`, `TRUE_DARK`)
until the user actively changes the setting.

| Stored value | Resolves to |
|---|---|
| `AUTO` | System |
| `LIGHT` | Light |
| `DARK` | Dark |
| `TRUE_DARK` | Dark + AMOLED |
| `MIDNIGHT` | Dark + blue accent |
| `FOREST` | Dark + green accent |

**Why:** a write-time migration is a one-way door — it runs once, on first launch of the new
build, before anyone has seen the result. If the mapping is wrong the original value is gone.
Read-time mapping makes the whole change reversible by reverting the build. It also matches
the precedent already in this codebase: `LEGACY_THEME_DEFAULT_COLORS` in `Color.kt` maps two
legacy note-colour values back to "no colour" on read, for the same reason, and that comment
explains the alternative (a migration racing across three clients) was rejected.

**Cost to reverse:** none. Revert the build and every stored value still means what it did.

**Consequence to remember:** the mapping function is load-bearing indefinitely, not
transitional. It cannot be deleted after "everyone has upgraded", because nothing ever
rewrites the stored value. It is covered by a test across all six legacy values.

---

## D3 — AMOLED reuses the existing `true_dark_mode` DataStore key.

**Decided:** the AMOLED toggle reads and writes `TRUE_DARK_MODE_KEY`, which already exists.

**Why:** the key, the repository property, the `MainViewModel` setter and the `MainState`
field were all already built and wired — and nothing called the setter or read the state. It
was a complete, dead vertical slice of exactly this feature. Adding a second key beside it
would leave the dead one in place and create two sources of truth for one concept.

**Cost to reverse:** trivial.

**Note:** users who previously chose the `TRUE_DARK` theme resolve to Dark + AMOLED via D2's
read-time mapping, *not* via this key. The key is only written when someone toggles AMOLED in
the new settings UI. The two paths agree, but they are independent.

---

## D4 — One icon colour role. The five drawer accent hues are removed.

**Decided:** drawer and settings icons take a single colour role derived from selection state
against the theme's `primary`. `NavIdentity`'s five hues (sky / amber / rose / violet / teal)
are deleted. Colour is reserved for note colours and the single user-chosen accent.

**Why:** five saturated hues in one column read as unrelated icon sets rather than a system,
and they left selection with no colour of its own to signal with. The comment in `Color.kt`
already claimed this had been done — it had not; `NavIdentity` was still live and still used
by `MainDrawerContent`. This makes the comment true.

**Cost to reverse:** low, but reversing it re-creates the WCAG problem the `NavIdentityColor`
light/dark pairs were introduced to solve.

---

## D5 — The Glance widget keeps its own palette, sourced from the shared one.

**Decided:** `WidgetThemes.kt` continues to define `ColorProvider` constants, but derives them
from the shared palette source rather than from independent literals.

**Why:** Glance composables cannot read `MaterialTheme` — this is a framework constraint, not
a choice. Deriving the values means a palette change cannot silently leave the widget behind,
while accepting that the widget resolves its theme separately.

**Cost to reverse:** n/a. Logged in `FINDINGS.md` (F6) as a gap that still owes a
widget-specific visual review.

---

## D6 — Search will be SQL-backed, without FTS.

**Decided:** Phase 2 replaces the in-Kotlin `contains()` scan with a normalized search-text
column maintained on write and queried with indexed SQL prefix matching, tokenized and
diacritic-folded in Kotlin at write time. Room FTS4/FTS5 is not used.

**Why:** FTS would have to generate and run correctly against **both** SQLCipher on Android
and `sqlite-bundled` on desktop, with FTS5 compiled into each. That is verifiable, but the
verification spike was declined, and committing a phase to an unproven capability across two
different SQLite builds is the kind of bet that fails late. The chosen approach uses only SQL
that is guaranteed present, adds no dependency, and still removes the actual defect: filtering
the entire note list in Kotlin on every keystroke.

**Cost to reverse:** moderate. The write-time normalization and the query surface stay useful
either way; swapping the storage for an FTS virtual table is a migration plus a query rewrite,
behind the same pure query function.

**Trade accepted:** slower than FTS5 on a large corpus. At this app's note counts the
difference is not measurable, and the brief's own budget (5,000 notes in under 50ms) is
reachable without it.

### Amended during Phase 2 — the SQL half turned out to be unnecessary

The normalised column happened; the SQL query did not, and should not.

Measured with the column in place, over 5,000 realistically-shaped notes:

| query | time | budget |
|---|---|---|
| free text | 1ms | 50ms |
| multi-token text | 1ms | 50ms |
| text + labels + colours + flags + sort | 1ms | 50ms |
| unfiltered | <1ms | 50ms |

Fifty times the headroom. Pushing the same predicates into a parameterised `RawQuery` would mean
rewiring the notes `Flow` to re-subscribe on every query change, and — worse — writing the query
semantics a second time in SQL, where it could disagree with the matcher the tests and the live
result count use. That is real complexity and a real correctness risk, bought for a saving that
does not register.

**So the plan changed on the evidence:** fold once on write, match in memory. The expensive thing
was never *where* the filtering ran, it was that the old code re-folded and re-allocated per note
per keystroke. `NoteQueryPerformanceTest` is the guard, and what would breach it is a change in
kind — folding per keystroke, or splitting the haystack per note — both of which were the previous
behaviour.

If a corpus ever arrives where this stops holding, the pure matcher is the right place to swap the
storage under, exactly as this entry originally described.

---

## D7 — The shared confirmation is a dialog, not a bottom sheet.

**Decided:** `ConfirmDialog`, built on `AlertDialog`, is the one confirmation component. The
design brief names a `ConfirmSheet`.

**Why:** this composable is in `commonMain` and therefore ships in the Windows build. A sheet
sliding up from the bottom edge is a phone convention; on a desktop window it is not what anyone
expects from a confirmation, and a modal dialog is. Building both — a sheet on Android, a dialog
on desktop — is two components to keep in agreement for a difference nobody asked for, in a phase
whose point is having fewer things that can disagree.

**What was kept from the brief's intent:** one component, used everywhere, that *states the
consequence*. `destructive` is a parameter rather than styling chosen per call site, which is what
fixed the real defect: of the four confirmations on the main screen, the two that coloured their
confirm action as destructive were "empty trash" and "delete", while "restore from cloud" — the
one that can silently remove notes the cloud no longer has — looked like the safest of the four.

**Cost to reverse:** low. The call sites pass content, not layout; swapping the surface is a
change inside this one file.

---

## D8 — The off-grid dp values are left as literals, not snapped to the grid.

**Decided:** the token sweep converted every `.dp` literal with an exact token — the 4dp grid, icon
and control sizes, radii, the reading measure — and left roughly 60 off-grid one-offs alone.
Phase 1 therefore does **not** fully meet the brief's "zero hardcoded dp values in screen code".

**What is left:** `6.dp` (15 uses) and `10.dp` (10) are the bulk; then a tail of 3, 7, 13, 15, 22,
28, 30, 52, 56, 64, 68, 72, 80, 220, 260, 280 and one 1408.

**Why:** snapping 6→8 and 10→12 is a *visible* layout change across the note card, the drawer and
the filter rail, and the value of doing it is grid tidiness rather than anything a user would
notice as better. The screens holding almost all of these — the notes list, the card, the editor,
the drawer — are rebuilt in the later phases of this project, where those numbers get chosen
deliberately against a real design rather than nudged to the nearest multiple. Doing it twice is
worse than doing it once, and doing it now spends the phase's verification budget on the part of
the work with the least user impact.

**Cost to reverse:** none — this is deferral, not a design position. The sweep script is a dozen
lines and the remaining values are already enumerated above.

**Note:** the ~18 `sp` literals in screen code are in the same position and deferred for the same
reason. The type *scale* is fully tokenised; what remains are per-call-site `fontSize` overrides,
each of which is a decision the screen phases should be making, not preserving.

---

## D9 — No backfill for the search column. Notes index themselves as they are written.

**Decided:** `MIGRATION_9_10` adds `searchText` and leaves every existing row null. Nothing sweeps
the table afterwards. A note gets indexed the next time it is saved, and until then the matcher
folds its fields on the spot.

**Why:** the obvious design was a startup pass filling nulls in batches, idempotent and
interruptible. Measured, it is not worth writing. At 5,000 notes:

| | time |
|---|---|
| fully indexed | **0ms** |
| fully un-indexed (the fallback) | **12ms** |

Both are inside the brief's 50ms budget, with four times the headroom in the worst case. So the
backfill would buy 12ms that nobody is waiting on, and it would pay for it by rewriting every row
of a SQLCipher-encrypted database holding the user's real notes. Every row rewritten is a row that
can go wrong; a pass that does nothing visible is a pass with only downside.

**What makes this safe rather than merely cheap:** the fallback is not a degraded mode, it is the
same answer computed a slower way, and there is a test asserting both paths return the identical
result set. The column is an optimisation over a correct default, not a replacement for one.

**Cost to reverse:** low, and it stays reversible precisely because null already means "not yet
indexed" everywhere. A backfill can be added later as a pure optimisation with no schema change
and no change to the matcher.

**When to revisit:** if a corpus turns up where the un-indexed path breaches the budget. The perf
test measures both paths on every run, so that shows up as a failure rather than as a complaint.

---

## D10 — The query is not persisted through `SavedStateHandle`.

**Decided:** `sort` and `view` persist to DataStore as durable preferences. The ad-hoc dimensions
— text, labels, colours, flags, date range — live only in memory and reset when the process does.
The brief asks for `SavedStateHandle` survival; this does not do it.

**Why:** the plumbing is riskier than the feature. `Koin.kt` already carries a comment explaining
that standalone note windows on desktop *cannot* use `koinViewModel` because no
`SavedStateRegistryOwner` exists there, and resolve the editor through a plain factory with a
throwaway handle instead. Injecting a real handle into `MainViewModel` from the shared module
means either a definition desktop cannot satisfy, or a nullable parameter that only Android fills.

The second option is what this project keeps finding and removing: a fully-wired mechanism that
nothing connects — `markRestored`, `saveNoteAndAwait`, `cloudSyncedNoteCount`, `isTrueDarkMode`,
`MainState.appTheme`. Adding a `savedStateHandle` parameter that defaults to a fresh handle would
compile, look implemented, and persist nothing.

**What is actually lost:** only Android process death, and only the ad-hoc filters. A cold start is
*supposed* to reset them — the brief says so in the same section — so the gap is the narrow case
where Android reclaims the process mid-session and the user returns expecting their filter intact.

**Cost to reverse:** moderate, and it belongs with the phase that rebuilds the filter surface,
where the restore path can be exercised rather than assumed. The query is already a single
immutable object with one setter, which is the hard part; what remains is DI and a handful of
primitive round-trips.

---

## D11 — The performance requirement and the performance assertion are different numbers.

**Decided:** `NoteQueryPerformanceTest` prints the measured time against the brief's **50ms**
requirement, and asserts against a **250ms** ceiling.

**Why:** asserting 50ms directly was flaky. It failed twice during full-gate runs and passed on
re-run at the same numbers — 8–14ms measured, nowhere near the limit. The failures were CI-style
variance (GC landing inside a timed run, another process on the machine), not the code changing.

A timing check that reddens at random is worse than no check, because the response to a flaky test
is to stop reading it. Once ignored, it guards nothing.

**Why the loose ceiling still works:** what this test protects against is a change in *kind*, not a
few milliseconds — folding per keystroke instead of on write, splitting the haystack per note,
scoring inside a comparator. Each of those is an order of magnitude. Two of the three have already
happened during this project and both were caught by this test; both would still breach 250ms
comfortably.

**How the requirement stays honest:** the measurement is printed on every run, labelled `within` or
`OVER` the 50ms requirement. If that number starts approaching 50ms the log says so, which is the
signal — not a red build at an arbitrary threshold.

**Cost to reverse:** none. Tightening the ceiling is one constant, if this ever runs somewhere with
predictable timing.

**What was tried first, and kept:** sharing one corpus across the class rather than rebuilding
5,000 notes per test, and widening the sample to the best of nine after five warmups. Both reduced
the variance and are worth having; they were not enough on their own.

## D12 — A drag blocked by the sort gets an offer; a drag blocked by a filter gets nothing.

Reordering is gated on `NoteQuery.allowsManualReorder`, which is false for two unrelated reasons:
an automatic sort is active, or a filter is narrowing the list. Until now both produced the same
result — the drag handle simply was not drawn — which is honest but silent. Someone who reorders
their notes, then sorts by Newest, then wants to reorder again finds the handle gone with no
statement of why or what to do.

The brief asks for a prompt on drag under an automatic sort, and that is what shipped: the handle
stays visible, a drag on it moves nothing, and a dialog offers the switch to manual order.

It deliberately does **not** do the same for the filter case, and the two are not symmetrical:

- **Sort** is a choice the user made and can unmake in one tap. Offering the switch is offering a
  real fix, and the dialog's Switch button performs it.
- **Filters** cannot be fixed by any sort. Position is a property of the whole list, so dragging
  note 3 above note 7 while notes 4–6 are hidden has no meaning to express. The only "fix" is
  clearing the filters, which is a much larger action to trigger from a stray drag — and one the
  filter summary row already offers, in view, one tap away, at the moment it applies.

So a filtered list keeps hiding the handle. `NoteQuery.switchingSortWouldAllowReorder` encodes the
distinction on the model rather than in the composable, so it is testable, and
`ManualReorderGateTest` asserts the two predicates are never both true.

**Reviewed and kept.** The cost is real and worth naming: the handle's visibility depends on *which*
blocker is active, which is one more rule than "manual sort and no filters". The smaller alternative
— keep hiding it in both cases, say nothing — was what shipped before.

Kept because the two blockers genuinely differ in what the user can do about them. A sort is one tap
from being unmade, so silence there withholds a fix the user could apply; a filter is not, so a
handle offering nothing would be the worse lie. Asymmetric rules are acceptable when the underlying
situations are asymmetric.

Reverting is still one commit if it wears badly.

## D13 — Saved filters live in settings, not in the notes database.

A saved filter is a name and a `NoteQuery`, stored as JSON under one preferences key.

The alternative was a Room table, which is what "user data" normally earns. It was rejected on
proportionality. A saved filter is a lens on the user's notes, not a note: losing one costs a few
taps to rebuild, while a new table costs a schema migration on an encrypted database, a sync story
against a Firebase document schema this project is not allowed to change, and a conflict-resolution
question for a Windows client that would not know what a saved filter is. That is a large amount of
risk to protect something cheap.

Three consequences, all accepted deliberately:

- **They do not sync.** Filters saved on the phone stay on the phone. Correct for what they are: a
  saved filter references label ids, which are local, so syncing one would need id translation to
  mean anything on another device.
- **Reads never throw.** A blob that will not parse yields an empty list and a warning in the log.
  This flow feeds the drawer, which is on screen from launch, so a throw here would be a settings
  value taking down the notes list — to protect shortcuts that cost a few taps. `SerializationException`
  and `IllegalArgumentException` are caught specifically rather than `Exception`, so a genuine bug
  in this code still surfaces.
- **Reads are tolerant of the future.** `ignoreUnknownKeys = true`, so a filter written by a later
  build that added a query dimension still loads here minus the field this build does not know,
  rather than the whole list being discarded because one entry had an extra key.

`NoteQuery` and `DateRange` gained `@Serializable`, which is already the house pattern — `Note`,
`Label`, `ChecklistItem` and `Attachment` all carry it.

Saved filters store `narrowingOnly()` — the query without sort and view. Those are persisted
preferences, so a shortcut that carried them would rewrite two settings the user did not touch
every time it was tapped, and flip the list to two columns sorted oldest because that happened to
be on screen when the filter was saved.

`SavedFilterStorageTest` runs against a real file-backed DataStore rather than a fake, because the
parts worth doubting — a truncated blob, an unknown field, whether a name really is the identity —
are exactly the parts a fake would paper over.

## D14 — A heading may only describe an order the list actually has.

Date section headings were emitted on every list, whatever the sort. That is right for one of the
three orders and wrong for the other two.

Under a **manual** order — which is the default — a note's date says nothing about where it sits.
Edit a note from last week: its `timestamp` moves to today, its `position` does not, so it stays
where it was and the list grows a "Today" heading in the middle while the real one still sits at
the top. The heading names a grouping the list does not have.

**Mid-search** the order is relevance, by design (`NoteQueryMatcher.search` overrides the chosen
sort while there is text). Every date heading over a relevance-ordered list is equally arbitrary.

`NoteQuery.ordering` names the three cases — `RELEVANCE`, `MANUAL`, `DATE` — and
`noteSectionHeadings` maps each to what can honestly be said:

| Order | Headings |
|---|---|
| Relevance | none |
| Manual | Pinned / Others |
| Date | Pinned, then one per day |

"Others" only appears as the counterpart to "Pinned". Over a list with nothing pinned it would
divide nothing from nothing, so it is omitted. The string (`section_other_notes`) already existed
in the table and nothing rendered it — the split was intended once and never wired.

The derivation moved out of the composable into a pure function returning a list index-aligned with
the notes, which makes it testable without a screen and also removes a small inefficiency: the old
version formatted a date for the current note **and again for the note before it**, for every note,
on every recomposition.

## D15 — A note with no title gets no title line, not the word "Untitled".

The card rendered `Untitled` in the title slot whenever a note had no title. That put a word the
user did not write in the most prominent position on the card, and pushed their actual first line
down a row to make space for it.

The card's own accessibility description had already decided otherwise. It falls through
title → first line of content → "Untitled", so a screen reader heard *"A note with no title at
all"* while the eye read *"Untitled"*. The same note, described two ways, and the screen reader had
the better of it.

Now the title `Text` is simply not composed when the title is empty — the row stays, because it
carries the timestamp and status icons — and the gap that separated title from body is dropped with
it, since it would be space under nothing.

`untitled` is still used, in the one place it is the honest answer: the accessibility description of
a note with no title *and* no content.

**Reviewed and kept.** The argument against — a card with no heading looks unfinished beside cards
that have one — did not survive contact: the note's own first line becomes the heading, so the card
reads as finished, just written by the user rather than by the app.

Confirmed on real notes as well as the emulator. An untitled note now leads with "A note with no
title at all" rather than the word *Untitled* above it, which is both shorter and more informative.

Reverting is still one commit if it wears badly.
