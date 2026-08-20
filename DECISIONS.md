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
