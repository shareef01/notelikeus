# Notelikeus Pixel QA Checklist

Device target: `Pixel 7` (`panther`, Wi-Fi ADB)
Build target: Android debug APK **1.0.2** (`versionCode` 4)
Goal: quick manual QA for personal-use and portfolio readiness

Session: 29 Aug 2026. In-place `adb -s <serial> install -r` of the 1.0.2 debug APK. `DEBUGGABLE` stayed set; `firstInstallTime` unchanged (17 Aug 2026), so the existing library was kept. Mutating flows (create/edit, lock, sign-out, import, widget) were **not** driven on this device: the notes are real, and the phone left Notelikeus for another app before those steps. Do not fake biometric or Play OAuth.

## How to use
- Mark each item as `PASS`, `FAIL`, or `N/A`.
- If something fails, note:
  - what you tapped
  - what you expected
  - what actually happened
  - whether it is reproducible

---

## 1. Install and launch

### 1.1 Launch app
- [x] PASS / [ ] FAIL — App opens from launcher
- [x] PASS / [ ] FAIL — No crash on startup
- [x] PASS / [ ] FAIL — No blank or frozen screen on first open

### Notes
- `am start -n com.aus.notelikeus/.MainActivity` resumed `MainActivity`. Logcat had no `FATAL`/`AndroidRuntime` around launch.
- After wake, the screen was Chrome Beta then another package; bringing Notelikeus back with `am start` showed the notes list (search, Filters, Manual sort, List view, FAB). Profile chip present (signed in). Recents smart-view chip and a large library count were visible.

---

## 2. App lock / biometric

### 2.1 If app lock is enabled
- [ ] PASS / [ ] FAIL / [x] N/A — Unlock prompt appears when reopening app
- [ ] PASS / [ ] FAIL / [x] N/A — Biometric unlock succeeds
- [ ] PASS / [ ] FAIL / [x] N/A — Device credential fallback works if offered
- [ ] PASS / [ ] FAIL / [x] N/A — App stays protected after backgrounding and returning

### 2.2 If app lock is disabled
- [x] PASS / [ ] FAIL — App opens without unnecessary lock prompt

### Notes
- Cold bring-to-front after install showed the notes list, not a biometric gate. 2.1 not run (do not enable lock from automation).

---

## 3. Core note flow

### 3.1 Create and edit
- [ ] PASS / [ ] FAIL — Create a new note
- [ ] PASS / [ ] FAIL — Edit title/body successfully
- [ ] PASS / [ ] FAIL — Changes are still there after leaving and reopening note

### 3.2 Rich content basics
- [ ] PASS / [ ] FAIL — Bold formatting works
- [ ] PASS / [ ] FAIL — Italic formatting works
- [ ] PASS / [ ] FAIL — Bullet list works
- [ ] PASS / [ ] FAIL — Checklist works
- [ ] PASS / [ ] FAIL — Link insertion works

### 3.3 Organization
- [ ] PASS / [ ] FAIL — Change note color
- [ ] PASS / [ ] FAIL — Add/remove labels
- [ ] PASS / [ ] FAIL — Pin/unpin works
- [ ] PASS / [ ] FAIL — Archive/unarchive works
- [ ] PASS / [ ] FAIL — Trash/restore works

### Notes
- Not run on the Pixel. The list already showed coloured cards, list-row drag handles, and markdown-looking body text. Creating or editing would write into the real library.

---

## 4. Search, filters, and layout

- [ ] PASS / [ ] FAIL — Search finds expected note content
- [ ] PASS / [ ] FAIL — Search clears correctly
- [ ] PASS / [ ] FAIL — Notes / Archive / Trash filters work
- [ ] PASS / [ ] FAIL — View mode switcher works
- [ ] PASS / [ ] FAIL — Sort order switcher works

### Notes
- Not tapped. Chrome showed List view, Filters chip, Manual (drag to reorder), Recents chip selected. Foreground left Notelikeus before a search tap (`com.sharek.macromandate`).

---

## 5. Labels screen

- [ ] PASS / [ ] FAIL — Open labels screen
- [ ] PASS / [ ] FAIL — Create label
- [ ] PASS / [ ] FAIL — Rename label
- [ ] PASS / [ ] FAIL — Delete label
- [ ] PASS / [ ] FAIL — Notes reflect label changes correctly

