# Notelikeus — Rust Windows Migration Audit, Repair, and Production Polish

**Audit & Implementation Date:** 2026-11-23  
**Repository:** `https://github.com/shareef01/notelikeus`  
**Branch & Baseline Commit:** `rust/native-ui` @ `42867335` ("Fix Rust CI and upgrade ZIP dependency")  
**Target Workspace:** `desktop-rust/` (`notelikeus-core`, `notelikeus-app`, `nlk-native`, `nlk-tauri`, `nlk-cli`)

---

## 1. Repository Architecture & Migration State

### 1.1 Multi-Client Repository Structure

| Component | Path | Technology Stack | Role |
|---|---|---|---|
| **Web Client** | `web/` | React 19, TypeScript, TipTap 3, Zustand 5, Tailwind CSS 4, Vite, Workbox | Shipped PWA with IndexedDB AES-GCM local encryption and Supabase sync |
| **Kotlin Multiplatform Shared & Desktop** | `composeApp/` | Kotlin 2.4 Multiplatform, Compose Multiplatform 1.12, Room 2.8 + SQLCipher, Koin | Shared domain/sync/UI for Android and the reference JVM Windows Desktop client |
| **Android App** | `androidApp/` | Kotlin, Jetpack Compose, Glance widget, Android Keystore + SQLCipher | Shipped Android APK |
| **Native Windows Rust Workspace** | `desktop-rust/` | Rust 2024, Slint 1.19 (`renderer-femtovg-wgpu`, `renderer-skia-d3d`, `renderer-software`, `accessibility`), `aes-gcm`, Windows DPAPI, `ureq` + Schannel (`native-tls`) | Native Windows desktop client (`notelikeus.exe`) |
| **Supabase Backend** | `supabase/` | PostgreSQL, RLS, PL/pgSQL revision-checked RPCs, Auth (email OTP + Turnstile), pgTAP | Cloud persistence, revision fencing, wipe generation fencing, per-account quotas |
| **Attachments Worker** | `workers/attachments/` | Cloudflare Workers, R2 (`owners/{owner}/notes/{note}/attachments/{id}`) | Token verification, presigned R2 uploads/downloads/deletes, provisional commit promotion |
| **Cross-Client Contracts** | `contracts/` | JSON test vectors & `.nlkbak` bundles (`cloud/`, `sync/`, `query/`, `backup/`, `design/`) | Canonical source of truth shared across Kotlin, TypeScript, and Rust test suites |

### 1.2 Rust Workspace (`desktop-rust/`) Crate Responsibilities

1. **`crates/notelikeus-core`**:
   - Domain models (`Note`, `ChecklistItem`, `LocalState`, `PendingChange`, `Tombstone`, `TombstoneOrigin`), cloud wire rows (`CloudNoteRow`, `CloudChange`, `ApplyNoteChangeArgs`), conflict resolution and `cyrb53` content digest (`conflict.rs`), note & theme color palettes (`palette.rs`), search query parser and fuzzy matcher (`query.rs`), AES-256-GCM + Windows DPAPI (`CryptProtectData`/`CryptUnprotectData`) persistence (`crypto.rs`, `store.rs`), attachment metadata/blob store (`attachments.rs`), sync engine (`sync.rs`), and behind feature `http`, Schannel HTTPS Supabase/Worker transport (`http.rs`), email OTP auth (`auth.rs`), and loopback Turnstile challenge listener (`captcha.rs`).
2. **`crates/notelikeus-app`**:
   - Application service layer (`NotesService` in `service.rs` plus `service/{backup,diagnostics,settings}.rs`, `clock.rs`) shared by the UI front ends: guest vs. signed-in account isolation, atomic store switching, note CRUD, checklists, label registry, search/filtering/sorting (`SortOrder`), image attachment staging and rollback, v3 JSON backup import/export, diagnostics report generation with sensitive-value assertions, cloud data wipe (`delete_cloud_data`), post-wipe re-upload (`reupload_after_wipe`), and account deletion (`delete_account`).
