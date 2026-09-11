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

---

## F24 — The web client type-checks against `@types/node`, which nothing declares — **FIXED**

`src/lib/firestore/notesSync.emulator.test.ts` imports `node:fs`, `node:path` and reads `process`.
Those type-check only because `@types/node` happens to be installed, arriving transitively via
`vite`, `vitest`, `happy-dom` and `firebase`. **`web/package.json` does not mention it.**

TypeScript 5.9 auto-includes every `@types/*` package it finds, so this was invisible. TypeScript 7
does not, which is how it surfaced: three `TS2591` errors on a file that had been type-checking for
its whole life by accident. D17 adds `"types": ["node"]` to make the reliance explicit in config,
which fixes the errors but not the underlying gap — nothing still guarantees the package is there.

**Severity:** latent. Four separate declared dependencies supply it, so it realistically will not
vanish, and if it did the failure is loud (`Cannot find type definition file for 'node'`) rather
than silent. This is the same shape as the note already in `libs.versions.toml` about
`compose-lifecycle-runtime`: *"It used to arrive transitively, which meant a dependency bump
elsewhere could silently remove it."* That one was worth pinning; this one probably is too.

**The fix is one line** — `"@types/node": "^26.2.0"` in `devDependencies`. Not applied here, because
adding to `package.json` is a dependency decision and those are the owner's call. For the record, in
the terms that decision is normally made in: it replaces nothing, it is **already on disk** so the
install cost is zero, it is DefinitelyTyped's most-used package, and the alternative — leaving it
undeclared — keeps a type-check that works by coincidence rather than by statement.

**Fixed** during the audit that produced F25–F27, once the decision was handed over. One line in
`devDependencies`, `^26.2.0` — the version already resolved on disk — and `npm install
--package-lock-only` added exactly one line to the lockfile with no other version movement, which is
what "it is already there" should look like when it is true.

---

## F25 — Every typed date in search resolved a day late east of UTC — **FIXED**

`NoteQueryParser.parseIsoDate` recovered "today" as a day index by dividing `dayStart(0)` by
86,400,000. But `dayStart(0)` is *local* midnight in epoch millis, and in any zone with a positive
UTC offset that instant falls on the previous UTC day, so the floor division answered a day early
and every offset computed from it was one too large.

`before:2026-08-27` in Berlin, Kolkata, Tokyo or Auckland therefore meant *before the 28th* — it
included a whole day the user had explicitly excluded. `after:` was wrong the same way. Relative
keywords (`today`, `week`) were unaffected: they go straight to `dayStart` and never touch the
arithmetic.

**Confirmed before it was written down**, by a throwaway test against the real parser: UTC and
`America/New_York` passed, `Asia/Kolkata` failed by exactly one day. That asymmetry is the whole
finding — the defect is invisible at or west of UTC, which is where the existing test fixture sat.

**Why the test suite could not see it.** `NoteQueryParserTest` injected
`dayStart = { TODAY_START + offset * DAY }` with `TODAY_START` on an exact UTC day boundary. That
models the one timezone in which the bug does not occur, so the suite could grow indefinitely
without ever discriminating.

**Fixed by deleting the arithmetic rather than correcting it.** The value being divided carries a
timezone; nothing in `commonMain` can honestly divide it. `parse` now takes a second injected
function, `dayStartOfDate(year, month, day)`, and an ISO date is handed straight to it — the parser
decides the *shape* of the operator (three integers) and nothing else. `epochDayFromCivil` and
`floorDiv` existed only to serve the deleted calculation and went with it; the calendar arithmetic
the tests still need to name a date now lives in the test file, where getting it wrong fails a test
instead of shipping.

Two tests, in the two places the behaviour now lives: `NoteQueryParserTest` models a whole zone as
an object so `dayStart` and `dayStartOfDate` cannot disagree in a fixture, and asserts against
UTC+05:30 as well as UTC; `DateUtilsCivilDateTest` sets a real default timezone and runs the real
`Calendar` code across seven zones on both sides of UTC.

**A second defect fell out of the fix, and only the tests found it.** The first version of
`DateUtils.startOfDay(y, m, d)` used a non-lenient `Calendar` to reject `2026-02-31`. Non-lenient
also throws for a date whose local midnight does not *exist* — America/Santiago and America/Havana
shift DST at midnight, so there is no 00:00 on transition day — which turned `before:<that date>`
into an unrecognised operator for everyone in those zones. Worse than the bug being fixed, and
invisible to review. The day-of-month range is now checked explicitly and the Calendar left lenient,
so a missing midnight moves forward to the first instant that exists, exactly as the
`startOfDay(timestamp)` overload already did. Guarded by a sweep of every day of a year in four
midnight-shifting zones rather than a named date, because tzdata moves transitions.

**Bonus, from the same change:** `2026-02-31` used to be accepted — the check only bounded the day
at 31 and the arithmetic carried the overflow into March 3. It is now recorded as `unknown`.

---

