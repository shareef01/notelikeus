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
