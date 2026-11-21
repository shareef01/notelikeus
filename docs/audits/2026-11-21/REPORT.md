# Audit, data-integrity fix and Rust experiment — 2026-11-21

Baseline: `main` at `95cb15d9` (v2.7.0 released; nothing waiting to deploy). Work is on branch
`audit/2026-11-21`. Findings already recorded in earlier audits (`docs/audit/AUDIT_REPORT.md`,
`docs/audits/2026-11-05` to `-18`) were not re-reported.

**Result: one confirmed data-loss defect on the web fixed with a regression test (AUD-I01). Kotlin already handled
the same case. An experimental Rust core for Windows was added and tested against the shared contracts.** No
production system was touched: no deploy, no migration, no hosted database, no credentials.

## Scope and method

The earlier passes covered most of the repository (see their Coverage sections). This pass put data integrity first,
as the brief asked:

- **Read end to end**: the web sync path (`notesSyncService.ts`, `supabaseRemoteNotesDataSource.ts`
  `subscribeToNotes` / `syncNotesWithCloud`, `supabaseSyncEngine.ts` `applyNoteChange` / `pullIncrementalChanges`,
  `pendingPushes.ts`, `notesLocalRepository.ts` `applyRemotePageAtomically` / `resolveConflictedPush`,
  `tombstoneStore.ts`, the delete path in `noteActions.ts`). On the Kotlin side: `ConflictResolution.kt`,
  `NoteSyncEngine` `mergeCloudTombstones`, `purgeLocalTombstonedNotes`, `pushPendingRecommits`, `cloudWinsConflict`
  and `NoteIdAllocator`.
- **Checked against the database**: the `apply_note_delete` and `apply_note_change` reply shapes in
  `20261102000000_owner_write_order_and_stored_quota.sql`.
- **Spot checks with no new finding**: the web query parser (`noteQueryParser.ts`, which the 2026-11-17 audit had
  not read), the CSP in `web/public/_headers` (Supabase and Worker origins are pinned at build time by
  `pinAttachmentsCspPlugin`), raw-HTML sinks (no `dangerouslySetInnerHTML` or `innerHTML` writes in `web/src`), link
  protocols (`LinkDialog.tsx` allows only http, https and mailto), and sign-out with unsynced edits (`SignOutDialog`
  counts and warns).
- **Not re-audited this pass**: the Worker, RLS policies and pgTAP suite, Android-only and Windows-only UI, the
  backup codecs, attachments. The earlier reports remain the record for those.

## Architecture (as found)

The README's diagram is accurate. Verified by reading:

- **Android and Windows** share one Kotlin Multiplatform module (`composeApp/commonMain`): one `NoteSyncEngine`, Room
  (SQLCipher on Android, key in the Keystore; desktop key wrapped by DPAPI under `~/.notelikeus`) and one Compose UI.
  `androidApp/` is only the entry point, widget and App Functions.
- **The web** is a separate TypeScript implementation of the same protocol: IndexedDB plus Zustand, a durable
  pending-push queue, and a tombstone store persisted through zustand `persist`.
- **The protocol**: every write is an RPC (`apply_note_change`, `apply_note_delete`, `restore_note`) carrying a base
  revision and the wipe generation. The server refuses a stale base and returns its current row. Pulls are
  `pull_changes` pages after a cursor, and first loads use `fetch_snapshot_page`. Tombstones stop resurrection.
- **Cross-client agreement** is enforced only by `contracts/` fixtures that both test suites read. There is no
  shared code between Kotlin and TypeScript. AUD-I01 is exactly that gap: the same rule, implemented twice, came out
  differently.

## Issue register

| ID | Sev | Platform | Finding | Evidence | Status |
|---|---|---|---|---|---|
| AUD-I01 | P2 | Web | An unsynced edit was silently dropped when a pull learned of the note's deletion on another device before the edit was pushed | Failing test, then fixed | Fixed (uncommitted) |

### AUD-I01: a pulled tombstone drops the pending edit under it

**Location**: `web/src/lib/supabase/pendingPushes.ts`, `flushOne` (lines 145-170 after the fix).

**Root cause.** The tombstone store does not record where a deletion came from. Two paths put an id into it:

1. a delete on this device (`noteActions.ts`), which removes the IndexedDB row *before* tombstoning it;
2. a cloud tombstone merged by a pull or snapshot (`mergeFromCloud`, from `pullIncrementalChanges`,
   `syncNotesWithCloud` and `loadBaseline`).

