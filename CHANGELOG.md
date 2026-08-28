# Changelog

All notable changes to Notelikeus are documented here.

## [Unreleased]

### Fixed
- Native sign-out now isolates the device: local notes, tombstones, known cloud ids and the
  pending upload queue are cleared so the next Google account cannot inherit them or apply
  leftover deletes to colliding ids. The sync engine refuses to run while leftover state belongs
  to a different uid
- Web live saves compare the existing cloud document before writing. An equal server stamp
  uses the same client-timestamp tie-break as Kotlin, so a stale flush cannot overwrite a newer
  revision on another device
- Native JSON import runs in one Room transaction and only schedules cloud uploads after that
  transaction commits, so a mid-loop failure cannot leave a partial library
- Web backup import uploads before updating the in-memory store, and drops realtime snapshots
  for the duration, so a stale listener cannot wipe the import
- Web search uses the same token-prefix and diacritic folding as Android and Windows, so the
  same query over the same notes no longer disagrees across clients

### Security
- Native markdown links reject `javascript:` / `data:` / other non-http(s)/mailto schemes,
  matching the web client's `toSafeHref`
- Hosting `Strict-Transport-Security` now includes `preload`, matching the live site. The repo
  CSP stays host-specific (`notelikeus.firebaseapp.com`) rather than the live wildcard

### Changed
- Firestore rules type-check `timestamp` and `reminderTimestamp` as `int` rather than the
  looser `number` — both are epoch millis written as integers by every client and read back
  as `Long`, completing the tightening that `localId` received in 1.0.1
- `@types/node` is a direct web `devDependency` so typecheck no longer relies on a transitive
  copy arriving by coincidence (F24)
- README documents that the web client can continue without an account

## [1.0.1] — 2026-08-17

### Changed
- Android opens the encrypted database on a background thread at startup. The key-manager
  decrypt (Keystore + file IO) and the first-run `sqlcipher_export` re-encryption no longer
  block the first composition — the window stays on the system splash until the database is
  ready
- Release builds are now minified with R8 (conservative keep rules; verified on-device through
  sign-in, editing, persistence and a cold restart of the encrypted database)
- Web styling migrated to Tailwind CSS v4 (CSS-first `@theme` config, `tw-animate-css`)
  with no visual changes intended; custom utilities verified in the built CSS
- `npm run deploy` (web) now gates on lint, unit tests and a successful build before touching
  Firebase
- Web CI runs oxlint (`correctness` as errors) on every PR — the repo previously had no JS/TS
  linter at all
- Firestore rules type-check `localId` as `int` rather than the looser `number`, matching every
  other numeric field — it is the note's primary key and is read back as a `Long`
- Desktop logs previously-swallowed failures (session persistence, token refresh, DPAPI
  migration) through a JUL-based `AppLog` so field issues stay diagnosable

### Refactored
- `MainViewModel` (717 lines) split into `CloudSyncController`, `NoteActionsController` and a
  pure `filterAndSortNotes`; public API unchanged
- `MainScreen` (1171 lines) split into `MainDrawerContent`, `MainDialogs` and `MainScaffold`
- `DatabaseKeyManager.kt` (four top-level types) split into one file per class

### Added
- Tests for previously-uncovered surfaces: `FirebaseSessionManager` (account mapping, debug-only
  email gate, error diagnosis), `ReminderScheduler` (exact alarm scheduling, past-timestamp
  guard, cancellation — Robolectric) and `DesktopTokenStore` (JWT claim decoding, session
  lifecycle)

### Fixed
- Android and desktop now resolve a sync conflict the way the web client already did when only one
  side carries a server-confirmed `serverUpdatedAt`: the confirmed side wins outright. A skewed
  clock or a hand-edited backup timestamp can no longer overwrite a revision the server has stamped
- A cloud download no longer rewrites every note — and deletes and re-inserts its labels and
  checklist items — when nothing has changed, and now reports the number of notes that actually
  moved rather than the size of the whole library

### Removed
- Unused deprecated `play-services-auth` dependency (Credential Manager is the sign-in path)

### Security
- Hosting adds `Strict-Transport-Security` for the first-request HTTPS upgrade
- Backup import rejects deeply nested JSON up front instead of crashing on `StackOverflowError`
- Dev-tooling npm audit is back to zero known vulnerabilities (pinned `uuid` and
  `@opentelemetry/core` overrides for `firebase-tools`)

## [1.0.0] — 2026-07-11

### Added
- Stable `cloudId` UUIDs for cross-device Firestore sync (Android + PWA)
- Android realtime Firestore listener for live multi-device updates
- Playwright smoke e2e tests and Vitest unit tests for the PWA
- Firestore security rules validation (field limits, locked-note plaintext guard)
- Firebase rules CI workflow with emulator-backed tests

### Changed
- Backup export redacts locked notes on Android and PWA; import sanitizes locked entries
- Corrupt SQLCipher databases are quarantined instead of silently deleted
- Google sign-in merges cloud data before uploading local notes
- Android cold start triggers cloud merge when already signed in
- Remote note deletions propagate using persisted known cloud IDs

### Security
- Locked notes no longer leak via reminders, backups, or realtime sync overwrites
- Firestore rules reject locked notes with non-empty title/content in cloud
- External `notelikeus://editor/{id}` links cannot skip per-note lock

### Previous beta work included in 1.0.0
- **Web PWA** — React app at `web/` with offline notes, Google sign-in, Firestore sync, backup import/export, responsive layout (mobile/tablet/desktop), and dedicated sign-in/sign-up screen
- PWA: swipe actions, undo toasts, trash lifecycle, offline/install banners, search highlights, service-worker reminders
- PWA: multi-select with bulk pin/unpin, archive, trash, restore, and delete
- PWA: manual drag reorder (list view), real-time Firestore sync when auto-sync is on
- PWA & Android: recent search history, date-grouped note sections (Today, Yesterday, etc.)
- Android: smart editor text processing (auto bullets, list continuation)
- PWA: smart editor text processing (auto bullets, `[ ]` → checklist)
- Optional Firebase cloud sync (Firestore) with Google Sign-In
- Auto-sync setting for signed-in users
- Cross-device restore with timestamp-based merge

### Changed
- PWA initial bundle split into lazy-loaded editor/auth screens and separate Firebase/React chunks for faster first load
- Locked notes are excluded from cloud sync; locking removes a note from the cloud
- Deep links no longer accept a lock-bypass flag; locked notes require biometric unlock in the editor
- Manual sync and restore require Google sign-in (no anonymous cloud uploads)
- Image attachments removed (archived under `archive/attachments-feature/`)

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
