# Expanded repository audit — 2026-11-05

Audited checkout: `aa5558f0cac670e3c5b97fc2c9ec48e3f202da84`, the changes subsequently merged in PR #434 as `2864aabce1e2befb819f3b4956c23c9c3e6ad58f`.

**Result: 13 additional actionable findings: 10 P1, 3 P2. Eleven were reproduced during the initial audit; remediation now includes executable regression coverage for the native cleanup and AppFunction findings as well. The findings below describe the audited baseline; all thirteen now have working-tree fixes and regression coverage.**

This is a broad source and test audit, not proof that the repository contains no other defects. Coverage depth and unavailable runtime checks are recorded below. The eleven findings fixed in PRs #431–#434 are excluded from this register.

## Remediation status

All E01–E13 are addressed locally. Web saves are serialized and retain draft revisions; removed-image cleanup is durable and follows cloud acceptance; asynchronous operations retain the original owner/session; labels persist with pending markers; native refresh and destructive cleanup retain their original authority; interrupted attachment swaps recover; existing empty notes save; AppFunctions use fenced repository APIs. AUD-D01 remains a separate build-tool dependency maintenance item.

## Findings

### AUD-E01 — P1: a completed autosave erases typing entered while it was running

Location: `web/src/hooks/useNoteEditor.ts:202–215`.

The editor snapshots `working`, awaits `saveNote`, then replaces the current editor state with that old snapshot. Typing remains enabled during the wait. Type version 1, let autosave start, type version 2, then complete the first save: version 2 disappears. The next debounce saves version 1 again. The wait includes remote operations, so an ordinary slow network exposes the window.

Evidence: `useNoteEditor.auditRegression.test.tsx`, AUD-E01, exercises the real hook and holds its save promise open. It verifies both the lost displayed text and the stale next save.

Fix: serialize saves, track a draft revision, and apply completion metadata without replacing newer user fields. Reserve a new note's identity before asynchronous work. Test continued typing, overlapping saves, new notes, deletion and navigation during a save.

### AUD-E02 — P1: attachment removal destroys bytes before the replacement note is durable

Locations: `web/src/lib/notes/noteActions.ts:65–86`; `web/src/hooks/useNoteEditor.ts`, `removeAttachment`.

`saveNote` calls `deleteAttachmentsForNote` before its local write. If encryption/IndexedDB then fails, the persisted note still references the image whose bytes were deleted. Remote deletion retires the attachment identity, so this is not repaired by retrying the same reference. The editor also directly deletes an image and releases staging before its debounce persists the removal.

Evidence: `noteActions.auditRegression.test.ts`, AUD-E02, rejects the local save and verifies cleanup already ran while the old note still references the image. Worker DELETE's claim-first, terminal identity behavior was traced in `workers/attachments/src/index.ts` and the SQL lifecycle migrations.

Fix: persist the changed note before releasing staged bytes; commit the note mutation before retiring a remote attachment. Record cleanup durably for retries. Remove the editor's independent early deletion.

### AUD-E03 — P1: an attachment edit from A can be saved and uploaded as B

Location: `web/src/lib/notes/noteActions.ts:62–86`, and `pushNote` at `:39–56`.

`saveNote` captures `sessionOwnerId`, but awaits attachment cleanup before calling `pushNote`. `pushNote` resolves the owner again. Switch from A to B while cleanup is suspended: the old note content is written to B's namespace and passed to B's remote upsert. The ownership check after `pushNote` is too late.

Evidence: `noteActions.auditRegression.test.ts`, AUD-E03. A deferred cleanup permits the switch, then the test observes both the local write and remote upsert receiving B with A's text.

Fix: thread the originating owner and session/dataset incarnation through the entire save. Reject before each account-owned effect when its authority has expired. A check only after the write is insufficient.

### AUD-E04 — P1: late permanent deletion removes B's note and poisons B's tombstones

Location: `web/src/lib/notes/noteActions.ts:159–204`.

