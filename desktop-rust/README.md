# Rust Windows client — experiment

**Status: experimental, not shipped, not a replacement yet.** The Kotlin Compose Desktop app (`composeApp/`,
MSI from `desktop.yml`) stays the Windows client. Nothing here opens, migrates or writes its data.

## Decision (2026-11-21)

| Option | Reuse | Cost to parity | Main risk |
|---|---|---|---|
| **Keep Kotlin Compose Desktop** | Shares `commonMain` with Android: the same `NoteSyncEngine`, Room schema, UI | None (shipped, MSI upgrades verified on 2026-11-19) | JVM footprint, the MSIX/AF_UNIX quirk on dev machines only |
| **Tauri 2 + existing React UI + Rust core** | The web UI (React, TipTap) almost as is; Rust for storage, sync, DPAPI, tray | Medium: swap the web's IndexedDB/Supabase data sources for Tauri commands | A **third** implementation of the sync rules to keep in step |
| Slint (native Rust UI) | Nothing from either UI | High: every screen rewritten | All of the above plus a UI rewrite |
| C | Nothing | Very high | No memory safety around encrypted note data. Educational only |

**Recommendation:** keep Kotlin as the shipped Windows client. Continue the Rust experiment along the Tauri 2 path
(React UI reused, Rust core underneath), and replace Kotlin only after the parity gates below pass.

The evidence comes from this repository. The data-loss bug found in this audit (AUD-I01, see
`docs/audits/2026-11-21/REPORT.md`) was the web and Kotlin implementations disagreeing on one sync rule. Android
and Windows share an engine, so they can't drift this way. A Rust client adds a third engine. That's why this crate
is tested against the same `contracts/` fixtures as the other two, and why the rule that bit the web
(tombstone origin) is an explicit type here (`TombstoneOrigin`).

Not measured: startup time and memory of the Compose Desktop app against a Tauri build. Any claim of a
performance win waits for that measurement.

## Native app (2026-11-21 onwards)

The owner's direction since 2026-11-21: a **fully Rust, native** Windows app, separate in logic and data from the
Kotlin app, built step by step. It's a Slint UI (`crates/nlk-native`, binary `notelikeus.exe`) over the shared app
logic (`crates/notelikeus-app`). The Tauri app stays as an earlier experiment.

- **Look:** laid out like the web app: a sidebar, a search bar with a `Ctrl K` hint and a round + button, a masonry
  grid of rounded cards, and the editor as a floating sheet. It uses the web app's icons and the design tokens in
  `docs/design/tokens.json`, follows the Windows theme by default, and is set in Inter (OFL, `licenses/`).
- **Data:** `%APPDATA%\NotelikeusRust` (override: `NLK_RUST_DATA_DIR`), sealed with the DPAPI-wrapped key like the
  rest of the Rust client. It never opens the Kotlin app's `~/.notelikeus` or the Tauri app's folder.
- **Licence:** Slint is used under its Royalty-free licence 2.0. The About screen displays the required "Made with
  Slint" attribution widget.
- **Tests:** `view.rs` unit tests (previews, card times, search, masonry), and `tests/ui.rs`, which drives the real
  window headlessly through its accessible labels (create, edit, reopen after a restart, empty drafts, pin, search,
  delete and Undo, add and view pictures, remove a picture, and read pictures from Trash). Debug builds embed
  element debug info for that.

**Roadmap.** One step at a time, each reviewed before the next:

| Step | Scope | State |
|---|---|---|
| 1 | Foundation: web-app layout and theme, notes create / edit (autosave) / pin / delete with Undo / search | **Done** |
| 2 | Note features: colours (the shared palette, `contracts/design/note-colors.json`), archive, trash with Restore / Delete forever / Empty trash, labels (note options and Edit labels), checklists | **Done** |
| 3 | Search operators (`contracts/query/parser-vectors.json`), word-prefix matching with relevance and near matches, the Filters sheet, saved filters, smart views | **Done** |
| 4 | Sign-in with an emailed code (the security check opens in the browser and returns over 127.0.0.1, as the Kotlin desktop app does), sync on a worker thread (after a change, every 2 minutes, on request), sign-out, conflict review | **Done** |
| 5 | Images: staging, upload through the attachments Worker, thumbnails | **Done** |
| 6 | Reminders: Windows notifications, tray | **Done** |
| 7 | Backups: v3 JSON and v4 `.nlkbak` against `contracts/backup` | **Done** |
| 8 | Settings: themes, account, diagnostics, delete account, About (Slint attribution) | **Done** |
| 9 | Packaging and polish: installer, accessibility review, performance, Markdown formatting, cloud wipe recovery, single-instance guard | **Done** (per-user installer with `.ico` and `VERSIONINFO`, UIA `FocusScope` inheritance fix, 7 canonical themes, `PINNED`/`OTHERS` sections, SortOrder + density modes, Markdown toolbar + `SafeHref`, cloud wipe actions, and single-instance activation lock; spoken screen-reader review and Authenticode signing remain) |

```
cargo run -p nlk-native                      # the app
cargo test -p nlk-native                     # view unit tests and the headless UI test
```

## What exists

```
desktop-rust/
  crates/notelikeus-core/   model, conflict rules, crypto, store, sync engine, Supabase transport (feature "http")
  crates/notelikeus-app/    the app logic both front ends share: accounts, notes, sign-in, sync (service.rs)
  crates/nlk-native/        notelikeus.exe: the native Slint app (see "Native app")
  crates/nlk-cli/           nlk-rust: guest-mode notes in the encrypted store (new / list / edit / delete / where)
  crates/nlk-tauri/         notelikeus-rust.exe: Tauri 2 window over the core, guest mode and signed-in sync
    ui/                     the interface: plain HTML, CSS and JS, no bundler
    e2e/drive.mjs           drives the real app over WebView2's DevTools protocol (guest mode)
    e2e/drive-account.mjs   the same, signing in and syncing against a local Supabase stack
```

- **Sign-in and sync** (`--features http`, which the app uses):
  - **Sign-in:** an emailed one-time code (`auth.rs`), with the same calls as the Kotlin client: `/auth/v1/otp` with
    `create_user`, then `/auth/v1/verify`.
  - **The session** is sealed beside the notes under the same DPAPI-wrapped key (`SessionVault`, bound to its
    purpose by associated data). It's refreshed before it expires, and again once if a request is refused.
  - **The project** comes from `NLK_SUPABASE_URL` / `NLK_SUPABASE_KEY`. A build without them has no sync at all,
    so the experiment cannot reach a project by accident.
  - **Accounts:** each account has its own sealed store. Notes written as a guest are not merged into an account,
    and an account's notes stay sealed on disk after sign-out.
  - **A device's first sync** downloads first and joins the account's current wipe generation.
  - **In the window:** an account panel (sign in, Sync now, sign out, and status: up to date, N waiting, or the
    error). It syncs on start, 3 s after typing stops, every minute and on focus. The editor is read-only during
    a sync, and the open note reloads if the sync changed it, so text typed over an old copy cannot be saved over
    another device's words.
  - **The security check** the hosted project requires (Turnstile, D30), via `captcha.rs`:
    - **When:** with `NLK_CAPTCHA_PAGE_URL` set (the web app's `captcha.html`, the page the Kotlin release builds
      use), "Send code" first opens that page in its own window with `?return=app&state=<random>`.
    - **Handing back:** the page's final `notelikeus-captcha://token?…` navigation is blocked and read. The token
      counts only with the matching state, as in the Android client.
    - **Containment:** the window gets no app permissions, may only navigate within the page's own site, cancels
      when closed and gives up after five minutes.
    - **Without the setting** nothing changes, which is what a local stack (no captcha) expects.