## F26 — An invalidated Keystore key stranded the passphrase in the deprecated ESP forever — **FIXED**

`getOrCreateSecretKey` returns the existing alias whenever `KeyStore.getKey` hands one back — and an
invalidated AndroidKeyStore key still *exists*; it only fails at `Cipher.init`. Nothing in the
codebase ever called `deleteEntry`.

So after a lock-screen credential reset or a device-to-device transfer: decrypt throws, the file is
preserved aside, a fresh passphrase is generated, `writeToKeystoreFile` asks for the same dead key,
encrypt throws, and the passphrase falls back to `EncryptedSharedPreferences`. Every later launch
repeats it identically. The passphrase then lives permanently in the deprecated store this class was
written to replace, and the app can never climb back onto the Keystore path.

It fails safe rather than losing data, which is why this is not higher — and the log line at `:47`
shows a one-off failure here was anticipated. What was missed is that the condition is permanent.

**Fixed** by deleting the alias and retrying once, but *only* when encryption itself failed. That
distinction is the whole fix and the first attempt got it wrong: retrying on any failure of the
combined encrypt-and-publish step would delete a perfectly healthy key because a rename lost a race
— which makes the existing passphrase file undecryptable and quarantines the database, precisely the
outcome the surrounding code is built to avoid. `encryptUnderKeystoreKey` and `publishByRename` are
now separate, and only the former's failure is evidence about the key.

Deleting is safe on that path and no other: by then the key protects nothing — either no passphrase
file exists (first run, or the legacy migration) or `preserveUnreadablePassphraseFile` has already
moved it aside. The decrypt path deliberately keeps the alias, because dropping it would destroy the
only chance of ever reading that preserved blob back.

**Was not covered by a test, and is now.** The obstacle was real: a real AndroidKeyStore key cannot
be invalidated from a test — invalidation is a lock-screen credential reset or a device transfer, not
an API call — so the branch was unreachable and this said so rather than claiming otherwise.

Closed by extracting `PassphraseKeyStore`, the same move `PassphraseFileCodec` already represents:
the interesting behaviour cannot be driven without a seam. The public API did not move —
`DatabaseKeyManager(context)` is a secondary constructor now — and the fake supplies a key that
genuinely fails to encrypt, since a 7-byte AES key makes `Cipher.init` throw exactly the way an
invalidated one does.

`DatabaseKeyManagerRecoveryTest` asserts the dead key is replaced *once* and that the passphrase is
**persisted** under the replacement, read back through a second manager — the property that actually
matters, because without the retry no file is published and every launch regenerates a different
passphrase, which is how an openable database becomes a quarantined one. Confirmed to discriminate:
reverting `writeToKeystoreFile` to the pre-fix version fails two of the three tests.

**One thing it still does not cover**, stated rather than glossed: the first attempt's bug deleted a
healthy key when *publishing* failed, and that variant is unreachable here — publish always succeeds
against Robolectric's temp `filesDir`, so the third test passes against the buggy version too. It
guards against deletion becoming unconditional, not against that specific race. Forcing a rename
failure was attempted and abandoned: a directory planted at the target path gets moved aside by
`preserveUnreadablePassphraseFile` before publish is ever reached.

---

## F27 — The desktop session loader could take the whole app down on a non-Windows JVM — **FIXED**

`persist()` catches `Throwable`, with a comment explaining why: on a non-Windows JVM, loading
`Crypt32` fails with `UnsatisfiedLinkError`, which is an `Error` and would otherwise escape.
`load()` makes the same JNA call three times and caught only `Exception` — and `load()` runs from
`init`, so an escape there fails the whole Koin graph and the app never starts.

Latent rather than live: `persist()` can never create a `.session` on a platform where `load()`
would fail, so the file is not there to trip over. But it is the same failure shape as F19 and F23,
with the mitigation already written one function above and simply not carried across.

**Fixed** by widening both catches to `Throwable`. That change forced a second one: the inner catch
used to `delete()` the session file, and once the `Error` is actually caught, that line would start
destroying sessions on any machine where DPAPI is merely *unavailable* rather than the blob being
bad. It now keeps the file — consistent with `preserveUnreadablePassphraseFile` and the quarantine
path, which never delete what they only failed to read. Nothing is lost by keeping it: the next
successful `save()` overwrites the file wholesale.

---

## F28 — Web Editor `Escape` key shortcut bypassed pending saves and raced unmount — **FIXED**

In `web/src/screens/MainScreen.tsx`, the global `Escape` shortcut had `allowInInputs: false` (default)
and called `useUiStore.getState().closeEditor()`. When editing a note's title or body, pressing
`Escape` was ignored while focused or bypassed `editor.flushSave()` when triggered, dropping pending
debounced edits upon subsequent reload in Playwright browser E2E tests (`note-lifecycle.spec.ts:106`).
`EditorScreen.tsx` also only trapped `Escape` on `isFloatLayout`.

