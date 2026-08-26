# Findings

Bugs and defects noticed while working on the UI/UX + smart-filtering overhaul that are
**outside that project's scope**. Recorded here rather than fixed inline, so the diffs stay
about one thing at a time.

Nothing here is a regression introduced by the overhaul. Fixed items stay listed, struck
through, with the commit that closed them.

---

## F1 — Desktop route arguments are parsed with a regex over `toString()` — **FIXED**

`NavGraph.kt:135-146` reads `noteId` and `initialColor` by regex-matching
`backStackEntry.arguments?.toString()`, because Compose Navigation does not populate
`SavedStateHandle` on the JVM/desktop target.

**Why it matters:** a navigation-library bump can change that `toString()` format without
any compile error. The failure is silent and lands on the user as "tapping a note opened a
blank new note" — the existing comment says as much. There is no test pinning the format.

**Suggested fix:** pass the arguments out-of-band on desktop (the `EditorWindowLauncher`
already carries them for the separate-window path), or add a test that asserts the regex
still extracts both arguments from a real `NavBackStackEntry`.

**Severity:** medium. Latent, but it silently loses the user's navigation intent.

---

**Fixed**, and the premise turned out to be wrong in an instructive way.

Writing the guard is what found it. The regex had **already stopped working on desktop**:
`arguments.toString()` is now `androidx.savedstate.SavedState@36cf295c`, an identity string with no
values in it at all. Nobody noticed because desktop stopped using this route when the editor moved
into its own OS window — `MainScreen` sends desktop through `editorWindowLauncher.launch(...)`
instead, which carries the arguments out-of-band.

So the workaround was applied on the platform that no longer needs it, and the platform actually
relying on it was **Android**, where `Bundle.toString()` still happens to include the contents. The
comment describing it had it backwards.

`arguments.read { getLong("noteId") }` — the supported savedstate accessor — works on both targets,
proven by the same test. `NavGraph` uses that now, with the keys named once as constants so the
route and the reads cannot drift apart. No string format is depended on anywhere.

`RouteArgumentParsingTest` drives a **real** `NavHost` with the real argument types, which is the
only shape of test that could have caught this: a fixture-based test would encode an assumption
about the format and keep passing through exactly the change it exists to detect. Verified on the
emulator that tapping a note still opens that note.

---

## F2 — `notes.isLocked` is a vestigial column — **CLOSED: won't fix**

Note locking was removed from the product. `NoteEntity.isLocked` remains, `NoteCloudMapper`
still writes `isLocked: false` on every upload, and `firestore.rules` still type-checks it.

The entity comment explains why it was not dropped, and the reasoning is sound: recreating
`notes` fires the `ON DELETE CASCADE` that `checklist_items` and `note_label_cross_ref`
declare against it. Not worth risking checklists and label links to reclaim one boolean.

**Severity:** cosmetic. Documented deliberately; listed so a future reader does not "discover" it
and try to clean it up.

**Closed as won't-fix, by decision rather than neglect.** SQLite cannot drop a column; the table has
to be recreated, and recreating `notes` fires the `ON DELETE CASCADE` that `checklist_items` and
`note_label_cross_ref` declare against it. Reclaiming one unused boolean is not worth putting every
checklist and label link in a populated, encrypted database at risk.

The cost of leaving it is genuinely nil: one boolean per row, written as `false` on every cloud
upload, type-checked by `firestore.rules`. Nothing reads it.

**If it is ever revisited**, the reason to do so would be a migration that has to rebuild `notes`
anyway for some other purpose — at which point dropping this column is free. Doing it on its own is
the version that is not worth it. Do not "discover" this and tidy it up as an isolated change.

---

## F3 — Unused string resources (21) and lint `Typos` (18) — **FIXED**

`lintDebug` reports 21 `UnusedResources` and 18 `Typos`, zero errors. Some of the unused
strings are for features that were removed; some may be reachable only from Glance or the
widget and mis-detected.

**Suggested fix:** a dedicated pass, verifying each is genuinely unreachable before deleting.
Deleting a string that only the widget uses would not fail the build.

