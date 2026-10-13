# Notelikeus — Production Audit Report

| | |
|---|---|
| Commit audited | `0551a5b6710a23c87dbfd78c8a96920a1d0b59b2` (`main`, 2026-10-12 01:09 +0200, "chore: ignore Kilo's local .kilo/ folder") |
| Audit date | 2026-10-12 |
| Method | Source reading of the actual implementation, plus execution of the repository's own test, type-check and lint commands. No production code was changed. |
| Status | **NO-GO** for a release that claims reliable cross-device sync, until F-01, F-02, F-05 and F-06 are fixed (see §11). |

---

## 1. Executive summary

Notelikeus is unusually defensive code. The attachment Worker, the SQL RPC layer and the Kotlin sync engine are heavily fenced: generation tokens, delete claims, terminal-delete gates, and owner-derived object keys. The test suites that run are green. On the server and Worker side I found **no cross-account access path**.

The real risks are in places the fencing does not reach:

1. **The Kotlin client cannot restore a deleted note to the cloud (F-01).** Since commit `4a0ee3bb` (2026-10-06), Android and Windows send a 15th argument, `p_attachment_ids`, to `restore_note`. The only `restore_note` signature has 14 arguments, so PostgREST cannot resolve the call. The note is restored locally only. It never syncs again and its restore marker retries forever.
2. **The web client has no persistent outbox, and its fallback merge is last-writer-wins on server timestamps (F-06).** If a user edits a note offline in the browser and another device edits the same note meanwhile, the browser edit is overwritten when it reconnects, with no conflict copy.
3. **Web IndexedDB read-modify-write races (F-02, F-03, F-04).** The legacy-plaintext sealing migration writes back a stale snapshot. Revision metadata is updated across 3–4 separate transactions. The pull cursor is advanced to the client's own push revision, which can skip other devices' changes.
4. **The Android backup import loads the whole archive and all of its decompressed entries onto the heap (F-07).** For JSON files, it decodes up to 256 MB into a `String` before checking its size. An `OutOfMemoryError` is not caught by the import path.

Strengths: owner-derived R2 keys that the Worker re-derives and never takes from the database; a claim-first delete protocol with sweeper recovery; per-owner advisory-lock quota serialization; an atomic web key claim; generation-fenced Kotlin revision state; and quarantine (never delete) on undecryptable databases and attachments.

## 2. Audit scope

**Inspected (source read):**
- `workers/attachments/src/*.ts`: the whole Worker, including sweep, auth, limits and object key.
- `supabase/migrations/*.sql`: latest definitions of `finalize_note_attachment_put`, `authorize_note_attachment_put`, `apply_note_change`, `restore_note` (all overloads), `attachment_delete_is_terminal`, and the RLS policy inventory.
- Web: `lib/crypto/*`, `lib/local/notesLocalRepository.ts`, `lib/local/notesSealing.ts`, `lib/supabase/{revisionStore,supabaseSyncEngine,supabaseRemoteNotesDataSource}.ts`, `lib/notes/{remoteMerge,noteActions,notesSyncService,tombstones}.ts`.
- Kotlin: `SupabaseNoteTransport.kt`, `NoteSyncEngine.kt` (restore, tombstone-merge and GC paths), `RevisionStateStore.kt`, `NoteRepositoryImpl.kt` (restore), `AttachmentAtRestMigrator.kt`, `PlaintextDatabaseMigrator.kt`, `BackupBundleTransfer.kt`, `BackupBundleCodec.kt`, `BackupBundleLimits.kt`.
- Android: `BackupDocumentIo.kt`, `MainActivity.kt` (import/export launchers), `AndroidManifest.xml`.

**Not inspected in depth (explicit limitation):** Compose UI and ViewModels beyond the restore and backup paths; widgets; AppFunctions; reminders and DST handling; the desktop window lifecycle; the service worker and CSP; React components and accessibility; the web backup importer; desktop DPAPI key storage; Android Keystore and SQLCipher key derivation; CI workflows; and the dependency tree. These areas are not cleared. They were not examined. Phases 6, 7, 9 and 12 of the brief are therefore **only partly covered**. No UX, accessibility, localization or performance profiling was done, and none is claimed.

**Previous reports** (`docs/AUDIT_2026.md`, `docs/AUDIT_DEEPSEEK_2026-09-20.md`, `docs/FINDINGS.md`) were not used as evidence. Every claim below was checked against the current source.

## 3. Architecture (as implemented)

```
 Android app ─┐                         ┌─ Supabase Postgres (RLS + SECURITY INVOKER RPCs)
 (Room+SQLCipher, Keystore)             │    notes / note_tombstones / note_attachments
 Windows app ─┼── composeApp (KMP) ─────┤    sync_revision_seq (GLOBAL sequence)
 (Room+JDBC, DPAPI)  NoteSyncEngine     │    apply_note_change / apply_note_delete / restore_note
              │  SupabaseNoteTransport  │    pull_changes / fetch_full_snapshot
              │  R2AttachmentBlobTransport ── /v2/attachments (deferred: provisional row)
 Web (React) ─┴── IndexedDB (AES-GCM sealed bodies, key in separate IDB db)
                  supabase-js RPC + Realtime ── /v1/attachments (legacy: committed at upload)
                                        │
                                        └─ Cloudflare Worker ── R2 bucket
                                             PUT: precheck → body → authorize → R2 put → finalize
                                             DELETE: claim → R2 delete → finalize (sweep recovers)
                                             cron: orphan sweep + unconfirmed-delete sweep (service_role)
```

- **State ownership.** Each device's local database is authoritative for unsynced edits. Postgres is authoritative for revisions, which are optimistic concurrency control drawn from one global sequence. R2 holds the bytes, with object keys derived from `(owner, note, attachment)`.
- **Authorization boundary.** The Worker authenticates the bearer token and then calls the RPCs *with the caller's token*. The RPCs are `SECURITY INVOKER` and use `auth.uid()`. Only the sweep uses `service_role`, and it re-derives the key from the owner before deleting.
- **Encryption boundary.** Data is encrypted at rest on the device only. Cloud rows and R2 objects are plaintext to the backend. This is a confidentiality-against-device-theft control, not end-to-end encryption.
- **Conflict resolution.** The server enforces optimistic concurrency with `p_base_revision`. On conflict, Kotlin re-learns the revision. The web client throws and relies on `syncNotesWithCloud`, which applies last-writer-wins on `serverUpdatedAt` (F-06).
- **Attachments.** Kotlin uses the deferred protocol: the row stays provisional until a note commit names it. The web client uses v1, where the row is committed at upload time. Both coexist.

## 4. Findings register

Severity: P0 Critical / P1 High / P2 Medium / P3 Low. Confidence: **Confirmed** means a source-level proof of the executable path; **Probable** means the path exists but needs runtime confirmation of one step.