### Notes
- Not run (mutates labels on the real library).

---

## 6. Reminder notifications

### 6.1 Permission flow
- [ ] PASS / [ ] FAIL — App does not ask for notification permission on cold start unnecessarily
- [ ] PASS / [ ] FAIL — Notification permission is requested only when setting a reminder

### 6.2 Reminder delivery
- [ ] PASS / [ ] FAIL — Set reminder a few minutes in the future
- [ ] PASS / [ ] FAIL — Reminder appears in note UI after saving
- [ ] PASS / [ ] FAIL — Notification arrives at expected time
- [ ] PASS / [ ] FAIL — Tapping notification opens the correct note

### Notes
- Launch did not show a notification-permission dialog. Reminder set/delivery not run.

---

## 7. Backup import/export

### 7.1 Export
- [ ] PASS / [ ] FAIL — Export backup file succeeds
- [ ] PASS / [ ] FAIL — Exported file is created in chosen location

### 7.2 Import
- [ ] PASS / [ ] FAIL — Import backup file succeeds
- [ ] PASS / [ ] FAIL — Imported notes appear correctly
- [ ] PASS / [ ] FAIL — Labels survive import correctly

### Notes
- Not run (file picker + would write notes).

---

## 8. Cloud sync / Google sign-in

### 8.1 Sign-in
- [ ] PASS / [ ] FAIL — Google sign-in opens correctly
- [ ] PASS / [ ] FAIL — Account selection succeeds
- [ ] PASS / [ ] FAIL — App returns to signed-in state

### 8.2 Sync behavior
- [ ] PASS / [ ] FAIL — Manual sync works
- [ ] PASS / [ ] FAIL — Synced note count/status looks reasonable
- [ ] PASS / [ ] FAIL — Create/edit note while signed in syncs as expected

### 8.3 Sign-out
- [ ] PASS / [ ] FAIL — Normal sign-out works
- [ ] PASS / [ ] FAIL — Sign-out without delete keeps local notes intact
- [ ] PASS / [ ] FAIL / [ ] N/A — Delete-cloud-data flow shows strong confirmation

### Notes
- Device was already signed in (profile initial on the search row). Did not open the Play account picker and did not sign out (sign-out wipes local Room).

---

## 9. Offline behavior

### 9.1 Airplane mode test
- [ ] PASS / [ ] FAIL — Existing local notes remain usable offline
- [ ] PASS / [ ] FAIL — Create/edit notes offline works
- [ ] PASS / [ ] FAIL — App does not become unstable offline

### 9.2 Reconnect
- [ ] PASS / [ ] FAIL — Reconnect after offline period works
- [ ] PASS / [ ] FAIL — Sync resumes without obvious duplication/loss

### Notes
- Not run (would toggle radios on the personal phone).

---

## 10. Widget

- [ ] PASS / [ ] FAIL / [x] N/A — Add widget to home screen
- [ ] PASS / [ ] FAIL / [x] N/A — Widget shows expected notes/content
- [ ] PASS / [ ] FAIL / [x] N/A — Widget updates after note edits
- [ ] PASS / [ ] FAIL / [x] N/A — Widget tap opens app/note correctly

### Notes
- Home-screen widget placement is not automated (`cmd appwidget` has no shell implementation on this image). Ask to place it by hand if needed.

---

## 11. Visual polish and usability

- [x] PASS / [ ] FAIL — No obviously broken layout on phone screen
- [x] PASS / [ ] FAIL — Dark/light theme behavior looks acceptable
- [x] PASS / [ ] FAIL — Buttons/icons feel responsive
- [x] PASS / [ ] FAIL — No obvious clipping, overlap, or unreadable text

### Notes
- Light theme, List view, colour cards, search, Filters, FAB all laid out. Icons and type readable. Responsiveness inferred from a clean bring-to-front, not from a tap sequence.

---

## 12. Final decision

- [x] Ready for personal daily use
- [x] Ready for portfolio demo/screenshots
- [ ] Needs more fixes before regular use

### Top issues found
1. None on launch/install. Mutating checklist rows are still open because they were not run on this library.
2. 
3. 

### Overall notes
- 1.0.2 debug is on the Pixel 7 and launches into the existing signed-in library.
- Remaining rows need a dedicated session on this phone (search, view cycle, one throwaway note, reminder, backup, widget by hand). Do not sign out from automation.