3. **`crates/nlk-native`** (`notelikeus.exe`):
   - The primary native Windows Slint application: `ui/{theme.slint, app.slint}`, `controller.rs` (`controller/{account,background,pictures}.rs`), `view.rs` (card previews, Markdown formatting & stripping, `SafeHref` validation, masonry layout), `backup.rs` (v4 `.nlkbak` ZIP bundle export/import with decompression-bomb defenses), `images.rs` (EXIF orientation normalization and thumbnail decoding), `settings.rs` (atomic `settings.json` persistence), `instance.rs` (single-instance advisory file lock and window activation hand-off), `win_notify.rs` (Win32 `Shell_NotifyIconW` balloon notifications), and `build.rs` (Win32 `.ico` and `VERSIONINFO` resource compilation).
4. **`crates/nlk-tauri`** (`notelikeus-rust.exe`):
   - Earlier Tauri 2 + WebView2 experimental client retained in workspace and CI.
5. **`crates/nlk-cli`** (`nlk-rust.exe`):
   - Command-line utility for inspecting and editing guest notes in an encrypted store directory.

---

## 2. Kotlin-to-Rust Feature Parity Matrix

Comparison of the Kotlin Compose Desktop reference (`composeApp/src/{commonMain,jvmMain}`) against the native Rust Windows client (`desktop-rust/crates/{notelikeus-core,notelikeus-app,nlk-native}`):