- **Native settings & UI features:**
  - **Appearance:** 7 canonical theme palettes (`contracts/design/theme-colors.json`: System/Light/Dark × Neutral/Blue/Green × AMOLED black), view density (Grid / List / Compact), sort order (Manual / Newest / Oldest), and sidebar collapse state are persisted atomically per install in `settings.json`.
  - **Notes layout & rich text:** Separate `PINNED` and `OTHERS` sections in the Notes view; Markdown formatting toolbar (`**bold**`, `_italic_`, `• ` bullet lists, `[label](url)` links validated with `SafeHref` rules matching Kotlin and Web) plus `Ctrl+B` / `Ctrl+I` shortcuts; card previews strip inline Markdown markers (`strip_markdown`).
  - **Cloud wipe & account actions:** Settings includes sign-in, `Sync now`, sign-out, `Delete cloud notes` (`delete_all_user_cloud_data` + `finalize_cloud_wipe`), and a `Re-upload local notes` recovery banner when another device wiped the cloud (`uploads_blocked_after_wipe`). Account deletion requires typing `DELETE` and a recent sign-in; after the server accepts deletion, the app clears the account's local sealed data and returns to the separate guest notes.
  - **Diagnostics & About:** Exportable and copyable JSON diagnostics report (`schemaVersion`, note/attachment/pending/tombstone counts, `wipeGeneration`, `uploadsBlockedAfterWipe`, and error category) verified by `assert_no_sensitive_values` to never leak note text, emails, JWTs, or raw account UUIDs. About displays the Slint attribution widget required by its licence.
  - **Single-instance & tray reminders:** Advisory file lock on `instance.lock` with `activate.request` hand-off restores and focuses the existing window when a second instance is launched. A 60-second heartbeat checks due reminders while running, and closing the window hides to the system tray only when pending future reminders exist (exiting cleanly otherwise).
- **Native Windows package:** from `desktop-rust`, run
  `powershell.exe -NoProfile -ExecutionPolicy Bypass -File packaging/native/package.ps1`. It builds the MSVC release
  executable (`--locked`) with embedded Win32 `.ico` and `VERSIONINFO` resources (`build.rs`), and writes
  `target/native-packages/NotelikeusRust-<version>-<target>.zip` plus its `.sha256` checksum. The archive has a per-user
  installer (`install.ps1`), Start menu shortcut, Installed apps entry, dependency notices, and an uninstaller (`uninstall.ps1`)
  that checks for running `notelikeus` processes and preserves the separate `%APPDATA%\NotelikeusRust` notes and settings folder.
  The archive is unsigned and remains for local evaluation until a trusted release signing identity is available.

- **Native runtime review (2026-11-22, one run per app):** the Rust release opened its first window in 181 ms and
  used 78.5 MiB working set / 114.8 MiB private memory after 30 seconds idle. The installed Kotlin desktop app took
  4,190 ms to its first window and used 311.7 MiB / 438.6 MiB at the same idle point (main process plus launcher).
  Both ran with isolated empty data. These are single-run measurements with uncontrolled OS file caches.
- **Keyboard and accessibility review:** interactive controls (`IconButton`, `FilterChip`, `PillButton`, `ThemeOption`,
  `CheckBox`, `NavItem`, `Card`) inherit `FocusScope` directly on their root element so Windows UI Automation (UIA) tracks
  keyboard focus accurately on the accessible element itself rather than an inner child scope. Modal dialogs and sheets restore
  focus to the main window key scope on close, gate background shortcuts (`Ctrl+N`, `Ctrl+K`, `Ctrl+F`), and dismiss in strict
  top-down Z-order on `Escape`. Spoken screen-reader announcements were not independently checked.

- **Tauri 2 app** (guest mode): a notes list with search, an editor that autosaves, pin, delete with Undo,
  keyboard shortcuts (Ctrl+N new, Ctrl+F search, Ctrl+S save now), light and dark themes from
  `docs/design/tokens.json`, and a one-pane layout below 720 px. Every save goes through the core's encrypted store
  under `%APPDATA%\NotelikeusRustExperimental` (override: `NLK_RUST_DATA_DIR`).
  - Saves are queued in order and each one captures its note when requested. Switching notes never sends a pending
    save to the wrong note, and keystrokes are never dropped while a save is running.
  - Closing the window waits for the last autosave (`onCloseRequested`).
  - A store that cannot be opened stops the app with a dialog that says why, never an empty library.
  - Security: a strict CSP, note text written only as text (never HTML), and capabilities limited to `core:default`
    plus `core:window:allow-destroy`. No filesystem, shell or HTTP plugins.
  - Debug builds only: `NLK_RUST_DEVTOOLS_PORT` opens WebView2's DevTools protocol on loopback for the
    end-to-end driver. Release builds ignore it (checked).

