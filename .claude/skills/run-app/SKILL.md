---
name: run-app
description: Launch and drive Notelikeus — the Windows desktop app, the web app, or the Android app on an emulator — and capture screenshots. Use when asked to run, start, screenshot, or manually verify the app.
---

# Running Notelikeus

Three clients, three different launch paths. None of them work with the obvious
first attempt, which is why this file exists.

## Windows desktop (Compose Multiplatform)

```bash
./gradlew :composeApp:run
```

Run it backgrounded (`run_in_background: true`); `:composeApp:run` blocks until
the window closes.

**Do not set `JAVA_HOME`.** This entry used to insist it was required, on the
grounds that there is no `java` on PATH — there is (OpenJDK 21, Microsoft build),
and every other Gradle invocation in this repo works without it. Exporting the
POSIX path is actively harmful: Gradle is a Windows process, the `/c/...` form
does not survive the boundary, and the launch dies with

```
Error: could not open `C:\Program Files\Android\Android Studio\jbr\lib\jvm.cfg'
```

which reads like a broken JDK rather than a broken variable. Verified by removing
it: the app launches.

### Use a throwaway data directory

The app stores its database at `%APPDATA%\Notelikeus`. To avoid touching the
real notes — and to get a genuine first-run experience — override `APPDATA`:

```bash
export APPDATA="C:\\Users\\LENOVO\\AppData\\Local\\Temp\\nk-demo-appdata"
```

Two bugs were only ever visible on a first run, because every real install is
already signed in. If you are verifying startup behaviour, do it against an
empty directory.

**`APPDATA` isolates the database. It does not isolate the account.** This is the part that
bites, and it is now understood.

The throwaway directory does get its own `notelikeus_db` — that part works, and the real
`%APPDATA%\Notelikeus` is genuinely left alone (check its mtimes afterwards to confirm). But the
Firebase credential is restored from **outside** `APPDATA`, so the app can start on the sign-in
gate, look perfectly isolated, and then be **signed into the real account a few seconds later** —
writing a fresh `.session` into the throwaway directory and pulling the real notes down from
Firestore into the throwaway database.

Observed exactly that: screenshotted the sign-in gate, clicked *Continue offline*, created a note,
and the next screenshot showed a signed-in account with the real notes beside a new empty one.
Local data was untouched; the exposure is the **other direction** — anything created or edited in
that state can sync **up** to the real account.

Consequences worth internalising:

- **Checking the rail once is not enough.** Check it again in the screenshot before *every*
  mutating action, not just at startup.
- **Never press "Sign in with Google"** while driving. It can complete silently against a live
  browser session, and there is no confirmation step.
- If a signed-in account appears, **`Stop-Process -Force` immediately** rather than tidying up
  through the UI, then verify the account from another client before doing anything else.
- Prefer read-only exploration on the desktop app. For flows that must create notes, the **Android
  emulator** is the safe target: local data, no account.

**Searching is read-only, and it verifies more than it looks like it does.** A whole class of
behaviour can be confirmed on the real app without writing anything: type into the search box and
screenshot the result. That is how the `before:` / `after:` day-boundary fix was verified end to end
against real notes — `before:<today>` returning the library and `before:<yesterday>` returning
nothing is the exact pair that distinguishes a correct day boundary from an off-by-one, and it needs
no note to be created, edited or deleted.

It is also how the date-chip overflow was found, which no test had caught. **Reach for a read-only
interaction before concluding a flow cannot be checked safely** — the account hazard above is about
data flowing *up*, and a search never does.

Driving the search box: click it, then `SendKeys('^a{DEL}')` before typing, or the new query lands
appended to the old one.

So never trust the export. Screenshot the window and read the bottom-left rail *before* every
action, and treat a signed-in account as a stop signal. Notes are real data: prefer killing the
process (`Stop-Process -Force`) over closing the window, because a graceful close runs the
editor's save-on-close and rewrites the note's timestamp.

### Seeding demo notes

The desktop database is **plain SQLite** (no SQLCipher — that is Android only),
so write to it directly instead of automating the UI. Close the app first.

```
%APPDATA%\Notelikeus\notelikeus_db
tables: notes, labels, note_label_cross_ref, checklist_items
```

`notes.color` is a **signed 32-bit ARGB int**, not a palette index. Values come
from `NOTE_COLOR_OPTIONS` in `ui/theme/Color.kt` — e.g. dark blue is
`0xFF2A4A6E`, which stores as `-14005650`.

### Screenshots — the parts that bite

Use **`PrintWindow`**, not `CopyFromScreen`. `SetForegroundWindow` is refused for
background processes, so screen capture silently grabs whatever window is
actually on top (this produced screenshots of VS Code). `PrintWindow` with flag
`2` (`PW_RENDERFULLCONTENT`) captures the window's own content regardless of
occlusion.

Call `SetProcessDPIAware()` first, or `GetWindowRect` returns a rect that does
not match what is on screen and the capture is cropped.

**The window often launches minimized.** `GetWindowRect` then returns an
off-screen rect (around `-25600`), and `PrintWindow` happily writes a ~199x34
image of nothing instead of failing. Check `IsIconic` and call
`ShowWindow(h, 9)` before capturing, and treat an implausibly small rect as an
error rather than saving it.

`Add-Type -PassThru` returns an **array** when the definition declares a struct
alongside methods. Select the class:

```powershell
$types = Add-Type -MemberDefinition $sig -Name Win32 -Namespace Nk -PassThru
$api = $types | Where-Object { $_.Name -eq 'Win32' }
```

To actually **click**, the window must be foreground, and Windows blocks that
from a background process unless an ALT keypress unlocks it first:

```powershell
$api::keybd_event(0xA4,0,0,0); $api::keybd_event(0xA4,0,2,0)
[void]$api::SetForegroundWindow($h)
```

Verify `GetForegroundWindow()` matches before clicking; otherwise the click
lands on another app.

Note the editor opens in its **own OS window** on desktop, so the main window
handle goes stale after opening a note — re-query by title.

## Web

```bash
cd web && npm run dev          # dev server
cd web && npm run test:e2e     # Playwright against the Firebase emulators
```

For screenshots or manual checks with real data flow, the e2e setup is the
easiest driver: `--mode e2e` builds with `.env.e2e`, which points Auth and
Firestore at local emulators and enables the email/password test login. See
`e2e/note-lifecycle.spec.ts` for a working sign-in → create → reload flow.

Gotchas encoded in that spec: the app opens **straight onto the auth screen**
(clicking `/sign in/i` hits "Sign in with Google" and hangs); saves are
**debounced ~1s** and reloading early destroys the write rather than racing it,
so wait on the `Write/channel` response; and a note card's clickable element is
a **button whose accessible name is the title** — `getByText` matches the inner
`<h2>`, which never becomes clickable.

## Android

```bash
emulator -avd Medium_Phone_API_36.1 -no-snapshot-load -no-boot-anim
./gradlew :androidApp:assembleDebug
adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s emulator-5554 exec-out screencap -p > shot.png
```

Use `exec-out screencap -p`, not `shell screencap -p /sdcard/...` — the latter
fails on current images.

**Installing onto the physical device is safe, but be deliberate about it.** The
build already there is a **debug** build (`dumpsys package com.aus.notelikeus`
shows `flags=[ DEBUGGABLE ]`), signed with the same debug key, so
`adb -s <serial> install -r` updates it in place and keeps the notes —
verified by `firstInstallTime` staying put across an update. The older warning
here said it was a release build needing an uninstall first; that was wrong, and
acting on it would have meant avoiding a safe operation or performing a
destructive one.

Check before you trust that, though: if `flags` ever loses `DEBUGGABLE`, the
signatures differ and `install -r` fails — at which point the only way through
*is* an uninstall, and that does destroy the notes.

**`./gradlew installDebug` targets every connected device**, so with a phone
plugged in it installs there too, whatever you intended. Pass an explicit
`adb -s <serial> install -r <apk>` when you mean one device.

**Placing the widget on the home screen is unsolved too.** There is no
`cmd appwidget` shell implementation on this image ("No shell command
implementation"), so it has to go through the launcher. The picker itself
automates fine — long-press home → Widgets → search "Notelikeus" → tap to
expand — but the final drag does not. Both `input swipe` and a hand-built
`input motionevent DOWN / MOVE… / UP` sequence (with a 2s hold and 5px creep
before the larger moves) dismiss the sheet without binding a widget;
`dumpsys appwidget` shows the provider but never an instance. Note also that
the picker's preview is the **static `previewImage`**, not a live Glance
render, so expanding the entry tells you nothing about the widget's actual
colours.

If you need to see the widget, ask for it to be placed by hand — it takes
seconds and has already cost two automation attempts.

Seeding demo notes is **unsolved**. The database is SQLCipher-encrypted so it
cannot be written directly the way the desktop one can, and `adb` UI automation
has failed repeatedly: focus starts in the body rather than the title, and the
editor does not reliably close on `keyevent 4`, so subsequent notes type into
the still-open one. If you need populated Android screenshots, add the notes by
hand — it takes under a minute and has cost hours of automation attempts.

### Clicking a desktop window

The window is often **taller than the screen** (1250x1000 launched on a 1536x864
display), so a control near the bottom maps to a screen coordinate that does not
exist and the click silently goes nowhere. Move and size the window into the
virtual screen first (`SetWindowPos`), then compute from the fresh rect.

Apply the same guards to clicking that the screenshot code needs: a **minimized**
window reports a rect near `-32000`, and clicking that lands nowhere. Check
`IsIconic` and `ShowWindow(h, 9)` first, refuse an implausible rect, and refuse a
target outside `SystemInformation::VirtualScreen`. Without those a failed click
looks exactly like a working button that does nothing — which is how a "the +
button is broken" report nearly got confirmed from a click that never landed.

Match windows by **process**, not title — but by the *right* process name, which
depends on how it was started:

- **`./gradlew :composeApp:run`** (the dev path above) produces a plain **`java`**
  process. `Get-Process -Name Notelikeus` finds nothing at all, which looks
  exactly like "the app failed to start". There are usually several `java`
  processes; the app is the one with a non-zero handle:
  `Get-Process -Name java | Where MainWindowHandle -ne 0`.
- **The packaged build** is `Notelikeus` (two processes — launcher and JVM — and
  only one has the window).

Safest either way, since it covers both: `Get-Process | Where MainWindowHandle -ne
0 | Where { $_.ProcessName -match 'java|Notelikeus' }`. Editor windows are `undecorated` with **no title at all**,
so a title search cannot find them; to answer "did a window open", capture the
whole `VirtualScreen` and look.

## Driving, not just launching

A launch with no interaction only proves the entrypoint resolves. Click
something and screenshot the result, and **look at the image** — a blank frame,
or the wrong window, is a failure to launch.