### F-01 — Kotlin `restore_note` call can never resolve; restored notes never reach the cloud
- **Severity:** P1 · **Confidence:** Confirmed at source level (runtime confirmation of the PostgREST response recommended) · **Platforms:** Android, Windows
- **Location:** `composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/remote/SupabaseNoteTransport.kt:330` passes `note.toRpcArgs(...)`, which at `:384-397` always adds `p_attachment_ids`. Every `restore_note` definition has exactly 14 parameters, ending at `p_checklist`: the latest is `supabase/migrations/20260908120000_attachment_delete_claim.sql:361`. Only `apply_note_change` gained a 15-argument overload (`20260910000000_attachment_commitment.sql:248`).
- **Root cause:** One argument builder is shared by two RPCs whose signatures diverged in R19.2 (commit `4a0ee3bb`). PostgREST picks an overload by the set of named arguments, so an extra key matches no function. It answers 404/PGRST202, which `DesktopSupabaseRpcClient.send` and the Android client turn into `SupabaseTransportException`.
- **Effect:**
  - In `NoteSyncEngine.restoreNoteWithToken` (`NoteSyncEngine.kt:523`) the exception fails the restore.
  - In `mergeCloudTombstones` (`:1508`) the failure is swallowed. The restore marker stays set, so the tombstone is suppressed locally, but the cloud keeps the tombstone.
  - Every later `apply_note_change` for that note returns `conflict / note_deleted` (`20260910000000_attachment_commitment.sql:356-362`).
  - The note therefore exists on this device only, permanently. Uninstalling, losing the device, or clearing data loses it, while the UI showed a successful restore.
- **Preconditions:** A Kotlin client restores (undoes) a note whose delete already reached the cloud.
- **Reproduce:** Run against local Supabase. On desktop, create a note, sync, delete it and let the delete sync, restore it, then sync. Inspect the HTTP 404 with `PGRST202`; `public.notes` has no row and `note_tombstones` still has it.
- **Expected:** The note is live in the cloud again. **Actual:** It stays tombstoned in the cloud and lives on this device only.
- **Fix:** Build `restore_note` arguments without `p_attachment_ids`. Do not add a 15-argument `restore_note` unless restore also needs to promote provisional rows. If it does, add the overload in a new migration and keep the 14-argument one.
- **Regression tests:** (1) A contract test asserting that the argument key set for each RPC equals the parameter set of a deployed overload. Parse the migrations, or run against `supabase start`. (2) Update `contracts/cloud/note-rpc-args.json`, which is stale: it says "both clients send" 14 keys.
- **Complexity:** S

### F-02 (NLU-01) — Web legacy-plaintext sealing migration overwrites newer edits and resurrects deleted or cleared notes
- **Status (branch `fix/audit-sync-findings`):** Fixed. `web/src/lib/local/legacySealingRace.test.ts` holds the migration's `encrypt` open while a newer save, a delete or an account clear lands. All three failed against the pre-fix code; they pass now. The migration write is a compare-and-set inside its own transaction.
- **Severity:** P1 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/lib/local/notesLocalRepository.ts:106-111` fires `void maybeMigrateStoredNote(ownerId, record.note, write)`. `web/src/lib/local/notesSealing.ts:171-186` awaits the key and then `crypto.subtle.encrypt`, and calls `write`, which is a blind `store.put`, with the snapshot read earlier.
- **Root cause:** A read-modify-write spans an unbounded async gap with no compare-and-set. The write does not re-read the row inside its own `readwrite` transaction.
- **Failure sequences**, each starting from a legacy plaintext row:
  - (a) `listStoredNotes` reads v1, the user saves v2 (`putNote`), then the migration puts sealed v1. **Edit reverted.**
  - (b) The read happens, then `deleteNote` runs, then the migration put lands. **Deleted note resurrected locally.** The cloud copy is gone, so the delete-on-absence logic may later act on it.
  - (c) The read happens, then the user signs out and `clearOwner` runs, then the put lands. **The previous account's note body stays in IndexedDB** under the old owner id after sign-out. This is a privacy and isolation failure.
  - (d) `applyRemoteSnapshotAtomically` replaces the owner's notes, then the stale put lands. **A note the server deleted reappears.**
- **Preconditions:** A row written before body sealing shipped, and a concurrent write in the same tab or another tab. The migration runs on every `listStoredNotes` while the row is still plaintext.
- **Fix:** Do the migration inside a single `readwrite` transaction: seal first, then open the transaction, `get` the row, and `put` only if the stored payload still has no `sealedBody` and still equals the snapshot (for example, the same `timestamp`). Otherwise skip. Never write after `clearOwner`.
- **Regression test:** With fake-indexeddb, delay the crypto promise behind a deferred, run `putNote` / `deleteNote` / `clearOwner` while it is pending, then resolve it. Assert the row equals the newer write, is absent, or is absent, respectively.
- **Complexity:** S

### F-03 (NLU-04) — Web revision metadata is updated with non-atomic, multi-transaction read-modify-write
- **Status (branch `fix/audit-sync-findings`):** Fixed. Every revision-state write is now one transaction. Pull pages and snapshots merge per note and never lower a revision. A snapshot keeps any revision learned after it was read. Tests: `web/src/lib/supabase/revisionStateIntegrity.test.ts`, which failed against the pre-fix code.
- **Severity:** P1 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/lib/supabase/revisionStore.ts:25-64`. `rememberNoteRevision` reads (transaction 1), then `saveRevisionState` reads (transaction 2), then `setOwnerMeta` reads (`notesLocalRepository.ts:361`, transaction 3) and puts (transaction 4). `pullIncrementalChanges` (`supabaseSyncEngine.ts:155, 170, 211-217`) writes a `noteRevisions` map computed from state loaded *before* its network round trip.
- **Root cause:** Every writer replaces the whole `noteRevisions` map and `knownCloudIds` from its own stale snapshot. Concurrent writers occur within one tab (async push of several notes, push overlapping a realtime pull, a reconcile) and across tabs.
- **Effect:** Lost per-note base revisions and lost `knownCloudIds` entries. Consequences: spurious conflicts; silent remote-delete no-ops through F-05; and `knownCloudIds` regressions that weaken the "unexplained missing" mass-delete guard. The cursor itself is protected by `Math.max` in `saveRevisionState`, but `applyRemotePageAtomically` takes the page's `noteRevisions` wholesale.
- **Fix:** Add one `updateOwnerMeta(ownerId, (meta) => meta')` helper that does `get` and `put` inside one `readwrite` transaction, and merge per key (`noteRevisions[id] = max(old, new)`, explicit deletions) instead of replacing whole maps. In `applyRemotePageAtomically`, merge the page's revisions into the meta read inside its own transaction.
- **Regression test:** Two concurrent `rememberNoteRevision` calls for different notes must both persist. A pull page applied while a push records a revision must keep the push's revision when it is higher.
- **Complexity:** M