| Feature Area | Kotlin Desktop Reference | Rust Native (`nlk-native`) Implementation | Status | Notes |
|---|---|---|---|---|
| **Note CRUD (Create, Edit, Autosave, Empty-Draft Discard)** | Autosaves debounced edits; empty untitled drafts are discarded on close | Debounced autosave (`autosave-due`) plus save-on-close; empty drafts without text, checklist, or images are discarded | **Complete** | Verified in `tests/ui.rs` (`creating_editing_and_reopening`, `a_draft_left_empty_is_not_a_note`) |
| **Rich-Text / Markdown Formatting & Safe Links** | Stores Markdown (`**bold**`, `__bold__`, `_italic_`, `• ` bullets, `[label](url)`); `SafeHref` allows only `http://`, `https://`, `mailto:` (no credentials/spaces); card previews strip markers | Editor toolbar (`Bold`, `Italic`, `Bulleted list`, `Insert link`) + `Ctrl+B`/`Ctrl+I` operating on UTF-8 byte-to-char offsets (`view::{toggle_bold, toggle_italic, toggle_bullet_list, insert_link}`); `normalize_safe_href` enforces `SafeHref` rules; `strip_markdown` cleans card previews | **Complete** (Intentionally Markdown in `TextInput`) | Slint `TextInput` displays inline Markdown tokens during editing while cards render stripped plain-text previews |
| **Checklists & Dual Content Preservation** | Drag/toggle/add/remove items; converting text↔checklist; notes imported with both `content` and `checklist` preserve underlying `content` when checklist items change | Toggle moves checked items below unchecked; `update_checklist_note` preserves underlying `note.content` on dual notes; `convert_to_checklist` refuses >500 items instead of silently truncating | **Complete** | Verified in `update_checklist_note_preserves_dual_content` and `convert_to_checklist_errors_instead_of_truncating_overflow` |
| **Labels, Colors, Pinning, Archive, Trash** | 12 note colors (`contracts/design/note-colors.json`); case-insensitive label registry (max 100/note); `PINNED`/`OTHERS` split; Archive; Trash with Restore, Delete forever, Empty trash, and Undo toast | 12 contract-tested light/dark note colors with WCAG AA ink (`#151515` light / `#FFFFFF` dark); label create/rename/delete/toggle; separate `PINNED` and `OTHERS` grid sections; Archive, Trash, Restore, Delete forever, Empty trash, Undo toast | **Complete** | Verified against `contracts/design/note-colors.json` and `tests/ui.rs` |
| **Search, Operator Query, Filters Sheet, Smart Views, Sorting, Density** | Query operators (`label:`, `color:`, `is:`, `in:`, `before:`, `after:`, `has:`), prefix + typo fallback, saved filters, smart views, sort orders (`Manual`, `Newest`, `Oldest`), Grid/List/Compact density | Contract-tested query parser (`contracts/query/parser-vectors.json`), fuzzy fallback, Filters sheet, saved filters, smart views, `SortOrder::{Manual, Newest, Oldest}`, Grid/List/Compact density persisted in `settings.json` | **Complete** | Verified in `tests/query.rs` and `tests/ui.rs` |
| **Reminders & Windows Tray Notifications** | Background loop checks due reminders; `SystemTray` balloon notification; closing window hides to tray when reminders are pending | 60s `reminder_heartbeat` timer + check on open/save; zero-initialized Win32 `Shell_NotifyIconW` (`NIF_INFO`) tied to top-level `HWND`; closing window hides to tray only when `has_pending_reminders()` is true | **Complete** | `cleanup_notifications` removes the Win32 notification icon on exit |
| **Image Attachments & EXIF Orientation** | Max 20 images/note, 10 MiB/image; magic-byte MIME sniffing; EXIF orientation normalization; R2 Worker upload/download/delete; provisional commit (`p_attachment_ids`) | Max 20 images/note (`MAX_ATTACHMENTS_PER_NOTE`) with blob rollback on failure; magic-byte MIME sniffing (PNG/JPEG/GIF/WebP/AVIF/HEIC/HEIF); EXIF tags 2–8 orientation normalization + 128 MiB decode cap; 4-worker bounded thumbnail pool; R2 Worker upload/download/delete | **Complete** | Verified in `images::tests::exif_orientation_6_rotates_image_upright` and `tests/ui.rs` |
| **Local Encryption & Crash-Safe Storage** | SQLCipher DB with DPAPI-wrapped key in `~/.notelikeus` | AES-256-GCM sealed `state.nlkr` & `.nla` blobs bound via AAD to owner/attachment ID, key wrapped by Windows DPAPI (`CryptProtectData` + fixed entropy) in `%APPDATA%\NotelikeusRust`; refuses `~/.notelikeus` (including via symlinks/junctions); quarantines corrupt `state.nlkr` to `state.nlkr.corrupt-<ms>` before `.nlkr.prev` fallback | **Complete** (Separate storage format by design) | Existing Kotlin `~/.notelikeus` is explicitly refused so both apps can coexist safely; cross-app migration uses v3/v4 backups |
| **Authentication, Turnstile Captcha & Session Persistence** | Email OTP (`/auth/v1/otp`, `/auth/v1/verify`), loopback Turnstile challenge, DPAPI session storage, proactive token refresh | Email OTP, loopback Turnstile (`127.0.0.1` with random `state` token & origin validation), sealed `session.nlkr`, proactive refresh 60s before expiry, transient IO errors preserve saved session | **Complete** | URL and publishable-key validation rejects `sb_secret_` and `service_role` JWTs |
| **Cloud Sync, Conflict Copies & Cloud Wipe Generation** | Revision-checked push/pull; conflict copies (`(conflicted copy)`) with side-by-side diff; attachment cloning on conflict copies; `NL001`/`NL002` wipe generation block with `Delete cloud data` and `Re-upload local notes` | Attachment upload before note push (R19.2 `p_attachment_ids`); conflict copy attachment cloning (`forked_sources`); side-by-side conflict review dialog; `uploads_blocked_after_wipe` persisted across restarts; `Delete cloud notes` & `Re-upload local notes` banner | **Complete** | Verified across all 14 tests in `tests/sync_scenarios.rs` and `contracts/sync/*.json` |
| **Account Switching, Sign-Out & Account Deletion** | Per-account storage isolation; in-flight generation fence; `DELETE` confirmation + `/functions/v1/delete-account` with token refresh | Atomic target store load before mutating in-memory state; `account_generation` counter discards stale worker completions; proactive refresh + 401 retry before `/functions/v1/delete-account`; local account directory & blobs wiped on deletion | **Complete** | Verified in `failed_signed_in_store_load_preserves_guest_notes` |
| **Settings, 7 Canonical Themes & Privacy-Preserving Diagnostics** | System/Light/Dark × Neutral/Blue/Green × AMOLED black (`contracts/design/theme-colors.json`); JSON diagnostics report with `assertNoSensitiveValues` | All 7 canonical palettes contract-tested against `contracts/design/theme-colors.json`; `diagnostics_json()` validated by structural + regex + runtime secret checks without false positives on note text; Copy report + Export JSON | **Complete** | Verified in `theme_palettes_match_contracts_design_tokens` and `diagnostics_allows_sensitive_words_inside_note_text` |
| **Backups (v3 JSON & v4 `.nlkbak` ZIP Bundle)** | Import/export v3 JSON and v4 `.nlkbak` ZIP with manifest + media blobs; enforces `contracts/backup/import-limits.json` and rejects duplicate ZIP entries / ZIP bombs | v3 JSON and v4 `.nlkbak` export/import verified against Kotlin & Web fixtures (`contracts/backup/v3-*.json`, `v4-*-bundle.nlkbak`); checks `archive.has_duplicate_names()`, entry count (`10,000`), total uncompressed bytes (`512 MiB`), per-entry cap (`10 MiB`), and batched `add_backup_images` | **Complete** | Verified in `v3_kotlin_and_web_fixtures_match_expected_normal_form` and `nlk-native/src/backup.rs` tests |
| **Single-Instance Guard, Window Activation & Packaging** | Single-instance activation; MSI installer | Advisory lock on `instance.lock` + `activate.request` hand-off restores minimized/hidden window within 400ms; embedded `.ico` + `VERSIONINFO`; hardened `install.ps1`, `uninstall.ps1`, and `package.ps1` (`.sha256`) | **Complete** | Verified in unit tests and live release binary smoke test |
| **Supabase Realtime Push Wake-Ups** | WebSocket Realtime subscription triggers immediate pull while window is open | Periodic 2-minute sync timer, sync-on-start, sync-on-focus, and 3s post-edit sync | **Intentionally Different** | Avoids adding a persistent WebSocket runtime dependency; manual and focus/timer sync cover desktop usage |