**Fixed** by registering an `Escape` shortcut with `allowInInputs: true` inside `EditorScreen.tsx`
bound directly to `handleBack()` (which flushes `flushSave()`), and updating `MainScreen.tsx` so it
does not close the editor asynchronously when `EditorScreen` is active. Verified by 100% pass on all
Playwright browser E2E tests.

---

## F29 — Web note grid emitted date grouping headers under manual and search sorts (Decision D14 violation) — **FIXED**

`web/src/components/notes/NoteStaggeredGrid.tsx` emitted date headers (`getDateHeader(note.timestamp)`)
across all views including manual and search, violating Decision D14 and diverging from Kotlin's
`NoteSections.kt`.

**Fixed** by updating `buildBoardItems` in `NoteStaggeredGrid.tsx` to omit date headers on search
relevance and manual sorts, showing only `Pinned` and `Others` under manual sort when pinned notes exist.

---

## F30 — `PRIVACY_POLICY.md` falsely stated web client requires Google sign-in — **FIXED**

`PRIVACY_POLICY.md` claimed web requires Google sign-in and only keeps notes in the cloud, while the
web client fully supports accountless offline guest storage and local backups.

**Fixed** by updating `PRIVACY_POLICY.md` to accurately disclose accountless local-first operation
across all three platforms (Android, Windows, Web).

---

## F31 — Web editor opened blank / new note when clicking an existing note card — **FIXED**

When clicking an existing note in the web client, `useNoteEditor` initialized `useState<EditorState>(createBlankEditorState())` synchronously on initial render, deferring the lookup of existing note title/content to an asynchronous `useEffect`. Furthermore, `<EditorScreen />` was rendered in `MainScreen.tsx` and `App.tsx` without an explicit `key` attribute (`key={noteId}`), causing React to reuse stale component instances and retain blank / empty editor state when transitioning between editor routes.

**Fixed**:
1. `useNoteEditor`: Synchronously initialized `useState<EditorState>(() => ...)` from `useNotesStore.getState().notes` when `noteId && noteId !== 'new'`, ensuring the existing note's title, body, color, checklist, and timestamps render immediately on the initial mount frame without layout flash or stale state.
2. Initialized `loadedRouteRef.current` to `noteId` and `lastContentEditRef.current` with the note content.
3. Added explicit keys to all `<EditorScreen />` render sites in `MainScreen.tsx` (`key={dockedEditor.mode === 'new' ? 'new' : dockedEditor.noteId}` and `key={overlayEditor.mode === 'new' ? 'new' : overlayEditor.noteId}`) and `App.tsx` (`key="new"` and `key={editorNoteId}`).
4. Added unit tests in `useNoteEditor.test.ts` covering note loading, state mapping, and route transitions.
5. Rebuilt web bundle and deployed to live production on Firebase Hosting (`https://notelike.web.app`).



## F32 — `SideDrawer.tsx` had TypeScript errors: stale `iconClass`/`barClass` destructuring and undefined `MANAGE_ITEMS` — **FIXED**

`SideDrawer.tsx` was destructuring `iconClass` and `barClass` from `NAV_ITEMS` map (whose type definition
`{ filter: NoteFilter; label: string; Icon: typeof NotesIcon }` never included those fields), and
passing them as props to `NavButton` which likewise does not accept them. Additionally, the `Manage`
section NavButtons for "Edit labels" and "Settings" referenced `MANAGE_ITEMS.labels.iconClass` and
`MANAGE_ITEMS.settings.iconClass` — a constant that does not exist anywhere in the file.

**Effect:** TypeScript typecheck (`npm run typecheck`) exited with code 1 with 8 type errors. The runtime was
not affected because TypeScript erasure means these ghost props are silently ignored, but the presence of
errors would have masked any real type regressions and blocked strict CI enforcement.

**Root cause:** residue from a past refactoring that removed the `iconClass`/`barClass` styling system
and the `MANAGE_ITEMS` constant from the SideDrawer without cleaning up all the consumption sites.

**Fixed** by removing the stale destructured bindings from `NAV_ITEMS.map(...)` and removing the
`iconClass`/`barClass` props from all three `NavButton` call sites, plus the two undefined `MANAGE_ITEMS`
references. `npm run typecheck` now exits 0 with no errors.

**Severity:** medium (CI gate broken; runtime correct but opaque to future regressions).

---

## F33 — `NoteCard` displayed "Untitled" as the card heading for empty-title notes, violating Decision D15 — **FIXED**

`NoteCard.tsx` set `const title = note.title || 'Untitled'` and rendered it unconditionally in the
`<h2>` for all three density modes (list, grid, dense). Decision D15 explicitly states the title text
is not rendered when the title is empty; "Untitled" is only the honest accessibility label when **both**
title and content are empty.

**Effect:** A note with no title but with body text showed "Untitled" as the bold card heading, while
the actual content was suppressed below. The user sees a heading that isn't there and misses the first
line of their note.

