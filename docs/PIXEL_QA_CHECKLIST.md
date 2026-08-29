# Notelikeus Pixel QA Checklist

Device target: `Pixel 7` (`panther`, Wi-Fi ADB)
Build target: Android debug APK **1.0.2** (`versionCode` 4)
Goal: quick manual QA for personal-use and portfolio readiness

Session closed: 29 Aug 2026. In-place `adb -s <serial> install -r` of the 1.0.2 debug APK. `DEBUGGABLE` stayed set; `firstInstallTime` unchanged (17 Aug 2026), so the existing library was kept. Automation only covered install, launch, and a visual pass. Remaining on-device rows were completed by the owner the same evening. Do not fake biometric or Play OAuth. Do not sign out from automation (that wipes local Room).

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
- Cold bring-to-front after install showed the notes list, not a biometric gate. Lock was not enabled for this session (do not fake biometric).

---

## 3. Core note flow

### 3.1 Create and edit
- [x] PASS / [ ] FAIL — Create a new note
- [x] PASS / [ ] FAIL — Edit title/body successfully
- [x] PASS / [ ] FAIL — Changes are still there after leaving and reopening note

### 3.2 Rich content basics
- [ ] PASS / [ ] FAIL / [x] N/A — Bold formatting works
- [ ] PASS / [ ] FAIL / [x] N/A — Italic formatting works
- [ ] PASS / [ ] FAIL / [x] N/A — Bullet list works
- [ ] PASS / [ ] FAIL / [x] N/A — Checklist works
- [ ] PASS / [ ] FAIL / [x] N/A — Link insertion works

### 3.3 Organization
- [ ] PASS / [ ] FAIL / [x] N/A — Change note color
- [ ] PASS / [ ] FAIL / [x] N/A — Add/remove labels
- [ ] PASS / [ ] FAIL / [x] N/A — Pin/unpin works
- [ ] PASS / [ ] FAIL / [x] N/A — Archive/unarchive works
- [ ] PASS / [ ] FAIL / [x] N/A — Trash/restore works

### Notes
- Owner completed a throwaway create/edit on the Pixel. Rich-format and archive/trash were not part of this wrap pass.

---

## 4. Search, filters, and layout

- [x] PASS / [ ] FAIL — Search finds expected note content
- [x] PASS / [ ] FAIL — Search clears correctly
- [x] PASS / [ ] FAIL — Notes / Archive / Trash filters work
- [x] PASS / [ ] FAIL — View mode switcher works
- [x] PASS / [ ] FAIL — Sort order switcher works

### Notes
- Owner completed search, filter, and view-cycle on the Pixel. Automation had already shown List view, Filters, Manual sort, and Recents on launch.

---

## 5. Labels screen

- [ ] PASS / [ ] FAIL / [x] N/A — Open labels screen
- [ ] PASS / [ ] FAIL / [x] N/A — Create label
- [ ] PASS / [ ] FAIL / [x] N/A — Rename label
- [ ] PASS / [ ] FAIL / [x] N/A — Delete label
- [ ] PASS / [ ] FAIL / [x] N/A — Notes reflect label changes correctly

### Notes
- Skipped: mutates labels on the real library. Not in the wrap pass.

---

## 6. Reminder notifications

### 6.1 Permission flow
- [x] PASS / [ ] FAIL — App does not ask for notification permission on cold start unnecessarily
- [x] PASS / [ ] FAIL — Notification permission is requested only when setting a reminder

### 6.2 Reminder delivery
- [x] PASS / [ ] FAIL — Set reminder a few minutes in the future
- [x] PASS / [ ] FAIL — Reminder appears in note UI after saving
- [x] PASS / [ ] FAIL — Notification arrives at expected time
- [x] PASS / [ ] FAIL — Tapping notification opens the correct note

### Notes
- Launch did not show a notification-permission dialog. Owner completed reminder set/delivery on the Pixel.

---

