# Changelog

All notable changes to Notelikeus are documented here.

## [Unreleased]

### Fixed
- **Attachment delete could leave a note pointing at a file that no longer exists.** The Worker deleted the R2 object first and then asked the database to record the deletion without checking the answer, so a refused, failing, or timed-out record still reported success. Deletion is now claimed in the database before any byte is touched, every phase is idempotent, and a delete abandoned half-way is finished by the cron sweep instead of being stranded.
- **Restoring a note brought back attachments the user had already deleted**, whose files were gone — they now stay deleted, while attachments removed only as a side effect of deleting the note still come back.
- **An unreadable answer from the database is no longer treated as a refusal.** A Worker deployed ahead of its migrations, or a malformed response, now reports a server error instead of telling the caller they are not allowed.
- **Re-uploading a deleted attachment could bring it back with no file behind it.** Deleting an attachment now retires its id for good — including when the delete lands while that same id is mid-upload — and an upload aimed at a retired id is refused instead of resurrecting it. Replacement images already use a new id, so this changes nothing about adding or replacing a picture.
- **A cloud read that lost notes on the way could delete them everywhere.** Reconciliation reads a note missing from the cloud as deleted-elsewhere, so a snapshot truncated in transit removed the local copies, wrote tombstones, and pushed those deletions to every other device. Full snapshots now carry the server's own note count and are refused if fewer notes arrive than the server says exist. Nothing is deleted, tombstoned, or updated on a short read; the next successful sync reconciles normally.
- **Deleting your last note on another device could wedge syncing for good.** Sync refused any empty cloud while it remembered notes being there, and the memory was only updated by a sync that succeeded — so once it happened, every later sync failed the same way. An empty cloud whose missing notes are all explained by deletions is now recognised as genuinely empty, while an unexplained empty answer is still refused.
- **A network problem could take the screen away from notes stored on the device.** Failures to reach the cloud shared a channel with failures to read local storage, and that channel drives the whole screen. Notes in local storage now stay visible and editable while syncing is failing, an unreachable cloud shows a banner with a Retry above the notes rather than replacing them with an error page, and a genuinely empty library still reads as empty instead of broken.
- **Errors could appear as `[object Object]`.** Failures thrown as plain objects, which is what the database client does, are now turned into readable text wherever they reach the screen, with a plain fallback when there is nothing readable in them.
- **The notes screen could show a failure and an empty-library message at once.** It now shows exactly one thing at a time: loading, then a blocking error only when local storage itself could not be read, then the empty state, then the notes.
- **Retrying a failed load no longer reloads the page.** It re-reads local storage and restarts syncing in place, so nothing already on screen is thrown away, and a successful retry clears the error.
- **Buttons in dialogs and the note editor's bottom bar could be impossible to tap on a phone.** The page behind an overlay could still scroll — locking it missed the document element, and confirmation dialogs never locked it at all — and on a mobile browser that scrolling moves the page out from under a fixed panel, so a tap landed where the button had been rather than where it was. The editor's bottom bar had a second version of the same fault, drifting with the scroll position because its offset was measured against it.
- **Full screen editing now uses the whole window.** It previously centred a fixed-width panel with the page background showing down both sides, which at ordinary laptop widths looked narrower than the docked layout it replaced. The writing column stays a readable width.

