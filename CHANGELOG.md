# Changelog

All notable changes to Notelikeus are documented here.

## [Unreleased]

### Added
- **Web PWA** — React app at `web/` with offline notes, Google sign-in, Firestore sync, backup import/export, responsive layout (mobile/tablet/desktop), and dedicated sign-in/sign-up screen
- Optional Firebase cloud sync (Firestore) with Google Sign-In
- Auto-sync setting for signed-in users
- Cross-device restore with timestamp-based merge

### Changed
- Locked notes are excluded from cloud sync; locking removes a note from the cloud
- Deep links no longer accept a lock-bypass flag; locked notes require biometric unlock in the editor
- Manual sync and restore require Google sign-in (no anonymous cloud uploads)
- Image attachments removed (archived under `archive/attachments-feature/`)

### Security
- External `notelikeus://editor/{id}` links cannot skip per-note lock

## [1.0] — 2026-07-08

### Features
- Offline notes with titles, colors, labels, checklists, and rich text
- Rich text: bold, italic, links, bullet lists (WYSIWYG markdown editing)
- Archive, trash, pin, search, color/label filters, list/grid views, drag-to-reorder
- Per-note biometric lock and optional app-wide lock
- Reminders with notifications (survive reboot; cancelled on trash/archive)
- JSON backup export and import
- Home screen widget
- Undo for archive, trash, and delete actions
- True dark mode (OLED)

### Security
- SQLCipher-encrypted Room database
- Android Auto Backup excludes encrypted DB and key material

### Privacy
- No analytics or advertising SDKs
- In-app privacy policy