**Fixed:**
1. Changed `const title = note.title` (empty string when blank, never the word "Untitled").
2. Added `const firstBodyLine = previewBody.split('\n')[0]?.trim() ?? ''` for the visual fallback.
3. In **list layout**: wrapped the `<h2>` in `{title ? ... : null}`, and changed the body `<p>` to
   add `font-semibold` + remove the top margin when there is no title (first-line-as-heading styling).
4. In **grid/dense layout**: wrapped the `<h2>` in `{title ? ... : null}`, and added an
   `aria-hidden` flex sibling spacer when no title so the status-icon cluster stays right-aligned.
5. Updated the accessibility label to `title || firstBodyLine || 'Untitled'` — "Untitled" only
   appears in the a11y tree when the note is completely blank, per D15.

**Severity:** medium (intentional product decision violated, every note with content-only displayed wrong).

---

## F34 — `LabelsScreen.tsx` had buttons without `type="button"` and an unlabelled edit input — **FIXED**

The "edit label" trigger button and the "delete label" button inside `LabelsScreen.tsx` both lacked
`type="button"`. Without an explicit type, HTML defaults to `type="submit"`. While not a runtime
crash here (neither button is inside a `<form>`), this is a correctness issue and would cause problems
if the component structure ever gains a wrapping form.

Additionally, the inline edit input shown when renaming a label had no `aria-label`, making it
inaccessible to screen readers.

**Fixed:** Added `type="button"` to the label-click and label-delete buttons, and added
`aria-label="Edit label name"` to the inline edit input.

**Severity:** low (a11y + best-practice; no user-visible regression).

---

## F35 — In-app privacy dialog still claimed Google sign-in was required on web — **FIXED**

`PrivacyPolicyDialog.tsx` body text still said *"Sign-in with Google is required"* and *"There is no separate offline-only mode on the web"*, while `PRIVACY_POLICY.md` was corrected in F30 and the app supports guest/local-first operation.

**Fixed** by rewriting `PRIVACY_POLICY_BODY` to match the August 2026 policy: offline-first on all platforms, optional cloud sync, guest web operation.

---

## F36 — Mobile and fullscreen editor overlays had no focus trap — **FIXED**

`EditorScreen.tsx` applied `useFocusTrap` only to the float layout. Phone editor (`!isTabletUp`) and tablet fullscreen used `fixed inset-0` overlays without `role="dialog"`, `aria-modal`, or focus trapping — Tab reached the notes list behind the editor.

**Fixed** by adding `overlayPanelRef = useFocusTrap(...)` for `needsOverlayTrap` (`!isTabletUp || editorLayout === 'fullscreen'`) and attaching it to those shells with dialog semantics.

---

## F37 — Labels screen overlay had no focus trap or Escape close — **FIXED**

`LabelsScreen.tsx` was a full-screen overlay without `useFocusTrap`, `useBodyScrollLock`, or `role="dialog"`. Tab could escape to the main UI; only the close icon dismissed it.

**Fixed** with focus trap, body scroll lock, `role="dialog"`, `aria-modal`, and Escape via the shared trap.

---

## F38 — Delete confirm Escape closed the entire options sheet — **FIXED**

`EditorOptionsSheet.tsx` nested a delete confirm dialog inside `ResponsiveSheet`. Both registered document `Escape` listeners; the sheet trap called `onClose()` and dismissed the whole sheet instead of canceling delete only.

**Fixed** by adding `closeOnEscape` to `useFocusTrap` / `ResponsiveSheet` and passing `closeOnEscape={!confirmDelete}` to the sheet. Regression tests in `useFocusTrap.test.ts`.

---

## F39 — Sort chip appeared functional during search but did not change order (D14) — **FIXED**

While search text is active, results are relevance-ranked regardless of sort. Cycling sort still updated the chip label and showed a toast, but note order did not change — a dead control.

**Fixed** by disabling the sort chip during active search (`sortDisabled` on `FilterRow`, label shows *Relevance*).

---

## F40 — Notes error Retry button used hard-coded white on light theme — **FIXED**

`MainScreen.tsx` error state used `text-red-300`, `bg-white/10`, `text-white/80` on `bg-true-surface` — near-invisible in light theme.

**Fixed** with theme tokens (`text-red-500`, `border-brand-outline`, `bg-brand-primary/10`, `text-brand-primary`).

---

## F41 — Checklist remove buttons and several form inputs lacked accessible names — **FIXED**

`ChecklistEditor.tsx` remove buttons all announced *"Remove item"* (Kotlin F18 fixed this on Android). Link URL, new-label, and create-label inputs had placeholder-only labels.

**Fixed**: per-item remove labels (`Remove ${text}` / *Remove empty item*), `aria-label` on link URL, new label, and create-label inputs.

**Severity:** low–medium (accessibility).

---

## F42 — Auth overlay lacked focus trap; Google sign-in button ignored theme — **FIXED**

Optional auth overlay (`AuthScreen` with `mandatory={false}`) had no focus trap — Tab reached the notes UI underneath. `GoogleSignInButton` always used `bg-white` / `#1f1f1f`, jarring on dark auth screens.