The local deletion is explicitly scoped to its captured owner, but after its await the function unconditionally updates the global tombstone and notes stores. If B replaces A while the operation is pending, B's note with the same id disappears and B inherits A's deletion marker. Subsequent reconciliation can interpret that marker as B's deletion intent.

Evidence: `noteActions.auditRegression.test.ts`, AUD-E04. The test switches account and replaces both global stores while A's IndexedDB deletion is suspended, then observes B's note removed and its id tombstoned.

Fix: keep deletion intent per owner and guard global publication by the originating session incarnation. Apply the same rule to restore/undo and deferred attachment GC; those continuations also use global state or ambient credentials.

### AUD-E05 — P1: cloud wipe still borrows the replacement account's credentials

Locations: `web/src/lib/auth/accountAuth.ts:38–47`; `web/src/lib/supabase/deleteAllUserCloudData.ts:72–98`.

`signOutAccount` captures A for local cleanup, but `deleteAllSupabaseCloudData` only checks that some session exists. Its RPC uses ambient SDK authorization; its later Worker deletes obtain another ambient token. A switch during the authentication await can wipe B while clearing A locally and signing B out. A switch after the wipe can instead direct A's attachment ids to B's namespace. The owner binding added for account deletion does not cover cloud wipe.

Evidence: `cloudWipe.auditRegression.test.ts`, AUD-E05, observes B selected for the destructive RPC and A selected for local cleanup after a deferred authentication check.

Fix: capture the explicit intended owner at the UI action, obtain one verified owner-bound credential, and use it for every RPC and Worker request. Bind cleanup and sign-out to the same original session. Preserve that intent through reauthentication.

### AUD-E06 — P1: an import publishes the old account's data into the new account's library

Location: `web/src/lib/backup/commitImportedNotes.ts:43–58`; caller in `web/src/screens/main/useAccountActions.ts`, `confirmImport`.

The IndexedDB import is owner-scoped, but after awaiting it `publishImported` appends the imported notes to whichever global library is active. A switch to B during A's write displays A's imported text in B. Later editing that note uses B's ownership. Bundle staging/rollback also resolves the current owner independently, rather than retaining the preview/confirmation owner.

Evidence: `importOwner.auditRegression.test.ts`, AUD-E06, observes A's imported secret appended to B's active library after a delayed write.

Fix: bind preview, staging, commit, publication and rollback to one owner/incarnation. Keep A's durable result under A, and suppress publication to B. Explicitly decide how a preview behaves when the account changes.

### AUD-E07 — P2: label rename/delete updates memory but never persists or queues note edits

Location: `web/src/hooks/useLabelManagement.ts:16–48`.

The hook updates the label registry and calls `upsertLocalNote`, which only changes Zustand memory. It never calls a durable local edit or remote operation. The notes store persists only filters; there is no general subscription that saves these mutations. Reloading restores the old labels; no pending push tells the cloud about the change. The separate registry can then disagree with the labels on notes.

Evidence: `useLabelManagement.auditRegression.test.tsx`, AUD-E07, uses real IndexedDB: the UI says “After”, the stored note still says “Before”, and the pending queue is empty. Delete follows the same unpersisted path.

Fix: durably commit affected notes and their pending markers before changing the visible registry/library. Handle partial failure and retain the original owner throughout the batch.

### AUD-E08 — P1: native refresh restores a signed-out session or overwrites another account

Location: `composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/remote/SupabaseSessionStore.kt:36–46`.

`validAccessToken` captures a refresh token, suspends in `refresh`, then unconditionally calls `save(refreshed)`. Neither `clear` nor a new sign-in invalidates this continuation. A delayed A refresh after sign-out restores A, including its persisted credentials. If B signed in while it was pending, A replaces B. Separate volatile fields do not make this session transition atomic.

Evidence: The original isolated JVM harness reproduced both refresh failures. It has been replaced by `SupabaseSessionRefreshIsolationTest.kt`, which now verifies sign-out, account-switch and same-owner replacement safety.