**Severity:** low.

**Fixed, after doing the verification pass this entry asked for.** A release APK was built both
ways, debug-signed with `apksigner`, installed on the emulator and driven through launch, the notes
list, the Filters sheet, the drawer and the editor — the Compose resource lookups that shrinking
historically breaks. Nothing missing, nothing in logcat, screenshots identical to the unshrunk
build.

**14,861,446 → 14,358,143 bytes: 492 KiB, 3.4%.** Modest, which is the honest number — most of this
APK is the bundled Inter fonts and native libraries, and neither is a `res/` entry.

---

## F4 — Release builds minify but do not shrink resources — **FIXED**

`androidApp/build.gradle.kts:59` sets `isMinifyEnabled = true` without
`isShrinkResources = true`. Lint flags it.

**Why it matters:** APK/AAB size only. R8 was enabled deliberately with conservative keeps
and verified on-device, so turning resource shrinking on needs the same verification pass —
it is the step that historically breaks Compose resource lookups.

**Severity:** low.

---

## F5 — Two trivial lint warnings in `androidApp` — **FIXED**

- `MainActivity.kt:37` — `mutableStateOf` holding a `Long`; should be `mutableLongStateOf`
  (`AutoboxingStateCreation`).
- `AndroidManifest.xml:40` — `enableOnBackInvokedCallback` is API 33+, minSdk is 26
  (`UnusedAttribute`, harmless — the attribute is ignored below 33).
- `AndroidManifest.xml:55` — redundant `android:label` (`RedundantLabel`).

**Severity:** cosmetic.

**Fixed.** `mutableLongStateOf` for the navigation counter (it is bumped on every deep link and
widget tap, so the generic version boxed a `Long` each time), `tools:targetApi="tiramisu"` on the
back-callback attribute, and the activity's `android:label` dropped as a repeat of the
application's.

---

## F6 — The Glance widget carries a fourth, independent palette — **FIXED**

`ui/widget/WidgetThemes.kt` defines 18 colour literals because Glance composables cannot read
`MaterialTheme`. That is a real platform constraint, not sloppiness, but it means the widget's
colours can drift from the app's without anything failing.

Phase 1 of the overhaul sources these constants from the shared palette to limit drift. The
widget remains a separate render path with its own theme resolution, and a widget-specific
visual review is still owed.

**Severity:** low. Partially mitigated; listed so the remaining gap stays visible.

---

**Fixed**, and the drift this was warning about had already happened — in two ways.

The eighteen literals are gone. `widgetColorsFor` calls `colorSchemeFor`, the same function the app
renders with, and wraps its six colours in `ColorProvider`s. The widget cannot disagree with the app
about a colour any more, because it is asking the app.

What the literals were hiding:

- **The chosen base was ignored.** `WidgetNoteLoader` resolved the base into `resolvedDark` and then
  used it for the AMOLED branch *only* — every other arm keyed off `isSystemDark`. So choosing Light
  while the OS was dark gave a dark widget beside a light app, and choosing Dark on a light OS gave
  the reverse, unless Pure black happened to be on.
- **The accent was read and discarded.** `AccentColor.fromName(...)` was passed into
  `toThemePreference` and `preference.accent` was never looked at, so Midnight and Forest users had
  a neutral widget.

The monochrome branches went too. `USE_MONOCHROME_THEME_KEY` has no writer anywhere in the app and
defaults to `true`, so those two arms always won — and both were aliases of the arms below them,
which is exactly how the base being ignored went unnoticed.

`WidgetThemeParityTest` sweeps all 18 settings combinations × both system modes and asserts the
widget's six colours are the app's, which no shared list of constants could guarantee.

**The one thing never done:** the widget has not been looked at on a home screen.

Attempted and failed to automate. There is no `cmd appwidget` shell implementation on this emulator
image, so placement has to go through the launcher; the picker automates fine but the final drag
does not, under either `input swipe` or a hand-built `motionevent DOWN/MOVE/UP` sequence. The
picker's preview is the static `previewImage`, not a live Glance render, so it shows nothing useful
either. Recorded in the run-app skill so the next attempt does not repeat it.