- **Model and wire format**: `CloudNoteRow`, `CloudChange`, `ApplyNoteChangeArgs`, checked against
  `contracts/cloud/*.json`.
- **Conflict rules**: `decide`, `cloud_wins_by_stamp`, `is_locally_edited`, `authored_content_digest` (cyrb53, same
  vectors as Kotlin and web), `owner_tag`, conflicted copies. Checked against `contracts/sync/inbound-resolution.json`.
- **Storage**: one AES-256-GCM sealed file per account (`accounts/<owner>/state.nlkr`). Associated data binds each
  file to its owner. The data key is wrapped by current-user DPAPI with fixed entropy. Writes go temp → fsync → keep
  `.prev` → rename, and a damaged current file falls back to `.prev`, never to an empty library. A missing key next
  to existing data is refused rather than replaced. The Kotlin client's `~/.notelikeus` is refused.
- **Sync**: an offline queue that keeps each edit's base revision, a revision-checked push, a paged pull, a "kept
  both" outcome on every conflict, a delete that loses to a newer edit elsewhere, an edit of a note deleted
  elsewhere kept as a copy whether the push or the pull learns of the delete first, a push that never moves the
  pull cursor (F-04), uploads blocked by a wipe elsewhere (NL002), idempotent retries after a lost reply.
- **Transport** (`--features http`): `apply_note_change`, `apply_note_delete`, `pull_changes` over HTTPS with
  SChannel. Errors are classified by SQLSTATE code, never by message text. Plain HTTP is accepted only on loopback.

## Verified (2026-11-23, this machine)

`cargo test --workspace --all-features --locked` passed 116 test entry points across all workspace crates:
- `nlk-native`: 16 library unit/contract tests (`backup`, `images`, `instance`, `view`, `controller::account`), 1 headless Slint UI test suite (`tests/ui.rs`, exercising 15 end-to-end scenarios), and 1 live test entry point (`tests/live.rs`).
- `notelikeus-app`: 28 library unit/contract tests (`clock`, `service`, `backup`, `diagnostics`, `settings`).
- `notelikeus-core`: 7 library unit tests, 11 contract tests (`tests/contracts.rs`, including theme palettes and SQL migration RPC signatures), 11 HTTP reply & URL/key validation tests (`tests/http_replies.rs`), 10 query parser tests (`tests/query.rs`), 12 DPAPI & encrypted store tests (`tests/store.rs`), 14 sync engine scenario tests (`tests/sync_scenarios.rs`), and 5 live Supabase test entry points (`tests/live_supabase.rs`).

`cargo clippy --workspace --all-features --all-targets --locked -- -D warnings` and `cargo fmt --all -- --check` pass with 0 warnings. The release binary (`target/release/notelikeus.exe`, 22.09 MiB, embedded Win32 `FileVersion = 0.1.0` and `ProductName = Notelikeus`) was smoke-tested with an isolated `NLK_RUST_DATA_DIR`, verifying DPAPI key creation, `instance.lock` acquisition, and second-instance exit-code-0 activation hand-off.

**Tauri app (2026-11-21, MSVC toolchain):** 6 service unit tests, plus `node crates/nlk-tauri/e2e/drive.mjs` with 18
checks against the real window, passing four runs in a row. The checks cover typing and saving, newest first,
search and its empty state, pin order, delete and Undo, persistence across a restart, an edit typed just before
closing surviving the close, markup staying text, no plaintext on disk, and the narrow layout. The release build is
an 8.1 MB exe. Idle memory was 4.6 MB for the app process plus about 147 MB across WebView2's six processes (one
measurement; the Kotlin app was not measured alongside it).

**Against a local Supabase stack (2026-11-21):**
- `tests/live_supabase.rs` passes 5 tests:
  - two devices on one account converge, keep both versions on conflict, and keep an edit to a note deleted
    elsewhere as a copy
  - repeating a sync changes nothing
  - what the Rust client writes appears in `fetch_full_snapshot`, the read the web and Kotlin clients start from
  - a second account sees nothing of the first, and writing under the same note id cannot change it
  - a bad token is classified as unauthorised, and a refreshed token works
  - a real emailed-code sign-in works, with the code read from the local mail catcher