Fix: use an atomic session snapshot/incarnation and conditional refresh completion, with persistence under the same serialization. Discard stale refresh results. Coalesce concurrent refresh requests and propagate cancellation.

### AUD-E09 — P2: interrupted native attachment encryption is never recovered

Location: `composeApp/src/jvmMain/kotlin/com/aus/notelikeus/data/attachments/AttachmentAtRestMigrator.kt:25–30, 101–117`.

The swap renames the source to `.pre-encrypt`, then renames `.encrypt-tmp` to the original name. A process death between the renames leaves no file at the path notes use. On the next startup both survivors are excluded from migration, so neither is recovered. The source bytes survive, but the attachment stays unavailable through the normal application path. Pending staging has the same missing-file shape.

Evidence: The original isolated JVM harness constructed the interrupted filesystem state and reproduced the missing path. `DesktopAttachmentCryptoTest.kt` now checks recovery and resealing for both attachment roots.

Fix: recover interrupted swaps before the normal scan, validate the candidate under the correct AAD, then atomically publish or restore the source. Cover both local and pending roots and every swap interruption point.

### AUD-E10 — P1: delayed local hydration displays A's private notes in B's session

Location: `web/src/lib/local/hydrateFromRemote.ts:24–43, 71–86`.

The loader reads an explicitly scoped namespace and then unconditionally publishes to the global store. Callers' `cancelled` checks happen only after the helper has already published. A slow A read can therefore replace B's library with A's plaintext. The helper also uses the current global tombstone set when deciding which rows in A's namespace to remove.

Evidence: `importOwner.auditRegression.test.ts`, AUD-E10, delays the read, switches to B and then observes A's private note displayed while auth still identifies B.

Fix: carry a session/incarnation predicate into the helper and validate it before filtering, cleanup and publication. Do not consult a different owner's global tombstones when operating on A's namespace.

### AUD-E11 — P2: clearing an existing note's last text is treated as “nothing to save”

Locations: `web/src/hooks/useNoteEditor.ts:111`; `composeApp/src/commonMain/kotlin/com/aus/notelikeus/ui/editor/EditorViewModel.kt:690–696`.

The empty-draft shortcut applies to existing notes as well as never-saved drafts. Clear the title/body of a text-only note: the screen becomes empty, but no replacement is persisted and the old text returns on reopening. Kotlin has the equivalent early return. An intentionally cleared existing record must be saved or explicitly deleted according to product policy.

Evidence: `useNoteEditor.auditRegression.test.tsx`, AUD-E11, verifies a blank displayed editor, no save call and the original stored content. Kotlin behavior is source-confirmed, not independently exercised in this audit.

Fix: skip empty notes only when they have never existed durably. Test clearing text, deleting the final checklist item/image and whitespace-only edits.

### AUD-E12 — P1: native account-deletion completion can erase the replacement account

Locations: `composeApp/src/desktopMain/kotlin/com/aus/notelikeus/platform/DesktopSyncManager.kt:111–125, 160–176`; Android equivalent `AndroidSyncManager.kt:116–130, 169–180`.

Both managers await deletion and then unconditionally sign out the live session and call `isolator.isolate()` on the live local dataset. The same cleanup runs for an NL003 response from an earlier sync. If B has replaced A while A's deletion/request is pending, A's success or refusal can erase B's current local notes and queued work. No captured owner/incarnation is checked at these cleanup sites. `WorkerAccountDeletionTransport` also performs its ambiguous-outcome probe through an ambient callback rather than an explicitly captured identity.

Evidence: confirmed source path through both managers and `AccountDeletionTransport.kt`, including DI wiring of `checkAccountLive`. The remediation adds two passing account/dataset-switch regressions in `DesktopSyncManagerAccountDeletionTest.kt`.

