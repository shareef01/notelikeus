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

## What exists

```
desktop-rust/
  crates/notelikeus-core/   model, conflict rules, crypto, store, sync engine, Supabase transport (feature "http")
  crates/nlk-cli/           nlk-rust: guest-mode notes in the encrypted store (new / list / edit / delete / where)
  crates/nlk-tauri/         notelikeus-rust.exe: Tauri 2 window over the core, guest mode and signed-in sync
    src/service.rs          what the window's commands do (unit-tested without Tauri)
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
  - **Hosted sign-in is not possible yet:** the hosted project requires a Turnstile token, and this client cannot
    show the challenge.

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

## Verified (2026-11-21, this machine)

`cargo test --workspace --all-features` passed 30 tests: 8 contract, 10 sync scenarios, 7 store (including the real
DPAPI round trip and tamper rejection), 5 reply parsing. `cargo clippy -- -D warnings` and `cargo fmt --check` are
clean. The CLI was smoke-tested end to end with a temporary data folder. No note text is in plaintext on disk, and
`--data-dir ~/.notelikeus` is refused.

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
- `e2e/drive-account.mjs` passes 12 checks through the real window: sign-in by emailed code, an empty account (no
  guest notes merged in), a note synced and found in the database for that account only, still signed in after a
  restart, and the guest notes back after sign-out.

Without the stack's variables these tests skip themselves, so CI runs 50 Rust tests either way.

## Not done: parity gates before replacing Kotlin

1. **UI parity.** No labels, colours, checklists, images, reminders, archive or trash, or settings. The interface
   is new and small. It does not yet reuse the web app's React components; that's the next step if the experiment
   continues.
2. **Hosted sign-in**: the Turnstile challenge the hosted project requires. Sign-in, token refresh and the sealed
   session work against a local stack.
3. **First download**: `fetch_snapshot_page` with the unexplained-missing-ids guard. The engine only pulls with
   `pull_changes`, which is safe for an empty device but has no snapshot consistency check.
4. **Attachments** (R2 Worker), **realtime** wake-ups, **reminders and the tray**, **backups** (v3 JSON, v4
   `.nlkbak`), **restore and undo** (`restore_note`), **digest-verified deletes** of notes whose revision was never
   known, the **re-upload after a wipe** flow, **account deletion**, **search operators**.
5. **A hosted project has never been used.** The transport and sign-in are verified against the local stack only.
   Never point the tests at production; they create accounts, and refuse any URL that is not this machine.
6. **Sync runs under the store's lock.** Edits made during a slow sync wait for it to finish. That's fine locally;
   on a slow network it should become a snapshot-and-merge.
7. **Migration path**: importing a Kotlin backup (`.nlkbak`) is the only supported way to bring data across. Direct
   reading of the Room/SQLCipher database is deliberately not planned.

## Build

With the MSVC Build Tools and Windows SDK installed (the Tauri app needs them; WebView2 ships with Windows 11):

```
cargo test --workspace --all-features
cargo run -p nlk-tauri                      # the app, guest mode
cargo build -p nlk-tauri && node crates/nlk-tauri/e2e/drive.mjs [screenshot-dir]
```

Against a local Supabase stack (`npx supabase start` from the repository root; Docker must be running):

```
# from `npx supabase status -o env`: API_URL, and ANON_KEY or PUBLISHABLE_KEY
NLK_SUPABASE_URL=http://127.0.0.1:54321 NLK_SUPABASE_KEY=<key> cargo run -p nlk-tauri
NLK_TEST_SUPABASE_URL=http://127.0.0.1:54321 NLK_TEST_SUPABASE_KEY=<key> NLK_TEST_MAILPIT_URL=http://127.0.0.1:54324 \
  cargo test --workspace --all-features -- --test-threads=1
NLK_TEST_SUPABASE_URL=... NLK_TEST_SUPABASE_KEY=... NLK_TEST_MAILPIT_URL=... node crates/nlk-tauri/e2e/drive-account.mjs
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