What that review would still add is now narrower than when this was written. The colours are
*provably* the app's — `WidgetThemeParityTest` sweeps all 18 settings combinations against both
system modes — so what remained unverified was **layout and legibility at widget sizes**, not colour
correctness.

**Closed by decision.** The owed visual review is not being carried any longer. Note what that does
and does not mean: the widget's colours cannot drift from the app's, because they are the app's, and
that is tested. Nobody has confirmed the *layout* reads well at 3x2. If the widget ever looks
cramped or clipped on a home screen, that is unexamined ground rather than a regression, and this
entry is where to start.

---

## F7 — The web client's theme picker still offers the six fused themes — **FIXED**

The Kotlin clients now express appearance as base × black level × accent (`ThemePreference`).
The web client still stores a single `AppTheme` of `auto | light | dark | true_dark | midnight |
forest`, applied as one of five CSS classes by `ThemeApplier.tsx`.

**Why it matters:** a user with both sees two different settings screens for the same concept.
It is not a *data* divergence — the web stores its theme in localStorage, not Firestore, so there
is nothing to migrate and nothing to conflict — which is why it was safe to leave for now.

**Suggested fix:** mirror `toThemePreference` in `settingsStore.ts` as a read-time migration from
the six stored strings, compose the CSS class from base + accent with a separate `amoled` class,
and rebuild `ThemePicker.tsx` as two rows plus a toggle. The Kotlin implementation and its tests
are the specification.

**Severity:** low, and deliberately deferred rather than missed.

---

**Fixed** along the lines suggested. `toThemePreference` in `settingsStore.ts` mirrors the Kotlin
function case for case, applied through zustand's `migrate` at version 1 — read-time only, so a user
who downgrades still has their theme. `ThemeApplier` composes three classes on `<html>` (a base, an
optional accent, an optional black level) and `globals.css` decomposes the five fused rules to
match, including the specificity that lets an accented theme go pure black while keeping its hue —
the combination the six named themes could not express at all.

`ThemePicker` is two swatch rows and a toggle, the same three controls the Kotlin clients show.

**One real bug fell out of it.** The old applier resolved `auto` with a dark OS to `true_dark`, so
choosing **System** turned the app pure black without anyone asking. It resolves to the ordinary dark
palette now, with the black level applied only if it was chosen — and only on a dark base, since a
black background on a light theme is simply wrong.

Verified: 220 web tests (up from 201), `tsc` clean, `npm run lint` 0 errors, production build clean,
and the composed selectors confirmed present and correctly ordered in the built CSS. **Not** viewed
in a browser — the class logic is covered by unit tests and the CSS by inspection, but nobody has
looked at the result on screen.

---

## F8 — `EditorViewModel` injects a `SettingsRepository` it never uses — **FIXED**

`EditorViewModel`'s constructor takes `settingsRepository: SettingsRepository` and the class body
references it exactly once — in the parameter list. Nothing reads it.

It is left over from when a new note took its colour from the active theme's background; that was
removed when `NO_NOTE_COLOR` was introduced, and the dependency stayed. It surfaced during the
Phase 1 audit because `EditorViewModelTest` was still stubbing `settingsRepository.appTheme`,
which is how a dead dependency stays invisible: the test keeps it looking used.

**Why it matters:** minor, but it is a constructor argument threaded through the Koin module and
the desktop `EditorWindowLauncher`'s manual factory, so it makes the editor look like it depends
on settings when it does not.

**Fixed.** Removed from the constructor, both Koin factories and the test's stub — the stub being
the thing that kept it looking used.

**Suggested fix:** drop the parameter, then the Koin definition and the desktop factory call.
Deliberately not done inside the audit — it touches DI wiring in three places and belongs in a
commit of its own.

**Severity:** low.

---

## F9 — The app crashes on its first launch after install (`AppStartup` snapshot race) — **FIXED**

Reproduced twice on a clean emulator, both times at launch:

```
java.lang.IllegalStateException: Reading a state that was created after the snapshot was taken
        or in a snapshot that has not yet been applied
    at androidx.compose.runtime.snapshots.SnapshotKt.readError(Snapshot.kt:2159)
    at com.aus.notelikeus.AppStartup.isReady(NotelikeusApp.kt:139)
    at com.aus.notelikeus.MainActivity.onCreate$lambda$10(MainActivity.kt:60)
```

**Why it happens.** `AppStartup` is a process-lived `object` holding `var isReady by
mutableStateOf(false)`, and `markReady()` is called from `startupScope`, a
`CoroutineScope(SupervisorJob() + Dispatchers.IO)`. A Kotlin `object` initialises on first touch —
so on a cold start the `MutableState` can be *created* on that background thread, after
composition has already taken its snapshot. Composition then reads a state that did not exist when
its snapshot was taken, which is exactly what the error says.

It is a race, which is why it is intermittent and why it shows up on a fresh install: that is when
the database open is slowest, so the background coroutine and the first composition are most
likely to interleave badly.

**Why it matters more than its frequency suggests.** The app recovers on the next launch, so it
looks harmless in daily use — but the moment it happens is *the first time someone opens the app
after installing it*, and it lands in Play vitals as a crash-on-launch.

**Suggested fix.** `mutableStateOf` has snapshot semantics that assume main-thread creation;
nothing here needs them. Replace it with a `MutableStateFlow<Boolean>` read through
`collectAsStateWithLifecycle()`, which is built for cross-thread publication and has no snapshot
identity. Failing that, force `AppStartup` initialisation on the main thread in `onCreate` before
`setContent`, and write through `Snapshot.withMutableSnapshot { }`.

**Fixed** in #71, separately from the query work, as this entry asked. `AppStartup.isReady` is a
`MutableStateFlow` rather than a `mutableStateOf`: the snapshot state was being created off the main
thread by `markReady`, which is where the `IllegalStateException` came from. `MainActivity` reads it
through `collectAsState`, so the Compose state is created in composition, on the main thread, where
it belongs.

This heading went un-updated for several sessions after the fix landed, which is its own small
lesson: a findings file is only worth what it costs to keep true.

## F10 — Manual reordering is unreachable with a screen reader — **FIXED**

`NoteCard` draws the reorder handle with `semantics { contentDescription = reorderLabel }`, but the
only interaction attached to it is `detectDragGestures`. TalkBack and other screen readers cannot
produce a drag, so the handle announces a control that its user has no way to operate. The list can
be reordered by sighted touch only.

The fix is `semantics { customActions = listOf(CustomAccessibilityAction(moveUp, …), … ) }` on the
card, calling the same `onMoveNote(from, to)` the drag already calls, plus `onReorderComplete()`.
The plumbing is already in `NoteStaggeredGrid` — this is an addition at the semantics layer, not a
change to how reordering works.

Pre-existing; not introduced by the reorder-prompt work, which only changed when the handle is
drawn.

**Fixed** in the commit after the one that recorded this. The handle now carries
`CustomAccessibilityAction`s for Move up and Move down, bounded at the ends of the list, calling the
same `onMoveNote` / `onReorderComplete` pair the drag calls. The handle offered under an automatic
sort carries the explanation as an action instead, so the switch is reachable without a drag too.

`NoteReorderSemanticsTest` asserts on the semantics tree rather than on pixels, which is the point:
that tree *is* the API a screen reader consumes, so the test exercises the thing that was broken.

## F11 — Backslash escapes render literally in Compose Multiplatform resources — **FIXED**

Android's `aapt` unescapes `\'` and `\"` in `res/values/strings.xml`. Compose Multiplatform's
resource pipeline does not: it stores the string verbatim, backslash included. Ten strings in
`composeApp/src/commonMain/composeResources/values/strings.xml` used Android's convention, so ten
user-facing messages rendered with a visible backslash:

```
Couldn\'t save that change
Google Play Services isn\'t available on this device
```

Confirmed by decoding the packaged resource rather than by inference — `strings.commonMain.cvr` in
the built APK stores base64 values, and `note_delete_failed` decoded to `Couldn\'t delete that`.