**Fixed**: focus trap + dialog semantics on auth overlay (`closeOnEscape: !mandatory`); Google button uses theme tokens (`bg-true-surface`, `text-brand-primary`).

---

## F43 — Keyboard focus rings missing on drawer, editor chrome, filter chips, and settings rows — **FIXED**

Many interactive controls in `SideDrawer`, `EditorScreen`, `FilterRow`, `ProfileSheet`, `LabelsScreen`, and `AuthScreen` had no visible `focus-visible` outline, making keyboard navigation hard to follow. `TopBar` and `SelectionBar` already had a local constant; the rest did not.

**Fixed** by extracting `CHROME_FOCUS` to `web/src/lib/ui/focusStyles.ts` and applying it across drawer nav buttons, editor header/actions, filter chips, settings rows, auth tabs, and the markdown preview toggle (which also gained `aria-label="Edit note body"`).

**Severity:** low (accessibility polish).

---

## F44 — Bold and Italic toolbar buttons appeared dead on web and Android — **FIXED**

Two separate failures stacked:

**Web (especially mobile):** Toolbar buttons used `click` + `mousedown preventDefault`, which does not stop touch from blurring the textarea before formatting ran. When formatting did insert `****` / `__`, blur flipped the editor into markdown preview mode (`contentFocused` false + non-empty content), hiding the markers from the user's working view. Fix: run actions on `pointerdown` with `preventDefault`, and force `setContentFocused(true)` after applying format. Sync `lastContentEditRef` in `applyContentFormatting`.

**Android:** Toolbar `IconButton`s took focus from the body field, collapsing selection before `applyFormatting` read `contentValue`. Fix: `focusProperties { canFocus = false }` on all toolbar buttons. Also set `contentEdited = true` in `applyFormatting` so an in-flight note load cannot overwrite a format applied before load completed.

**Severity:** high (core editor formatting).

---

## F45 — `manifest.webmanifest` declared wrong sizes for PWA icons — **FIXED**

Both `public/icons/icon-192.png` and `public/icons/icon-512.png` are identical 1024×1024 PNG files
(both 943,861 bytes). The manifest declared them as `192x192` and `512x512` respectively, which is
incorrect. A mismatched `sizes` declaration causes the browser to misrank or discard an icon during
PWA install, splash screen generation, and home screen placement.

Additionally, the `favicon.svg` entry declared `"sizes": "512x512"` — incorrect for an SVG, which
is natively scalable and should use `"sizes": "any"`.

**Fixed** by updating all three icon entries to reflect actual dimensions:
- `icon-192.png` → `"sizes": "1024x1024"` (actual: 1024×1024)
- `icon-512.png` (maskable) → `"sizes": "1024x1024"` (actual: 1024×1024)
- `favicon.svg` → `"sizes": "any"` (vector, no fixed size)

The duplicate `icon-512.png` `"purpose": "any"` entry (identical to the maskable entry) was also removed.

**Severity:** low (incorrect metadata; install works but browser makes suboptimal icon choices; correct
icon still served since browsers use the file data, not just the declared size).


---

## F46 — Attachment DELETE deleted the R2 object before recording the deletion, and ignored the result — **FIXED**

`workers/attachments/src/index.ts:deleteAttachment` ran `authorize_note_attachment_delete` (a
read-only preflight since `20260906160000`), deleted the R2 object, then called
`finalize_note_attachment_delete` **and discarded the answer**, always replying
`200 {"deleted":true}`.

Every failure of that third call therefore produced a live metadata row pointing at an object that
no longer existed:

- 403/404/400 from the RPC became `{ allowed: false }` and was thrown away — a false 200.
- 5xx, a network failure, a timeout, or a malformed body raised `UpstreamServiceError`, which the
  outer handler turned into 502/503 **after** the bytes were already gone.
- Nothing recorded the intent, so a client that gave up left the row live forever. The orphan
  sweeper could not help: `list_orphaned_deleted_attachments` only looks at rows whose
  `deleted_at` is set, and this row's never was.

A related integrity bug fell out of the same ordering: because only finalization set `deleted_at`
and nothing marked the deletion as the owner's own, `restore_note` would later undelete a row the
user had explicitly deleted, resurrecting metadata whose bytes the Worker had destroyed. Reproduced
directly against the migrations (delete attachment → delete note → restore note → row live again).

**Fixed** by claiming the deletion first, mirroring the sweeper's own claim protocol from
`20260907140000_attachment_purge_claim.sql`. `20260908120000_attachment_delete_claim.sql` adds
`begin_note_attachment_delete` (row lock, marks `deleted_at` + `delete_claimed_at`, returns the
canonical key), stamps `object_deleted_at` at finalization, teaches `restore_note` to skip
owner-claimed rows, and adds a service-role recovery pass
(`list_unconfirmed_attachment_deletes` / `confirm_attachment_object_deleted`) so an abandoned
delete is finished by the cron rather than left forever. The Worker now reports an unconfirmed
stamp as `confirmed: false` inside a 200, because by then the attachment genuinely is deleted.