---

## 3. Confirmed Security, Data-Loss, and Functional Defects Fixed

### Phase 1 — Critical Security & Data-Integrity Fixes

1. **Cryptographic Secret & Token Zeroization (`notelikeus-core/src/{crypto,store,attachments,auth,http}.rs`)**:
   - Added volatile byte wiping (`crypto::wipe`, `crypto::wipe_string`) across `Store::Drop`, `SessionVault::Drop`, `Blobs::Drop`, `Session::Drop`, `HttpRemote::Drop`, `OtpVerifyRequest::Drop`, `RefreshRequest::Drop`, and intermediate plaintext JSON buffers in `Store::load`, `Store::save`, and `SessionVault::save`.
   - Hardened Win32 DPAPI (`dpapi_call`) with `u32::try_from` input length checks, null-pointer guards on `out.pbData`, and unconditional `LocalFree`.
2. **Supabase URL, Publishable Key & TLS Redirect Hardening (`notelikeus-core/src/{http,auth,captcha}.rs`, `notelikeus-app/src/service.rs`)**:
   - `checked_base_url` and `checked_page_url` now parse host/port strictly, reject `@` userinfo credentials, require valid port numbers (`1..=65535`), and refuse plain HTTP except on `localhost` / `127.0.0.1`.
   - `checked_publishable_key` rejects empty keys, `sb_secret_*` keys, and JWTs whose payload decodes to `"role":"service_role"`.
   - Disabled automatic HTTP redirects (`.redirects(0)`) on all `ureq` agents so bearer and apikey headers can never be forwarded across redirects.
3. **Complete SQLSTATE Error Classification & RPC Contract Parity (`notelikeus-core/src/http.rs`, `tests/contracts.rs`)**:
   - Mapped `NL001` and `NL002` to `TransportError::WipeGenerationStale`, `NL003` to `TransportError::AccountDeleted`, `NL004` to `TransportError::ReauthenticationRequired`, `NL005` to `TransportError::AccountNoteLimit`, and `22023` to `TransportError::PayloadInvalid`.
   - Added `get_sync_generation`, `delete_all_user_cloud_data`, `finalize_cloud_wipe`, `fetch_full_snapshot_rows`, `restore_note`, `list_pending_deleted_attachments`, and `purge_deleted_note_attachment`, and added `http_rpcs_match_latest_supabase_migrations` to verify every RPC in `http.rs` against `supabase/migrations/*.sql`.