- The app's service passes a live test: two installs sync one account, guest notes stay apart, and the sign-in
  survives a restart.
- `e2e/drive-account.mjs` passes 15 checks through the real window:
  - the security check: the window opens first, then closes after handing back the token
  - sign-in by emailed code
  - an empty account (no guest notes merged in)
  - "1 change waiting to sync" after a save
  - a note synced and found in the database for that account only
  - still signed in after a restart
  - the guest notes back after sign-out
- The security check was exercised with the web app's own `captcha.html`, built locally with Cloudflare's published
  always-pass **test** site key (`1x00000000000000000000AA`). That covers the real Turnstile widget and the whole
  hand-back. The local stack does not verify the token, and the production page and project were not used.

**Native image attachments (2026-11-22):**
- `service::tests::signed_in_images_upload_between_installs_and_delete_through_local_worker` passes against an
  isolated local Supabase stack and Wrangler Worker: upload, commit, list and download on a second install, then
  remove the remote object.
- The headless window test covers adding pictures to a draft, thumbnail display on the card, opening and navigating
  the viewer, removal, persistence across a restart, and read-only viewing from Trash.

Without local-stack variables, the live integration tests return early; CI runs the remaining Rust tests.

## Remaining items before replacing Kotlin as the default Windows release

1. **Hosted sign-in verification:** the Turnstile security check is implemented and tested end to end with a local test key,
   and should be manually verified once against `https://notelikeus.pages.dev/captcha` and the hosted project using a
   throwaway account.
2. **Realtime wake-ups & editor inline WYSIWYG:** `nlk-native` syncs on start, focus, 3 seconds after edits, every 2 minutes,
   and on manual `Sync now` (without a WebSocket Realtime subscription), and edits Markdown syntax (`**bold**`, `_italic_`,
   `• `, `[label](url)`) in `TextInput` while stripping markers in card previews.
3. **Authenticode signing & spoken screen-reader pass:** the per-user package is currently unsigned, and an interactive listening
   pass with NVDA / Windows Narrator remains recommended alongside the automated Slint accessibility tests.
4. **Migration path:** importing a Kotlin backup (`.nlkbak` or v3 `.json`) is the supported way to bring data across;
   direct reading of `~/.notelikeus` Room/SQLCipher files is deliberately refused so both clients can coexist safely.

## Native reminders (2026-11-22)

- Active notes can be scheduled with the one-hour, tomorrow-at-9, and next-week presets, or a local date and time.
  Reminder timestamps use the same synced note field as the other clients.
- Closing the main window hides it in the system tray, so reminders keep running. Choose **Exit** from the tray menu
  to stop the process; reminders are delivered only while the app is running.
- Windows reminder balloons use a generic title and message, so note text is not shown in system notifications.

## Native backups (2026-11-22)

- **Export JSON backup** writes all local notes, including archived and trashed notes, in the v3 format. **Export backup with pictures** writes a v4 `.nlkbak` ZIP bundle whose manifest embeds the exact v3 JSON bytes.
- Bundle export includes only picture bytes already on this device. Cloud-only, unreadable, invalid-id, or oversized images are skipped and counted; it never downloads from the Worker.
- **Import backup** accepts v3 JSON and v4 `.nlkbak`. It asks before importing because every import adds copies. Note and attachment IDs are re-minted, positions are assigned after active notes, labels match case-insensitively, and reminders that have passed are cleared.
- Bundle imports reject duplicate ZIP entry names, derive media paths from validated attachment IDs, and verify each image's declared size and SHA-256. A missing or invalid picture is skipped while its note is kept.
- The shared limits are in `contracts/backup/import-limits.json`; JSON exports are capped at 25 MB, bundle files at 256 MB, and each picture at 10 MB.

## Build

### Efficient local workflow

Run Cargo commands from this `desktop-rust` workspace root so `.cargo/config.toml` applies its three-job limit.