### F-04 — Web pull cursor jumps to the client's own write revision, skipping other devices' changes
- **Status (branch `fix/audit-sync-findings`):** Fixed. The cursor now moves only with pulls and snapshots. The reproduction showed the effect is worse than "skipped": once the cursor had jumped, `applyRemotePageAtomically` discarded the whole next pull page as stale. `supabaseDeleteNote.test.ts` had pinned the old cursor bump and was updated.
- **Severity:** P2 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/lib/supabase/revisionStore.ts:49-56` sets `lastRemoteRevision = max(cursor, revision)` after every successful `apply_note_change` (`supabaseSyncEngine.ts:143`). Deletes do the same explicitly (`supabaseRemoteNotesDataSource.ts:301-308`).
- **Root cause:** `sync_revision_seq` is one global sequence. Suppose the cursor is C, another device commits R, and this tab then pushes and receives P, with C < R < P. The cursor becomes P, and `pull_changes(p_after_revision = P)` never returns R.
- **Effect:** Another device's edit, new note or tombstone is missing in the tab until a full snapshot reconcile, which runs only on visibility/online events and is throttled to 30 s / 15 s windows. An edit made in the meantime on stale content conflicts and then goes through F-06.
- **Fix:** Advance the cursor only from `pull_changes` and snapshot results, never from the revisions of the client's own writes. Record the write's revision in `noteRevisions` only.
- **Regression test:** Fake RPC: cursor 10; push returns 12; `pull_changes(after = 10)` contains 11. Assert that 11 is applied.
- **Complexity:** S

### F-05 — Web remote delete silently does nothing when no base revision is known
- **Status:** Not changed; **design decision pending**. This behaviour is deliberate and tested, under rule DEL-6 (`supabaseDeleteRevisionAuthority.test.ts`, `supabaseDeleteParity.test.ts`): a delete must never adopt a revision from the server, and with no baseline the cloud copy wins at the next snapshot. The F-03 fix removes the main way a synced note lost its baseline. What remains is notes whose revision was already lost before this fix.
- **Severity:** P2 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/lib/supabase/supabaseRemoteNotesDataSource.ts:257-262` returns success after only marking the note deleted locally when `baseRevision == null`.
- **Effect:** The note is hidden on this browser, still live in the cloud and on every other device, and nothing reports the divergence. A null baseline happens after F-03 loses the revision, or for notes whose revision was never learned in this profile.
- **Fix:** On a null baseline, look the revision up (`lookup_note_revision` exists) and delete against it, or keep a persisted pending-delete that the next snapshot completes. Do not report success.
- **Regression test:** Delete with an empty revision map, then snapshot. Assert that `apply_note_delete` is issued and the cloud row is gone.
- **Complexity:** S

### F-06 — Web offline edit is discarded when the note changed elsewhere (no outbox, last-writer-wins on server time)
- **Status (branch `fix/audit-sync-findings`):** Fixed, with reproduction tests in `web/src/lib/notes/offlineEditDurability.test.ts`. Reproducing it showed the defect is **wider than first written**: no second device is needed. `loadBaseline` replaces every IndexedDB row the snapshot contains, and `applyRemotePageAtomically` overwrites rows on every pull. So an offline edit was reverted on the next page load whenever the cloud still had the older version.
- **Severity:** P1 · **Confidence:** Confirmed by a failing test against the pre-fix code · **Platform:** Web
- **Location:**
  - `web/src/lib/notes/noteActions.ts:90-95`: a failed push is swallowed with the comment "retry on reconciliation".
  - `web/src/lib/notes/remoteMerge.ts:8-24`: `shouldUploadOverRemote` compares `serverUpdatedAt` values.
  - `web/src/hooks/useNoteEditor.ts:148-149, 234-235`: local edits keep the old `serverUpdatedAt`.
  - `web/src/lib/supabase/supabaseRemoteNotesDataSource.ts:417-435`: a conflict is caught and "keep merged remote winner".
- **Sequence:** The note was synced at S1. The browser goes offline and the user edits it; the local copy still has `serverUpdatedAt = S1`. Android edits the same note, giving S2 > S1. On reconnect, `reconcileNow` sees remote S2 > local S1, keeps the remote copy, and `applyNotes` writes it over the local edit in IndexedDB. **The browser edit is gone, with no copy and no prompt.** This is true even when the browser edit is newer in wall-clock time.
- **Fix (smallest safe change):** Persist a dirty flag (or a local edit counter) per note. When a dirty note loses to a remote change, save the losing local version as a new "conflicted copy" note instead of dropping it. A full outbox is the longer-term fix.
- **Regression test:** Local note `{serverUpdatedAt: S1, dirty}`, remote `{S2}` with different content. After `syncNotesWithCloud`, both contents exist.
- **Complexity:** M

### F-07 (NLU-05) — Android backup import can exhaust the heap and crash
- **Status (branch `fix/audit-sync-findings`):** Import fixed on Android and desktop. The desktop picker had the same defect: an uncapped `file.readBytes()`. `BundleImportHeapTest` imports a 144 MB bundle in a child JVM capped at `-Xmx96m`. Against the pre-fix code it fails with `OutOfMemoryError`; it now passes. Export is fixed too: it streams into the destination the user picked, one image at a time, and `BundleExportHeapTest` exports 144 MB of images under `-Xmx96m` (it failed with `OutOfMemoryError` before).
- **Severity:** P1 (restore availability) · **Confidence:** Confirmed at source level (peak heap needs runtime measurement) · **Platform:** Android (the bundle codec is shared with desktop)
- **Location:**
  - `androidApp/src/main/kotlin/com/aus/notelikeus/BackupDocumentIo.kt:62-80` buffers up to 256 MiB in a `ByteArrayOutputStream`. Growth by doubling plus `toByteArray()` means roughly 2–3× transient.
  - `MainActivity.kt:171-187`: for a non-bundle file it calls `bytes.toString(Charsets.UTF_8)`, up to 256 MiB of bytes becoming a UTF-16 `String` of up to 512 MiB, **before** the 10 M-character check. The bounded reader `BackupDocumentIo.read` (`:43`) has no production caller.
  - `composeApp/src/jvmMain/.../BackupBundleCodec.kt:141-146, 296-338` decompresses **every** entry into memory, up to 512 MiB total, while the 256 MiB archive array is still alive.
  - `BackupBundleTransfer.importBundle` (`:197-204`) catches `Exception`, not `OutOfMemoryError`.
- **Effect:** A valid bundle of around 150–250 MB, which needs only 15–25 notes with 10 images of 1 MB, exceeds a typical 256–512 MB app heap (no `largeHeap` in the manifest). The app crashes on restore, so a user cannot restore their own backup on the device that needs it. Choosing a large non-backup file also crashes. Export (`exportBundle`, `:143-191`) likewise holds every attachment plus the whole zip in memory (P2).
- **Fix:** Stream the import. Copy the URI to a temp file in the cache dir with the 256 MiB cap, open it with `java.util.zip.ZipFile` for random access, parse `manifest.json` first, then read each `media/` entry one at a time straight into `staging.stage`. For JSON, use the existing bounded `BackupDocumentIo.read`. Catch `OutOfMemoryError` at the launcher boundary and report `ImportFailed`. The bundle format does not change, so backward compatibility is unaffected.
- **Regression test:** A JVM test that builds a 200 MB stored bundle and imports it under `-Xmx256m`. An instrumented test with a 300 MB non-bundle file, expecting `ImportFailed` and no crash.
- **Complexity:** M

