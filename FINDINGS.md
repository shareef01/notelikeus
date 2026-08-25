# Findings

Bugs and defects noticed while working on the UI/UX + smart-filtering overhaul that are
**outside that project's scope**. Recorded here rather than fixed inline, so the diffs stay
about one thing at a time.

Nothing here is a regression introduced by the overhaul. Fixed items stay listed, struck
through, with the commit that closed them.

---

## F1 — Desktop route arguments are parsed with a regex over `toString()`

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

## F2 — `notes.isLocked` is a vestigial column

Note locking was removed from the product. `NoteEntity.isLocked` remains, `NoteCloudMapper`
still writes `isLocked: false` on every upload, and `firestore.rules` still type-checks it.

The entity comment explains why it was not dropped, and the reasoning is sound: recreating
`notes` fires the `ON DELETE CASCADE` that `checklist_items` and `note_label_cross_ref`
declare against it. Not worth risking checklists and label links to reclaim one boolean.

**Severity:** cosmetic. Documented deliberately; listed so a future reader does not
"discover" it and try to clean it up.

---

## F3 — Unused string resources (21) and lint `Typos` (18)

`lintDebug` reports 21 `UnusedResources` and 18 `Typos`, zero errors. Some of the unused
strings are for features that were removed; some may be reachable only from Glance or the
widget and mis-detected.

**Suggested fix:** a dedicated pass, verifying each is genuinely unreachable before deleting.
Deleting a string that only the widget uses would not fail the build.

**Severity:** low.

---

## F4 — Release builds minify but do not shrink resources

`androidApp/build.gradle.kts:59` sets `isMinifyEnabled = true` without
`isShrinkResources = true`. Lint flags it.

**Why it matters:** APK/AAB size only. R8 was enabled deliberately with conservative keeps
and verified on-device, so turning resource shrinking on needs the same verification pass —
it is the step that historically breaks Compose resource lookups.

**Severity:** low.

---

## F5 — Two trivial lint warnings in `androidApp`

- `MainActivity.kt:37` — `mutableStateOf` holding a `Long`; should be `mutableLongStateOf`
  (`AutoboxingStateCreation`).
- `AndroidManifest.xml:40` — `enableOnBackInvokedCallback` is API 33+, minSdk is 26
  (`UnusedAttribute`, harmless — the attribute is ignored below 33).
- `AndroidManifest.xml:55` — redundant `android:label` (`RedundantLabel`).

**Severity:** cosmetic.

---

## F6 — The Glance widget carries a fourth, independent palette

`ui/widget/WidgetThemes.kt` defines 18 colour literals because Glance composables cannot read
`MaterialTheme`. That is a real platform constraint, not sloppiness, but it means the widget's
colours can drift from the app's without anything failing.

Phase 1 of the overhaul sources these constants from the shared palette to limit drift. The
widget remains a separate render path with its own theme resolution, and a widget-specific
visual review is still owed.

**Severity:** low. Partially mitigated; listed so the remaining gap stays visible.

---

## F7 — The web client's theme picker still offers the six fused themes

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

## F8 — `EditorViewModel` injects a `SettingsRepository` it never uses

`EditorViewModel`'s constructor takes `settingsRepository: SettingsRepository` and the class body
references it exactly once — in the parameter list. Nothing reads it.

It is left over from when a new note took its colour from the active theme's background; that was
removed when `NO_NOTE_COLOR` was introduced, and the dependency stayed. It surfaced during the
Phase 1 audit because `EditorViewModelTest` was still stubbing `settingsRepository.appTheme`,
which is how a dead dependency stays invisible: the test keeps it looking used.

**Why it matters:** minor, but it is a constructor argument threaded through the Koin module and
the desktop `EditorWindowLauncher`'s manual factory, so it makes the editor look like it depends
on settings when it does not.

**Suggested fix:** drop the parameter, then the Koin definition and the desktop factory call.
Deliberately not done inside the audit — it touches DI wiring in three places and belongs in a
commit of its own.

**Severity:** low.

---

## F9 — The app crashes on its first launch after install (`AppStartup` snapshot race)

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

**Not fixed here** because it is unrelated to the query work and an Android startup race deserves
its own change and its own verification rather than a drive-by at the end of another phase. It is
the highest-severity item in this file.

**Severity:** high — it is a crash, on the first launch a user ever performs.

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