- For routine edits, run `cargo fmt --check` and `cargo check -p <affected-crate>`. `cargo check` catches compile errors without linking the app or running tests.
- For changed behavior, run the narrowest relevant test, such as `cargo test -p notelikeus-core --lib <filter>`. For Slint/native UI changes, use a targeted native test or launch the app once after the related edits.
- Run `cargo test --workspace --all-features` for release gates or changes that cross crates/features; it is not the default edit loop. Build release artifacts when packaging or checking release-only behavior.
- Before a full compile, check C: free space with `Get-Volume -DriveLetter C`. Reuse `target` between sessions; routine cleanup forces dependency recompilation.
- Use the active stable MSVC toolchain for Windows work. GNU and MSVC builds share this workspace target; if you intentionally use GNU, set `CARGO_TARGET_DIR` in your user environment to a separate location rather than versioning a machine-specific path.
- Do not delete release binaries, dependency caches, or development artifacts without explicit authorization.

With the MSVC Build Tools and Windows SDK installed (the Tauri app needs them; WebView2 ships with Windows 11):

```
cargo test --workspace --all-features
cargo run -p nlk-tauri                      # the app, guest mode
cargo build -p nlk-tauri && node crates/nlk-tauri/e2e/drive.mjs [screenshot-dir]
```

Against a local Supabase stack (`npx supabase start` from the repository root; Docker must be running):

```
# from `npx supabase status -o env`: API_URL, and ANON_KEY or PUBLISHABLE_KEY
NLK_SUPABASE_URL=http://127.0.0.1:54326 NLK_SUPABASE_KEY=<key> cargo run -p nlk-tauri
NLK_TEST_SUPABASE_URL=http://127.0.0.1:54326 NLK_TEST_SUPABASE_KEY=<key> NLK_TEST_MAILPIT_URL=http://127.0.0.1:54324 \
  cargo test --workspace --all-features -- --test-threads=1
NLK_TEST_SUPABASE_URL=... NLK_TEST_SUPABASE_KEY=... NLK_TEST_MAILPIT_URL=... node crates/nlk-tauri/e2e/drive-account.mjs
```

For the native image round trip, run the attachments Worker locally with `SUPABASE_URL` and `SUPABASE_ANON_KEY`
from the local stack in `workers/attachments/.dev.vars`. Set `NLK_TEST_SUPABASE_URL` and
`NLK_TEST_SUPABASE_KEY` from `npx supabase status -o env`, plus
`NLK_TEST_ATTACHMENTS_URL=http://127.0.0.1:8787`, when running
`cargo test -p notelikeus-app --lib signed_in_images_upload_between_installs_and_delete_through_local_worker`.
This live test refuses non-local Supabase and Worker URLs.

To include the security check, serve the web app's challenge page with Turnstile's test key and point the driver at
it:

```
cd web && VITE_TURNSTILE_SITE_KEY=1x00000000000000000000AA npx vite build --mode e2e --outDir <dir> --emptyOutDir
npx vite preview --outDir <dir> --port 4174 --strictPort --host 127.0.0.1
NLK_TEST_CAPTCHA_PAGE_URL=http://127.0.0.1:4174/captcha.html NLK_TEST_SUPABASE_URL=... node crates/nlk-tauri/e2e/drive-account.mjs
```

The local stack sends at most two emails an hour. The emailed-code test and the account driver use one each, and
say so instead of failing when the limit is used up.

The core and CLI are pure Rust, so they build without a C compiler. On a machine without the Build Tools, use the
self-contained GNU toolchain (the Tauri crate will not build there):

```
rustup toolchain install stable-x86_64-pc-windows-gnu --profile minimal
cargo +stable-x86_64-pc-windows-gnu test -p notelikeus-core -p nlk-cli --all-features
```

CI (`.github/workflows/desktop-rust.yml`, not a required check) uses the default MSVC toolchain on `windows-latest`.
`Cargo.lock` pins `windows-sys` 0.59, `schannel` 0.1.27 and `native-tls` 0.2.14. Newer versions link through
`raw-dylib`, which needs `dlltool` on the GNU toolchain.