**Severity:** high (silent metadata/object divergence, unrecoverable without the claim).

---

## F47 — Worker authenticated against Supabase before rejecting unroutable requests — **FIXED**

`handleAttachmentRequest` called `resolveAuthenticatedUserId` first, so `PATCH /invalid-path` with a
garbage bearer cost one outbound Supabase Auth request before the 404/405 that any local check
could have produced.

**Fixed** by ordering the pipeline route shape → method → upload headers → rate limit →
authenticate → per-user rate limit → resource authorization. Everything decided before
authentication is a property of the request alone, so no status difference can be used to probe for
another user's notes or attachments.

**Severity:** medium (amplification of unauthenticated traffic into upstream auth calls).

---

## F48 — PUT buffered the whole upload before checking who owned the note — **FIXED**

`putAttachment` read up to 10 MB into Worker memory and only then called
`authorize_note_attachment_put`, so any authenticated account could make the Worker buffer an
upload aimed at someone else's note before being told 403.

**Fixed** with `precheck_note_attachment_put`, an advisory RPC that answers ownership, note
liveness, MIME, the per-note count quota, and (from `Content-Length`, when present) an obvious
byte-quota overflow before `request.body` is touched. Nothing authoritative moved: the real size,
both quotas, note liveness and the canonical key are still enforced by
`finalize_note_attachment_put` under the per-owner advisory lock, against the bytes that actually
arrived.

**Severity:** medium.

---

## F49 — An unreadable Supabase answer was indistinguishable from a refusal — **FIXED**

`authorizeAttachment` treated any non-401, non-5xx failure as `{ allowed: false }`, and parsed the
body with a bare `response.json()`. Two consequences: PostgREST's `404 PGRST202` — the RPC does not
exist, i.e. a Worker deployed ahead of its migrations — was reported to the caller as "you may not
do that"; and a JSON `null` body parsed to `null`, which the call sites read as "token rejected"
and answered 401.

**Fixed** by keeping three outcomes distinct: null for a rejected token, `{ allowed: false }` for a
refusal, and `UpstreamServiceError` (503 unreachable / schema behind, 502 malformed) for an answer
that cannot be trusted. A non-object JSON document is now an upstream fault, not a decision.

**Severity:** medium.

---

## F50 — No repository-managed abuse controls on the attachments Worker — **PARTIALLY FIXED**

`wrangler.toml.example` configured no rate limit, body limit, or timeout, and nothing in the repo
throttled per user or per endpoint.

**Fixed** as far as the repository can: the Worker now applies an *optional* Cloudflare
rate-limiting binding (`ATTACHMENT_RATE_LIMITER`) keyed by client IP before authentication and by
user id after, documented in `wrangler.toml.example`. Cloudflare counts these at the edge, so
unlike an in-isolate counter the limit is not reset by the request landing on a different isolate.
It fails open by design — throttling is a mitigation, not the authorization boundary.

**Not fixed, and not fixable here:** zone-level WAF rules, bot management, per-endpoint rate
limiting, and platform request/CPU limits are configured on the Cloudflare zone, not in this
repository. Deployments that want them must set them there.

---

## F51 — Windows and Web store notes unencrypted at rest — **DESIGN / PRODUCT DECISION**

Android encrypts its Room database with SQLCipher under an AndroidKeyStore-sealed passphrase.
Windows uses `BundledSQLiteDriver` against a plaintext file (only the Supabase session token is
DPAPI-sealed), and Web stores plain records in IndexedDB. `PRIVACY_POLICY.md` already describes
this accurately.

Not implemented here, deliberately: the desktop Room stack has no JVM-capable encrypted SQLite
driver to swap in, so it would mean a new native dependency plus a custom Room KMP driver, a
Windows CI job, and a decision about guest-mode users for whom key loss is unrecoverable data
loss. The full threat model, migration plan, recovery/backup/key-loss implications, and testing
requirements are in [`docs/LOCAL_ENCRYPTION_AT_REST.md`](LOCAL_ENCRYPTION_AT_REST.md), which also
records why browser-side encryption must not be described as an XSS mitigation.

---

## F52 — `finalize_note_attachment_put` could revive an attachment whose deletion was already claimed — **FIXED**

Follow-up review of F46. `20260908120000` made an owner-claimed deletion terminal and taught
`restore_note` to respect it, but missed the other route back to live: the finalizer ends in
`INSERT ... ON CONFLICT (owner_id, attachment_id) DO UPDATE SET ... deleted_at = NULL`, with no
check on `delete_claimed_at`, `purge_claimed_at`, or `object_deleted_at`.

Reproduced against the applied schema, not inferred:

- **Sequential.** Upload `attA` → delete `attA` → re-PUT `attA` left
  `deleted_at = NULL, delete_claimed_at NOT NULL, object_deleted_at NOT NULL` — a live attachment
  whose bytes the Worker had already destroyed, and one no sweeper revisits: the unconfirmed-delete
  pass skips confirmed rows, and the orphan pass needs the note to be gone.