Fix: capture the deletion operation's owner and dataset incarnation at entry. Bind the transport/probe to that immutable identity, and refuse cleanup of any replacement incarnation. Test direct deletion and a late NL003 from a background sync.

### AUD-E13 — P1: Android AppFunctions bypass the account generation gate

Locations: `androidApp/src/main/kotlin/com/aus/notelikeus/appfunctions/NoteAppFunctions.kt:60–68, 132–148, 165–169`; unfenced repository overloads in `NoteRepositoryImpl.kt:264–294`.

The system-agent entry points suspend for the lock check and note reads, then call the legacy repository insert/update overloads. They never capture a `LocalCommitToken`. Isolation can finish between reading A's note and writing it; the old operation then writes/schedules in the current dataset. With colliding ids it replaces B's note fields, and create can insert an A-initiated payload into B even without a collision. The fenced overloads used by the editor are available but unused here.

Evidence: confirmed source trace from the AppFunction entry points to the legacy implementations. Existing AppFunction tests mock those unfenced overloads and do not exercise an account boundary. The remediation adds an isolation-during-lookup regression in `NoteAppFunctionsTest.kt`; all 18 AppFunction tests passed via the cached JVM toolchain.

Fix: capture a coherent local commit token at entry before suspension, use fenced repository APIs and fence reminder side effects. Reject stale reads/publication as well as writes. Test isolation during the settings check, lookup and mutation.

## Additional dependency maintenance item — AUD-D01 (P3)

The full web development dependency audit reports six affected package entries (two low, four moderate), representing three underlying advisories propagated through `vite-plugin-pwa@2.0.0 -> workbox-build@7.4.1`:

- `@rollup/plugin-node-resolve@16.0.3 -> deepmerge@4.3.1`: [prototype poisoning advisory](https://github.com/advisories/GHSA-gcqx-w48m-w2h4). The advisory lists no patched version.
- `glob@11.1.0`: [cancellation bypass advisory](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4), patched in 14.0.2.
- `glob -> minimatch@10.2.6`: [pattern amplification advisory](https://github.com/advisories/GHSA-g3hv-75wg-m249), patched in 11.0.1.

Verified with `npm audit --json`, `npm ls ... --all` and the advisory pages. These are build tooling dependencies; `npm audit --omit=dev` is clean. No path from note/backup content to an exploitable build input was established, so this is not counted as an additional application vulnerability. Do not blindly accept npm's suggested downgrade of `vite-plugin-pwa` to 1.1.0. Evaluate compatible upstream updates or targeted overrides, then verify the generated worker, precache manifest and production build.

Dependency follow-up (2026-11-06): scoped Workbox overrides select glob 14.0.2
and minimatch 11.0.1. A reproducible patch-package patch rejects `__proto__`
in deepmerge's target and source loops in both distributed bundles and source.
Clean installation, ten dependency security/compatibility regressions, all 1,307
web unit tests, production build, lint and desktop/mobile Chromium offline reload
checks passed. Production npm audit is clean. Full npm audit retains four low
package entries for the single locally patched deepmerge advisory; no moderate,
high or critical entries remain. See `web/patches/README.md` for removal criteria.

## Suggested repair order

1. E03/E04/E05/E06/E10: owner/incarnation binding across web saves, deletes, wipe, import and hydration. Include attachment GC and undo in the same authority review.
2. E08/E12/E13: native session refresh, deletion completion and AppFunction entry-point fences.
3. E01/E02: editor save sequencing and persist-before-cleanup behavior.
4. E07/E09/E11: durable label batches, interrupted attachment-swap recovery and clearing existing notes.

These are related groups, not permission to omit any finding. The independent regressions are retained alongside the fixes.

## Verification performed

- Full web test run: **1,307 tests in 172 files passed**, including configured coverage gates. Focused auth/attachment checks also passed after the final owner-binding changes.
- Final focused audit run: `cd web; npx vitest run auditRegression` — nine regressions across five files, converted to assert safe behavior. Additional cleanup retry tests cover the durable removal queue.
- Native regressions: session refresh, deletion transport/cleanup, interrupted migration and editor durability are compiled and executed with the cached Kotlin compiler/JUnit because Gradle cannot establish a loopback connection on this machine.
- Worker test run: **172 tests in seven files passed**.
- Web TypeScript and production build passed (build used the CI placeholder Supabase configuration).
- Native validation: 36 focused data/editor tests and 18 AppFunction tests passed through cached Kotlin/JUnit tooling. Changed native source passed Detekt. Full Gradle could not start because of the local loopback connection failure.
- Cloudflare security-header parity check passed.
- Twenty Node tests for bundle secret scanning, Worker deployment config and Kotlin staging-property generation passed.
- Published vulnerability checks: web production packages, the complete Worker dependency tree and root tooling reported zero known vulnerabilities. The complete web development tree reported six low/moderate package entries detailed in AUD-D01; no high/critical entries.

Passing suites do not invalidate the reproduced defects: their prior cases did not force the relevant asynchronous interleavings or durability failures.

## Coverage and limits

| Surface | Work performed | Remaining verification |
|---|---|---|
| Web authentication and destructive actions | Read auth listeners, session start/cleanup, account deletion and cloud wipe, owner-bound transport, reauthentication/UI action paths | Real multi-tab sign-in/refresh transitions against Auth |
| Web editing and local durability | Read editor hook, note actions, stores, IndexedDB notes/attachments, sealing/migrations, bootstrap/hydration, labels, ordering and import commit | Browser process death/storage-pressure experiments; all multi-tab permutations |
| Sync/conflict handling | Followed pending queue, OCC, snapshot paging, wipe generation, remote merge, restore/deletion/GC and conflict attachment paths; existing suites ran | Fresh database integration and real concurrent device sessions |
| Backup and hostile input | Read JSON and ZIP bounds, nesting checks, additive import and bundle staging/export; reviewed native streaming importer/codec and file readers | Low-memory Android and maximum-size library/backup profiling |
| Worker | Read routing, bearer authentication, authorization pipeline, limits, canonical object keys, claim-first delete, terminal upload behavior, sweeps, account deletion and CORS; all tests ran | Live R2/Auth failure injection, production bindings and deployment state |
| PostgreSQL | Reviewed latest paging, wipe/recent-auth, account-deletion, guard-schema, quota and attachment-lifecycle definitions and grants; inspected CI database gate | Fresh pgTAP run and two-session concurrency run unavailable: Docker daemon is not running locally |
| Native data/auth | Read account isolation/generation gate, session refresh/persistence, sync manager deletion/wipe, repository writes, attachment staging/encryption/migration, backup paths and editor persistence | Full Gradle/instrumented suites were not rerun; platform-specific crypto/device behavior remains outside the small JVM reproductions |
| Android external surfaces | Reviewed manifest exports/backup controls, external-share reader bounds, AppFunctions, widget lock handling, reminder receiver/scheduler | Emulator/device intent fuzzing, accessibility, reboot and notification permission flows |
| Desktop | Reviewed DPAPI/session/key handling, attachment path containment, loopback CAPTCHA and reminder scheduling | Windows Narrator/font scaling, crash recovery under real filesystem/DPAPI failures, multiple app instances |
| Hosting/release/dependencies | Reviewed CSP/SW message-origin controls and caching, selected release/deploy credential/commit gates, header parity and tooling tests, npm advisories | GitHub branch/environment protections, hosted project settings and shipped-artifact verification were not inspected |
| General UI/search/accessibility | Existing web suite executed; representative hooks, text/link safety and notes/labels flows inspected | No fresh visual, screen-reader, locale/DST matrix or performance profiling; not every React/Compose component received line-by-line review |

No live account, production data or cloud deployment was modified. Production source fixes are local and uncommitted. The untracked user `AGENTS.md` was left untouched. Audit artifacts and regression tests are uncommitted.