`applyRemotePageAtomically` correctly keeps a row that holds an unconfirmed edit, because it is the only copy of
that edit. But `flushOne` began with `if (isDeleted(noteId)) dropPendingPush()`, and its comment read every
tombstone as "Deleted on this device". After a pull had merged another device's tombstone, the next flush therefore
dropped the edit's marker. The note was filtered out of the UI as deleted and never uploaded. Its words stayed in
IndexedDB under a tombstone, out of the user's reach.

**When it happens**: the edit is still queued when a download sees the tombstone. That covers a flush that failed
once on a flaky connection followed by a successful snapshot in `syncNotesWithCloud` (which merges tombstones after
its flush), and a realtime-triggered `pullIncrementalChanges`, which does not flush first. When the push itself
meets the tombstone, the outcome was already right: `note_deleted` → `keepBothVersions`, covered by the existing
test "keeps the edit as a copy when another device deleted the note meanwhile".

**Kotlin parity**: not affected. `purgeLocalTombstonedNotes` (`NoteSyncEngine.kt:2022`) applies the right rule: "A
row still present under a tombstone was deleted on another device (a local permanent delete removes the row
itself). If it was edited here, keep the words". The inbound contract pins it as `fork-then-delete`.

**Fix**: `flushOne` reads the pending edit first. With no row, the delete happened here and the marker is dropped as
before. With a row under a tombstone, the delete happened elsewhere, and the edit goes through `keepBothVersions(…,
null, …)`, the same path a `note_deleted` reply takes. The words become a "(conflicted copy)" and are uploaded.

**Regression test**: `offlineEditDurability.test.ts`, "keeps the edit as a copy when a pull learns of the delete
before the edit is pushed". It failed before the fix (`expected [ 'offline edit' ] to include 'offline edit
(conflicted copy)'`) and passes after.

**Follow-up worth considering, not done**: record the origin in the tombstone store itself (local or cloud), as the
Rust core's `TombstoneOrigin` does. The row-presence rule is the same discriminator the Kotlin engine uses, and it
is sufficient today. An explicit field would stop the next reader from repeating the "deleted here" assumption.

*Decided later the same day: not done.* `contracts/sync/scenarios.json` now guards the rule behaviourally:
putting the old `flushOne` line back fails its download-first run of "an offline edit of a note deleted elsewhere
survives as a copy". A stored origin would need every one of the six `markDeleted` callers to tag correctly, and
several of them infer a deletion from the cloud. One mislabelled caller could reintroduce the bug the field was
meant to prevent.

## Changes (file by file)

| File | Change |
|---|---|
| `web/src/lib/supabase/pendingPushes.ts` | AUD-I01 fix in `flushOne` |
| `web/src/lib/notes/offlineEditDurability.test.ts` | AUD-I01 regression test |
| `desktop-rust/**` | New: experimental Rust core and CLI (see `desktop-rust/README.md`) |
| `.github/workflows/desktop-rust.yml` | New, non-required: fmt, clippy `-D warnings`, tests on `windows-latest`, only when `desktop-rust/` or `contracts/` change |
| `docs/audits/2026-11-21/REPORT.md` | This report |
| `docs/audit/REMAINING_WORK.md` | Row for this pass |

## Test results (this machine, 2026-11-21)

| Command | Result |
|---|---|
| `cd web && npm run lint` | Passed (exit 0; warnings only, as on `main`) |
| `cd web && npm run typecheck` | Passed |
| `cd web && npx vitest run` (baseline) | 188 files, **1501 passed** |
| same, after the fix | 188 files, **1502 passed** (the new test) |
| `npm --prefix workers/attachments test` | **175 passed** (baseline; the Worker was not changed) |
| `./gradlew :composeApp:desktopTest detekt` (with `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/nlk-tmp`) | BUILD SUCCESSFUL, **1056 tests, 0 failures**, Detekt clean (baseline; Kotlin was not changed) |
| `cargo test --workspace --all-features` (desktop-rust, GNU toolchain) | **30 passed** |
| `cargo clippy --workspace --all-features --all-targets -- -D warnings`, `cargo fmt --check` | Clean |

**Not run, blocked by the environment:**
- `npm run test:e2e` and `npm run supabase:test` (pgTAP): the Docker daemon wasn't running, and Claude must not start
  Docker Desktop on this machine.
- `:composeApp:testDebugUnitTest`, `:androidApp:testDebugUnitTest` and the instrumented suites: not run this pass.
- No device or emulator run.

**Not runtime-verified:** AUD-I01 in a browser against a live backend. Reproducing it needs two signed-in clients and
a local Supabase stack, so the unit test against the cloud fake is the evidence.

## UI/UX

No UI/UX changes in this pass. The 2026-11-11 audit (NL-01 to NL-12) and the 2026-11-18 pass remain current. Their
open manual checks (Windows Narrator, 200% scaling, multiple monitors, contrast themes) are still open in
`REMAINING_WORK.md` §3. Automated a11y coverage is `npm run test:audit-ui` (axe), which was not run this pass.