### F-08 (NLU-02) — Worker deletes the R2 object when finalization commits but its response is lost
- **Status (branch `fix/audit-sync-findings`):** Fixed in the Worker source. It takes effect only after the Worker is redeployed. A failed finalization no longer triggers compensation unless the answer is definitive (a refusal, or `terminally_deleted`). The new test "keeps the bytes when finalization committed but its answer was lost" failed against the pre-fix code. Two tests that encoded "a 5xx means nothing committed" were updated. Residual: an upload abandoned after an ambiguous failure leaves bytes with no row. These are reported, not deleted: the Worker cron logs how many R2 objects older than a week have no row, and their total size (`find_unreferenced_attachment_objects`, migration `20261012010000`). A deleting sweep would have to keep a late retry of the same upload working, and the clients do not handle the Worker's 409, so it was deferred until the report shows the leak is worth it.
- **Severity:** P2 · **Confidence:** Confirmed that the deletion happens; partially mitigated · **Platform:** Worker (affects all clients)
- **Location:** `workers/attachments/src/index.ts:152-167`: the 10 s `AbortSignal.timeout`, or a lost body, throws `UpstreamServiceError`. `:337-340`: `catch { await compensate('upstream finalization error') }`, which deletes `objectKey` whenever `preflight.already_live !== true` (`:285-293`).
- **Sequence:** A fresh upload writes to R2. `finalize_note_attachment_put` commits (`20260910000000_attachment_commitment.sql:185-217`). The response exceeds 10 s or the stream drops. The Worker deletes the object and returns 503.
- **Mitigation found:** On a client retry, `authorize_note_attachment_put` returns `already_live: true` for the now-existing row, including provisional rows (`20260908130000_attachment_delete_is_terminal.sql:415-425`). The Worker's `head()` then finds no object and re-uploads it (`index.ts:266-278`). So the damage self-repairs **if and when the same client retries with the same bytes**.
- **Residual risk:** For a web (v1) upload the row is *committed* immediately. Until the retry, other devices hydrate an attachment whose GET returns 404. If the originating device never retries (pending bytes lost, profile cleared, app uninstalled), the committed row points at nothing permanently.
- **Fix:** On an *ambiguous* finalization failure, do not compensate. Leave the bytes. A missing row with bytes present is recoverable by a sweep that lists R2 prefixes against metadata. A present row with missing bytes is not recoverable. Keep compensation only for definitive answers: a `403` refusal or `terminally_deleted`.
- **Regression test:** In the Worker suite, have the finalize mock commit and then abort the response. Assert that `ATTACHMENTS_BUCKET.delete` is not called.
- **Complexity:** S

### F-09 — Provisional attachment rows are never reclaimed and permanently consume quota
- **Status (branch `fix/audit-sync-findings`):** Fixed in source; needs the migration applied and the Worker redeployed. New `claim_orphaned_provisional_attachments` (migration `20261012000000`, service-role only), run by the Worker's cron. It claims a provisional row only when the note was committed again after the upload without naming it, and that commit is at least 14 days old. A row still waiting for its note commit is never touched, and rows of deleted notes already go through the orphan sweep. A claimed row is shaped exactly like an owner DELETE, so the existing unconfirmed-delete pass removes the bytes. Worker tests run and pass; the pgTAP test `notelikeus_provisional_attachment_sweep.test.sql` was **not run** here (no Docker daemon).
- **Severity:** P2 · **Confidence:** Probable · **Platforms:** Server, Kotlin clients
- **Location:** `20260910000000_attachment_commitment.sql`. `committed_at IS NULL` is read only by `list_user_attachments`; no sweep or RPC removes provisional rows (grep across `supabase/`, `workers/`, `web/src`, `composeApp/src/commonMain`). The quota checks (`:166-183`) count every `deleted_at IS NULL` row, provisional rows included.
- **Effect:** Each abandoned deferred upload (a stale continuation, which the migration header describes as an expected case) permanently consumes up to 10 MB of the 200 MB quota and one of the 20 slots per note, and its R2 bytes leak. Over time users hit "quota exceeded" with no visible attachments.
- **Fix:** Add a sweep pass for provisional rows older than N days (for example 7) that claims, deletes the object, and marks the row deleted, reusing the purge-claim protocol.
- **Complexity:** M

### F-10 — Android plaintext→SQLCipher migration quarantines a healthy database on a transient export failure
- **Status (branch `fix/audit-sync-findings`):** Fixed on Android and desktop. Desktop had the same flaw in three places. A readable plaintext database that cannot be encrypted is kept and opened as plaintext for that launch; the migration retries on the next launch. Only a file that opens neither way is quarantined. Android now proves the encrypted copy opens *before* the swap deletes the original. Interrupted-swap recovery now re-migrates the restored backup instead of handing a plaintext file to an encrypted open. Desktop tests: `DesktopEncryptionFailureRetentionTest` (run, passing). Android: `PlaintextDatabaseMigratorTest.aFailedExportKeepsThePlaintextDatabaseInPlace` (compiles; needs a device).
- **Severity:** P2 · **Confidence:** Probable · **Platform:** Android
- **Location:** `composeApp/src/androidMain/.../PlaintextDatabaseMigrator.kt`. Any exception from `sqlcipher_export`, such as low disk space, results in `quarantineDatabaseFiles` and Room then creates an empty database.
- **Effect:** The user's notes vanish from the app. The bytes still exist under a quarantined name, but nothing in the UI recovers them. For signed-in users the cloud re-hydrates, but guest/offline users effectively lose everything.
- **Fix:** On an export failure where the source still opens as plaintext, keep using it and retry the migration on the next launch, instead of quarantining.
- **Complexity:** S

