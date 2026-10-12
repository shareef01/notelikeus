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
- **Severity:** P1 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/lib/supabase/revisionStore.ts:25-64`. `rememberNoteRevision` reads (transaction 1), then `saveRevisionState` reads (transaction 2), then `setOwnerMeta` reads (`notesLocalRepository.ts:361`, transaction 3) and puts (transaction 4). `pullIncrementalChanges` (`supabaseSyncEngine.ts:155, 170, 211-217`) writes a `noteRevisions` map computed from state loaded *before* its network round trip.
- **Root cause:** Every writer replaces the whole `noteRevisions` map and `knownCloudIds` from its own stale snapshot. Concurrent writers occur within one tab (async push of several notes, push overlapping a realtime pull, a reconcile) and across tabs.
- **Effect:** Lost per-note base revisions and lost `knownCloudIds` entries. Consequences: spurious conflicts; silent remote-delete no-ops through F-05; and `knownCloudIds` regressions that weaken the "unexplained missing" mass-delete guard. The cursor itself is protected by `Math.max` in `saveRevisionState`, but `applyRemotePageAtomically` takes the page's `noteRevisions` wholesale.
- **Fix:** Add one `updateOwnerMeta(ownerId, (meta) => meta')` helper that does `get` and `put` inside one `readwrite` transaction, and merge per key (`noteRevisions[id] = max(old, new)`, explicit deletions) instead of replacing whole maps. In `applyRemotePageAtomically`, merge the page's revisions into the meta read inside its own transaction.
- **Regression test:** Two concurrent `rememberNoteRevision` calls for different notes must both persist. A pull page applied while a push records a revision must keep the push's revision when it is higher.
- **Complexity:** M

### F-04 — Web pull cursor jumps to the client's own write revision, skipping other devices' changes
- **Severity:** P2 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/lib/supabase/revisionStore.ts:49-56` sets `lastRemoteRevision = max(cursor, revision)` after every successful `apply_note_change` (`supabaseSyncEngine.ts:143`). Deletes do the same explicitly (`supabaseRemoteNotesDataSource.ts:301-308`).
- **Root cause:** `sync_revision_seq` is one global sequence. Suppose the cursor is C, another device commits R, and this tab then pushes and receives P, with C < R < P. The cursor becomes P, and `pull_changes(p_after_revision = P)` never returns R.
- **Effect:** Another device's edit, new note or tombstone is missing in the tab until a full snapshot reconcile, which runs only on visibility/online events and is throttled to 30 s / 15 s windows. An edit made in the meantime on stale content conflicts and then goes through F-06.
- **Fix:** Advance the cursor only from `pull_changes` and snapshot results, never from the revisions of the client's own writes. Record the write's revision in `noteRevisions` only.
- **Regression test:** Fake RPC: cursor 10; push returns 12; `pull_changes(after = 10)` contains 11. Assert that 11 is applied.
- **Complexity:** S

### F-05 — Web remote delete silently does nothing when no base revision is known
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
- **Status (branch `fix/audit-sync-findings`):** Import fixed on Android and desktop. The desktop picker had the same defect: an uncapped `file.readBytes()`. `BundleImportHeapTest` imports a 144 MB bundle in a child JVM capped at `-Xmx96m`. Against the pre-fix code it fails with `OutOfMemoryError`; it now passes. Export still builds the bundle in memory (P2, not addressed).
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
- **Severity:** P2 · **Confidence:** Confirmed that the deletion happens; partially mitigated · **Platform:** Worker (affects all clients)
- **Location:** `workers/attachments/src/index.ts:152-167`: the 10 s `AbortSignal.timeout`, or a lost body, throws `UpstreamServiceError`. `:337-340`: `catch { await compensate('upstream finalization error') }`, which deletes `objectKey` whenever `preflight.already_live !== true` (`:285-293`).
- **Sequence:** A fresh upload writes to R2. `finalize_note_attachment_put` commits (`20260910000000_attachment_commitment.sql:185-217`). The response exceeds 10 s or the stream drops. The Worker deletes the object and returns 503.
- **Mitigation found:** On a client retry, `authorize_note_attachment_put` returns `already_live: true` for the now-existing row, including provisional rows (`20260908130000_attachment_delete_is_terminal.sql:415-425`). The Worker's `head()` then finds no object and re-uploads it (`index.ts:266-278`). So the damage self-repairs **if and when the same client retries with the same bytes**.
- **Residual risk:** For a web (v1) upload the row is *committed* immediately. Until the retry, other devices hydrate an attachment whose GET returns 404. If the originating device never retries (pending bytes lost, profile cleared, app uninstalled), the committed row points at nothing permanently.
- **Fix:** On an *ambiguous* finalization failure, do not compensate. Leave the bytes. A missing row with bytes present is recoverable by a sweep that lists R2 prefixes against metadata. A present row with missing bytes is not recoverable. Keep compensation only for definitive answers: a `403` refusal or `terminally_deleted`.
- **Regression test:** In the Worker suite, have the finalize mock commit and then abort the response. Assert that `ATTACHMENTS_BUCKET.delete` is not called.
- **Complexity:** S

### F-09 — Provisional attachment rows are never reclaimed and permanently consume quota
- **Severity:** P2 · **Confidence:** Probable · **Platforms:** Server, Kotlin clients
- **Location:** `20260910000000_attachment_commitment.sql`. `committed_at IS NULL` is read only by `list_user_attachments`; no sweep or RPC removes provisional rows (grep across `supabase/`, `workers/`, `web/src`, `composeApp/src/commonMain`). The quota checks (`:166-183`) count every `deleted_at IS NULL` row, provisional rows included.
- **Effect:** Each abandoned deferred upload (a stale continuation, which the migration header describes as an expected case) permanently consumes up to 10 MB of the 200 MB quota and one of the 20 slots per note, and its R2 bytes leak. Over time users hit "quota exceeded" with no visible attachments.
- **Fix:** Add a sweep pass for provisional rows older than N days (for example 7) that claims, deletes the object, and marks the row deleted, reusing the purge-claim protocol.
- **Complexity:** M

### F-10 — Android plaintext→SQLCipher migration quarantines a healthy database on a transient export failure
- **Severity:** P2 · **Confidence:** Probable · **Platform:** Android
- **Location:** `composeApp/src/androidMain/.../PlaintextDatabaseMigrator.kt`. Any exception from `sqlcipher_export`, such as low disk space, results in `quarantineDatabaseFiles` and Room then creates an empty database.
- **Effect:** The user's notes vanish from the app. The bytes still exist under a quarantined name, but nothing in the UI recovers them. For signed-in users the cloud re-hydrates, but guest/offline users effectively lose everything.
- **Fix:** On an export failure where the source still opens as plaintext, keep using it and retry the migration on the next launch, instead of quarantining.
- **Complexity:** S

### F-11 — A transient key-store failure on web disables all saves for the session
- **Severity:** P3 · **Confidence:** Confirmed at source level · **Platform:** Web
- **Location:** `web/src/lib/crypto/notesCryptoKey.ts:107-130` memoizes the promise, including a `null` result from `openKeyDb` `onerror`/`onblocked` or from an aborted claim transaction. Every later `sealNoteForStorage` then throws `missing_key` until the page reloads.
- **Fix:** Do not cache a `null` result; cache only a resolved `CryptoKey`.
- **Complexity:** XS

### F-12 — Cross-client RPC contract coverage gap
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