### Changed
- **Backend:** Cloud sync and authentication now use Supabase (Auth, PostgreSQL, RPC, Realtime) instead of Firebase Auth and Firestore. Web hosting is Cloudflare Pages. Existing Firebase cloud accounts and Firestore data are **not migrated** — sign in again as a new Supabase user.
- **Attachments:** Binary objects remain on Cloudflare R2 behind a Worker that verifies the Supabase session.
- **Uploads are authorized before the file is read.** An upload aimed at a note the caller does not own is refused without the Worker buffering it first; the real size, both quotas, and the file's destination are still checked authoritatively against the bytes that actually arrived.
- **Malformed requests cost nothing upstream.** The Worker settles the route, method, and upload headers locally before it verifies a session.
- **Rate limiting is wired into the deploy.** The Worker throttles by client IP and by authenticated user through a Cloudflare rate limiter (`ATTACHMENT_RATE_LIMITER`), and the owner-operated deploy now binds it — previously the code shipped but no deployment turned it on, so it did nothing. The allowance is configurable per deploy and defaults to 120 requests a minute per key; setting it to zero leaves throttling off. Throttling stays independent of authorization in both directions: it only ever answers 429, it fails open if the limiter cannot answer, and ownership checks run regardless. WAF and per-endpoint rules remain zone configuration, outside this repository.
- **The hosted orphan sweep stays opt-in.** Its cron does nothing unless a service-role key is configured, because such a key bypasses row-level security; the deploy now supplies one only when the repository has it, and says which way it went.

## [1.0.3] — 2026-08-30

### Fixed
- **Rich text formatting toolbar:** Fixed dead Bold/Italic actions on mobile web and Android. On web, toolbar actions run on `pointerdown` to prevent blur-to-markdown-preview flip; on Android, toolbar buttons reject focus so body selection remains intact.
- **Note card headings (D15):** Notes with no title and only body content no longer render the placeholder text "Untitled" as an `<h2>` card heading.
- **Accessibility & Focus management:** Added full keyboard focus trapping, `aria-modal`, `role="dialog"`, and `Escape` dismiss handling to mobile editor overlays, full-screen labels management, and auth dialogs.
- **Nested Escape collisions:** Fixed delete confirmation dialog inside editor options sheet dismissing the entire parent sheet upon pressing `Escape`.
- **Keyboard focus rings:** Standardized visible focus rings across drawer nav buttons, editor chrome controls, filter chips, and settings rows.
- **Contrast & Theme tokens:** Fixed invisible error state retry buttons on light theme by transitioning hardcoded colors to semantic theme tokens.
- **Search & Sort harmonization (D14):** Disabled the sort chip during active search queries to reflect relevance ranking.
- **PWA Manifest:** Corrected declared icon dimensions for high-resolution PNG assets and scalable SVG favicon.

### Changed
- Project documentation ([`docs/FINDINGS.md`](docs/FINDINGS.md), [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md)) updated with the audit findings F1-F45.

## [1.0.2] — 2026-08-29

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
- Web search uses the same token-prefix, diacritic folding, relevance ranking and typo
  fallback as Android and Windows, so the same query over the same notes no longer disagrees
  across clients

### Security
- Native markdown links reject `javascript:` / `data:` / other non-http(s)/mailto schemes,
  matching the web client's `toSafeHref`
- Hosting `Strict-Transport-Security` now includes `preload`, matching the live site. The repo
  CSP stays host-specific (`notelikeus.firebaseapp.com`) rather than the live wildcard

### Changed
- Firestore rules type-check `timestamp` and `reminderTimestamp` as `int` rather than the
  looser `number` — both are epoch millis written as integers by every client and read back
  as `Long`, completing the tightening that `localId` received in 1.0.1
- README documents that the web client can continue without an account
- Android CI minifies a release APK on every PR so an R8 keep-rule or resource-shrink
  break cannot wait for a `v*` tag. The AAB and GitHub Release artifacts stay tag-only
- Android notes view matches the web: List, Grid and Compact only. The filters sheet's
  Where row is Notes, Archive and Trash — `in:all` still works from search
- Historical notes live in `docs/` (decisions, findings, worklog). `:androidApp` Kotlin
  sources sit under `src/main/kotlin` rather than `java`

### Added
- A populated Room upgrade from version 1 through current: notes, labels and checklists
  survive the full chain, `searchText` arrives as null, and attachments / `cloudId` do not.
  The 9→10 test now calls the migration twice rather than issuing the ALTER itself

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
- Image attachments removed (restored on Cloudflare R2 in a later release)

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