### F-11 — A transient key-store failure on web disables all saves for the session
- **Status (branch `fix/audit-sync-findings`):** Fixed for both the notes key and the attachments key. A failed load is no longer remembered; concurrent callers still share a single load. `web/src/lib/crypto/cryptoKeyRetry.test.ts` failed for both keys against the pre-fix code.
- **Severity:** P3 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/lib/crypto/notesCryptoKey.ts:107-130` memoizes the promise, including a `null` result from `openKeyDb` `onerror`/`onblocked` or from an aborted claim transaction. Every later `sealNoteForStorage` then throws `missing_key` until the page reloads.
- **Fix:** Do not cache a `null` result; cache only a resolved `CryptoKey`.
- **Complexity:** XS

### F-12 — Cross-client RPC contract coverage gap
- **Status (branch `fix/audit-sync-findings`):** Fixed. Both clients now check the argument sets they send against the signatures the migrations leave behind: `RpcSignatureContractTest.kt` (added with F-01) and `web/src/lib/contract/rpcSignatureContract.test.ts`, which was confirmed to fail when a bogus argument is injected. The stale fixture description and the contracts README are corrected.
- **Severity:** P3 · **Confidence:** Confirmed · **Area:** Tests
- **Detail:** `contracts/cloud/note-rpc-args.json` describes 14 keys that "both clients send". Kotlin now sends 15. The Kotlin restore tests (`NoteSyncEngineRemoteIdentityF1Test.kt:123`) use a fake that answers `restore_note` for any body, so F-01 is invisible to every suite that runs.
- **Fix:** See F-01's regression test.

## 5. Phase 10 — Verification of earlier hypotheses

| ID | Verdict | Evidence |
|---|---|---|
| NLU-01 (encryption migration overwrites / resurrects) | **CONFIRMED (web).** Kotlin `AttachmentAtRestMigrator` works on files with atomic rename and its window is a cold-start race only (not reproduced). | F-02 |
| NLU-02 (finalize timeout deletes committed object) | **PARTIALLY CONFIRMED.** The deletion happens; it self-repairs only on a same-client retry. | F-08 |
| NLU-03 (tabs mint different keys) | **FIXED.** `claimKeySlot` reads and writes in one `readwrite` transaction (`notesCryptoKey.ts:68-105`; same in `attachmentCryptoKey.ts`). Residual: F-11. | — |
| NLU-04 (non-atomic revision metadata) | **CONFIRMED (web)**, plus a new cursor-skip variant (F-04). **FALSE POSITIVE for Kotlin**: `RevisionStateStore` is in memory, `Mutex`-guarded and epoch-checked (`RevisionStateStore.kt:27-105`). | F-03, F-04 |
| NLU-05 (Android import OOM) | **CONFIRMED** at source level; peak heap **REQUIRES RUNTIME VERIFICATION**. | F-07 |

## 6. Data-loss risk assessment

| Path | Kind | Finding |
|---|---|---|
| Kotlin restore of a cloud-deleted note | Note lives on one device forever; lost with the device | F-01 |
| Web offline edit plus a remote edit | Local edit silently overwritten | F-06 |
| Web legacy row migration racing an edit or delete | Reverted edit, resurrected note, previous account's data kept after sign-out | F-02 |
| Web delete with lost baseline | Delete never reaches the cloud; note reappears elsewhere | F-03 → F-05 |
| Web cursor skip | Other devices' changes invisible until a full reconcile | F-04 |
| Android backup restore of a large archive | Restore crashes; backup unusable on device | F-07 |
| Finalize timeout | Committed attachment row with no bytes until a retry | F-08 |
| Android encryption migration failure | Library hidden (quarantined) | F-10 |

**Not found:** cross-account data exposure on the server or Worker. Object keys are re-derived from the authenticated user id in the Worker (`index.ts:66`) and checked against every RPC answer. All RPCs inspected use `auth.uid()` under `SECURITY INVOKER`. Service-role RPCs are revoked from `authenticated`.

## 7. Security assessment (inspected scope)

- **Authorization:** RLS is enabled on `notes`, `note_tombstones`, `sync_meta`, `note_attachments` and `firebase_uid_mappings`, with `*_own` policies. Writes are additionally gated by the `sync_mutation_guard` transaction flag, which only RPCs set. Grants are revoked from `PUBLIC` and `anon` for every RPC inspected. The one `SECURITY DEFINER` function (`20250904010000_verified_firebase_uid_link.sql:73`) was not reviewed line by line. **Unverified.**
- **Worker:** It rate-limits before authentication; validates MIME type and declared size before reading the body; enforces size on the actual bytes; serves GET responses with `Content-Disposition: attachment`, `nosniff` and `no-store`; and never trusts an object key returned by the database. No public bucket path was found.
- **At-rest encryption:** Web bodies are sealed with AES-GCM, using AAD bound to `(owner, noteId)` and a non-extractable key. This does not protect against XSS, as the code comments state. Cloud data is not end-to-end encrypted. The privacy policy and store listing should not imply otherwise (not checked).
- **Privacy:** F-02(c) can leave the previous account's note bodies in IndexedDB after sign-out.

## 8. Test execution (commands actually run)

| Command | Result |
|---|---|
| `web: npx vitest run` | **107 files, 801 tests passed** (exit 0) |
| `web: npx tsc -b --noEmit` | exit 0 |
| `web: npx oxlint` | exit 0 (warnings only: `no-array-sort`, `consistent-function-scoping`) |
| `workers/attachments: npx vitest run` | **6 files, 128 tests passed** (exit 0) |
| `workers/attachments: npx tsc --noEmit` | exit 0 |
| `gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest` | See §8.1 |

**Not run:** Playwright E2E (needs local Supabase and a browser build); `supabase test db` (needs Docker/Supabase CLI); Android Lint; instrumented tests (need an emulator); release compilation; dependency or CVE scans. **None of these is claimed as passing.**

### 8.1 Kotlin tests
- **`:composeApp:desktopTest`**: Gradle marked it `UP-TO-DATE` because its inputs were unchanged, so **it was not re-executed during this audit**. The cached results in `composeApp/build/test-results/desktopTest` (latest timestamp 2026-10-11T23:58Z, same sources) show 751 tests, 0 failures, 0 errors. That is earlier-run evidence, not a run observed here.
- **`:composeApp:testDebugUnitTest`**: **did not run.** The Gradle test executor JVM exits at launch on this machine. The first attempt logged `Could not find or load main class ;C:\Users\...\Python310;...`, meaning a malformed JVM argument taken from the environment. Later attempts logged `The pipe is being closed`. This is an environment problem; it is not attributed to the source. Re-run on CI or a clean shell before release.
- Earlier attempts failed earlier still, with `Unable to establish loopback connection` (sandbox) and a wrong `JAVA_HOME`. None produced test results.

## 9. Performance (evidence-based only)

- **Backup import/export memory** (F-07): peak heap is O(archive + all entries). Measure with Android Studio's memory profiler while importing 50, 150 and 250 MB bundles.
- **Web `listStoredNotes`** decrypts rows sequentially (`notesLocalRepository.ts:93-104`). Cost is linear in note count; measure cold load at 1k, 5k and 10k notes. Not profiled here.
- No other performance claims are made. Startup, recomposition and rendering were not measured.

## 10. Remediation roadmap

Order follows user impact, then dependencies, then risk:

1. **F-01** (S): stop sending `p_attachment_ids` to `restore_note`, and add the RPC-signature contract test first. Rollback: revert one function. No data migration; queued restore markers will then succeed on their own.
2. **F-06** (M): persist a dirty marker and keep a conflict copy. Additive IndexedDB field, so old rows read as clean.
3. **F-02** (S): make the migration a transactional compare-and-set. Test first with deferred crypto.
4. **F-03 → F-05 → F-04** (M/S/S): one transactional `updateOwnerMeta` first (F-05 and F-04 rely on stable revision state), then the null-baseline delete, then the cursor rule. Existing meta rows stay compatible.
5. **F-07** (M): streaming import from a temp file with `ZipFile`. Format unchanged.
6. **F-08** (S): do not compensate on ambiguous finalization failures. Add a byte-orphan sweep later.
7. **F-09** (M): sweep provisional rows. New migration plus a Worker sweep pass. Ship behind a conservative age threshold.
8. **F-10, F-11, F-12** (S/XS/S).

Each fix should land with its regression test in the same change, and the test should be seen failing on the current code first.

## 11. Release readiness

**NO-GO** for a release that promises reliable multi-device sync and restorable backups. F-01 and F-06 lose or strand user data in ordinary use (undoing a delete; editing in a browser while offline). F-07 makes large backups unrestorable on Android.

**Conditional GO** after F-01, F-02, F-06 and F-07 are fixed and verified, with the remaining P2 items scheduled. Server-side isolation and the Worker are in good shape and do not block release on the evidence collected.

---

## 12. Follow-up audit — areas the first pass did not cover (2026-10-12)

Scope: the deployed `SECURITY DEFINER` surface; Android exported components (AppFunctions, share intents, widget receiver); web XSS sinks, link handling, CSP, service-worker caching and token storage; desktop key storage; reminders and DST; dependency vulnerabilities; and accessibility, via source heuristics plus the existing e2e coverage. Audit only: no product code was changed for this section.

### Findings

#### FU-1 — Signing a different account into a browser wipes the previous account's unsynced edits, without warning
- **Status (branch `fix/audit-sync-findings`):** Fixed. The switch keeps the previous account's namespace while it has pending pushes, and the sign-out dialog says how many notes have edits not uploaded yet (`bootstrap.test.ts`, `SignOutDialog.test.tsx`).
- **Severity:** P2 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/hooks/useNotesSync.ts:38-41` calls `clearLocalUserDataForAccountSwitch(lastMerged)` whenever the signed-in uid differs from the last account used in that browser. `web/src/lib/bootstrap.ts:196-207` then runs `clearOwner(previousOwnerId)`, which deletes that owner's note rows **and** its meta row, including `pendingPushes` (F-06).
- **Sequence:** account A edits offline, then signs out. Signing out keeps A's IndexedDB rows, so A's edits are still safe at this point. Account B then signs in on the same browser profile, and A's unsynced edits are deleted. `SignOutDialog` gives no indication that unsynced edits exist.
- **Fix:** when `listPendingPushes(uid)` is non-empty at sign-out, say so in `SignOutDialog` (N edits not yet uploaded; they upload when this account signs in again here; signing in a different account on this browser deletes them). Before the account-switch wipe, refuse or confirm when the previous owner still has pending pushes.
- **Test:** an account-switch test with a pending marker for A expects a confirmation, or expects A's row and marker to survive until confirmed.