Seven were pre-existing (five error snackbars, two sign-in messages). Three I introduced in this
branch's search notices, and those are what surfaced it: `No results for \"zzzqqq\"` was visible on
screen during device testing.

**Fixed** by using typographic quotes and apostrophes — `’` and `“ ”` — which need no escaping in
XML, match the punctuation the rest of the file already uses (`—`, `…`), and read better than the
straight forms. Verified by decoding the rebuilt APK's resource table and by screenshot.

Worth knowing for anything added later: **this file must not use backslash escapes at all.** The
apostrophe in `Couldn't` is simply an apostrophe here.

## F12 — The drawer never told a screen reader which destination was current — **FIXED**

`SideDrawerNavItem` marked the selected row with a background wash and an accent bar, and nothing
else. It used a plain `clickable`, so the row announced "Notes, button" whether or not it was the
current view: the one piece of state the drawer exists to convey was the one piece it did not
convey to anyone not looking at the screen.

Its icon also carried `contentDescription = label` unconditionally, while the same label sat
visibly beside it — so an open drawer read every destination twice.

Pre-existing, and both got worse with this branch, which added four more rows to this component
(three smart views and one per saved filter).

**Fixed**: `selectable(selected, onClick, role = Role.Tab)` instead of `clickable`, and the icon
describes itself only when the drawer is collapsed and there is no visible label to read.

`SideDrawerNavItemTest` had an assertion requiring the duplicate description in the expanded state.
It was asserting the bug, so it is now asserting its absence, alongside a new test for the selected
state.

## F13 — The empty state showed on top of a populated library at every cold start — **FIXED**

`isLoading` was cleared the moment the notes DAO emitted, but the query pass that turns those notes
into `filteredNotes` runs off the main thread. So there was a published state saying "not loading"
with an empty list, and the notes screen read that as an empty library and rendered **"Notes you add
appear here"** over four notes.

The root cause was worse than the window that first showed it. Restoring the stored sort and view at
startup pushes them through the same query funnel a user tap does, so passes run *before the DAO has
emitted anything*. Those finish instantly against an empty list, so even "loading ends when a query
finishes" ended it before there was anything to show.

On the emulator, where opening the encrypted database takes tens of seconds from cold, the empty
state was on screen for roughly twenty seconds — long enough that I first mistook it for data loss.

**Fixed**: loading ends only when a query has run over notes that actually arrived (`notesLoaded`),
and a scope change resets it, so switching to Archive shows a spinner rather than inheriting the
previous scope's emptiness.

Two tests: one holds the query pass open on a standard dispatcher to observe the state the UI
actually rendered, and one drives the settings-restore path against a DAO that has not emitted.
Both were confirmed to fail against the code before the fix.

## F14 — Bold, Italic and Link did nothing when nothing was selected — **FIXED**

`TextFormatting.wrapSelection` returned the value untouched for a collapsed selection, and
`wrapAsLink` did the same. So three of the five buttons on the editor's formatting toolbar were dead
controls in the ordinary case — tapping **B** with no selection is not an edge case, it is what you
do when you are *about* to type something bold — and there was no selection on screen to hint at
why nothing happened.

Link was the worst of the three, because it wasted work rather than merely ignoring a tap: the
dialog opened, you typed a URL, you confirmed, and the note was unchanged. Its OK button was also
enabled for a blank URL, so that path threw the interaction away too.

**Fixed**: with no selection, Bold and Italic open an empty pair of markers at the cursor and place
the caret between them, so the next thing typed is formatted — what every other editor does. Link
inserts `[example.com](https://example.com)`, a link that works immediately with a label that can be
edited into something better. `LinkDialog` now uses the shared `ConfirmDialog`, which disables its
confirm button — visibly — for a blank URL.

Bullets were already correct: `prefixLinesWithBullet` acts on the line the cursor is in, selection
or not. A test now pins that so it stays true.

Verified on the emulator: Bold with the caret mid-word inserts the markers where the caret is.

## F15 — The reminder dialog ignored its own input and its OK button cancelled — **FIXED**

