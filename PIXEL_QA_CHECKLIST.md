# Notelikeus Pixel QA Checklist

Device target: `Pixel 7`
Build target: Android debug APK
Goal: quick manual QA for personal-use and portfolio readiness

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
- [ ] PASS / [ ] FAIL — App opens from launcher
- [ ] PASS / [ ] FAIL — No crash on startup
- [ ] PASS / [ ] FAIL — No blank or frozen screen on first open

### Notes
- 

---

## 2. App lock / biometric

### 2.1 If app lock is enabled
- [ ] PASS / [ ] FAIL / [ ] N/A — Unlock prompt appears when reopening app
- [ ] PASS / [ ] FAIL / [ ] N/A — Biometric unlock succeeds
- [ ] PASS / [ ] FAIL / [ ] N/A — Device credential fallback works if offered
- [ ] PASS / [ ] FAIL / [ ] N/A — App stays protected after backgrounding and returning

### 2.2 If app lock is disabled
- [ ] PASS / [ ] FAIL — App opens without unnecessary lock prompt

### Notes
- 

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
- 

---

## 4. Search, filters, and layout

- [ ] PASS / [ ] FAIL — Search finds expected note content
- [ ] PASS / [ ] FAIL — Search clears correctly
- [ ] PASS / [ ] FAIL — Notes / Archive / Trash filters work
- [ ] PASS / [ ] FAIL — View mode switcher works
- [ ] PASS / [ ] FAIL — Sort order switcher works

### Notes
- 

---

## 5. Labels screen

- [ ] PASS / [ ] FAIL — Open labels screen
- [ ] PASS / [ ] FAIL — Create label
- [ ] PASS / [ ] FAIL — Rename label
- [ ] PASS / [ ] FAIL — Delete label
- [ ] PASS / [ ] FAIL — Notes reflect label changes correctly

### Notes
- 

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
- 

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
- 

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
- 

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
- 

---

## 10. Widget

- [ ] PASS / [ ] FAIL / [ ] N/A — Add widget to home screen
- [ ] PASS / [ ] FAIL / [ ] N/A — Widget shows expected notes/content
- [ ] PASS / [ ] FAIL / [ ] N/A — Widget updates after note edits
- [ ] PASS / [ ] FAIL / [ ] N/A — Widget tap opens app/note correctly

### Notes
- 

---

## 11. Visual polish and usability

- [ ] PASS / [ ] FAIL — No obviously broken layout on phone screen
- [ ] PASS / [ ] FAIL — Dark/light theme behavior looks acceptable
- [ ] PASS / [ ] FAIL — Buttons/icons feel responsive
- [ ] PASS / [ ] FAIL — No obvious clipping, overlap, or unreadable text

### Notes
- 

---

## 12. Final decision

- [ ] Ready for personal daily use
- [ ] Ready for portfolio demo/screenshots
- [ ] Needs more fixes before regular use

### Top issues found
1. 
2. 
3. 

### Overall notes
- 