4. **Corrupt State File Quarantine & Safe `.prev` Rotation (`notelikeus-core/src/store.rs`)**:
   - Previously, when `state.nlkr` was corrupted and `Store::load` fell back to `state.nlkr.prev`, the damaged `state.nlkr` remained in place and was rotated *over* `state.nlkr.prev` on the next `Store::save`, destroying the only good backup.
   - `Store::load` now renames a damaged `state.nlkr` to `state.nlkr.corrupt-<epoch_ms>` before returning the recovered state from `state.nlkr.prev`, and `Store::save` copies `state.nlkr` to a temporary `.prev` file and renames it atomically so `state.nlkr` is never missing between renames.
5. **Symlink-Resolving Kotlin Directory Guard & Attachment ID Validation (`notelikeus-core/src/{store,attachments}.rs`)**:
   - `is_inside` now canonicalizes existing ancestor prefixes before comparing against `~/.notelikeus`, preventing junctions or symlinks from bypassing the Kotlin directory guard.
   - `Blobs::path` validates attachment IDs against `[A-Za-z0-9_-]{1,128}`, refusing dot-segments (`"."`, `".."`, `"foo.bar"`) and path separators, and writes `.nla` files atomically via process/sequence-tagged temporary files.
6. **Atomic Account Switching, Transient Session Preservation & Dual-Content Preservation (`notelikeus-app/src/service.rs`)**:
   - `NotesService::open` now clears `session.nlkr` only on `Error::Corrupt` or `Error::InvalidInput`, preserving the saved session on transient OS/IO errors.
   - `signed_in`, `sign_out`, and `refreshed` open and load the target account's `Store` before mutating `self.store` or `self.state`, ensuring a failed store load never corrupts active in-memory guest notes.
   - Added `update_checklist_note` so editing checklist items or titles in `nlk-native` preserves underlying `note.content` on dual notes imported with both prose and checklist items.
7. **Sync Ordering, Conflict Copy Attachment Cloning & Persisted Cloud-Wipe Block (`notelikeus-core/src/sync.rs`, `notelikeus-app/src/service.rs`)**:
   - Reordered `NotesService::sync_now` so pending image attachments for existing notes are uploaded *before* `sync::sync` pushes note revisions (allowing `ApplyNoteChangeArgs.p_attachment_ids` to promote provisional uploads in the same transaction per R19.2), then uploads attachments for newly created notes and clones local/remote attachment blobs onto conflicted copies via `report.forked_sources`, and finally deletes removed remote attachments *after* note revisions are committed.
   - Persisted `state.uploads_blocked_after_wipe = true` to disk whenever `WipeGenerationStale` (`NL001`/`NL002` on push or higher `wipe_generation` on pull) occurs, and skipped single notes rejected with `PayloadInvalid` (`22023`) without aborting sibling notes.
8. **v4 `.nlkbak` ZIP Duplicate-Entry & Decompression-Bomb Hardening (`nlk-native/src/backup.rs`, `notelikeus-core/src/attachments.rs`)**:
   - Discovered during contract testing that `zip = 9.8.1` deduplicates central directory entries into an `IndexMap` during `ZipArchive::new`, so iterating `0..archive.len()` alone missed duplicate entry names in `contracts/backup/v4-duplicate-entry.nlkbak`. Added an explicit `archive.has_duplicate_names()` check alongside `MAX_ZIP_ENTRIES` (`10,000`), `MAX_TOTAL_UNCOMPRESSED_BYTES` (`512 MiB`), `MAX_ATTACHMENT_BYTES` (`10 MiB`), required `sizeBytes`, and batched single-commit `add_backup_images`.
   - Updated `attachments::sniff_mime` to check the 4-byte PNG magic prefix (`\x89PNG` with `bytes.len() >= 8`) so the 8-byte contract test vectors in `contracts/backup/v4-{kotlin,web}-bundle.nlkbak` import and round-trip cleanly.