Three defects in one 30-line composable, all of the same family: the UI said something that was not
so.

1. **`initialTimestamp` was never read.** The caller computed it carefully —
   `state.reminderTimestamp ?: (now + 1h)` — and the dialog threw it away. A parameter that
   compiles, looks implemented, and does nothing.
2. **The confirm button called `onDismiss`.** So "OK" and "Cancel" were the same button with
   opposite labels, and tapping OK after choosing nothing looked like it had set something.
3. **The preset rows rendered on `colorScheme.surface`** inside an `AlertDialog` painted
   `surfaceContainerHigh` — a white slab dropped into a grey card.

**Fixed**: the dialog now says *"Currently set for Aug 25, 2026, 3:45 PM"* when there is a reminder
to remove, which is exactly when `initialTimestamp` is real. The fake OK is gone — choosing a preset
*is* the confirmation — leaving Cancel and, when applicable, Remove in error red. The rows are
transparent and carry `Role.Button`.

Verified on the emulator, both states.

## F16 — `ReminderDateTime.kt` is a fossil of a date/time picker that no longer exists — **RESOLVED**

`combineDateAndTime` is a one-line pass-through to `DateUtils.combineDateAndTime` that no production
code calls — only its own test does, which therefore tests the pass-through and nothing else.

The editor still names its flag `showDateTimePicker`, but what it opens is a three-preset list with
no date or time picker in it. The helper is what is left of the picker that used to be there.

**Resolved by removing it.** Building a custom picker is a new feature, and new features are last in
this project's stated priority order — so of the two honest options, deletion is the one that was
actually in scope.

`DateUtils.combineDateAndTime` — the real implementation — stays. Only the pass-through and the flag
name went. The test moved to `DateUtilsCombineTest` and now calls the real function, so it exercises
the behaviour rather than the indirection; it also gained a midnight case, where an off-by-one-day
bug would surface first. `showDateTimePicker` is now `showReminderDialog`, which is what it opens.

**Still true, and still worth doing:** three presets cannot express "Friday at 6". If a custom
date/time option is wanted, this is the note that says so — the arithmetic it needs is one call to
`DateUtils.combineDateAndTime`, now covered by two tests.

## F17 — The editor's label list announced as buttons with no checked state — **FIXED**

Each label row was a clickable `ListItem` wrapping a `Checkbox` that had its own `onCheckedChange` —
one action wearing two hit targets. The row announced as **"Work, button"**, so the only thing the
list exists to communicate, which labels are on, was the one thing it did not communicate to anyone
not looking at the screen.

**Fixed** with the idiomatic pairing: `Modifier.toggleable(value, role = Role.Checkbox)` on the row,
and `onCheckedChange = null` on the checkbox so it is a picture of the state rather than a rival
control. It now announces "Work, checkbox, checked".

Worth recording how this nearly slipped through. My first guard counted toggleable nodes, expecting
the broken version to produce more of them — it does not. `ListItem`'s clickable merges its
descendants, so the inner checkbox's state merges upward either way, which is exactly how the row
could carry the right state and still describe itself with the wrong role. The assertion that
discriminates is on `Role`, and it was confirmed to fail against the old code.

Two smaller ones in the same sheet: the Delete row's icon repeated its own visible label, so it
announced "Delete, Delete"; and both action rows were `clickable` with no `Role`, so neither said it
was a button.

## F18 — Every checklist control announced the same thing as every other — **FIXED**

A checklist is a column of identical controls, so each has to say what it belongs to. Neither did.

The checkbox's label lives in a separate `BasicTextField` node beside it, not inside it, so the
checkbox announced **"checkbox, checked"** — the same words for every row on the list, with nothing
to say which item was being ticked. The remove button was worse in the same way: `cd_remove_item` is
literally "Remove item", repeated down the column, identifying nothing.

**Fixed**: the checkbox carries the item's text as its content description, and the remove button
reads "Remove Bread". An item with no text yet gets "Empty item" rather than an empty string, so its
controls are still nameable.

`ChecklistSemanticsTest` asserts each control names its item, and that no two controls in a list
share a description — which is the property that was actually violated.

