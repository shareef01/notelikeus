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

The ordered plan for closing the gap below — with the verification and rollback for each step — is in [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md). None of it has been executed.

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
| FU-8 | P3 | Multi-select starts only on long-press; `combinedClickable` binds Enter to `onClick` and has no Space binding, so a keyboard user cannot toggle selection. | **Fixed and verified** — key-injection test; the two Space cases fail without the binding |
| FU-4 | P3 | `begin_sync_mutation()` is REST-callable by `authenticated`. | **Fixed** — helpers moved to `notelikeus_internal`, exposure pinned by a test |
| FU-5 | P3 | `EncryptedSharedPreferences`/`MasterKey` are deprecated and hold the database passphrase. | **Already implemented — corrected**, see below |
| **F-14** (new) | **P2** | `public.restore_note` accepted `p_base_revision` and never read it — one return path, `applied` — so a restore was the only mutation with no optimistic-concurrency fence: it deleted the tombstone and unconditionally upserted, and an offline device's undo could put its older copy on top of a newer edit elsewhere, silently. | **Fixed** on the server and in both clients (F-14, below) |

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
| `npx supabase test db` | **PASS — 31 files, 350 assertions, 0 failures.** 35 assertions are new (`notelikeus_account_wipe_generation.test.sql`), 15 more are new (`notelikeus_internal_guard_schema.test.sql`). All 29 pre-existing files still pass, which is itself the backward-compatibility evidence for generation `0` and for the FU-4 move. |
| `web: npx vitest run` | **PASS — 117 files, 855 tests** (113 files / 833 tests at the branch point: 7 new in `wipeGenerationProtocol.test.ts`, 7 in `restoreRevisionFence.test.ts`, 6 in `CloudWipeNotice.test.tsx`, 2 in `supabaseExposure.test.ts`) |
| `web: npx tsc -b --noEmit` | exit 0 |
| `web: npx oxlint` | 0 errors, 83 warnings (pre-existing class: `no-array-sort`, `consistent-function-scoping`) |
| `web: npm run test:e2e` (Playwright, against local Supabase) | **PASS — 171 passed, 1 skipped, 0 failed, 8.3 min** across `chromium` and a Pixel 5 viewport. See §13.3.3. |
| `npm run pages:verify` | **PASS — "Cloudflare Pages artifacts verified in web/dist", "Cloudflare Pages security headers verified".** Builds the bundle and checks the artifact's shape and headers, so the CSP and redirect claims are verified against what the deploy would ship rather than against the source that generates it. |
| `node --test scripts/ops/attachments-worker-deploy.test.mjs` / `write-kotlin-staging-properties.test.mjs` | **PASS — 7 pass, 0 fail** / **PASS — 0 fail** (CI-parity checks, run locally here) |
| `npx supabase db lint --local --level warning --fail-on error` | **PASS — exit 0, zero errors.** Three advisory warnings only: two in `public.restore_note` (see F-14) and one in the pgTAP helper `tests.authenticate_as`. It lints `notelikeus_internal` and `public` clean, which independently type-checks the 1269-line generated FU-4 migration and the wipe-generation migration — analysis no test in this repository performs. |
| `npx supabase db diff --local --schema public` and `--schema notelikeus_internal` | **PASS — "No schema changes found"** for both. `db diff` replays every migration into a shadow database and compares, so this says the committed migrations *determine* the schema: nothing was created outside them, and `20261014000000` is complete rather than merely accepted. |
| `npx supabase migration list --local` | **PASS** — all 34 migrations applied, including `20261012000000`, `20261012010000`, `20261013000000`, `20261014000000`. |
| `npx supabase projects list`, `supabase db push` | **NOT RUN — no credentials.** The CLI is not linked and has no access token (`SUPABASE_ACCESS_TOKEN` is unset and the token is a GitHub secret, which cannot be read back). The remote state therefore stays API-verified (§13.1) and `db push` stays the owner's action. |
| `workers/attachments: npx vitest run` | **PASS — 6 files, 137 tests** |
| `workers/attachments: npx tsc --noEmit` | exit 0 |
| `gradlew :composeApp:desktopTest --no-daemon --rerun` | **PASS — 101 suites, 768 tests, 0 failures, 0 errors**, re-executed after the contract fixture and migration changes so the contract tests really ran (782 by the end of the pass, once FU-8's key-injection tests and the `MainDialogs` dialog tests landed) |
| `gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest :androidApp:testDebugUnitTest :composeApp:compileDebugAndroidTestSources :androidApp:compileDebugAndroidTestSources --no-daemon --rerun-tasks` | **PASS — 177 suites, 1427 tests, 0 failures, 0 errors** (desktop 105/784, composeApp unit 70/617, androidApp unit 2/26), instrumented sources compile, with `-Djava.io.tmpdir` shortened; see §13.3.1 |
| `gradlew :composeApp:connectedDebugAndroidTest` on a **real Pixel 7 (Android 16) over wireless ADB** | **5 tests, 0 failures — but the task still failed**, on a non-zero device instrumentation exit code. See §13.3.2. |
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

1. `gradlew` with a **daemon** cannot start its test-worker JVM here: `java.io.IOException: Unable to establish loopback connection` at `sun.nio.ch.UnixDomainSockets.connect`. `--no-daemon` succeeds. This is the failure the first pass recorded as "the Gradle test executor JVM exits at launch", and it is why that pass reported `:composeApp:testDebugUnitTest` as never having run. It is an environment problem, not a source defect: `:composeApp:desktopTest` (778 tests by the end of this pass) and `:composeApp:testDebugUnitTest` + `:androidApp:testDebugUnitTest` (643 tests) both pass with `--no-daemon`.
2. Two Robolectric tests in `DatabaseMigrationSchemaTest` fail with `SQLiteCantOpenDatabaseException: ... doesn't exist and CREATE_IF_NECESSARY is set, check directory permissions` because the sandbox temp path is too long for Robolectric to create its database directory. With `-Djava.io.tmpdir` pointed at a short path they pass, and they were the only two failures — 615 of 617 passed without the workaround.

So every Kotlin suite that this machine can run was run, and is green. What remains unverified on a device is listed in §13.4.

#### FU-8, verified by pressing the keys
`NoteCard` handles `Key.Spacebar` on key-**up** on the focused card and runs the same callback the long press runs, so mouse, touch and keyboard cannot drift apart and a key repeat cannot toggle selection back and forth. It is dispatched on the card rather than at screen level, because the screen's handler already owns Ctrl-key shortcuts, Delete and Escape and does not see a focused card's Space.

`NoteCardKeyboardSelectionTest` exercises it the way a user would, by focusing the card and injecting the key: Space starts a selection, Space on an already-selected card still answers (so a selection can be undone from the keyboard, not only added to), Enter still opens the note instead of selecting it, and a click still opens it. **Both Space cases fail with the binding removed and the Enter and click cases pass either way** — checked, not assumed, so the tests are evidence rather than decoration. That is a stronger position than the previous pass, which could only read the modifier.

What is still not verified is the *app*: nobody has walked the Compose UI with a keyboard, and the new cloud-wipe dialog (`MainDialogs`) has only compile-plus-reachability behind it.

#### FU-4, in full

`begin_sync_mutation()` and `end_sync_mutation()` were never exploitable — the flag they set is transaction-local and PostgREST gives each request its own transaction, so a caller could not pair one with a table write. But a helper whose only job is to open a write gate has no business being addressable from outside the database, and it was, because `public` is exposed and the SECURITY INVOKER RPCs need EXECUTE on it.

They now live in `notelikeus_internal`, which `supabase/config.toml` does not list in `[api] schemas`, together with `sync_mutation_guard()`. The 16 RPCs that called them were recreated with a qualified reference — generated from each caller's own latest definition rather than retyped, so no body changed apart from the three references — and the four guard triggers were repointed before the `public` originals were dropped, so a missed dependency would fail the drop loudly instead of leaving a dangling trigger. Grants are unchanged for every RPC; `authenticated` and `service_role` keep USAGE on the schema and EXECUTE on the helpers, and `anon` gets neither.

Two things surfaced while doing it, both handled:

- The pgTAP suites were themselves calling `public.begin_sync_mutation()` to open the guard for their own direct table writes. They now call the internal one — which is what an in-database caller does, and is the honest way to read the change: the *Data API* surface closed, the in-database contract did not.
- `migrations()` extraction over-read function bodies: several migrations terminate with `$function$;`, and the single-line `AS $$ SELECT ...; $$;` bodies end their dollar-quote without a preceding newline. Both are worth knowing for anyone parsing these files; the generated migration was rebuilt from scratch twice as a result, and the `db reset` plus 348 assertions are what caught it.

Two new assertions pin the property so it cannot silently reopen: `notelikeus_internal_guard_schema.test.sql` (15 assertions — the helpers are absent from `public`, present in the new schema, executable by exactly the right roles, and a direct write is still refused while the guard is closed) and `web/src/lib/contract/supabaseExposure.test.ts` (the `[api] schemas` list is exactly `public` and `graphql_public`, and the last definition of each helper is in the internal schema).

#### 13.3.2 Instrumented tests on a real device

> **Operational warning, learned the hard way.** Do not run the instrumented suites against a device
> that already has a *differently signed* build of the app installed. This pass ran
> `:androidApp:connectedDebugAndroidTest` against a Pixel 7 that carried a release-signed
> `com.aus.notelikeus`, and the package — **with its local data** — was gone afterwards:
> `pm list packages -u` shows nothing, and `/data/data/com.aus.notelikeus` no longer exists. The
> run reported `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.aus.notelikeus signatures
> do not match newer version` and a failed `DELETE_FAILED_INTERNAL_ERROR` uninstall of the test
> APK. Use a clean emulator (what CI does) or a device where nothing local matters. Nothing in the
> repository or in CI is affected — this is a property of the instrumented-test runner meeting a
> personal device.

A Pixel 7 (Android 16, API 36) was reachable over wireless ADB, so the instrumented suite was run on it rather than left as "needs an emulator". `composeApp` has exactly one instrumented class, `PlaintextDatabaseMigratorTest`, and **all five of its tests ran and passed** on the device — the JUnit XML the run wrote records `tests="5" failures="0" errors="0"` against `device=192.168.2.223_35271`, the HTML report says 100%, and the device log shows each test finishing:

- `aPlaintextDatabaseIsMigratedToEncryptedWithItsRowsIntact`
- `aFailedExportKeepsThePlaintextDatabaseInPlace` — the test the first pass recorded as "compiles; needs a device"
- `anUnopenableDatabaseIsQuarantinedRatherThanDeleted`
- `aDatabaseThatOpensWithTheCurrentKeyIsLeftAlone`
- `quarantiningLeavesANoticeForTheUser`

That is **new evidence for F-10**: the quarantine-never-delete behaviour on a failed encryption export is now device-verified, not source-verified.

The task itself, however, still reports failure: `:composeApp:connectedDebugAndroidTest` fails with "There were failing tests" while its own report shows none, because the Android Gradle Plugin decides from the device instrumentation's exit code and `build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt` contains `1`.

Running the same instrumentation directly settles what that code is not:

```
adb shell am instrument -w -e class com.aus.notelikeus.data.local.PlaintextDatabaseMigratorTest \
  com.aus.notelikeus.shared.test/androidx.test.runner.AndroidJUnitRunner
  → OK (5 tests)            adb exit code: 0
```

So the tests pass, the device agrees, and the non-zero code belongs to the Gradle plugin's own orchestration. `:androidApp:connectedDebugAndroidTest` — run afterwards on the same device — fails earlier and more bluntly, and explains the family this belongs to: it could not install its debug APK at all over a differently-signed `com.aus.notelikeus` (`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`), and its teardown could not uninstall `com.aus.notelikeus.test` (`DELETE_FAILED_INTERNAL_ERROR`). CI runs the identical command on API 30 and 36 emulators, which are clean, and passes.

**The instrumented suite is therefore not claimed green through Gradle.** The tests passed on the device; the task did not report success; the cause is the device's package state, not the product or the repository, and the same device-run hazard is called out at the top of this section.

#### A label bug in this pass's own Compose work, found by writing the test

`MainDialogsCloudWipeTest` was written to close the last source-only claim about the new dialog, and it found a defect in it immediately: the button read `Upload this device's notes` **with a visible backslash**.

The cause: the three strings this pass added to `composeResources/values/strings.xml` were the only ones in the file written with an Android-style `\'`, and this Compose resource loader does not unescape it. The file's other eleven apostrophes are typographic (`’`, U+2019) and need no escaping at all — the repo's convention was there to copy and this pass did not. Both affected labels now use `’`, and the test keeps a guard that fails if any user-facing string in the dialog contains a backslash.

Worth recording because of what it says about the previous pass's evidence: this shipped nowhere (the branch is unmerged) but it would have reached every Android and Windows user of the wipe notice, and no amount of source reading had noticed it — the fix was only visible by asserting the rendered text. That is the same reason FU-8 needed key injection rather than a modifier to look at.

#### 13.3.3 Playwright, finally run

The first pass listed the end-to-end suite as **not run** — "needs local Supabase and a browser build". Both became available in this pass, so it was run rather than left as a caveat: `npm run test:e2e` asserts the Supabase URL is loopback, builds for the e2e mode, serves it, and drives it with Playwright.

**171 passed, 1 skipped, 0 failed** in 8.3 minutes, across `chromium` and a Pixel 5 viewport. `test-results/.last-run.json` records `"status": "passed"` with an empty `failedTests`. The single skip is project-conditional and deliberate: the layout controls only render on tablet width and up, so the mobile project skips that one assertion rather than passing it vacuously.

That closes a gap this audit has carried since its first pass, and it covers ground no unit test reaches: axe accessibility checks, dialog focus containment and start-focus, reflow with no sideways scrolling at 320/360/390/768/1024/1440px, swatch contrast in the light, dark and amoled themes, the 24px and 44px touch-target floors, `Ctrl+K` reaching the search field as the hint claims, and the note, label, reminder, filter, sort, search, save-failure, image-ingestion and backup journeys. It also exercises account isolation directly: `switching accounts does not leak the previous account notes` runs and passes, which is adjacent to FU-1 and FU-6.

It does **not** exercise the cloud-wipe paths — those need the Attachments Worker and a second device, which the browser harness does not have. The wipe protocol's verification therefore stays where §13.3 put it: pgTAP on the server, and unit-level on the clients.

#### F-14 — a restore ignored the revision it was given, and now does not

Found with `npx supabase db lint`, which this pass ran for the first time; it is the only tool in the project that inspects plpgsql bodies rather than behaviour.

`public.restore_note` takes `p_base_revision` and **never reads it**. In the final body the parameter appears once, in the signature; the body locks the tombstone and the note with `FOR UPDATE`, branches on `FOUND`, and then unconditionally `UPDATE`s or `INSERT`s. Every other mutation (`apply_note_change`, `apply_note_delete`) compares `p_base_revision` with the stored revision and answers `conflict` when they disagree; restore is the exception, so it is un-fenced optimistic concurrency in the one direction that writes content. It is not merely that the parameter is unused: the function has exactly **one** return path, `'status': 'applied'`, so it cannot answer anything else. The conflict branch both clients carry for restore — `SupabaseNoteTransport.restoreNote`'s `"conflict" ->` and the web's `if (result.status !== 'applied')` — is unreachable code that documents a belief the server does not hold.

- Device A edits a note, syncs, then goes offline.
- Device B edits the same note and syncs; the cloud holds B's newer version.
- On A the user undoes a delete (or a queued restore marker replays) and A's restore runs — B's version is overwritten with A's older copy, with no conflict and nothing reported.

The trigger is narrow: it needs a restore marker and a newer edit elsewhere. It is not the F-01 defect (that was the argument *name* making the RPC unresolvable) and it is not fixed by F-13 (the wipe generation fences wiped accounts, not stale edits on a live one) — the two are orthogonal.

It is fixed, on the owner's decision, and the fix is small because the fence was the missing piece rather than a design:

- **Server** (`20261015000000_restore_revision_fence.sql`): when the note is live, the restore must present its current revision, and `NULL` is refused too — "I do not know the baseline" is not a licence to overwrite what is there, which is what `apply_note_change` already answers for a null base against an existing row. A refusal carries `current`, so the caller can learn the revision. When there is **no** live row the restore applies whatever baseline was claimed: nothing is being overwritten, so there is nothing for a fence to protect, and that is the ordinary undo path.
  - A stricter version — refusing a claimed-but-absent baseline as well — was written, tried against the suite, and **reverted**: it refused a legitimate undo purely for presenting a baseline, which two pgTAP files caught.
  - An "identical payload means idempotent success" exemption was considered and dropped: once both clients present the revision they hold, the benign case passes the fence on its own, and the exemption would have added a comparison to reason about for no user-visible gain.
- **Web**: `restoreCloudNote` now presents the revision this browser holds instead of a hard-coded `null`, and turns a refusal into a typed `RestoreRefusedError` after recording the revision the server actually has. `restorePermanentlyDeletedNote` and `retryPendingCloudRestores` treat that as **terminal** — the marker goes, so no reconcile retries a request that can only be refused again — while a transient failure still keeps it. The user is told the note changed on another device and that the version there was kept.
- **Android/Windows**: the transport raises `RestoreRefusedException` on the conflict instead of returning an empty map (which read as "nothing to record"), after recording the server's revision; the engine clears the restore marker and lets the failure surface, in both the direct restore path and the merge-time one.
- **Evidence**: two pgTAP assertions in `notelikeus_restore_note.test.sql` were rewritten deliberately — the old ones asserted exactly the value the fence now refuses — and the suite is 350 assertions green. Seven new web tests in `restoreRevisionFence.test.ts` (the revision presented, the typed refusal, the recorded revision, terminal versus transient, and the user-facing message) and two Kotlin tests in `RestoreRevisionFenceTest` (the refusal fails and leaves no marker; a transient failure keeps it).

What it deliberately does not change: a restore can still consume a tombstone another device wrote, so undoing a delete that happened elsewhere still succeeds locally. That is a cross-device disagreement about a *delete*, pre-existing and separate from a stale copy overwriting a live row; it is recorded here rather than folded in silently.

The lint's other advisory — `v_existing` assigned and never read — is benign: the `SELECT ... FOR UPDATE` is load-bearing for the row lock and for `FOUND`, not for the variable.

#### 13.3.4 The app, launched on a real device

With the owner's authorization, the debug build was installed on the Pixel 7 (Android 16, API 36) and driven over ADB. This is the first time in this audit that the Android app has been *run* rather than read, and it covers three of the items the first pass listed as never verified.

- **It launches and renders.** Sign-in gate first (`Sign in with Google`, `Continue offline`, the debug email/password form), then the notes screen after `Continue offline`: search field, `Filters`, the sort chip, the layout toggle, the empty-state illustration, the compose button. Dark theme throughout, no crash, process alive throughout.
- **The encrypted local database works end to end on the device.** Created a note through the UI, `am force-stop`, relaunched: the note was still there. That exercises the whole chain the F-10/FU-5 work protects — AndroidKeyStore-backed passphrase, SQLCipher database open, Room persistence across process death — on real hardware rather than in Robolectric.
- **Large font does not break the layout.** At `font_scale 1.5` the app relaunched, the note card reflowed its title onto a second line instead of clipping, and every control stayed reachable. The one crash in the log during that check was `com.android.launcher3` — the *system* launcher, on its own taskbar recreation — not the app.

Two things this did **not** establish, and they stay on the not-run list:

- **Keyboard navigation on Android.** `adb shell input keyevent TAB/SPACE` on this device did not reach the app's focus system: the presses landed in system UI and raised a clipboard overlay instead of moving or selecting anything. Nothing about the Android focus model is claimed from that attempt. FU-8's evidence remains the Compose key-injection test (§13.2), which is a real check of the modifier but not a walkthrough of the running app.
- **TalkBack.** Not exercised at all. Turning a screen reader on and traversing the UI is not something `adb` can assert.

The device was left as the owner asked: the debug build installed with its data cleared (`pm clear`), `font_scale` and display density restored to their original values, and the screenshots this pass took removed from `/sdcard`.

#### 13.3.5 Cold start, memory, and the accessibility tree

Three numbers this audit previously declined to guess at, measured on the Pixel 7 (Android 16) with the **debug** build, plus the first look at what a screen reader would actually be given.

**Cold start** — `am force-stop`, then `am start -W`, three times:

| Run | TotalTime |
|---|---|
| 1 | 1774 ms |
| 2 | 1769 ms |
| 3 | 1758 ms |

**Memory after launch** — `dumpsys meminfo`: TOTAL PSS 174,100 KB (≈170 MB), TOTAL RSS ≈293 MB, Java heap 21.5 MB, native heap 9.5 MB.

Both carry the same caveats, and they are not small: this is a debug build (no R8, no minification, extra instrumentation), on one device, with no release-build comparison and no baseline from before this pass. They are data points, not a regression check, and nothing here should be quoted as the app's production cost.

**The accessibility tree** — `uiautomator dump` on the notes screen gives 44 nodes, and every `clickable="true"` container has its label inside its own subtree:

| Container | Label announced |
|---|---|
| note card | the note title, plus its timestamp as a sibling node |
| drawer | `Menu` |
| search field | `Search your notes` |
| profile | `Open settings` |
| filter chip | `Filters` |
| sort chip | `Manual (drag to reorder)` |
| view toggle | `Change view mode` |
| compose button | `Add note` |

The editor's icon-only controls are all described too — `Bold`, `Italic`, `Link`, `List`, `Checklist`, `Add image`, `Back`, `Set reminder`, `Pin note`, `Archive note`, `More options` — and the save state is a labelled line (`Saved locally • 05:14`). This is stronger than the previous pass's source heuristic ("the 30 `contentDescription = null` icons sit beside text labels"): it is the tree TalkBack is handed, and it shows no actionable control without a name.

What it still does not show is **spoken traversal** — the order a screen reader walks those nodes, and whether the card's merged description reads well out loud. That needs a person with TalkBack on, and it stays on the not-run list.

#### 13.3.6 The Windows desktop app, started for the first time in this audit

`gradlew :composeApp:run` was launched with `APPDATA` pointed at an isolated directory, so the app could not open, migrate or sync the owner's real library. It created its own database there, came up **signed out**, and rendered its sign-in gate — brand mark, `Sign in to continue`, `Sign in with Google`, `Continue offline` — in the dark theme at 1000×800.

The check that the isolation actually held: `%APPDATA%\Notelikeus` and `~/.notelikeus/notes-db.key` were byte-for-byte the same before and after, same sizes and same timestamps. So the app was genuinely exercised without touching the library, the session or the DPAPI key that were already on this machine.

The window was captured with `PrintWindow` against its handle rather than a screen grab, deliberately: a screen grab of a 1920×1080 desktop shows whatever else is open, and this one would have. `PrintWindow` asks the window for its own surface, which is both the honest evidence and none of anything else.

**The keyboard walkthrough did not happen, and could not.** `SetForegroundWindow` is refused for a background process — Windows' focus-stealing protection — so the app cannot be given focus, and the first probe showed the foreground window was the owner's browser. Sending keys then would have typed into that browser. Posting synthetic `WM_KEYDOWN` messages into a Compose/Skia window was not tried as a workaround, because a result from it would not be evidence about real keyboard use. FU-8's Windows half therefore stays verified by the Compose key-injection test, and the walkthrough stays outstanding with a reason rather than a shrug.

#### FU-5 was already fixed, and the finding was stale

The second pass recorded FU-5 as "`DatabaseKeyManager` and `AndroidSupabaseSessionPersistence` store their secrets in `EncryptedSharedPreferences`/`MasterKey`… they work today" and left the replacement as scheduled work. That is no longer true of the code, and this pass verified it rather than inheriting the claim:

- **`DatabaseKeyManager`** stores the SQLCipher passphrase as an AES-GCM file under `filesDir`, with the key in AndroidKeyStore — the replacement FU-5 asked for. The legacy ESP is read **once**, copied in, and only then cleared; a file whose key can no longer decrypt it is renamed aside (`unrecoverable-<timestamp>`), never deleted; the payload is published by rename so a crash cannot leave a truncated file that reads back as corrupt and costs the database; a Keystore write failure falls back to the legacy ESP so the database still opens. The alias is deleted in exactly one case — an encrypt failure, where it protects nothing — and the decrypt path deliberately keeps it.
- **`AndroidSupabaseSessionPersistence`** has the same shape for the session, including the plaintext fallback preferences the previous pass did not mention: read once, migrated into the Keystore file, then cleared. An undecryptable session file is treated as signed out and left on disk.
- Both are what production DI constructs (`PlatformModule`: `single { DatabaseKeyManager(get()) }`, `single { SupabaseStore(AndroidSupabaseSessionPersistence(get())) }`), not test-only paths.
- The behaviour is executed, not merely read: `DatabaseKeyManagerRecoveryTest` covers a key that cannot encrypt being replaced with the passphrase re-persisted under the new one, a healthy key never being deleted, and an undeletable key not being retried into a loop; `DatabaseKeyManagerTest` covers the codec (fresh IV per encryption, tamper and magic rejection) and the swap's failure branches; `AndroidSupabaseSessionPersistenceTest` covers the plaintext-fallback migration and the undecryptable-file case. All of them ran green in this pass's `:composeApp:testDebugUnitTest` sweep.

What remains is not a defect but a support decision: the legacy ESP **read** path has to stay until the project is willing to drop installs old enough to still hold secrets there. It is the only remaining `EncryptedSharedPreferences` reference in the Android sources, it is annotated as legacy, and removing it early would strand exactly the users FU-5 set out to protect.

#### 13.3.7 The instrumented suites on a clean emulator

The Pixel could not run them (§13.3.2), so one was provisioned: a dedicated AVD (`nlu-instrumented`, API 35, `google_apis`) created from the system image already on this machine. The owner's own AVDs (`deutschflow-test`, `test-tabakpp`) were not touched, and the AVD was deleted afterwards along with the emulator.

| Module | Result |
|---|---|
| `:composeApp:connectedDebugAndroidTest` | **5 tests, 0 failures** |
| `:androidApp:connectedDebugAndroidTest` | **14 tests, 12 passed, 2 failed** |

The `composeApp` result is the confirmation the Pixel could not give: the same suite passes on a clean device, so the non-zero exit code there was that device's package state and not the tests, the product or the repository.

The `androidApp` run is the first device-level evidence this audit has for two paths it had only compiled:

- `BackupDocumentIoTest` — 6/6, including the import size limit, non-ASCII content surviving a round trip, and an unreadable or unwritable document failing rather than throwing.
- `MainActivityShareInstrumentationTest` — 6/6, cold and warm image-share delivery, rotation without duplicating or crashing, rapid successive shares being rejected rather than merged, and an external share intent that must not target an existing note.

**Two failures, recorded as an open question rather than a verdict.** Both are in `PredictiveBackTest`, with the assertion "a back press did not reach `OnBackPressedDispatcher`; BackHandler in EditorScreen would not fire, so backing out of the editor would not save" (and its sibling, that the system does not drive `OnBackInvokedDispatcher`, so the manifest opt-in is not in effect for the running app). CI runs this suite on **API 30** (where it self-skips below 33) and **API 36** (where it passes), so an API 35 device failing it is either version- or image-specific — this was a headless emulator with no GPU acceleration — or a gap in what CI covers. A reproduction on an API 36 image was attempted here and failed at *install* (`Failure calling service package: Broken pipe (32)`; the `google_apis_playstore` image is too heavy for this machine), so it is **not settled**, and nothing is claimed about whether the app's predictive-back opt-in works on API 35. The way to settle it is CI's own API 36 leg with this class selected.

### 13.4 What this pass did not do

It did not merge, deploy or release anything, and it did not touch the hosted Supabase project: no credential for it was available from this machine, so remote state is the GitHub-API read in §13.1 and never a query of the database. The plan for the owner to close that is in [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md).

- **TalkBack and Narrator.** The accessibility *tree* was inspected on the device and every actionable control is named (§13.3.5), but spoken traversal — order, phrasing — needs a person with a screen reader on, on both Android and Windows.
- **Keyboard navigation of the Compose UI.** FU-8's modifier is verified by key injection, but neither running app has been walked by keyboard: the Android attempt was inconclusive (§13.3.4) and the Windows one cannot be given focus at all (§13.3.6).
- **Performance beyond the two numbers in §13.3.5.** Cold start and post-launch memory are now measured, on a debug build. A library of thousands of notes, recomposition counts, sync latency and Worker duration are all still unmeasured, and no number is claimed for them.
- **The new cloud-wipe dialog** in `MainDialogs` is now covered by `MainDialogsCloudWipeTest` — four Compose tests that press its button and assert its label, its explanation, its callback and the absence of an escape leak (§13.3.2). It was **not** opened on the device: by the time the fixed build was installed the phone had locked, and unlocking it is the owner's to do. `Profile → Restore from cloud` is the path someone should look at.
- **Cloud paths end to end.** The wipe protocol has no browser-level test (the e2e harness has no Worker and no second device), so its verification stays at pgTAP and unit level.
- **Anything from §13.1.** The deployments, the `#317` merge and the client releases are all still outstanding and need the owner.

### 13.5 Remaining risks

| Class | Item |
|---|---|
| P1 | `supabase.yml` (`20261012000000`, `20261012010000`, `20261013000000`, `20261014000000`) and `attachments-worker.yml` are still undeployed — verified, not inferred. The F-08, F-09 and F-13 fixes exist only in source. |
| P1 | Android and Windows releases still do not carry F-01, F-07, F-10, FU-2 or F-13/F-13b. |
| P2 | The Kotlin block is only lifted through the new button; until it ships, a wiped account's Android or Windows device reports the wipe and uploads nothing, which is safe but requires the user to reach that dialog. |
| P3 | A restore still succeeds against a tombstone another device wrote, so undoing a delete that happened elsewhere is not refused — F-14's residual, out of its scope, pre-existing, and recorded rather than folded in (see the F-14 section). |
| P3 | `PredictiveBackTest` fails on an API 35 `google_apis` emulator while CI passes the same suite on API 36 (§13.3.7). Not settled: either version/image-specific or a CI version gap. |
| P3 | The legacy `EncryptedSharedPreferences` read path in `DatabaseKeyManager` and `AndroidSupabaseSessionPersistence` (FU-5's residual) — remove when the support window allows, not before. |