9. **Diagnostics Privacy Verification Without False Positives (`notelikeus-app/src/service/diagnostics.rs`)**:
   - Replaced fragile substring checks that previously caused `diagnostics_json()` to panic if a user's note title or body happened to contain words like `"title"`, `"content"`, or `"access_token"` with structural checks against forbidden JSON keys, JWT/UUID patterns, and actual runtime secrets (`session.user_id`, `session.email`, `access_token`, `refresh_token`), and fixed `withAttachments` to count only live non-trashed notes.

### Phase 2 & 3 — Missing Functionality, UI/UX Polish & Windows Integration

1. **Windows UI Automation (UIA) Focus Tracking Fix (`nlk-native/ui/app.slint`)**:
   - Resolved the previously documented Windows accessibility defect (`desktop-rust/README.md:119-120`) where UIA continued reporting `Search notes` as focused when tabbing inside dialogs: interactive components (`IconButton`, `FilterChip`, `PillButton`, `ThemeOption`, `CheckBox`, `NavItem`, `Card`) now inherit `FocusScope` directly on their root element (with explicit `preferred-width`/`preferred-height` on `Card` and `NavItem`), and closing any modal dialog or sheet automatically restores focus to `keys.focus()`.
2. **7 Canonical Theme Palettes & WCAG Contrast (`notelikeus-core/src/palette.rs`, `nlk-native/ui/{theme.slint,app.slint}`)**:
   - Implemented all 7 canonical palettes from `contracts/design/theme-colors.json` (`Light Neutral`, `Dark Neutral`, `AMOLED Neutral`, `Dark Blue`, `AMOLED Blue`, `Dark Green`, `AMOLED Green`) plus light-mode Blue (`#0B57D0`) and Green (`#1B6B2E`) accents, and updated colored note cards and the editor sheet to use `#151515` primary ink in light mode and `#FFFFFF` in dark mode.
3. **Markdown Formatting Toolbar, SafeHref Link Dialog & Layout Controls (`nlk-native/src/{view,controller}.rs`, `ui/app.slint`)**:
   - Added a formatting bar (`Bold`, `Italic`, `Bulleted list`, `Insert link`) and `Ctrl+B`/`Ctrl+I` keyboard shortcuts in `EditorSheet` using Slint 1.19's `anchor-position-byte-offset`, `cursor-position-byte-offset`, and `set-selection-offsets`, plus an `Insert link` modal dialog validating URLs through `view::normalize_safe_href`.
   - Split the `Notes` view into `PINNED` and `OTHERS` sections, added `SortOrder` (`Manual`, `Newest`, `Oldest`) and view density (`Grid`, `List`, `Compact`) controls in the toolbar, made the `Sidebar` collapsible, and gated global shortcuts (`Ctrl+N`, `Ctrl+K`, `Ctrl+F`) whenever any modal sheet or dialog is open.
4. **Background Worker Resilience, Bounded Picture Pool & Single-Instance Lock (`nlk-native/src/{instance,win_notify,images,controller}.rs`)**:
   - Wrapped background worker commands in `std::panic::catch_unwind`, replaced unbounded thread-per-image spawning with a 4-worker bounded pool + 128 MiB image allocation cap + EXIF orientation normalization, added a 60-second reminder heartbeat and conditional close-to-tray (`has_pending_reminders()`), and added an advisory file lock (`instance.lock`) with `activate.request` hand-off.

---

## 4. Verification & Observed Test Results

All validation commands were executed on Windows with the active stable MSVC toolchain (`cargo 1.94.0`) without modifying `Cargo.toml` or `Cargo.lock` (`--locked`).

### 4.1 Static Analysis & Formatting

| Command | Result |
|---|---|
| `cargo fmt --all -- --check` | **Passed** (exit code `0`, 0 diff lines) |
| `cargo clippy --workspace --all-features --all-targets --locked -- -D warnings` | **Passed** (exit code `0`, 0 warnings across `notelikeus-core`, `notelikeus-app`, `nlk-native`, `nlk-tauri`, `nlk-cli` and all test targets) |

### 4.2 Automated Test Suite (`cargo test --workspace --all-features --locked`)