`cd_remove_item` is left in place; the widget still uses it.

## F19 — The packaged Windows desktop app could not start at all — **FIXED**

`AppConfig.isDebug` asks the `RuntimeMXBean` whether a JDWP agent is attached, which reaches
`java.lang.management.ManagementFactory`. That class lives in the **`java.management`** JDK module,
and `java.management` was not in the `modules` list `jlink` builds the packaged runtime from.

So the packaged app died on its first Koin resolution — before a window was ever shown:

```
Exception in thread "main" java.lang.NoClassDefFoundError: java/lang/management/ManagementFactory
	at com.aus.notelikeus.util.AppConfig.<clinit>(AppConfig.desktop.kt:10)
	at com.aus.notelikeus.di.PlatformModuleKt.platformModule$lambda$20$lambda$12(PlatformModule.kt:68)
```

Reproduced on `main` at `8a60a91`, not on any branch of mine. `./gradlew run` cannot catch it — it
has the whole JDK on hand, which is exactly what the build file's own comment says about the last
three modules that went missing this way.

**Fixed** by adding `java.management` to the list. Verified by rebuilding the image — `MODULES=` in
`runtime/release` now carries it — and by launching the packaged executable, which stayed up for 40
seconds with an empty log where it previously died instantly.

### Why CI was green

The `package` job did assert the modules were present, but against a **hand-maintained list of
four** — so it only ever checked what someone had remembered to add. `java.management` was never on
it.

That list is now five, and the job also **launches the packaged executable** and fails if it exits
within 30 seconds. Building is not the check, and neither is a list: jlink succeeds either way and
just emits a smaller runtime. Only starting the thing tests every module at once, including the ones
nobody has thought of yet.

## F20 — Every settings toggle announced as a button with no on/off state — **FIXED**

`SettingsToggleListItem` put `onClick` on the row **and** a live `Switch` in its trailing slot — one
action wearing two hit targets, the same shape as F17 one layer up. The merged row announced
**"Pure black, button"**: the state of the setting withheld from precisely the person who cannot see
the switch.

The row is now `toggleable` with `Role.Switch`, and the switch takes `onCheckedChange = null` so it
pictures the state rather than rivalling the row for the tap. `SettingsRow` gained an optional
`checked` that drives the semantics only — `onClick` still does the work — so a row is a button or a
switch and says which.

Its leading icon also carried the title as its description while the title sat visibly on the next
line, so every setting announced itself twice. Same fix as F12 and F17: the visible label is the
description.

## F21 — "Cloud Sync …" was a progress indicator for work that would never start — **FIXED**

`CloudSyncStatus` starts at `Unknown` and `_syncStatus` is only ever written *inside* `runTimedSync`
— that is, during an actual sync. Anyone who chose "Continue offline" therefore sat under a
permanent **"…"** in the settings sheet, with a `CloudQueue` icon implying something was queued.
Nothing was, and nothing ever would be.

**Fixed**: with no signed-in account the row reads **"Not signed in"** with a `CloudOff` icon.
Verified on the emulator.

The underlying oddity is left alone deliberately: `Unknown` really does mean "no sync has run yet",
and that is honest as a *status*. It was the rendering that turned it into a claim about work in
progress.

## F22 — Nested emphasis leaves its inner markers on screen — **FIXED**

`**bold with __inner__ inside**` renders bold, correctly — and shows the `__` characters. Same for
`**_x_**`. `splitIntoSegments` matches the outer marker, emits the inner text as one opaque segment
and never re-parses it, so any emphasis inside a span survives as literal characters.

Found on a real note on the Pixel, where a bulleted line read:

```
• __this is a new note; the plus button on the windows app seems to be broken....
```

bold, with the underscores visible. Reproduced exactly:

| in | out |
|---|---|
| `**__inner__**` | `__inner__` (bold) |
| `**_inner_**` | `_inner_` (bold) |

