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
```

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

## Not done: parity gates before replacing Kotlin

1. **UI.** The Tauri 2 shell isn't written. It needs the MSVC Build Tools and the Windows SDK, which this machine
   doesn't have, so it was not built here.
2. **Sign-in**: emailed one-time code (`/auth/v1/otp`, `/auth/v1/verify`), Turnstile, token refresh, session in DPAPI.
3. **First download**: `fetch_snapshot_page` with the unexplained-missing-ids guard. The engine only pulls with
   `pull_changes`, which is safe for an empty device but has no snapshot consistency check.
4. **Attachments** (R2 Worker), **realtime** wake-ups, **reminders and the tray**, **backups** (v3 JSON, v4
   `.nlkbak`), **restore and undo** (`restore_note`), **digest-verified deletes** of notes whose revision was never
   known, the **re-upload after a wipe** flow, **account deletion**, **search operators**.
5. **The HTTP transport has never talked to a server.** Only its reply parsing is tested. Run it against the local
   Supabase stack (`npm run supabase:start`) before any hosted project, and never against production for testing.
6. **Migration path**: importing a Kotlin backup (`.nlkbak`) is the only supported way to bring data across. Direct
   reading of the Room/SQLCipher database is deliberately not planned.

## Build

The core is pure Rust, so it builds without a C compiler. On a machine without the MSVC Build Tools, use the
self-contained GNU toolchain:

```
rustup toolchain install stable-x86_64-pc-windows-gnu --profile minimal
cargo +stable-x86_64-pc-windows-gnu test --workspace --all-features
```

CI (`.github/workflows/desktop-rust.yml`, not a required check) uses the default MSVC toolchain on `windows-latest`.
`Cargo.lock` pins `windows-sys` 0.59, `schannel` 0.1.27 and `native-tls` 0.2.14. Newer versions link through
`raw-dylib`, which needs `dlltool` on the GNU toolchain.