- **Concurrent**, two real sessions. A DELETE claim committing between a PUT's authorization and
  its finalization was overwritten by that finalization. (The reverse order was already correct —
  the claim waits on the insert's row lock and then wins.)
- **Worker-level.** With a DELETE landing between `authorize` and `R2.head()`, the `already_live`
  repair path re-uploaded bytes for an identity that had just been retired; `ownedByThisRequest`
  was false, so ordinary compensation would have declined to remove them and nothing would ever
  have collected them.

**Fixed** in `20260908130000_attachment_delete_is_terminal.sql`. `finalize_note_attachment_put`
now takes `FOR UPDATE` on the conflict row and returns
`{allowed: false, reason: 'terminally_deleted', object_key}` — a value, not an exception, so the
Worker can distinguish it from a timeout and force compensation for bytes it just wrote.
`precheck_note_attachment_put` and `authorize_note_attachment_put` refuse the same identity early
so no body is read. A CHECK constraint makes it structural: no function, including the
still-reviving `register_note_attachment`, can leave a live row carrying a claim. A repair pass
re-marks any pre-existing live-but-claimed row before the constraint is added.

**Behaviour change**, and the one existing assertion it invalidated: an attachment id is retired by
its own deletion, so re-uploading that id is refused (Worker 409) rather than treated as fresh.
`notelikeus_attachment_put_idempotency.test.sql` step 5 asserted the old contract and now asserts
the new one; replacement content uses a new attachment id, exactly as the architecture already
documented.

**Severity:** high (silent resurrection of destroyed data; unreachable by any sweeper).

---

## F53 — A backup exported by the web client could not be imported on Android or Windows — **FIXED**

`BackupData.labels` was typed `List<Label>`, whose `id` is `Long?`. The web exporter writes its own
label ids into the same field, and those are slugs (`"label-travel"`). `ignoreUnknownKeys` does not
help with a *type* mismatch, so `decodeFromString` threw at `$.labels[0].id` and
`importFromJson`'s outer `catch (e: Exception)` turned it into a generic
`BackupImportResult.Error` — "Import failed", with no reason.

Reproduced against the real importer, not inferred:

```
expected:<Success(notesImported=3, labelsCreated=2)>
but was:<Error(throwable=JsonDecodingException: Unexpected symbol 'l' in numeric literal
  at path: $.labels[0].id ... "id": "label-travel" ...)>
```

Total and silent: every user who exported on the web and tried to restore on a phone or on Windows
lost the transfer, while `README.md`'s feature table claimed JSON backup import/export on all three
clients.

**Fixed** with a `LabelBackupDto` whose `id` is a raw `JsonElement`, accepting a number and a string
alike. Neither importer has ever read that field — labels are matched by name — so it is carried
rather than dropped, and what Kotlin *writes* is byte-for-byte unchanged, so a file from this build
still imports into the previous one. A genuinely malformed file now reports the decoder's own path
instead of an unexplained failure.

Guarded by `CrossClientContractTest` against `contracts/backup/v3-web-export.json`.

**Severity:** high (documented cross-platform path completely broken in one direction).

---

## F54 — Deleting every cloud note broke sync permanently — **FIXED**

`SuspectEmptyCloudException` exists to stop a fetch that fails *open* from being read as
"everything was deleted elsewhere". Its own comment names the distinguishing fact — a genuine
remote delete leaves tombstones, which `mergeCloudTombstones` has already applied — and the guard
never used it. Both `downloadAllNotes` and `uploadAllNotes` compared the whole `knownCloudIds` set
against an empty fetch without subtracting the ids those tombstones explain.

Worse, it could not heal. `setKnownCloudIds` only runs at the end of a *successful* download, so
the set that trips the guard was never updated, and `uploadAllNotes` carried the same check — so
creating a new note did not clear it either. Delete your last note (or empty the trash) on another
device and this device answered "Check the connection or sign in again" on every sync, forever.

The web client was already correct: `supabaseRemoteNotesDataSource.ts` computes
`unexplained = knownIds.filter(id => !snapshotIds.has(id) && !tombstoneIds.has(id))` and has the
test `'lets a snapshot empty by deletion through once every note is tombstoned'`. This was
Kotlin-only drift from a decision the other client had already made.

**Fixed** with one helper, `unexplainedMissingCloudIds`, used by both guards. The safety property is
untouched — a failed-open read leaves the previously-known ids unexplained, so it still refuses.

Guarded by `EmptyCloudAfterDeletingEverythingTest`, whose four cases include two that would fail
against a naive "just drop the check" fix.

**Severity:** high (unrecoverable sync failure).

---

## F55 — The web reminder picker pre-filled a time in the past — **FIXED**

`ReminderPickerDialog.toInputValue`'s null branch built the default from
`nextHour.toISOString().slice(0, 16)`. A `datetime-local` value is **local wall-clock time**;
`toISOString()` is UTC. Measured in `Asia/Kolkata`, the offered value parsed back as more than 240
minutes in the past. `buildSwReminders` filters `fireAt > now`, so a user who accepted the offered
time got no reminder and no warning. West of UTC the reminder landed hours late instead.

The non-null branch was already correct, so this only affected setting a *new* reminder — the
common case. Same family as F25, different site.

**Fixed** by `web/src/lib/reminders/reminderTime.ts`, which builds the value from the local getters
and parses it back as local time. Covered across five zones including `Australia/Eucla`, whose
:45 offset catches offset arithmetic that lands on the wrong minute.

**Severity:** medium.

---

## F56 — `putNotes` could hang instead of failing — **FIXED**

`web/src/lib/local/notesLocalRepository.ts`'s `putNotes` handled `tx.onerror` but not
`tx.onabort`. An IndexedDB transaction can abort with no request having errored — the browser
reclaiming storage, an internal fault — and `error` does not fire for those, so the promise never
settled. `withStore`, `clearOwner`, `replaceAllNotes` and `applyRemotePageAtomically` all already
handled both.

`hydrateFromRemote` awaits it before the app reports ready, so a hang left the app stuck on boot;
`applyNotes` rolls the optimistic UI back in `.catch`, so a hang left the store claiming a durable
write that never happened.

**Fixed** with an `onabort` handler and a test hook matching the file's existing
`abortNextRemotePageApplyForTests` convention.

**Severity:** medium (a local write that never settles is worse than one that fails).

---

## F57 — Web and Kotlin disagreed about an elapsed reminder on import — **FIXED**

Kotlin's importer dropped a `reminderTimestamp` already in the past
(`takeIf { it > currentTimeMillis() }`); the web importer kept it. The same backup produced
different notes on different platforms — on web, a reminder chip for an alarm that can never fire.

**Fixed** by converging web onto Kotlin's behaviour, which is the older and better-reasoned one.
Pinned in `contracts/backup/v3-expected-notes.json` as a note whose reminder is in the past.

**Severity:** low (cross-client divergence).

---

## F58 — `npm run pages:verify` could not run on Windows — **FIXED**

The script used a `VAR=value command` prefix, which is POSIX shell syntax. npm runs scripts through
`cmd.exe` on Windows, where that is not an assignment:

```
'VITE_SUPABASE_URL' is not recognized as an internal or external command
```

CI runs on ubuntu, so it stayed green and hid this from the one platform the desktop app is built
for. **Fixed** with `scripts/ops/verify-pages.mjs`, which sets the placeholders itself — no new
dependency, runnable from a fresh clone.

**Severity:** low (tooling; a documented verification command unusable by its own maintainer).

---

## F59 — Kotlin's cloud fetch has no structural completeness check — **FIXED**

The web client validates its snapshot structurally: `fetch_full_snapshot` returns a separate
`note_count`, and a mismatch against the row count is refused as
`Incomplete snapshot: expected N notes, got M`.

**Fixed** (`67080b3c`): `SupabaseNoteTransport.parsedSnapshot` requires `note_count` and refuses a
raw-array mismatch; `CloudNoteSnapshot.authoritativeNoteCount` survives parse drops;
`NoteSyncEngine.fetchCompleteSnapshot` throws `IncompleteCloudSnapshotException` before
reconcile / local deletes / tombstones. Covered by `SupabaseNoteTransportTest`,
`TruncatedCloudSnapshotTest`, and `CloudNoteTransportContractTest`.

**Severity was:** medium-high (silent local deletion on a partially failed read).

---

## F60 — Bundle export is not implemented on Android or Windows — **FIXED**

The `.nlkbak` backup bundle (notes plus attachment bytes) is implemented end-to-end on the web
client and on Android/Desktop.

**Fixed:** shared `jvmMain` hosts `BackupBundleCodec` / `BackupBundleTransfer`. Export includes
local pending and `file:` attachment bytes (never R2). Import remints attachment ids onto newly
allocated note ids. Profile sheet offers `.nlkbak` export (primary), JSON notes-only (secondary),
and a single import that sniffs PK / `.nlkbak` vs JSON.

---

## F61 — `partialRemoteSnapshot.test.ts` fails under CPU contention — **MOSTLY FIXED**

Observed once during the 2026 audit: the full web suite failed a single assertion
(`partialRemoteSnapshot.test.ts:140`) on a run that took 220s instead of its usual 24s because it
was competing with an R8 release build for CPU.

**Fixed for the common cases:** wake helpers and bootstrap waits now use `vi.waitFor` /
`fireRealtimeWake()` keyed on RPC counts and emissions, not fixed sleeps.

One remaining `setTimeout(SUPABASE_PULL_DEBOUNCE_MS * 3)` is intentional in the in-flight-bootstrap
race test: the queued pull issues no RPC while the snapshot gate is held, so there is no
observable to wait on. The real assertion is that `emitted[0]` is the full library after release.

**Severity:** low (test infrastructure; residual timing only in that race case).