#### FU-2 — Desktop: an unreadable DPAPI key crashes the app on every launch, with no message
- **Status (branch `fix/audit-sync-findings`):** Fixed. `main()` checks the key before anything opens the database. An unreadable key gets a dialog that says nothing was deleted and where the notes and key are, then the app exits cleanly (`UnreadableDatabaseKeyStartupTest`). The dialog itself is not exercised by tests.
- **Severity:** P2 (availability) · **Confidence:** Probable (not reproduced on a live profile) · **Platform:** Windows
- **Location:** `DesktopDatabaseKeyManager.loadOrCreate` throws `IllegalStateException` when the key file exists but `CryptUnprotectData` fails. That correctly keeps the key file. But it throws while Koin builds the database singleton, and `main.kt` has neither a startup handler nor an uncaught-exception handler.
- **When:** an administrator password reset, or a profile copied to another machine. In both, the user's DPAPI master key no longer opens the blob. The encrypted notes stay intact on disk, but the app just exits. Android shows `DatabaseRecoveryNotice` for its equivalent case.
- **Fix:** catch the key failure at startup and show a dialog that explains it and says where the files are, without deleting anything. Optionally offer to start a fresh library after moving the old files aside.
- **Test:** a desktop test with a blob store that throws, asserting a typed startup result instead of an escaped exception.

#### FU-3 — Web reminders fire only while the app is open (withdrawn: already disclosed)
- **Severity:** none · **Status:** Withdrawn after re-checking.
- **Detail:** `web/src/sw.ts:33-41` documents that service-worker timers die with the worker, so without the Push API a reminder due while the app is closed fires late, usually on the next visit. The first pass reported that the UI does not say so, but `ReminderPickerDialog.tsx:50-53` already does: "Web reminders fire when this app is open or recently used. They can be late or miss if the browser is fully closed". The original search used other phrasings and missed it. Reliable delivery would need Web Push with a server-side scheduler, which is a product decision rather than a defect.

#### FU-4 — `begin_sync_mutation()` is callable by `authenticated` over REST
- **Severity:** P3 (hardening) · **Confidence:** Confirmed · **Platform:** Server
- **Detail:** `20250904000000_mapping_and_attachment_guards.sql:511` grants it to `authenticated`, so PostgREST exposes it. It sets a transaction-local flag, and PostgREST runs each request in its own transaction, so it cannot unlock a later direct table write: **not exploitable today**. Every caller is a `SECURITY INVOKER` RPC that already runs as `authenticated`. Revoking it would break those RPCs, so the hardening is to move the guard helpers to a non-exposed schema. That's low priority.

#### FU-5 — `androidx.security:security-crypto` is deprecated
- **Severity:** P3 (maintenance) · **Confidence:** Confirmed (CI deprecation warnings) · **Platform:** Android
- **Detail:** `DatabaseKeyManager` and `AndroidSupabaseSessionPersistence` store their secrets in `EncryptedSharedPreferences`/`MasterKey`, which are deprecated. They work today. A replacement must read the existing values and re-wrap them with the Android Keystore directly, keeping the old path readable for upgrades, because losing the database passphrase makes the whole local database unrecoverable.

#### FU-6 — Web session (refresh token) lives in `localStorage`
- **Severity:** P3 (note) · **Confidence:** Confirmed · **Platform:** Web
- **Detail:** supabase-js defaults (`persistSession: true`, PKCE). Any XSS could read the refresh token. Mitigated by the absence of HTML sinks and by a script CSP of `'self'` plus Google sign-in. No change recommended unless the CSP is loosened.

### Checked and found sound
- **`SECURITY DEFINER`:** the only definer function (`firebase_uid_proven_elsewhere`) is dropped by `20260905000000_remove_firebase_compatibility.sql`. None remain deployed.
- **AppFunctions:** callable only by holders of the platform's privileged permission. Every function refuses while the app lock is on, and inputs are size-limited.
- **Share intents:** `content://` only; the provider's own type must be `image/*`; reads are capped at the attachment limit. The app exports no file or content provider that could be pointed back at its own files.
- **Exported widget receiver:** an `APPWIDGET_UPDATE` only triggers a refresh, which returns nothing while the app is locked.
- **Web XSS:** no `dangerouslySetInnerHTML`, `innerHTML`, `eval` or `new Function`. Markdown links allow only `http(s):` and `mailto:` (`toSafeHref`).
- **CSP and headers:** `default-src 'self'`; scripts limited to `'self'` and Google sign-in; `object-src 'none'`; `frame-ancestors 'none'`; HSTS, `nosniff`, `X-Frame-Options: DENY`. `style-src` allows `'unsafe-inline'`, which is common for React and low risk.
- **Service worker:** caches only the app shell, fonts and reminder schedules, never API responses. Reminder notifications never carry note text (`sanitizeReminders` owns the strings).
- **Reminders on Android and desktop:** one-off instants. The picker conversion handles both UTC-midnight date values and the skipped spring-forward hour. Android falls back to an inexact alarm, and reports that it did, when exact alarms are not allowed.
- **Desktop key storage:** user-scope DPAPI with per-purpose entropy, `CRYPTPROTECT_UI_FORBIDDEN`, and atomic publication by rename. An existing key that fails to unwrap is never replaced.
- **Dependencies:** `npm audit` reports 0 vulnerabilities in `web/`, `workers/attachments/` and the root. Gradle dependencies are covered by verification metadata and CI dependency review; no local CVE scanner was run.
- **Accessibility (web):** a heuristic scan finds no icon-only `<button>` without an accessible name. The e2e suite runs axe (`accessibility.spec.ts`), plus dialog, contrast, reflow and touch-target checks. Compose: the 30 `contentDescription = null` icons all sit beside text labels; none is the only content of a clickable control.