## Rust Windows client

The decision and status are in `desktop-rust/README.md`. In short: keep Kotlin as the shipped client, and continue
Rust along Tauri 2 with the React UI and a Rust core. A tested vertical slice exists: the model and contracts, the
conflict rules, the encrypted per-account store with a DPAPI-wrapped key, the sync engine with ten data-integrity
scenarios, the HTTP transport (parsing tested only) and a guest-mode CLI. There is no UI, sign-in or snapshot
download yet. The Tauri shell needs the MSVC Build Tools, which this machine lacks.

**Addendum, later the same day.** The Build Tools were installed and a guest-mode Tauri 2 app was added
(`desktop-rust/crates/nlk-tauri`). It has a notes list, search, autosave, pin, delete with Undo and a narrow layout.
The 18-check end-to-end driver runs against the real window. Building it caught one UI race (keystrokes typed while
a previous save was in flight could be lost) and one rendering bug (`hidden` overridden by CSS display rules). Both
were fixed before the first commit. There is still no sign-in or sync in the window.

## Remaining risks

1. **Two (soon three) implementations of the sync rules.** `contracts/sync/inbound-resolution.json` pins the Kotlin
   rules. The web reaches the same end states through a different mechanism, pinned only by
   `offlineEditDurability.test.ts`. A shared scenario fixture (inputs plus expected end state) that every engine
   runs would have caught AUD-I01. That's the highest-value next step, and the Rust scenario tests are a starting
   point.
   *Done later the same day:* `contracts/sync/scenarios.json` has 10 cases. Kotlin, web and Rust each run it, and all
   three reach every end state, with web and Rust checking both orders. The risk now is the fixture's coverage:
   it has one note, one other device and no attachments.
   *Widened afterwards:* 17 cases, now including labels and checklists (changes elsewhere, the same tick on both
   devices, different ticks on each) and two notes changing at once. All three engines still agree. Still not
   covered: attachments, pin/colour/archive-only changes, reminders, and more than one other device.
2. Everything already listed in `REMAINING_WORK.md` §2 and §3 (FU-6, ENV-1, device checks).

## Verification matrix

States: **V** verified working (this pass), **F** fixed and tested, **I** implemented, not runtime-tested this pass,
**N** not implemented, **B** blocked by environment. "I" on shipped platforms means the feature exists and is
covered by the suites listed above, but this pass did not exercise it on a device or browser.

| Feature | Android | Web | Kotlin Windows | Rust Windows |
|---|---|---|---|---|
| Note model and wire format (contracts) | V (shared Kotlin, desktopTest) | V (vitest) | V (desktopTest) | V (cargo test) |
| Create / edit / delete locally | I | I | I | V (CLI smoke and tests) |
| Encrypted local storage | I | I | I | V (DPAPI and AES-GCM tests) |
| Offline queue and push | I | V (unit) | I | V (scenario tests; live against a local stack) |
| Concurrent edits keep both versions | I | V (unit) | I | V (scenario tests; live, two devices) |
| Edit vs delete elsewhere | I | **F** (AUD-I01) | I | V (scenario tests; live, two devices) |
| Sign-in (email code) | I | I | I | V against a local stack (real emailed code); hosted blocked by Turnstile |
| Account isolation (RLS) | I | I | I | V against a local stack (a second account sees and changes nothing) |
| Realtime wake-ups | I | I | I | N |
| Attachments | I | I | I | N |
| Backups v3/v4 | I | I | I | N |
| Reminders | I | I | I | N |
| Account deletion | I | V in production (2026-11-18, earlier pass) | I | N |
| UI | I | I | I | V: guest mode, plus sign-in and sync through the window against a local stack (e2e drivers) |
| e2e and pgTAP | — | B (no Docker) | — | — |

## Addendum: Rust sign-in and sync (later the same day)

With Docker running, the local Supabase stack was started. Its one pending migration (`20261115000000`) was applied
with `supabase migration up --local`, which leaves local data in place, unlike `db reset`. The Rust client then
gained:
- emailed-code sign-in
- a DPAPI-sealed session
- token refresh
- first-sync generation adoption
- an account panel in the Tauri app

Everything was verified against that stack only (see `desktop-rust/README.md`, "Against a local Supabase stack"). No
hosted project was touched.

One defect was found and fixed in passing. The transport's plain-HTTP exception for a local stack compared URL
prefixes, so `http://localhost.example.com` passed as loopback. It now compares the host exactly
(`http::checked_base_url`, with tests). The impact was limited to the experimental client, which has no hosted
configuration.