| Crate / Target | Passed | Failed | Notes |
|---|---|---|---|
| `nlk-native` (`src/lib.rs` unit & contract tests) | **16** | 0 | Includes `v4_kotlin_and_web_bundles_import_and_round_trip`, `v4_duplicate_entry_bundle_fixture_is_rejected`, `zip_bombs_and_malformed_v4_manifests_are_refused`, `exif_orientation_6_rotates_image_upright`, `second_instance_yields_and_signals_activation`, `safe_href_matches_kotlin_and_web_rules`, `strip_markdown_removes_inline_syntax_and_keeps_text`, `formatting_helpers_handle_selections_and_collapsed_carets` |
| `nlk-native` (`tests/ui.rs` headless Slint UI suite) | **1** (15 scenarios) | 0 | Exercises create/edit/reopen, empty draft discard, pin/search/delete/undo, archive/trash/restore/empty, colors/labels, checklists, search operators/saved filters, conflict review, pictures, appearance/density/sort/sidebar persistence, `PINNED`/`OTHERS` sections, Markdown formatting & `SafeHref`, dual content+checklist preservation, diagnostics dialog + copy, cloud wipe banner & modal shortcut/Escape Z-order gating |
| `nlk-native` (`tests/live.rs`) | **1** | 0 | No-op return when `NLK_TEST_SUPABASE_URL` is unset (see §4.4) |
| `notelikeus-app` (`src/lib.rs` unit & contract tests) | **28** | 0 | Includes `backup_limits_match_contracts_fixture`, `v3_kotlin_and_web_fixtures_match_expected_normal_form`, `update_checklist_note_preserves_dual_content`, `diagnostics_allows_sensitive_words_inside_note_text`, `convert_to_checklist_errors_instead_of_truncating_overflow`, `failed_signed_in_store_load_preserves_guest_notes` |
| `notelikeus-core` (`src/lib.rs` unit tests) | **7** | 0 | MIME sniffing, attachment listing merge, sealed blob AAD isolation, conflict line diffing, loopback Turnstile state check |
| `notelikeus-core` (`tests/contracts.rs`) | **11** | 0 | Cloud rows, `ApplyNoteChangeArgs`, content digests, inbound conflict resolution, owner tags, note color palette, 7 canonical theme palettes (`theme_palettes_match_contracts_design_tokens`), tombstones, and SQL migration RPC signatures (`http_rpcs_match_latest_supabase_migrations`) |
| `notelikeus-core` (`tests/http_replies.rs`) | **11** | 0 | Apply/delete/pull/auth reply parsing, SQLSTATE error classification, loopback/HTTPS enforcement, `checked_base_url_refuses_userinfo_and_bad_ports`, `publishable_key_rejects_secret_and_service_role_keys` |
| `notelikeus-core` (`tests/query.rs`) | **10** | 0 | Shared `contracts/query/parser-vectors.json`, operators, date windows, relevance, typo fallback, saved filters, smart views |
| `notelikeus-core` (`tests/store.rs`) | **12** | 0 | Real Windows DPAPI wrap/unwrap & tamper rejection, AES-GCM sealed round-trip, account AAD isolation, corrupt file quarantine (`corrupt_current_file_is_quarantined_before_fallback`), missing key refusal, `~/.notelikeus` refusal, traversal refusal (`attachment_blobs_reject_traversal_segments`) |
| `notelikeus-core` (`tests/sync_scenarios.rs`) | **14** | 0 | All shared `contracts/sync/scenarios.json` cases in `push-first` and `pull-first` orders, offline edits, lost replies, pagination, F-04 cursor invariance, pre-wiped account join, `wipe_block_persists_across_local_state_serialization_and_reload`, `payload_invalid_skips_only_offending_note_and_syncs_siblings` |
| `notelikeus-core` (`tests/live_supabase.rs`) | **5** | 0 | No-op return when `NLK_TEST_SUPABASE_URL` is unset (see §4.4) |
| **Total** | **116** | **0** | All 116 test entry points exited with code `0` |

### 4.3 Release Binary Build & Isolated Windows Smoke Test

Built with `cargo build --release --locked -p nlk-native` and executed against an isolated throwaway directory (`$env:TEMP\nlk-smoke-$PID` via `NLK_RUST_DATA_DIR`, leaving `%APPDATA%\NotelikeusRust` and `~/.notelikeus` completely untouched):