## 7. Backup import/export

### 7.1 Export
- [x] PASS / [ ] FAIL — Export backup file succeeds
- [x] PASS / [ ] FAIL — Exported file is created in chosen location

### 7.2 Import
- [ ] PASS / [ ] FAIL / [x] N/A — Import backup file succeeds
- [ ] PASS / [ ] FAIL / [x] N/A — Imported notes appear correctly
- [ ] PASS / [ ] FAIL / [x] N/A — Labels survive import correctly

### Notes
- Owner completed export. Import was skipped so a restore would not write over the real library.

---

## 8. Cloud sync / Google sign-in

### 8.1 Sign-in
- [ ] PASS / [ ] FAIL / [x] N/A — Google sign-in opens correctly
- [ ] PASS / [ ] FAIL / [x] N/A — Account selection succeeds
- [ ] PASS / [ ] FAIL / [x] N/A — App returns to signed-in state

### 8.2 Sync behavior
- [x] PASS / [ ] FAIL — Manual sync works
- [x] PASS / [ ] FAIL — Synced note count/status looks reasonable
- [x] PASS / [ ] FAIL — Create/edit note while signed in syncs as expected

### 8.3 Sign-out
- [ ] PASS / [ ] FAIL / [x] N/A — Normal sign-out works
- [ ] PASS / [ ] FAIL / [x] N/A — Sign-out without delete keeps local notes intact
- [ ] PASS / [ ] FAIL / [x] N/A — Delete-cloud-data flow shows strong confirmation

### Notes
- Device was already signed in. Play account picker was not re-run. Sign-out and delete-cloud were skipped (sign-out wipes local Room). Sync rows covered by the signed-in throwaway note.

---

## 9. Offline behavior

### 9.1 Airplane mode test
- [ ] PASS / [ ] FAIL / [x] N/A — Existing local notes remain usable offline
- [ ] PASS / [ ] FAIL / [x] N/A — Create/edit notes offline works
- [ ] PASS / [ ] FAIL / [x] N/A — App does not become unstable offline

### 9.2 Reconnect
- [ ] PASS / [ ] FAIL / [x] N/A — Reconnect after offline period works
- [ ] PASS / [ ] FAIL / [x] N/A — Sync resumes without obvious duplication/loss

### Notes
- Skipped: would toggle radios on the personal phone.

---

## 10. Widget

- [x] PASS / [ ] FAIL / [ ] N/A — Add widget to home screen
- [x] PASS / [ ] FAIL / [ ] N/A — Widget shows expected notes/content
- [x] PASS / [ ] FAIL / [ ] N/A — Widget updates after note edits
- [x] PASS / [ ] FAIL / [ ] N/A — Widget tap opens app/note correctly

### Notes
- Owner placed and checked the widget by hand. `cmd appwidget` has no shell implementation on this image, so it was never automated.

---

## 11. Visual polish and usability

- [x] PASS / [ ] FAIL — No obviously broken layout on phone screen
- [x] PASS / [ ] FAIL — Dark/light theme behavior looks acceptable
- [x] PASS / [ ] FAIL — Buttons/icons feel responsive
- [x] PASS / [ ] FAIL — No obvious clipping, overlap, or unreadable text

### Notes
- Light theme, List view, colour cards, search, Filters, FAB all laid out. Icons and type readable.

---

## 12. Final decision

- [x] Ready for personal daily use
- [x] Ready for portfolio demo/screenshots
- [ ] Needs more fixes before regular use

### Top issues found
1. None reported. Install/launch was clean; remaining wrap rows were owner-PASS.
2.
3.

### Overall notes
- Session closed 29 Aug 2026. 1.0.2 debug is on the Pixel 7 with the existing signed-in library.
- Skipped by design: lock enable, Play OAuth re-run, sign-out / delete-cloud, airplane radio toggle, label CRUD, backup import.
- Store listing / signed AAB remains out of scope until asked.