### Still not covered
Runtime screen-reader traversal and font scaling on Android and desktop; desktop keyboard-only navigation; performance profiling (cold start, large libraries, recomposition); a Gradle CVE scan (for example OWASP Dependency-Check).

---

## 13. Third pass — cloud-wipe generation, deployment verification, hardening (2026-10-13)

Audited ref | `main` at `08eb7f9c` (the `#316` merge) plus the open `#317` branch
Branch under review | `fix/wipe-generation-and-hardening` (this pass)
Method | Source reading, `supabase db reset` + `supabase test db` against a local Docker stack, the repository's own test/lint/typecheck commands, and the GitHub API for CI and deployment state. No production system was touched.

### 13.1 Deployment status, verified against the GitHub API (not inferred from a green build)

`supabase.yml` and `attachments-worker.yml` both gate their `deploy` job on `github.event_name == 'workflow_dispatch'`. The successful runs on `08eb7f9c` were `push` events, so they ran `database-tests` / `verify` and **skipped** `deploy`. Queried 2026-10-13:

| Artifact | Evidence | Verdict |
|---|---|---|
| F-01–F-12, FU-1, FU-2 merged | `08eb7f9c` is an ancestor of `main` | **MERGED** |
| Gate coverage on `main` | `supabase.yml` run `38296652949`: `preflight` success, `database-tests` success, `deploy` **skipped**; `attachments-worker.yml` run `38296653024`: `verify` success, `deploy` **skipped**; `android.yml` `38296653063` and `desktop.yml` `38296653155`: `build`/`package`/`instrumented` success, `release`/`msi-release` **skipped** | **CI VALIDATED, NOT DEPLOYED** |
| Supabase migrations | Last `workflow_dispatch` run of `supabase.yml`: 2026-09-09 (`34300509280`), before `20261012000000`/`20261012010000` existed | **NOT DEPLOYED** |
| Attachments Worker | Last `workflow_dispatch` run: 2026-09-10 (`34486228437`), before the F-08 change | **NOT DEPLOYED** |
| Web (Cloudflare Pages) | `cloudflare-pages.yml` run `38296653011` on the `08eb7f9c` push: success | **DEPLOYED** |
| Android / Windows releases | No `workflow_dispatch` release runs recorded | **NOT RELEASED** |
| PR `#317` | Open, `MERGEABLE`, `mergeStateStatus: CLEAN`, 17 checks; `release`, `deploy`, `deploy-preview`, `msi-release` are the only non-pass entries and are all `skipping` | **READY, NOT MERGED** |

`#317` was reviewed line by line: `signOutGoogle({deleteCloudData: true})` wipes the cloud first and throws on failure *before* touching anything local, then clears the account's notes, meta row and pending attachments, then signs out; an ordinary sign-out keeps the local copy. It is correct as written.

### 13.2 New work in this pass

| ID | Severity | What | Status |
|---|---|---|---|
| **F-13** (new) | **P1** | A device that was offline across "delete cloud data" could not tell an intentional wipe from a truncated read, so the cloud could be repopulated with the notes the user had just deleted. `delete_all_user_cloud_data()` recorded nothing durable. | Fixed on the server, on web and on Android/Windows | **Verified** on all three |
| **F-13b** (new) | **P1** | Found while implementing F-13: on Android and Windows the wipe undid *itself*, with no other device involved. `deleteAllCloudData()` clears only the sync-state store, so the next sync saw an empty cloud, found no known-id explanation and uploaded every local note straight back. | Fixed with F-13 | **Verified** |
| F-05 | P2 | A delete with no captured baseline answered success while the cloud row stayed live. Rule DEL-6 kept; the failure is now reported. | **Fixed, Option B** |
| FU-7 | P3 | Gradle libraries were absent from GitHub's dependency graph, so Dependabot could never raise an advisory for Android or Windows dependencies. | **Fixed** (`gradle/actions/dependency-submission`, pinned to `v6.4.0`) |
| FU-8 | P3 | Multi-select starts only on long-press; `combinedClickable` binds Enter to `onClick` and has no Space binding, so a keyboard user cannot toggle selection. | **Not done** |
| FU-4 | P3 | `begin_sync_mutation()` is REST-callable by `authenticated`. | **Not done** |
| FU-5 | P3 | `EncryptedSharedPreferences`/`MasterKey` are deprecated and hold the database passphrase. | **Not done** |

#### F-13 — the wipe protocol, as implemented

`supabase/migrations/20261013000000_account_wipe_generation.sql` adds a durable per-account `wipe_generation` that `delete_all_user_cloud_data()` increments in the same transaction as the deletes. `fetch_full_snapshot()`, `pull_changes()` and a new `get_sync_generation()` publish it.

Enforcement is a `BEFORE INSERT OR UPDATE OR DELETE` trigger on `notes` and `note_tombstones`, not a check inside each RPC. That is the load-bearing choice: it is the one place every write already passes through, so a future RPC, a direct PostgREST table write, or any *older arity* of every mutation RPC is covered by construction. The mutation wrappers copy the presented generation into a transaction-local setting; the trigger compares it against the durable value:

- generation `0` (never wiped) — every write allowed, with or without a generation. Accounts that were never wiped, and every deployed client, are untouched.
- generation `> 0` — a write presenting nothing is refused (`NL001`), one presenting a stale value is refused (`NL002`).

`assert_live_owned_note()` already requires a live note row for every attachment upload and finalization, so the attachment path is fenced behind the note path: after a wipe no attachment row can be recreated for that account. That is why no separate attachment enforcement was added.

The **invariant**: after a wipe, no note or tombstone can be created, updated or deleted by a writer that cannot prove it knows the wipe happened. A stale device therefore cannot silently reverse it.

Web behaviour: pushes present the generation; a refusal records a block, so nothing is attempted again — a retry loop cannot wear the protocol down. Local notes are kept, nothing is deleted, and `CloudWipeNotice` tells the user the cloud data was erased from another device and offers "Upload my notes again". That action is the *only* thing that clears the block, and it clears `knownCloudIds`/`noteRevisions` at the same time so the re-upload lands as new notes rather than reading their absence from the cloud as a deletion. The server cannot distinguish that decision from a stale arrival, which is why it is the user's to make.

#### F-13, Android and Windows

The transport learns the generation from every snapshot and presents it on `apply_note_change`, `apply_note_delete` and `restore_note`; `SupabaseTransportException` recognises `NL001`/`NL002` and the transport raises `CloudWipedElsewhereException` instead of a generic failure. It also reads the generation out of the wipe's own response, which is what closes F-13b.

The engine is where the invariant is enforced, because that is where the destructive decision lives:

- `downloadAllNotes` compares the snapshot's generation with the one this device acknowledged. If it is newer **and** there are local notes whose ids the last download recorded as present in the cloud, it records the generation, sets a durable block and refuses — *before* the loop that treats every absent known id as a deletion. Without that ordering the wipe would delete the user's local library on every device that had ever synced it.
- `uploadAllNotes` and `reconcileUploads` refuse while the block is set, so nothing is sent. A refused write also sets the block (`noteWipeRefusal`), so the block survives a transport-level refusal too.
- A device holding nothing — a fresh install on a wiped account — adopts the generation and syncs normally instead of failing forever.
- `NoteSyncEngine.allowUploadAfterCloudWipe()` is the user's answer: it lifts the block and clears `knownCloudIds`, so the local notes upload as ones the cloud has never seen. It is reached from `SyncManager.allowUploadAfterCloudWipe()` → `CloudSyncController` → `MainViewModel`, and the affordance is a button in the existing cloud dialog next to "Restore" (`MainDialogs`, strings `cloud_upload_after_wipe_*`). The repository's `unreferenced-kotlin` reachability check is what forced that wiring rather than a test-only entry point, and it passes.
- Accounts that were never wiped are untouched: generation 0 short-circuits every one of these paths, and a transport that cannot report a generation leaves it at 0.

### 13.3 Test execution (commands actually run, 2026-10-13)

| Command | Result |
|---|---|
| `npx supabase start` + `npx supabase db reset --yes` | exit 0 — all 28 migrations apply to a fresh database, including `20261013000000` |
| `npx supabase test db` | **PASS — 30 files, 333 assertions, 0 failures.** 35 assertions are new (`notelikeus_account_wipe_generation.test.sql`). All 29 pre-existing files still pass, which is itself the backward-compatibility evidence for generation `0`. |
| `web: npx vitest run` | **PASS — 114 files, 840 tests** (113 files / 833 tests at the branch point; 7 new in `wipeGenerationProtocol.test.ts`) |
| `web: npx tsc -b --noEmit` | exit 0 |
| `web: npx oxlint` | 0 errors, 83 warnings (pre-existing class: `no-array-sort`, `consistent-function-scoping`) |
| `workers/attachments: npx vitest run` | **PASS — 6 files, 137 tests** |
| `workers/attachments: npx tsc --noEmit` | exit 0 |
| `gradlew :composeApp:desktopTest --no-daemon --rerun` | **PASS — 101 suites, 768 tests, 0 failures, 0 errors**, re-executed after the contract fixture and migration changes so the contract tests really ran |
| `gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest :androidApp:testDebugUnitTest :composeApp:compileDebugAndroidTestSources :androidApp:compileDebugAndroidTestSources --no-daemon --rerun-tasks` | **PASS — 174 suites, 1417 tests, 0 failures, 0 errors** (desktop 102/774, composeApp unit 70/617, androidApp unit 2/26), instrumented sources compile, with `-Djava.io.tmpdir` shortened; see §13.3.1 |
| `node scripts/ops/unreferenced-kotlin.mjs` | **PASS** — 0 unreferenced declarations (§13.4) |

New assertions that matter most, in `notelikeus_account_wipe_generation.test.sql`:

1. A raw `INSERT INTO public.notes` performed **after** `begin_sync_mutation()` is still refused — the exact bypass an older endpoint or a direct Data-API write would attempt.
2. Each legacy arity (14-argument `apply_note_change`, 15-argument with attachment ids, 2-argument `apply_note_delete`, 14-argument `restore_note`) is refused with `NL001`, which is what protects the installed Android and Windows builds.
3. A delete for a note that is not on the server touches no row, so it returns `conflict / note_not_found` rather than an error — and still cannot resurrect anything.
4. The generation never decreases, and the value the first wipe published is stale after the second.

The client half is covered by `CloudWipeGenerationFenceTest` (desktop, 6 tests, all passing), which is where the destructive decision lives:

1. A snapshot at a generation this device has not seen, with a local note the last download recorded as being in the cloud: the download fails with `CloudWipedElsewhereException`, **the local note is still in the database**, nothing was uploaded, the generation is recorded and uploads are blocked.
2. A queued upload is refused while the block stands, and the note is still present.
3. `allowUploadAfterCloudWipe()` lifts the block and clears the known cloud ids, after which the same note uploads as new (`p_base_revision` null) instead of being deleted.
4. A device holding nothing adopts the generation and syncs — a fresh install on a wiped account is not left permanently broken.
5. An account that was never wiped (generation 0) syncs exactly as before, including the ordinary cloud-wins path.
6. The same generation is only news once, so answering the prompt is what lets the sync through.

#### 13.3.1 Kotlin tests on this machine

The Android and JVM suites were run in full, not skipped. Two environment problems had to be worked around, and both were reproduced rather than assumed:

1. `gradlew` with a **daemon** cannot start its test-worker JVM here: `java.io.IOException: Unable to establish loopback connection` at `sun.nio.ch.UnixDomainSockets.connect`. `--no-daemon` succeeds. This is the failure the first pass recorded as "the Gradle test executor JVM exits at launch", and it is why that pass reported `:composeApp:testDebugUnitTest` as never having run. It is an environment problem, not a source defect: `:composeApp:desktopTest` (768 tests) and `:composeApp:testDebugUnitTest` + `:androidApp:testDebugUnitTest` (643 tests) both pass with `--no-daemon`.
2. Two Robolectric tests in `DatabaseMigrationSchemaTest` fail with `SQLiteCantOpenDatabaseException: ... doesn't exist and CREATE_IF_NECESSARY is set, check directory permissions` because the sandbox temp path is too long for Robolectric to create its database directory. With `-Djava.io.tmpdir` pointed at a short path they pass, and they were the only two failures — 615 of 617 passed without the workaround.

So every Kotlin suite that this machine can run was run, and is green. What remains unverified on a device is listed in §13.4.

### 13.4 What this pass did not do

- **FU-8, FU-4, FU-5.** Unchanged from the previous pass, and still not release blockers. (FU-7 was done; it is listed in §13.2.)
- **Device verification.** A Pixel 7 is reachable over wireless ADB from the development machine and was used to confirm the toolchain and the debug keystore, but no instrumented suite, screen-reader walkthrough, font-scaling or performance measurement was run in this pass. Nothing about device behaviour is claimed.
- **The Compose affordance is only source-verified.** `MainDialogs` and the strings compile and the reachability check passes, but no one has looked at the dialog in a running app.

### 13.5 Remaining risks

| Class | Item |
|---|---|
| P1 | `supabase.yml` (`20261012000000`, `20261012010000`, `20261013000000`) and `attachments-worker.yml` are still undeployed — verified, not inferred. The F-08, F-09 and F-13 fixes exist only in source. |
| P1 | Android and Windows releases still do not carry F-01, F-07, F-10, FU-2 or F-13/F-13b. |
| P2 | The Kotlin block is only lifted through the new button; until it ships, a wiped account's Android or Windows device reports the wipe and uploads nothing, which is safe but requires the user to reach that dialog. |
| P3 | FU-4, FU-5, FU-8. |