- **Executable:** `desktop-rust\target\release\notelikeus.exe` (`23,158,272` bytes / `22.09 MiB`)
- **Embedded Win32 Version Info:** `ProductName = Notelikeus`, `FileVersion = 0.1.0`
- **Primary Instance Startup:** remained alive after 3s (`WorkingSet = 142.44 MiB`, `PrivateMemory = 171.14 MiB`), created `instance.lock` (`LockExists = True`) and DPAPI-wrapped `data-key.protected` (`KeyExists = True`) inside the isolated directory.
- **Secondary Instance Hand-Off:** a second `notelikeus.exe` launched against the same `NLK_RUST_DATA_DIR` detected the held `instance.lock`, wrote `activate.request`, and exited cleanly with `ExitCode = 0` while the primary instance remained alive (`PrimaryStillAlive = True`).
- **Distributable Package (`desktop-rust/packaging/native/package.ps1`):**
  - Archive: `desktop-rust/target/native-packages/NotelikeusRust-0.1.0-x86_64-pc-windows-msvc.zip` (`11,815,253` bytes / `11.27 MiB`)
  - Checksum (`NotelikeusRust-0.1.0-x86_64-pc-windows-msvc.zip.sha256`): `a73b337c8478b12ffda3058b4f9ad7e3591ac8c15ebcaefa1672593e476c33fa`

### 4.4 Environment Restrictions & Skipped Live Backend Tests

Because no local Docker/Supabase stack (`http://127.0.0.1:54326`), Mailpit (`http://127.0.0.1:54324`), or local Cloudflare Attachments Worker (`http://127.0.0.1:8787`) was running during this audit session, the following live integration tests returned early by design when `NLK_TEST_SUPABASE_URL`, `NLK_TEST_SUPABASE_KEY`, `NLK_TEST_MAILPIT_URL`, and `NLK_TEST_ATTACHMENTS_URL` were unset:

1. `nlk-native/tests/live.rs`:
   - `signing_in_syncing_and_reviewing_a_conflict_through_the_window`
2. `notelikeus-app/src/service.rs`:
   - `service::tests::signed_in_notes_sync_between_installs_and_stay_apart_from_guest_notes`
   - `service::tests::signed_in_images_upload_between_installs_and_delete_through_local_worker`
3. `notelikeus-core/tests/live_supabase.rs`:
   - `a_bad_token_is_unauthorized_and_a_refresh_gives_a_working_one`
   - `accounts_cannot_see_or_touch_each_others_notes`
   - `signing_in_with_an_emailed_code`
   - `two_devices_on_one_account_converge_and_keep_both_versions`
   - `what_the_rust_client_writes_is_what_the_other_clients_read`

---

## 5. Remaining Limitations & Recommended Next Steps

1. **Inline WYSIWYG Rendering vs. Markdown Tokens in Editor:** `nlk-native` stores and edits standard Markdown (`**bold**`, `_italic_`, `• `, `[label](url)`) using Slint's native `TextInput` with a formatting toolbar and `Ctrl+B`/`Ctrl+I` shortcuts, and strips Markdown syntax in card previews. Full inline styled spans inside the editor (hiding `**`/`_` delimiters while typing) would require a custom rich-text layout widget in Slint.
2. **Supabase Realtime WebSocket Wake-Ups:** `nlk-native` syncs on startup, window focus, 3 seconds after local edits, every 2 minutes on a timer, and on manual `Sync now`. Adding a WebSocket Realtime subscriber would provide sub-second push wake-ups while the window sits unfocused.
3. **Authenticode Code Signing:** `packaging/native/package.ps1` produces a reproducible `.zip` bundle and `.sha256` checksum with embedded Win32 `.ico` and `VERSIONINFO` resources, but the binary is currently unsigned until a Windows Authenticode certificate is provisioned in CI.
4. **Spoken Screen-Reader Pass:** The Windows UIA focus-tracking bug (`FocusScope` inheritance on interactive controls and focus restoration on modal close) is fixed and verified via Slint's accessibility testing backend, but an interactive listening pass with NVDA and Windows Narrator remains recommended before marking `nlk-native` as the default Windows release.
