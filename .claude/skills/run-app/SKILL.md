---
name: run-app
description: Launch and drive Notelikeus — the Windows desktop app, the web app, or the Android app on an emulator — and capture screenshots. Use when asked to run, start, screenshot, or manually verify the app.
---

# Running Notelikeus

Three clients, three different launch paths. None of them work with the obvious
first attempt, which is why this file exists.

## Windows desktop (Compose Multiplatform)

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :composeApp:run
```

`JAVA_HOME` is **required** — there is no `java` on PATH in this environment and
Gradle fails with "JAVA_HOME is not set" before it does anything else.

Run it backgrounded (`run_in_background: true`); `:composeApp:run` blocks until
the window closes.

### Use a throwaway data directory

The app stores its database at `%APPDATA%\Notelikeus`. To avoid touching the
real notes — and to get a genuine first-run experience — override `APPDATA`:

```bash
export APPDATA="C:\\Users\\LENOVO\\AppData\\Local\\Temp\\nk-demo-appdata"
```

Two bugs were only ever visible on a first run, because every real install is
already signed in. If you are verifying startup behaviour, do it against an
empty directory.

**Exporting `APPDATA` does nothing if a Gradle daemon is already warm.**
`DesktopPathProvider` reads `System.getenv("APPDATA")`, and `:composeApp:run`
forks the app from the *daemon*, which inherits the environment it was started
with, not the one you just exported. A daemon left over from an earlier
`./gradlew test` silently wins, and you get the real signed-in app holding the
real notes while believing you are on a throwaway directory. Stop it first:

```bash
./gradlew --stop
export APPDATA="C:\\Users\\LENOVO\\AppData\\Local\\Temp\\nk-demo-appdata"
./gradlew :composeApp:run
```

Check before doing anything destructive: a throwaway run opens on the sign-in
gate, the real one shows the signed-in account in the bottom-left rail.

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

**Never install onto the physical device.** The installed build there is a
release build holding real notes; installing a debug build requires uninstalling
first, which destroys them.

Seeding demo notes is **unsolved**. The database is SQLCipher-encrypted so it
cannot be written directly the way the desktop one can, and `adb` UI automation
has failed repeatedly: focus starts in the body rather than the title, and the
editor does not reliably close on `keyevent 4`, so subsequent notes type into
the still-open one. If you need populated Android screenshots, add the notes by
hand — it takes under a minute and has cost hours of automation attempts.

## Driving, not just launching

A launch with no interaction only proves the entrypoint resolves. Click
something and screenshot the result, and **look at the image** — a blank frame,
or the wrong window, is a failure to launch.