**Not fixed, deliberately.** The fix is to recurse into a matched segment and merge styles — but
`toTransformedText` pairs `parse(text)` with `buildOffsetMapping(text)`, and Compose's text field
throws if the two disagree about the transformed length. Hiding more markers in `parse` without
making the mapping hide exactly the same ones turns a cosmetic defect into a crash while typing.
Both need rewriting together, with the offset sweep in `MarkdownOffsetMappingTest` extended to cover
nesting first.

**Not a defect, for contrast:** the same note list shows `**_Wednesday, August 19...` with its
markers visible, because that note has no closing `**`. An unclosed marker is not emphasis, and
leaving it as text is what markdown is supposed to do.

**Severity:** cosmetic, but on a card preview, which is where notes are read most.

---

**Fixed**, by removing the reason it was dangerous rather than by patching around it.

`parse()` and `buildOffsetMapping()` were two independent walks over the same string that had to
agree about length or Compose's text field throws. They are now **one**: `render()` produces the
displayed text, the styles and the offset map together, and each caller takes what it needs. A
matched span recurses, so inner markers are hidden and styles merge — `**a __b__ c**` renders
*a b c* in bold, and `**_x_**` is bold italic.

Two smaller things fell out of building it that way. The `AnnotatedString` is now the rendered
string appended once and styled by offset, rather than a second assembly of the same pieces — so
the text the mapping was built against is literally the text on screen. And search highlighting runs
over the whole displayed string instead of per styled run, so a query spanning a style boundary
matches, which the old per-segment pass could not do.

Guarded by an invariant rather than examples: `the mapping always agrees with the text it was built
for` sweeps 24 sources — nesting, unclosed markers, bare `***`, links, long runs — and checks every
offset maps in range in both directions; `the mapping never goes backwards` protects the
binary-search inverse; `every character of the output belongs to a span` catches uncoloured text.

### What this did *not* turn out to explain

The two notes that led me here were **not** instances of it. Both contain **unclosed** markers —
`• __this is a new note…` with no closing `__`, and `**_Wednesday…` with no closing `**` — and
markdown correctly leaves those as literal text. Confirmed by rendering the exact strings: output
unchanged, font weight 400.

I had read the card as bold and built a theory on it twice. It is not bold; the maroon note's
background just makes it look heavier. The nesting bug was real and reproducible on its own terms
(`**__inner__**` → `__inner__`), but it was never what was on screen.

## F23 — Google sign-in killed the packaged desktop app — **FIXED**

`DesktopGoogleSignInHelper.captureAuthCode` stands up a `com.sun.net.httpserver.HttpServer` to
catch the OAuth redirect. That class lives in the **`jdk.httpserver`** module, which was not in the
`modules` list `jlink` builds the packaged runtime from. Pressing **Sign in with Google** therefore
did this:

```
Exception in thread "main" java.lang.NoClassDefFoundError: com/sun/net/httpserver/HttpServer
	at com.aus.notelikeus.platform.DesktopGoogleSignInHelper$captureAuthCode$2.invokeSuspend(...:138)
Caused by: java.lang.ClassNotFoundException: com.sun.net.httpserver.HttpServer
Failed to launch JVM
```

Not an error dialog — **the whole process died.**

The second instance of exactly the failure F19 was about, found the same way: by running the
packaged build rather than `./gradlew run`, which has the full JDK and cannot see any of this.

**Fixed** by adding `jdk.httpserver`. Verified by rebuilding (`MODULES=` now carries it) and by
pressing Sign in with Google on the packaged app against an isolated profile: process still alive,
log empty, where before it was gone instantly. The CI module assertion now lists six.

### Why the CI smoke test would not have caught this

The launch check added in F19 starts the app and fails if it exits within 30 seconds. This crash
needs a **click** first, so a launch-only check sails past it. That check is still worth having —
it would have caught F19 — but it establishes only that the app starts, not that it works.

A sweep of `desktopMain` for JDK packages turned up nothing else missing: `java.awt` and
`javax.swing` are covered by `java.desktop`, and `com.sun.jna` is a jar rather than a JDK module.
That sweep is the thing to repeat when this class of bug is suspected, rather than waiting to
stumble into the next one.
