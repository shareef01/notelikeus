# Deep audit — 2026-09-20

Local-first durability, sync convergence, attachment lifecycle and protocol-contract audit of the
Notelikeus repository, carried out against the working tree described below.

## 1. Baseline

| Item | Value |
| --- | --- |
| Branch | `feature/android-image-share-audit` |
| HEAD | `503618f24d4eb73db918eb77952e7f66e70d0b87` (`wip(android): harden image share lifecycle handling`) |
| `origin/main` | `501a36abf28d9efd6673a33d878b89f91bc27503` |
| Working tree | **Dirty before this audit began**: 16 modified tracked files + 1 untracked test file. See §5. |
| `git diff --check` | clean (exit 0) |
| OS | Windows 11 Pro, build 26200 |
| JDK | Microsoft OpenJDK 21.0.11 LTS (`JAVA_HOME` set) |
| Gradle | 9.7.1 (wrapper), Kotlin 2.4.0, Launcher JVM 21.0.11 |
| Node / npm | v24.16.0 / 11.13.0 |
| Android SDK | present (`adb`, `emulator` on PATH) — **no AVD, no attached device** |
| Docker | server reports 29.7.2/linux, but the engine is **non-functional** (see §6) |
| Page file | **none** (`AutomaticManagedPagefile=False`, no `Win32_PageFileUsage` entry) — commit limit 16–17 GB against near-total commitment, so JVMs intermittently fail to allocate. See §6. |

### Suite results

| Suite | Command | Result |
| --- | --- | --- |
| Kotlin desktop unit | `./gradlew :composeApp:desktopTest` | **68 files / 488 tests / 0 failures / 0 skipped** (476 pre-existing + 12 added here) |
| Kotlin Android unit | `./gradlew :composeApp:testDebugUnitTest` | **65 files / 557 tests / 0 failures / 0 skipped** — was **BLOCKED** (§10); needs a short `TEMP` (§6) |
| Android app unit | `./gradlew :androidApp:testDebugUnitTest` | **2 files / 19 tests / 2 failures** — was **BLOCKED** (§10). Both failures are Robolectric harness gaps in the WIP test, not source defects (§11). **Not green.** |
| Minified release / R8 | `./gradlew :androidApp:assembleRelease` | **BUILD SUCCESSFUL** (`isMinifyEnabled` + `isShrinkResources`) — was **BLOCKED** (§10); needs `-Xmx2560m` (§6) |
| Web unit | `cd web && npm test` | **103 files / 781 tests / 0 failures** (773 pre-existing + 8 added here) |
| Web typecheck | `cd web && npm run typecheck` | pass (exit 0) |
| Web lint | `cd web && npm run lint` | pass (exit 0; 0 errors, 74 pre-existing warnings) |
| Worker unit | `npm run test:attachments-worker` | **6 files / 121 tests / 0 failures** |
| Pages artifacts | `npm run pages:verify` | pass |
| Database (pgTAP) | `npm run supabase:test` | **BLOCKED** — Docker engine broken (§6) |
| Web e2e (Playwright) | `cd web && npm run test:e2e` | **BLOCKED** — needs local Supabase, same Docker failure |
| Android instrumented | `./gradlew :composeApp:connectedDebugAndroidTest` | **BLOCKED** — no device/AVD, and the Android target does not compile |
| Windows MSI | `./gradlew :composeApp:packageMsi` | not attempted (§6) |

## 2. Findings

| # | Severity | Status | Component | Invariant violated |
| --- | --- | --- | --- | --- |
| D1 | P0 | **CONFIRMED, FIXED** | `NoteSyncEngine.putNotes` (Kotlin) | A local write acknowledged to the user must never be reverted by an upload that was in flight when it happened |
| D2 | P0 | **CONFIRMED, FIXED** | `NoteSyncEngine.downloadAllNotes` / `CloudNoteRecord.toNote` (Kotlin) | Data that exists only on this device must never be destroyed by a payload that cannot speak for it |
| D3 | P2 | **CONFIRMED, FIXED** | `applyServerFields` (web) | A malformed/partial protocol response must fail closed, not synthesize server state from a client clock |
| D4 | P0 | **CONFIRMED, FIXED** | `AttachmentSyncService.confirmCommittedAttachments` (Kotlin) | Local source bytes are released only once a recoverable copy exists elsewhere |
| D5 | P1 | **CONFIRMED, FIXED** | Android `SharedPrefsNoteSyncStateStore` | Every platform must implement the sync-state contract the engine depends on — and compile |
| D6 | P1 | **CONFIRMED, FIXED** | `androidApp` (`MainActivity`, build config) | HEAD must compile and the tests it committed must be runnable |
| D7 | P0 | **CONFIRMED, FIXED** | `NoteSyncEngine` (Kotlin) | An operation authorized and started under account A may only commit effects to A's state |
| D8 | P0 | **CONFIRMED, FIXED** | `NoteSyncEngine` / `LocalCommitGate` / `LocalAccountIsolator` | Account validation and the local commit must be one critical section, not a check followed by a suspend write |
| D9 | P0 | **CONFIRMED, FIXED** | `NoteSyncEngine.downloadAllNotes` reconciliation | The ungated local writes in the reconciliation loops must not reach the new account either |
| — | P2 | **PRE-EXISTING, NOT FIXED** | `MainActivityRobolectricTest` | Two Robolectric harness gaps stay red; §11. Not claimed green. |

---

## 3. Confirmed and fixed

### D1 — An attachment upload reverts a newer durable local edit (P0)

**Component** `composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/sync/NoteSyncEngine.kt`,
`putNotes` (was lines 605–622).

**Invariant violated** *A local write that has been acknowledged to the user must never disappear
because an upload completes later.*

**Evidence / root cause.** `putNotes` captured a `Note` snapshot, then crossed **two** suspension
points — the attachment upload inside `AttachmentSyncService.syncNotesAttachments` and the
`transport.putNotes` round trip — and afterwards, for every note carrying attachments, performed a
**whole-row** write of the stale snapshot:

```kotlin
val syncedNotes = attachmentSync?.syncNotesAttachments(notes) ?: notes
val results = transport.putNotes(uid, syncedNotes)
for ((noteId, result) in results) {
    if (result.serverUpdatedAt != null) {
        noteDao.updateServerTimestamp(noteId, result.serverUpdatedAt)   // field-level write
    }
    ...
}
for (note in syncedNotes) {
    if (note.attachments.isNotEmpty()) {
        noteDao.updateNote(note.toNoteEntity())                          // full-row write, stale snapshot
        attachmentSync?.confirmCommittedAttachments(note)
    }
}
```

`NoteDao.updateNote` is `@Update` — every column of the row is replaced. `Note.serverUpdatedAt` is a
`val` and is copied into `NoteEntity` by `NoteMapper.toNoteEntity()`, so the stale snapshot carried
the *pre-upload* `serverUpdatedAt` and overwrote the fresh value written one statement earlier.
Everything else the row had gained since the snapshot — title, content, `timestamp`, pin/archive/
trash state, and the attachment set itself — was reverted with it.

**Deterministic reproduction.** `NoteSyncEngineStaleSnapshotTest` (desktop, real Room +
`BundledSQLiteDriver`). A `CompletableDeferred` gate holds `putNotes` at exactly the moment the
engine holds its snapshot and has not yet written anything back. No sleeps, no timing assumptions.

*Before the fix: 4 of 5 tests failed.*

```
> a durable edit made while an attachment upload is in flight survives the upload   FAILED (ComparisonFailure)
> the server timestamp returned by the upload is not reverted by the local echo      FAILED
> an attachment removed while the upload is in flight is not resurrected             FAILED
> an attachment added while the upload is in flight is preserved                     FAILED
> a note with no attachments is unaffected by the upload echo                        PASSED
5 tests completed, 4 failed
```

The fifth test passes because the whole-row write sits behind `note.attachments.isNotEmpty()`, which
pins the blast radius to attachment-bearing notes and guards against a future fix that widens it.

**User impact.** A user adds a photo to a note and keeps typing. The upload finishes and the note
silently reverts to its pre-upload text. The same write resurrects an image the user deleted during
the upload and drops one they added. This is the highest-severity class in the brief: an
acknowledged local write destroyed by a later asynchronous completion.

**Fix.** Field-level, compare-and-merge, re-reading the durable row after the asynchronous work
rather than re-writing the captured snapshot:

* New `NoteDao.replaceAttachmentsJsonIfUnchanged(noteId, expectedAttachmentsJson, attachmentsJson)`
  — a compare-and-set that touches **only** `attachmentsJson`, and only while the row still holds
  the value that was merged from. An explicit `IS NULL`/`=` predicate makes the invariant the
  method's name.
* `putNotes` now calls a new `mergeUploadedAttachmentPaths`, which re-reads the current row and
  merges the upload's results by stable `Attachment.id`:
  * an attachment the upload moved to `r2:` takes its new storage path;
  * an attachment the sync deliberately dropped (it was in the payload and its bytes are provably
    gone) is removed;
  * an attachment added while the upload ran is preserved;
  * an attachment removed while the upload ran is never resurrected.
* Staged source bytes are released **only** when the compare-and-set landed (or the row already
  held the uploaded paths). If the CAS loses, another writer's set stands and the bytes are kept —
  releasing them is only safe once the row being released against is the row on disk.

Ordering was deliberately *not* changed and the whole-row write was not merely moved: the fix
removes it and replaces it with a column-scoped mutation.

**Regression test** `composeApp/src/desktopTest/kotlin/com/aus/notelikeus/data/sync/NoteSyncEngineStaleSnapshotTest.kt`
(5 tests, all green after the fix).

**Validation**
```
./gradlew :composeApp:desktopTest --tests "com.aus.notelikeus.data.sync.NoteSyncEngineStaleSnapshotTest"
./gradlew :composeApp:desktopTest
```

---

### D2 — A cloud-winning download destroys attachments that only exist on this device (P0)

**Component** `NoteSyncEngine.downloadAllNotes` (`innerUpdate`) and `CloudNoteRecord.toNote`.

**Invariant violated** *Source bytes must never be deleted before a recoverable remote copy exists.*

**Evidence / root cause.** `CloudNoteRecord` carries **no attachment field at all** — attachments
travel as separate server-side metadata rows, reconciled by
`AttachmentSyncService.hydrateAllNotes`. Consequently `CloudNoteRecord.toNote` builds the note with
`attachments = emptyList()`, and the download path wrote that over the local row:

```kotlin
cloudWins -> {
    if (!sameContent(cloudNote, localNote)) {
        innerUpdate(cloudNote)      // -> noteDao.updateNote(cloudNote.toNoteEntity())  => attachmentsJson = NULL
        changes++
    }
}
```

For an `r2:` attachment the damage is repaired on the next `hydrateAllNotes`. For a **`pending:`**
attachment that has not been uploaded yet, or a **`file:`** reference to a local source file, there
is nothing to restore it from. The reference is destroyed, and because
`reconcileStagedAttachments` releases staged bytes that no note references any more, the only copy of
the user's image is deleted with it. This also defeats the deliberate design in
`mergeAttachmentsIntoNotes`, which explicitly *keeps* `pending:` attachments the server has no row
for.

**Deterministic reproduction.** `NoteSyncEngineAttachmentDurabilityTest` (desktop, real Room). Local
note carrying one attachment; the cloud holds a newer copy of the same note from another device.

*Before the fix: 2 of 3 failed.*

```
> a cloud-winning download keeps a staged attachment that was never uploaded        FAILED
> a cloud-winning download keeps a local file attachment that was never uploaded    FAILED
> a cloud-winning download removes an uploaded attachment the cloud no longer has   PASSED
3 tests completed, 2 failed
```

The third test is the counterpart invariant — an `r2:` attachment *is* spoken for by the server, so
a download that finds no metadata row for it must drop the reference. It passes before and after,
which pins that the fix is "keep what the payload cannot speak for", not "never write attachments".

**User impact.** A user attaches a photo on device A while offline, or imports a local file. Device B
edits the same note. Device A syncs, loses the reference to its own never-uploaded image, and then
garbage-collects the bytes. The image is unrecoverable from any device.

**Fix.** `Note.keepingLocalOnlyAttachmentsOf(local)`: the note built from a cloud record keeps the
local row's `pending:`/`file:` attachments (those the server cannot account for), and `r2:`
references are deliberately *not* carried over. The comparison is then made against what would
actually be written (`sameContent(merged, localNote)`), so the cloud's `serverUpdatedAt` still wins
while local-only attachments survive.

**Regression test** `composeApp/src/desktopTest/kotlin/com/aus/notelikeus/data/sync/NoteSyncEngineAttachmentDurabilityTest.kt`
(3 tests, all green after the fix).

**Validation**
```
./gradlew :composeApp:desktopTest --tests "com.aus.notelikeus.data.sync.NoteSyncEngineAttachmentDurabilityTest"
./gradlew :composeApp:desktopTest
```

---

### D3 — `applyServerFields` invents a "server" timestamp from the client clock (P2)

**Component** `web/src/lib/supabase/supabaseSyncEngine.ts`.

**Invariant violated** *Malformed or partial protocol responses must fail closed rather than
synthesize authoritative facts from client clocks or defaults.*

**Evidence.** The original implementation:

```ts
serverUpdatedAt: result.server_updated_at ?? note.serverUpdatedAt ?? Date.now(),
```

`apply_note_change` returns `server_updated_at` on **every** `applied` branch — verified across all
four historical definitions of the function
(`20250902000000_notelikeus_sync_schema.sql`, `20250902010000_sync_mutation_guard.sql`,
`20250904000000_mapping_and_attachment_guards.sql`, `20260906120000_restore_payload_and_lookup.sql`).
The only `jsonb_build_object('status','applied','idempotent',true)` return in the schema belongs to
`apply_note_delete`, not to this function. So there is **no backward-compatibility reason** for the
field to be optional, and the `Date.now()` fallback is unreachable in a well-behaved deployment but
reachable on a truncated, proxied or malformed response.

`serverUpdatedAt` is what conflict resolution compares (`cloudWinsConflict`, web
`shouldUploadOverRemote`). A client clock written in as if the server had stamped it — spoofable,
DST- and timezone-skew-prone, or simply ahead — can make a device reject a genuinely newer remote
copy or claim precedence it does not have.

**Deterministic reproduction.** `web/src/lib/supabase/serverUpdatedAtProtocol.test.ts`.

*Before the fix: 6 of 8 failed* (missing value, `null`, `NaN`, `±Infinity`, and an explicit check
that a wildly skewed client clock is never consulted). *After: 8 of 8 pass.*

**Fix.** `applyServerFields` now requires a finite numeric `result.server_updated_at` derived from
`typeof === 'number' && Number.isFinite(...)` and throws otherwise; the caller retries and receives
the real value. No control was weakened; the change makes a previously fail-open path fail closed.

**Regression tests**
* `web/src/lib/supabase/serverUpdatedAtProtocol.test.ts` (8 tests, TypeScript) — **executed, green**.
* `supabase/tests/database/notelikeus_apply_note_change_server_time.test.sql` (7 assertions, pgTAP) —
  pins the server side of the same contract so a future edit to the function cannot silently drop
  the field. **NOT EXECUTED — see §6.**

**Validation**
```
cd web && npx vitest run src/lib/supabase/serverUpdatedAtProtocol.test.ts
cd web && npm test
npm run supabase:test          # required before merge; blocked here (§6)
```

---

### D4 — The attachment commit pass deletes exactly the bytes it must protect (P0)

**Component** `composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/attachments/AttachmentSyncService.kt`,
`confirmCommittedAttachments`.

**Invariant violated** *Source bytes must never be deleted before a recoverable remote copy exists*
— the condition stated verbatim on `AttachmentStagingStore.release`: "Deletes staged bytes. Only
call once the bytes are recoverable elsewhere".

**Evidence / root cause.** `syncNoteAttachments` and `confirmCommittedAttachments` are two halves of
one decision, and they disagreed. The upload pass deliberately *keeps* a `pending:` (or `file:`)
attachment whose bytes it could not read — `readBytes`/`metadata` both answer `null` for "gone" and
for "could not read it this time", so the code consults `isStaged`/`exists` and keeps the reference
unless absence is *proven*. `StrandedAttachmentTest` covers that half explicitly ("an unreadable store
must not delete the user's picture"). The commit pass then ran on the **post-upload** note:

```kotlin
suspend fun confirmCommittedAttachments(note: Note) {
    for (attachment in note.attachments) {
        when {
            isPendingAttachment(attachment.storagePath) -> staging.release(pendingId, owner)
            isFileAttachment(attachment.storagePath) -> localStorage.deleteIfLocal(attachment.storagePath)
        }
    }
}
```

But the note it was handed is the one `syncNoteAttachments` returned. A successful upload has already
rewritten that attachment's path to `r2:` — so `r2:` entries match neither branch, and the *only*
attachments still carrying `pending:`/`file:` are precisely the ones the upload pass chose to keep.
The method therefore released the kept ones and never released the uploaded ones. The decision is
inverted, not merely mistimed.

**Deterministic reproduction.** `AttachmentCommittedReleaseTest` (desktop). Same real code, four
in-memory doubles. *Before the fix: 4 of 4 failed* — every failure on the target invariant, with both
preconditions passing, which is what confirms the preconditions (that the upload pass keeps the
unreadable-store attachment) hold and that the commit pass is what breaks the invariant:

```
committing never releases bytes the staging store could not read
  expected:<> but was:<[held-1]>          <- released the only copy of the user's picture
committing leaves an attachment the store could not read for a local file intact
  expected:<> but was:<[file:/tmp/photo.jpg]>   <- deleted the local source file
committing a successful staged upload releases the staged bytes
  expected:<[up-1]> but was:<[]>          <- never released a genuinely uploaded source
committing a successful local-file upload releases the local source file
  expected:<[/tmp/photo.jpg]> but was:<[]>      <- never released a genuinely uploaded source
4 tests completed, 4 failed
```

**User impact.** Two failures in opposite directions. (1) **Data loss**: an attached image whose
staging store was momentarily unreadable — a locked encrypted store, a transient disk fault — has its
bytes deleted while the note keeps referencing `pending:<id>`, so the picture is gone from every
device and the note renders a permanently broken attachment. (2) **Silent leak**: for a successful
`pending:` upload nothing released the staged bytes here, and for a `file:` source nothing releases
the local file on *any* path (`reconcileStagedAttachments` only sweeps staged bytes), so uploaded
local sources accumulate in the attachments directory forever.

**Fix.** The commit pass now needs both halves of the decision, and the signature says so:

```kotlin
suspend fun confirmCommittedAttachments(snapshot: Note, uploaded: Note)
```

It releases an attachment only when it was a local source (`pending:`/`file:`) in `snapshot` **and**
came back carrying an `r2:` path in `uploaded` — the proof that a recoverable copy exists. Two call
sites in `NoteSyncEngine.mergeUploadedAttachmentPaths` pass the snapshot and the upload result. No
retry behaviour, staging semantics, owner namespacing or deletion guard was changed; the guard got
strictly stronger, and the previously impossible cleanup now happens.

**Regression test** `composeApp/src/desktopTest/kotlin/com/aus/notelikeus/data/attachments/AttachmentCommittedReleaseTest.kt`
(4 tests, all green after the fix).

**Validation**
```
./gradlew :composeApp:desktopTest --tests "com.aus.notelikeus.data.attachments.AttachmentCommittedReleaseTest"
./gradlew :composeApp:desktopTest
```

---

## 4. Investigated and rejected

Recorded so a future agent does not rediscover them as new findings.

| Hypothesis | Verdict | Evidence |
| --- | --- | --- |
| Every caller of `supabaseRemoteNotesDataSource.fetchAllNotes()` is exposed to a structurally valid but empty snapshot overwriting durable revision/known-ID state | **SAFE — rejected** | Exactly one production caller: `hydrateFromRemote.ts:60`. `fetchSnapshotNotes` fails closed on `data == null`, a missing `note_count`, and any `count !== rows.length`. `hydrateIndexedDbFromRemote` uses upsert-only `putNotes` (never deletes), and carries an explicit guard at `hydrateFromRemote.ts:75-82`: an empty snapshot with a non-empty local namespace keeps the local notes. Covered by the pre-existing `web/src/lib/notes/emptyRemoteSnapshot.test.ts`. The `knownCloudIds`/`lastRemoteRevision` write in `fetchAllNotes` is therefore only reachable from a count-verified, genuinely authoritative snapshot. |
| `applyServerFields` is optional for backward compatibility | **REJECTED** | See D3: all four historical `apply_note_change` definitions return the field on every `applied` branch. |
| The whole-row write in `putNotes` is harmless because it writes "the same" note back | **REJECTED** | It writes the *pre-upload* note back, after two suspension points. Reproduced in D1. |
| The attachment-less upload path also clobbers newer edits | **REJECTED** | Guarded by `note.attachments.isNotEmpty()`; pinned by the fifth `NoteSyncEngineStaleSnapshotTest` case so a future widening of that path is caught. |

## 5. Working-tree ownership inventory (Priority 0)

`HEAD = 503618f24d4eb73db918eb77952e7f66e70d0b87`, branch `feature/android-image-share-audit`.
Nothing was reset, checked out, restored, stashed or reformatted. The tree was already dirty when
this audit began and all pre-existing work is intact.

### Modified tracked files (22)

| File | Class | Note |
| --- | --- | --- |
| `composeApp/src/androidMain/.../data/remote/NoteSyncStateStore.kt` | **MIXED** | user's revision-aware WIP + audit D5 (helpers/constants only) |
| `composeApp/src/commonMain/.../data/attachments/AttachmentSyncService.kt` | AUDIT (D4) | 21/4 lines |
| `composeApp/src/commonMain/.../data/local/dao/NoteDao.kt` | AUDIT (D1) | +28 lines (the CAS query) |
| `composeApp/src/commonMain/.../data/remote/SupabaseNoteTransport.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonMain/.../data/sync/CloudNoteRecord.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonMain/.../data/sync/CloudNoteTransport.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonMain/.../data/sync/NoteSyncEngine.kt` | **MIXED** | user WIP (137 lines) + audit D1/D2/D4 + 4 imports |
| `composeApp/src/commonMain/.../data/sync/NoteSyncStateStore.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonTest/.../contract/CrossClientContractTest.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonTest/.../data/remote/SupabaseNoteTransportTest.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonTest/.../data/sync/FakeCloudNoteTransport.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonTest/.../data/sync/FakeDaos.kt` | AUDIT (D1) | +15 lines, test double only |
| `composeApp/src/commonTest/.../data/sync/FakeNoteSyncStateStore.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonTest/.../data/sync/NoteSyncEngineTest.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/commonTest/.../data/sync/SyncChaosHarness.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/desktopMain/.../di/DesktopNoteSyncStateStore.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/desktopTest/.../data/sync/NoteSyncEngineTransactionTest.kt` | PRE-EXISTING USER/WIP | |
| `composeApp/src/desktopTest/.../platform/DesktopSyncCoordinatorTest.kt` | PRE-EXISTING USER/WIP | |
| `androidApp/src/main/kotlin/com/aus/notelikeus/MainActivity.kt` | AUDIT (D6) | 3 localized hunks, see §10 — **this is committed WIP feature code** |
| `androidApp/src/test/kotlin/com/aus/notelikeus/MainActivityRobolectricTest.kt` | AUDIT (D6) | 1 line, see §10 |
| `androidApp/build.gradle.kts` | AUDIT (D6) | 2 test deps + `testOptions`, see §10 |
| `gradle.properties` | PRE-EXISTING USER/WIP | untouched |
| `web/src/lib/supabase/supabaseSyncEngine.ts` | AUDIT (D3) | |

### Untracked files

| File | Class |
| --- | --- |
| `docs/AUDIT_DEEPSEEK_2026-09-20.md` | AUDIT (this document) |
| `composeApp/src/desktopTest/.../data/sync/NoteSyncEngineStaleSnapshotTest.kt` | AUDIT (D1 test) |
| `composeApp/src/desktopTest/.../data/sync/NoteSyncEngineAttachmentDurabilityTest.kt` | AUDIT (D2 test) |
| `composeApp/src/desktopTest/.../data/sync/AccountSwitchResumeTest.kt` | AUDIT (D7 test) |
| `composeApp/src/desktopTest/.../data/attachments/AttachmentCommittedReleaseTest.kt` | AUDIT (D4 test) |
| `composeApp/src/androidUnitTest/.../data/remote/SharedPrefsNoteSyncStateStoreTest.kt` | AUDIT (D5 test) |
| `web/src/lib/supabase/serverUpdatedAtProtocol.test.ts` | AUDIT (D3 test) |
| `supabase/tests/database/notelikeus_apply_note_change_server_time.test.sql` | AUDIT (D3 test, unexecuted) |
| `composeApp/src/desktopTest/.../data/sync/DesktopNoteSyncStateStoreTest.kt` | **PRE-EXISTING USER/WIP** — the author's desktop parity test |
| `hs_err_pid10576.log`, `14876`, `15784`, `17420`, `21604`, `22368`, `8420`, `pr_body.txt`, `replay_pid*.log` (4) | PRE-EXISTING junk (untouched) |
| `hs_err_pid3100.log`, `hs_err_pid7984.log`, `composeApp/hs_err_pid7984.log`, `hs_err_pid7196.log` | AUDIT-generated JVM crash dumps (see §6). Left in place as evidence of the host memory problem; not part of any fix. |

**Fix-boundary note (commit boundaries).** D1–D4 and D5/D6 are logically separable and are left
uncommitted, as instructed. If split into commits they are: (1) D1 — `NoteDao.kt`,
`NoteSyncEngine.kt` merge path, `FakeDaos.kt`, `NoteSyncEngineStaleSnapshotTest.kt`; (2) D2 —
`NoteSyncEngine.kt` `keepingLocalOnlyAttachmentsOf`, `NoteSyncEngineAttachmentDurabilityTest.kt`;
(3) D4 — `AttachmentSyncService.kt`, `NoteSyncEngine.kt` call sites,
`AttachmentCommittedReleaseTest.kt`; (4) D3 — `supabaseSyncEngine.ts`,
`serverUpdatedAtProtocol.test.ts`, the pgTAP file; (5) D5 — Android
`NoteSyncStateStore.kt`, `SharedPrefsNoteSyncStateStoreTest.kt`; (6) D6 — the `androidApp`
compile unlock, which should stay distinct from all of the above because it touches committed WIP
feature code and a build file.

### Files changed by this audit

```
composeApp/src/commonMain/.../data/local/dao/NoteDao.kt                        (D1)
composeApp/src/commonMain/.../data/sync/NoteSyncEngine.kt                      (D1, D2, D4)
composeApp/src/commonMain/.../data/attachments/AttachmentSyncService.kt        (D4)
composeApp/src/androidMain/.../data/remote/NoteSyncStateStore.kt               (D5)
composeApp/src/commonTest/.../data/sync/FakeDaos.kt                            (D1 test double)
composeApp/src/androidUnitTest/.../data/remote/SharedPrefsNoteSyncStateStoreTest.kt   (new, D5)
composeApp/src/desktopTest/.../data/sync/NoteSyncEngineStaleSnapshotTest.kt    (new, D1)
composeApp/src/desktopTest/.../data/sync/NoteSyncEngineAttachmentDurabilityTest.kt (new, D2)
composeApp/src/desktopTest/.../data/sync/AccountSwitchResumeTest.kt            (new, D7)
composeApp/src/desktopTest/.../data/attachments/AttachmentCommittedReleaseTest.kt (new, D4)
androidApp/src/main/kotlin/com/aus/notelikeus/MainActivity.kt                  (D6)
androidApp/src/test/kotlin/com/aus/notelikeus/MainActivityRobolectricTest.kt   (D6)
androidApp/build.gradle.kts                                                    (D6)
web/src/lib/supabase/supabaseSyncEngine.ts                                     (D3)
web/src/lib/supabase/serverUpdatedAtProtocol.test.ts                           (new, D3)
supabase/tests/database/notelikeus_apply_note_change_server_time.test.sql      (new, D3, UNEXECUTED)
```

`docs/FINDINGS.md` was deliberately **not** renumbered or edited: the repository's historical
findings keep their own identifiers and style, and D1–D6 are recorded here with their reproduction
and fix intact.

## 6. Unverified because the environment is unavailable

Nothing here is "passed"; each entry is a gap with its exact cause.

| Validation | Status | Environmental reason |
| --- | --- | --- |
| `npm run supabase:test` (pgTAP, incl. the new `notelikeus_apply_note_change_server_time.test.sql`) | **BLOCKED** | The Docker engine is broken. `docker info` and `docker image ls` return `request returned 500 Internal Server Error for API route and version .../v1.55/...`; `docker _ping` returns 500; `docker pull alpine:3.20` hung past a 2-minute timeout. `npx supabase start` fails on `failed to inspect docker image ... check if the server supports the requested API version`. **The new pgTAP file is therefore unexecuted and unproven.** |
| `cd web && npm run test:e2e` (Playwright against local Supabase) | **BLOCKED** | Requires the same local Supabase stack; same Docker failure. |
| `./gradlew :composeApp:testDebugUnitTest`, `:androidApp:testDebugUnitTest` | **UNBLOCKED, see §1** | Was blocked by §10; both now run. `:androidApp` still reports 2 failures (§11). |
| `./gradlew :composeApp:connectedDebugAndroidTest` | **BLOCKED** | No AVD (`emulator -list-avds` empty) and no attached device (`adb devices` empty). The compile blocker that previously also stood in the way is fixed (§10). |
| `./gradlew :androidApp:assembleRelease` (R8 / resource shrinking) | **UNBLOCKED** | BUILD SUCCESSFUL with `-Xmx2560m`; see §10. |

### Windows path-length limit: why the Android unit suite fails without a short `TEMP`

`DatabaseMigrationSchemaTest > migration4to5_addsForeignKeysAndDropsOrphanedCrossRefs` fails with
`SQLiteCantOpenDatabaseException ... CREATE_IF_NECESSARY is set, check directory permissions`.
It is **not** a code defect. Robolectric composes its per-test data dir from the class and method
name under the ambient temp root, and that one path measures **269 characters**:

```
C:\Users\SHAREE~1\AppData\Local\Temp\reasonix-session-tmp-935356342\robolectric-DatabaseMigrationSchemaTest_migration4to5_addsForeignKeysAndDropsOrphanedCrossRefs8365496998590368307\com.aus.notelikeus.shared.test-dataDir\databases\migration-schema-test-4544745597100.db
```

269 > `MAX_PATH` (260), so `mkdirs()` fails and SQLite cannot create the database. It is the longest
test name in the class that fails; the two shorter ones pass. Proven by changing only the temp root:

```powershell
$env:TEMP = "C:\nltmp"; $env:TMP = "C:\nltmp"   # the Gradle/test worker inherits these
.\gradlew.bat :composeApp:testDebugUnitTest --tests "com.aus.notelikeus.data.local.DatabaseMigrationSchemaTest"
# -> 3 tests completed, 0 failed
```

Note `-Djava.io.tmpdir=...` does **not** reach the forked test worker, which is why the first attempt
at this experiment was inconclusive. Every Android test result in §1 was produced with a short `TEMP`.
| `./gradlew :composeApp:packageMsi` and a packaged-app startup smoke test | **NOT RUN** | On Windows, so the MSI path is nominally available, but the tree is mid-refactor and MSI packaging was not attempted within this pass. **Windows DPAPI/MSI behaviour is therefore NOT verified by this audit.** |

### Memory constraint that affects any re-run on this machine

This host has **no page file**, so the commit limit is essentially the physical RAM and every JVM
competes for it. Two runs failed with infrastructure errors rather than test failures — a Kotlin
compile daemon that "terminated unexpectedly on startup" (which is what the `hs_err_pid*.log` files
in the repository root are from) and a test worker that could not map memory
(`os::commit_memory ... failed; error='The paging file is too small for this operation to complete'
(DOS error/errno=1455)`) — before succeeding with a leaner JVM layout:

```powershell
# stop stale daemons, then run as a single build JVM with in-process Kotlin compilation
.\gradlew.bat --stop
$env:GRADLE_OPTS = "-Xmx1024m -XX:MaxMetaspaceSize=384m"
.\gradlew.bat :composeApp:desktopTest --offline --no-daemon `
  '-Dkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.workers.max=1'
```

Every green result in §1 was produced this way (or with the equivalent smaller-heap daemon). Any
future failure on this machine should be checked against the commit limit before being read as a
test regression. Nothing in `gradle.properties` was modified — these are command-line-only settings.

## 7. Scope notes and remaining hypotheses

Work that this pass did **not** complete, stated plainly so it is not mistaken for a clean bill of
health:

* **Attachment source-byte lifecycle (H2 in the brief).** Both halves are now closed: D2 fixed the
  download-side destruction, and D4 fixed the upload-side release, which turned out to be inverted
  rather than merely mistimed. One related behaviour was left exactly as it was, deliberately:
  when `syncNoteAttachments` drops *every* attachment of a note (bytes provably absent) so the
  synced note carries none, `putNotes` skips the merge entirely and the dropped references stay on
  the mirror locally. That matches the pre-existing behaviour (the old whole-row write was also
  gated on `note.attachments.isNotEmpty()`), so changing it would be a behaviour change outside a
  data-loss fix. It is a cleanup gap, not a loss: `reconcileStagedAttachments` still sweeps the
  unreferenced bytes.
* **Cross-client state-machine parity corpus (H5).** Not built. Existing coverage is payload/fixture
  parity via `contracts/` and `CrossClientContractTest`, which the brief correctly notes is necessary
  but not sufficient.
* **Account/session races (H6), RLS/SECURITY DEFINER revalidation (H7), backup/import fuzzing (H8),
  dependency runtime-graph convergence (H9), scale/O(N²) and F61 sleep removal (H10).** Not audited
  in this pass. F50 (repository-managed rate limiting is one layer only; WAF/bot controls remain
  operator configuration) was not re-examined.
* The in-flight revision-aware sync work (§5) was read only far enough to audit around it and to
  confirm that `putNotes` still carried the D1 shape through the refactor. Its own correctness —
  revision monotonicity, `DeleteResult` conflict handling, the new `markDeleted(baselineRevision)`
  path that deliberately does nothing remote when no baseline exists — was **not** validated.

## 8. Files changed by this audit

```
composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/local/dao/NoteDao.kt            (D1)
composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/sync/NoteSyncEngine.kt          (D1, D2, D4 call sites)
composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/attachments/
    AttachmentSyncService.kt                 (D4)
composeApp/src/commonTest/kotlin/com/aus/notelikeus/data/sync/FakeDaos.kt                (D1, test double)
composeApp/src/desktopTest/kotlin/com/aus/notelikeus/data/sync/
    NoteSyncEngineStaleSnapshotTest.kt        (new, D1)
    NoteSyncEngineAttachmentDurabilityTest.kt (new, D2)
composeApp/src/desktopTest/kotlin/com/aus/notelikeus/data/attachments/
    AttachmentCommittedReleaseTest.kt         (new, D4)
web/src/lib/supabase/supabaseSyncEngine.ts                                               (D3)
web/src/lib/supabase/serverUpdatedAtProtocol.test.ts                                     (new, D3)
supabase/tests/database/notelikeus_apply_note_change_server_time.test.sql                (new, D3, UNEXECUTED)
```

`docs/FINDINGS.md` was deliberately **not** renumbered or edited: the repository's historical
findings keep their own identifiers and style, and D1–D3 are recorded here with their reproduction
and fix intact.

## 9. Reproduce everything that was verified

```powershell
cd "C:\Users\shareef01\AndroidStudioProjects\notelikeus"

# D1, D2, D4, then the containing suite
.\gradlew.bat :composeApp:desktopTest --tests "com.aus.notelikeus.data.sync.NoteSyncEngineStaleSnapshotTest"
.\gradlew.bat :composeApp:desktopTest --tests "com.aus.notelikeus.data.sync.NoteSyncEngineAttachmentDurabilityTest"
.\gradlew.bat :composeApp:desktopTest --tests "com.aus.notelikeus.data.attachments.AttachmentCommittedReleaseTest"
.\gradlew.bat :composeApp:desktopTest

# D3
cd web; npx vitest run src/lib/supabase/serverUpdatedAtProtocol.test.ts; npm test; npm run typecheck

# Worker + Pages
cd ..; npm run test:attachments-worker; npm run pages:verify

# Still required before merge, blocked in this environment
npm run supabase:test

# Android (needs a short TEMP: see §6)
$env:TEMP = "C:\nltmp"; $env:TMP = "C:\nltmp"
$env:GRADLE_OPTS = "-Xmx1280m -XX:MaxMetaspaceSize=448m"
.\gradlew.bat :composeApp:testDebugUnitTest :composeApp:desktopTest :androidApp:testDebugUnitTest `
  --no-daemon '-Dkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.workers.max=1'
.\gradlew.bat :composeApp:testDebugUnitTest --tests "com.aus.notelikeus.data.remote.SharedPrefsNoteSyncStateStoreTest"

# R8 / minified release (needs a larger heap; crashed at 1.2g)
$env:GRADLE_OPTS = "-Xmx2560m -XX:MaxMetaspaceSize=512m"
.\gradlew.bat :androidApp:assembleRelease --no-daemon '-Dkotlin.compiler.execution.strategy=in-process'
```

---

## 10. Priority 1 — unlocking the Android target (D5, D6)

Two independent blockers stood between this working tree and *any* Android validation. Both are
fixed; both were pre-existing, and neither came from the audit work in §3.

### D5 — Android never implemented the revision-aware sync state (P1, FIXED)

**Component** `composeApp/src/androidMain/kotlin/com/aus/notelikeus/data/remote/NoteSyncStateStore.kt`

**Invariant violated** *Every platform must implement the sync-state contract the engine depends on —
and must compile.*

**Evidence / root cause.** `:composeApp:compileDebugKotlinAndroid` failed with 14 errors, all
`Unresolved reference` for `readLongMap`, `writeLongMap`, `KEY_KNOWN_SERVER_REVISIONS` and
`KEY_BASELINE_DELETE_REVISIONS`. The user's revision-aware WIP added two account-scoped maps to the
`NoteSyncStateStore` interface and implemented them on desktop (`parseLongMap` +
`stringPreferencesKey`), but the Android implementation calls two helpers and two constants that were
never written.

**Diagnosis: missing helpers, not a semantic mismatch.** The Android store already persisted every
other concept the interface requires, using the identical JSON-object pattern for
`deletedAtById`/`writeDeletedMap`. Nothing else was absent, and no counter would have been
weakened by adding the two maps naively — but the *read* semantics did matter, so it was not done
naively (see below).

**Fix.** `readLongMap(key)` / `writeLongMap(key, map)` were added following the file's existing
JSON pattern, plus the two key constants. `readLongMap` deliberately departs from
`deletedAtById`'s convention: a stored value that is not a number is **skipped**, not defaulted.
`deletedAtById` reads a missing tombstone timestamp as "now", which is safe for a deletion that has
already been decided; doing that for a revision would hand the engine an invented *confirmed server
revision* and authorise a delete or skip a conflict against it. Absent stays absent. No revision
tracking was stubbed, emptied or weakened.

### D6 — HEAD's `androidApp` did not compile, and its committed tests could not run (P1, FIXED)

**Component** `androidApp/src/main/kotlin/com/aus/notelikeus/MainActivity.kt`,
`androidApp/src/test/kotlin/com/aus/notelikeus/MainActivityRobolectricTest.kt`,
`androidApp/build.gradle.kts`

Committed in the WIP commit `503618f2` itself (`MainActivity.kt` +112 lines). Four distinct gaps,
each fixed in the way the code already intends — nothing silenced, nothing stubbed:

1. `MainActivity.kt:363` — `SharedImageViewModel.ingest(mimeType: String, …)` was called with
   `ExternalShare.Image.mimeType`, which is `String?`. The ingestor it delegates to is already
   `ingest(uri, declaredMimeType: String?)` and resolves a missing type from the content provider, so
   the parameter became `String?` — matching both sides.
2. `MainActivity.kt:465` — `androidx.lifecycle.viewModelScope.launch { }`. A fully-qualified
   *extension property* is not a valid expression. `SharedImageViewModel` is a `ViewModel`, so the
   fix is the missing `import androidx.lifecycle.viewModelScope`; the call site is now
   `viewModelScope.launch`. (`kotlinx.coroutines.launch` was already imported.)
3. The WIP commit added `MainActivityRobolectricTest` but never declared Robolectric/androidx-test
   for that module's unit-test source set, so it could not compile. Added
   `testImplementation(libs.robolectric)` and `testImplementation(libs.androidx.test.ext.junit)`,
   mirroring versions already in `gradle/libs.versions.toml`.
4. The same test calls the `protected` `onSaveInstanceState`. Added
   `internal fun saveInstanceStateForTests(outState: Bundle)`, following the two
   `…ForTests()` bridges the same file already declares.

**Result** `:androidApp:compileDebugKotlin` ✅ · `:androidApp:assembleRelease` (R8 + resource
shrinking) ✅ `BUILD SUCCESSFUL`, fresh 65 MB `mapping.txt`, APK rebuilt · `:androidApp:testDebugUnitTest`
now **runs** (19 tests) with 2 failures that are the test's own harness gaps, not source defects (§11).

**Note for the author:** items 1–4 are edits to *your committed WIP feature code and build file*,
not to the audit's own area. They are small, semantically forced by the compiler, and trivially
revertible; but if you are mid-change on `MainActivity.kt`, resolve that file first.

### D5 regression tests

`composeApp/src/androidUnitTest/kotlin/com/aus/notelikeus/data/remote/SharedPrefsNoteSyncStateStoreTest.kt`
— Robolectric, 11 tests, all green. They cover the whole contract, not just the two new maps, because
what the engine depends on is semantic:

| Required by the brief | Test |
| --- | --- |
| persistence survives recreation | `known server revisions survive recreation, update and clear`; `delete baselines survive recreation, update and clear` — a new store instance per step |
| `clear()` removes all account-scoped sync metadata | `clear removes every account-scoped sync marker` — all eight concepts set, then asserted empty *and* the backing preference file asserted empty |
| malformed persisted state fails safely | `malformed revision state fails safely instead of inventing values` — truncated JSON, and a non-numeric value that must not become `0`; plus the store stays usable afterwards |
| revision values round-trip without truncation | `revision values larger than an int round-trip without truncation` — `5_000_000_000` and epoch-millis `1_789_880_000_000` |
| old installations without the new keys default safely | `an installation predating the revision keys starts empty and is usable`; `legacy string-set tombstones still migrate` |
| durable, not just in memory | `revision maps reach the durable preferences file, not just memory` — asserts the serialized map is in `shared_prefs/note_sync_state.xml` after `commit()` flushes the queued `apply()` |

The test duplicates the private preference-key strings on purpose: those names are the on-disk
contract a future version must keep reading, so a rename should fail the test rather than silently
orphan a user's existing sync state.

### Platform parity: sync-state persistence

`✔` = present and verified by reading the implementation; `–` = the concept is deliberately absent.

| Concept | Common contract | Desktop | Android | Web |
| --- | --- | --- | --- | --- |
| deleted tombstones | `deletedAtById`, `mergeDeleted`, `markDeleted`, `pruneExpired`, `isDeleted` | ✔ `sync_deleted_json`, in-memory mirror | ✔ `deleted_at_by_id` JSON + legacy `deleted_ids` string-set migration | ✔ zustand `persist` tombstone store, `mergeFromCloud` |
| restore markers | `markRestored`, `restoredIds`, `clearRestored` | ✔ `sync_restored_ids` (JSON string set) | ✔ `restored_ids` (string set) | ✔ `restoreRetry.ts` (`withoutRestoredDeletes`, `retryPendingCloudRestores`) |
| pending attachment GC | `markPendingAttachmentGc`, `pendingAttachmentGcEntries`, `clearPendingAttachmentGc` | ✔ `sync_pending_attachment_gc` (JSON map of sets) | ✔ `pending_attachment_gc_ids` (JSON map of arrays) | ✔ `retryPendingAttachmentGc` in `attachmentSyncService.ts` |
| known cloud IDs | `knownCloudIds`, `setKnownCloudIds` | ✔ `sync_known_cloud_ids` | ✔ `known_cloud_ids` | ✔ `ownerMeta.knownCloudIds` (`string[]`, normalized on read) |
| known server revisions | `knownServerRevisionById`, `updateKnownServerRevision(s)`, `clearKnownServerRevisions` | ✔ `sync_known_server_revisions` | ✔ `known_server_revisions` — **added by D5** | ✔ `ownerMeta.noteRevisions` (`Record<string, number>`) |
| baseline delete revisions | `baselineDeleteRevisionById`, `setBaselineDeleteRevision`, `clearBaselineDeleteRevision` | ✔ `sync_baseline_delete_revisions` | ✔ `baseline_delete_revisions` — **added by D5** | – no separate store: the base revision is read from `noteRevisions` at delete time (`getNoteBaseRevision`) |
| reconciliation high-water mark | `lastReconciledAt`, `markReconciled` | ✔ `sync_last_reconciled` (long) | ✔ `last_reconciled_at` (long) | ✔ `ownerMeta.lastRemoteRevision` (monotonic via `Math.max`) |
| last merged user identity | `lastMergedUserId`, `setLastMergedUserId` | ✔ `sync_last_merged_user_id` | ✔ `last_merged_user_id` | – implicit: IndexedDB owner namespaces are keyed by `ownerId`, so there is no stored "last merged" field to compare against |
| clear / sign-out | `clear()` | ✔ clears memory **and** `dataStore.edit { it.clear() }` | ✔ `prefs.edit().clear()` wipes the whole `note_sync_state` file | per-owner namespaces; nothing global to clear |

Two residual parity notes, recorded rather than fixed because neither is a defect today:

* The Web client has no distinct *baseline delete revision*, and no stored *last merged identity*.
  Both are structural differences, not omissions: Web resolves the delete base from its note-revision
  map at the moment of deletion, and its account scoping lives in the storage key rather than in a
  field. If Web ever needs the Kotlin behaviour of "delete only if the revision is unchanged", the
  base it reads at delete time is equivalent; the Kotlin baseline exists because the *intent* has to
  survive a process death between the tombstone write and the network call.
* Android's accessors re-read the preference store on every call while Desktop caches in memory.
  Android is the stricter of the two for durability, and is now the only one whose read of a
  malformed value cannot manufacture state.

---

## 11. Still red: the two `MainActivityRobolectricTest` failures

`:androidApp:testDebugUnitTest` runs 19 tests and 2 fail. Both belong to
`MainActivityRobolectricTest`, both are gaps in the **test harness**, and neither is a source
defect. **This suite is not reported as green.**

| Test | Failure | Nature |
| --- | --- | --- |
| `recreation after ingestion but before editor consumes payload preserves image exactly once` | `org.koin.core.error.KoinApplicationAlreadyStartedException: A Koin Application has already been started` | The Activity's `Application` calls `startKoin`; Robolectric re-runs it on the second launch inside the same process. Needs a test `Application` (`@Config(application = …)`) or an idempotent start. |
| `process death before editor consumes payload does not persist consumed marker` | `IllegalStateException: Android context is not initialized` from `org.jetbrains.compose.resources.ResourceReader_android` | Compose-Multiplatform's resource reader needs an initialized Android context, which Robolectric does not provide for the composition path here. |

Fixing these is a test-strategy decision (a test `Application`, and how to satisfy CMP resources
under Robolectric) belonging to whoever owns the image-share feature, so they were left alone rather
than guessed at. They were blocked from *compiling* before D6; they are now blocked only by these two
harness questions.

The third test class the WIP commit added, `MainActivityShareInstrumentationTest`, lives in
`androidApp/src/androidTest` and needs a device/emulator — **not executed** (no AVD, no device).

---

## 12. Priority 2 — H6 account/session isolation races (D7)

### D7 — an operation that resumes after another account signs in writes into the new account's local state (P0)

**Component** `composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/sync/NoteSyncEngine.kt`

**Invariant violated** *An operation authorized and started under account A may only commit effects
to A's namespace. It must not commit them merely because "the current user" is now B.*

### The design question, answered

The brief asks whether long-running operations capture an immutable owner token at start, or
repeatedly consult a mutable "current user". **The engine gets the first half right and the second
half wrong.** Every operation reads the account exactly once, at the top, and then uses that captured
`uid` for every *remote* call:

```kotlin
suspend fun uploadNote(noteId: Long): Result<Unit> {
    return runCatching {
        val uid = uidProvider().getOrThrow()   // captured once
        requireCurrentAccount(uid)
        refreshCloudTombstone(uid, noteId)     // suspension
        ...
        val note = noteDao.getNoteById(noteId)?.toNote()
        val remote = transport.fetchNote(uid, noteId)   // suspension
        ...
        putNote(uid, note)                     // local writes below, keyed by noteId only
    }
}
```

That captured uid is the right call for the remote half — A's data keeps going to A's cloud
namespace, and the backend derives ownership from the JWT anyway. The gap is the **local** half.
After those suspension points the engine writes device-local state keyed by `noteId` and by
attachment id, and nothing between the suspension and the write re-checks who is signed in now.
`requireCurrentAccount(uid)` cannot catch it: it compares against `syncStateStore.lastMergedUserId()`,
which `LocalAccountIsolator.isolate()` has just set to `null`, so it passes.

The ids are the problem. Note ids are small per-device autoincrements — B's first note very plausibly
reuses the id A was uploading — and an attachment id is a caller-generated string that repeats across
accounts by construction. Eight local-mutation sites follow a suspension point:

| Site | Local write | Post-await |
| --- | --- | --- |
| `putNotes` | `updateServerTimestamp`, `mergeUploadedAttachmentPaths`, `updateKnownServerRevision` | after `transport.putNotes` |
| `downloadAllNotes` (head) | note/label/checklist writes, `updateKnownServerRevisions`, `clearKnownServerRevisions`, `clearDeleted` | after `fetchCompleteSnapshot` |
| `downloadAllNotes` (tail) | `setKnownCloudIds`, `setLastMergedUserId`, `clearDeleted` | after `putNotes` + `hydrateAllNotes` |
| `mergeCloudTombstones` | `updateServerTimestamp`, `updateKnownServerRevision` | after `transport.restoreNote` |
| `restoreNote` | `updateServerTimestamp`, `updateKnownServerRevision` | after `transport.restoreNote` |
| `deleteNote` | `clearKnownServerRevisions`, `markPendingAttachmentGc` | after `transport.deleteNote` |
| `reconcileUploads` | `markReconciled` | after `fetchCompleteSnapshot` |
| `deleteAllCloudData` | `syncStateStore.clear()` | after `deleteAllOwnedCloudData` |

### Deterministic reproduction

`composeApp/src/desktopTest/kotlin/com/aus/notelikeus/data/sync/AccountSwitchResumeTest.kt`
(desktop, real Room). A `CompletableDeferred` gate holds `putNotes` after the engine has captured its
snapshot and account; the test then performs exactly what sign-out + sign-in does — clears A's rows
and A's sync state, installs B's note reusing **the same note id** and **the same attachment id**, and
flips the live session so `uidProvider()` now answers `uid-B`. Then it releases the gate.

*Before the fix: 3 of 3 failed.*

```
an upload that resumes after B signs in does not stamp A's server time onto B's note
  expected:<222> but was:<500000>          <- A's server timestamp landed on B's row
an upload that resumes after B signs in does not overwrite B's attachment with A's object
  expected:<[r2:owners/uid-B/notes/42/]shared-attachment-id>
   but was:<[pending:]shared-attachment-id> <- B's committed attachment reference was replaced
an upload that resumes after B signs in does not write A's revision into B's sync state
  expected:<999> but was:<7>                <- A's revision overwrote B's
3 tests completed, 3 failed
```

The attachment case is the sharpest: `mergeUploadedAttachmentPaths` merges by attachment id, so A's
upload result *replaced B's `r2:` object path with A's `pending:` reference* — B's durable attachment
was both corrupted and downgraded to "not yet uploaded".

**User impact.** Three distinct harms, all from one resumed request:
1. **B's durable content corrupted** — B's attachment reference overwritten with A's path, and B's
   note row stamped with A's server time. That timestamp is what conflict resolution compares, so a
   stale-but-higher A value can make B's later edits look older than the cloud and be refused.
2. **B's sync metadata polluted** — A's confirmed revision written into B's revision map for a
   colliding id, which is exactly the "B gets A's revisions" contamination the brief rates P1, and
   which can later authorise or block a delete against the wrong revision.
3. **An account-boundary leak of identity** — A's object path (`r2:owners/uid-A/...`) is written into
   B's local row. The Worker derives the object key from the authenticated user, so this is not a
   read-path disclosure, but it does put A's namespace coordinates into B's database.

### Fix

One guard, applied at every post-suspension local-mutation site:

```kotlin
private suspend fun requireSameSession(uid: String) {
    val current = uidProvider().getOrNull()
        ?: throw AccountChangedDuringSyncException(uid, "signed out")
    if (current != uid) throw AccountChangedDuringSyncException(uid, current)
}
```

plus `AccountChangedDuringSyncException`, and eight `requireSameSession(uid)` calls placed immediately
before each local write listed in the table above. The uid captured at the top is still the one passed
to every transport method, so remote behaviour is unchanged: A's data keeps going to A's cloud
namespace, and only A's *local* effects are refused. A vanished session counts as changed — if the
provider can no longer name an account, there is nobody whose state these results could belong to.

`runCatching` in the public methods turns the abort into an ordinary failed `Result`, which the
coordinator re-queues; the injected generation guard means that re-queue cannot reach disk.

**Regression test** `AccountSwitchResumeTest` — 3 tests, all green after the fix.

**Validation**
```
.\gradlew.bat :composeApp:desktopTest --tests "com.aus.notelikeus.data.sync.AccountSwitchResumeTest"
.\gradlew.bat :composeApp:desktopTest :composeApp:testDebugUnitTest
```
`desktopTest` 69 files / 491 tests / 0 failures · `testDebugUnitTest` 65 files / 557 tests / 0 failures.

### Deliberately not changed

The revision-aware sync WIP and its semantics were left alone — no revision tracking was removed,
stubbed or bypassed, and `DeleteResult`, `markDeleted(baselineRevision)` and the revise-then-delete
protocol are untouched. The only new behaviour is a refusal, and it only ever fires when the session
has actually changed.

`LocalAccountIsolator`, `DesktopSyncCoordinator`'s generation counter and the Android coordinator were
read but not modified. They are sound on their own terms; D7 is about what happens when their
cancellation is *not* enough, which the brief requires be treated as the normal case.

### D8 — the D7 guard is check-then-act, so a sign-in can still interleave (P0, FIXED)

**Component** `composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/sync/NoteSyncEngine.kt`,
`LocalCommitGate.kt`, `LocalAccountIsolator.kt`

**Invariant violated** Same invariant as D7 — but D7's fix does not establish it. *Account validation
and the local commit must belong to one critical section.*

**Root cause.** `requireSameSession(uid)` is a `suspend` call and so is every DAO write that follows
it, so "immediately before the write" is not atomic. A sign-out/sign-in landing between them lets the
already-authorized write reach the new account's rows. D7 asked "is there a check?"; D8 asks "is the
check atomic with the commit?" It is not. Same invariant, different root cause, different fix shape.

**Deterministic reproduction.** `AccountSwitchResumeTest` gained a second gate, this one *inside* the
DAO write (`GatedNoteDao.updateServerTimestamp` → `MutationGate`), so the test parks the engine after
`requireSameSession(A)` has already returned true and performs the sign-out/sign-in there. The switch
is taken under the same gate `isolate()` uses, so the assertion is order-independent.

*Before the fix:*

```
a sign-in landing between the session check and the local write still must not reach B's row
  expected:<222> but was:<500000>   <- the check passed, then the account changed, then the write landed
```

**Fix — validation and commit in one critical section.** `LocalCommitGate` is a process-wide `Mutex`
(deliberately not per-object: there is one signed-in account per process, and a per-object gate would
silently protect nothing at any site that constructed its own). A new helper:

```kotlin
private suspend fun <T> commitLocally(uid: String, block: suspend () -> T): T =
    localCommitGate.withLock { requireSameSession(uid); block() }
```

wraps **nine** device-local commit sites, and `LocalAccountIsolator.isolate()` now takes the same
mutex around its wipe. That leaves only two orders, both safe:

* isolation first → the re-read inside the commit sees the new account and the commit is refused;
* commit first → it lands on the *old* account's state, which isolation then wipes.

The gate is never held across a network call: every wrapped region is local database and preference
work, so isolation waits for a write, never for a round trip. Remote behaviour is unchanged — the uid
captured at the top is still the one passed to every transport method.

**Regression test** `AccountSwitchResumeTest` — 4 tests (3 D7 + 1 TOCTOU), all green after the fix.

**Validation**
```
.\gradlew.bat :composeApp:desktopTest :composeApp:testDebugUnitTest
```
`desktopTest` 69 files / 492 tests / 0 failures · `testDebugUnitTest` 65 files / 557 tests / 0 failures.

### D9 — the reconciliation writes inside `downloadAllNotes` were ungated (P0, FIXED)

**Component** `NoteSyncEngine.downloadAllNotes`

**Root cause.** D7/D8 covered the post-transport commit in `putNotes`. `downloadAllNotes` has further
device-local writes after its snapshot is fetched — `innerInsert` / `innerUpdate`, and the tombstone
sweep's `noteDao.deleteNote` + `syncStateStore.markDeleted` — and those sit inside loops that *also*
make remote calls (`transport.deleteNote`, `transport.writeTombstone`). That is why they were left
unwrapped: wrapping the loops wholesale would hold `LocalCommitGate` across a network round trip and
stall isolation behind it.

**Deterministic reproduction.** `AccountSwitchDownloadTest` (real Room; a gate on `updateNote` or
`deleteNote` so the two sites fail independently; the sign-out/sign-in is taken under
`LocalCommitGate` exactly as `isolate()` takes it). Before the fix:

```
a cloud-winning reconciliation that resumes after B signs in does not overwrite B's note
  expected:<B's note> but was:<A's cloud copy>
a reconciliation delete that resumes after B signs in does not delete B's note
  A's reconciliation deleted B's note, which merely reused the same note id
2 tests completed, 2 failed
```

The most severe cross-account effect found so far: A's download **overwrote** B's note row, and A's
tombstone sweep **deleted** B's note.

**Fix — split the phases, gate only the local half.**

```kotlin
localNote == null -> { commitLocally(uid) { innerInsert(cloudNote) }; changes++ }
cloudWins        -> { … if (!sameContent(merged, localNote)) { commitLocally(uid) { innerUpdate(merged) } } }
// tombstone sweep: local half inside the gate, remote tombstone write outside it
val deletedAt = now()
commitLocally(uid) { noteDao.deleteNote(localNote.toNoteEntity()); syncStateStore.markDeleted(noteId, deletedAt) }
transport.writeTombstone(uid, noteId, deletedAt)
```

`deletedAt` is hoisted above the block; the local write order is unchanged; no transport call is
inside `commitLocally`.

**Regression test** `AccountSwitchDownloadTest` — 2 tests, green after the fix.
**Validation** `desktopTest` 70 files / 494 tests / 0 failures · `testDebugUnitTest` 65 files / 557
tests / 0 failures.

### LocalCommitGate wiring — CONFIRMED SAFE (security boundary)

The D8 guarantee is only as strong as the wiring: if `NoteSyncEngine` and `LocalAccountIsolator` ever
held *different* mutexes, the check-then-act window would silently reopen with every test still green.
Proved shared, two ways:

**By construction (inspection of every site).**
`LocalCommitGate` is a Kotlin `object`, so `LocalCommitGate.mutex` is a single process-wide instance
(`LocalCommitGate.kt:31-32`). `NoteSyncEngine`'s parameter *defaults* to it
(`NoteSyncEngine.kt:137`), and `LocalAccountIsolator.isolate()` names it directly
(`LocalAccountIsolator.kt:43`) rather than taking it as a constructor parameter. A repository-wide
search for an explicit override (`localCommitGate =`) returns **no hits** — the only
`= LocalCommitGate.mutex` occurrences are the two tests that read the shared instance deliberately.
There is therefore exactly one gate, and no DI scope involved to get wrong. A future refactor that
switches to an injected `Mutex()` parameter *without* a shared binding would break this, which is why
it is pinned below.

**By test (behavioural).** `LocalCommitGateWiringTest`:
* `a default-constructed engine commits through the process-wide gate` — holds
  `LocalCommitGate.mutex`, runs a **default-constructed** engine (the production shape) into
  `reconcileUploads`, parks it via a gated `writeSyncMeta` immediately before its commit, gives it 20
  `yield()`s to get past the lock, and asserts it has **not** completed. If the engine had copied its
  own mutex it would have finished. Releasing the lock then lets it complete, which is also the
  bounded-completion / no-deadlock assertion (bounded by `runTest`'s timeout, so a future deadlock
  fails rather than hangs).
* `isolation that takes the gate first makes the stale commit a no-op` — explicit **Ordering B**:
  isolation acquires the gate first, performs the transition (A's rows and state cleared, uid flipped
  to B, B's colliding note installed), releases; the parked A upload then acquires the gate and its
  `requireSameSession(A)` fails. Asserts the upload's `Result` is a failure **and** that B's note row
  and B's revision map are byte-for-byte unchanged.

**Ordering A** (commit holds the gate → isolation waits → isolation wipes A's state → B clean) is
proven by the D8 test in `AccountSwitchResumeTest`, where the switch is taken under the same gate and
therefore cannot interleave.

### D10 — `ensureLabel` created A's labels in B's library (P1, FIXED)

**Component** `NoteSyncEngine.downloadAllNotes` → `ensureLabel`

**Root cause.** `downloadAllNotes` builds each cloud note via
`cloudNote = record.toNote(::ensureLabel)`, and `ensureLabel` calls `labelDao.insertLabel` for any
label the local library lacks. That call sits inside the reconciliation loop — **after** the snapshot
fetch and after the gated revision block — so the account gate was free when it ran and nothing
re-checked the session before the insert landed. It was the last device-local write in the engine
outside `commitLocally`.

**Deterministic reproduction.** `AccountSwitchLabelTest` (real Room; gate on `LabelDao.insertLabel`;
the sign-out/sign-in is taken under `LocalCommitGate` as `isolate()` takes it, and is given every
chance to interleave before the insert is released). Before the fix:

```
a label insert decided under A does not appear in B's library
  expected:<[B-label]> but was:<[A-only-label, B-label]>
1 test completed, 1 failed
```

**Severity P1, not P0.** Cross-account metadata contamination that is user-visible — B sees a label B
never created, carrying A's label name — but no durable B content is destroyed or disclosed, and no
destructive follow-on is reachable: the note↔label cross-refs that would attach it to B's notes are
written by `innerInsert`/`innerUpdate`, which are gated, so they are refused. B's own label row and
its cross-refs are untouched.

**Fix.** Wrap only the insert — pure local work, no transport inside the block:

```kotlin
val id = commitLocally(uid) {
    labelDao.insertLabel(LabelEntity(name = name.trim()))
}
```

**Regression test** `AccountSwitchLabelTest` — 1 test, green after the fix. `commitLocally` sites:
12 → **13**.

### Attachment lifecycle across an account switch — SAFE BY TEST

`AccountSwitchAttachmentLifecycleTest` (1 test, green). Real Room + the real `AttachmentSyncService`
with an owner-keyed recording staging store, a recording file store and a blob transport returning
A's owner namespace in the object key. A's upload has succeeded remotely and is parked inside
`commitLocally` immediately before the merge write; B is then installed with the **same** `noteId` and
the **same** `attachmentId`, its own staged bytes and its own local file. Asserted: B's title,
content, timestamp, `serverUpdatedAt`, attachment id and `storagePath` all unchanged; no `uid-A` path
in B's row; B's staged bytes exactly intact; B's file source present; no `release` naming owner B —
**and** A's own release did happen, so the completion was genuinely executed rather than skipped.

No code change was required. This is coverage, not a fix.

### Durability hypothesis (same-account partial commit) — hypothesis only, NOT fixed

`commitLocally { noteDao.deleteNote(...); syncStateStore.markDeleted(...) }` spans two persistence
systems. `LocalCommitGate` provides *account isolation*, not transactional atomicity: a cancellation
or error between the two writes can leave A's **own** local state partially applied — a deleted Room
row with no tombstone, or a tombstone with the row still present.

This is deliberately kept **outside H6**: no cross-account impact has been demonstrated, and the
brief forbids fixing it without deterministic reproduction. Recorded here as a separate durability-pass
hypothesis. **UNVERIFIED.**

### Repository-wide post-suspension local-write inventory (D10 follow-up)

D10 changed the strategy: the defect class is not limited to notes and attachments. A systematic
sweep of all production Kotlin outside `NoteSyncEngine.kt` for device-local write call sites
(`noteDao.*`/`labelDao.*`/`syncStateStore.*` writes, staging release, local-file delete, preference
and DataStore writes) found **86 hits**, concentrated as:

| File | Sites | Classification |
| --- | --- | --- |
| `data/repository/NoteRepositoryImpl.kt` | 23 | **SUSPECTED** — see below |
| `data/repository/SettingsRepositoryImpl.kt` | 12 | device-scoped settings, not account-scoped. Likely SAFE BY CONSTRUCTION, **UNVERIFIED** |
| `data/attachments/AttachmentSyncService.kt` | 11 | owner-keyed (`stage`/`release`/`readBytes`/`metadata` all take an owner) → SAFE BY CONSTRUCTION for the staging namespace; note-row effects covered by D2/D4/D7 |
| `androidMain/.../NoteSyncStateStore.kt`, `di/DesktopNoteSyncStateStore.kt` | 10 + 10 | behind the interface the engine guards; the impls take no account identity, so the gate must be — and is — at the call site |
| `data/local/dao/NoteDao.kt` | 6 | query declarations, not call sites |
| `androidMain/.../PendingCloudSyncStore.kt` | 2 | persisted coordinator queue; the desktop equivalent is guarded by the coordinator `generation`, **the Android path is unverified** |
| `data/sync/LocalAccountIsolator.kt` | 2 | the isolation wipe; now inside `LocalCommitGate` |
| `data/local/DatabaseMigrations.kt` | 2 | runs before any account exists |
| `data/mapper/NoteMapper.kt`, `data/local/entity/NoteEntity.kt` | 3 | pure mapping, no IO |
| `DesktopReminderManager.kt`, `SidebarCollapsedStore.kt`, `WindowMetricsStore.kt`, `DesktopPendingSyncStore.kt`, `NavigationIntents.android.kt` | 1 each | reminders, UI prefs, pending-intent state |

**The serious candidate — `NoteRepositoryImpl` (23 sites) — is UNVERIFIED, not CONFIRMED.**
`insertNote`, `insertNoteWithResult`, `insertNoteWithoutSync`, `finalizeImportedNotes`, `restoreNote`,
`updateNote`, `updateNotePositions` and `deleteNote` all write `noteDao.*` rows keyed by a
caller-supplied `noteId`, and **not one of them takes or checks an account identity**. They are driven
from the ViewModels (`EditorViewModel`/`MainViewModel`) in response to user actions and then call
`syncCoordinator.schedule*`. A sign-out landing while an editor save is in flight would therefore let
that write reach the cleared, B-populated database — the same shape as D9, with the editor instead of
the reconciliation loop as the initiator. `LocalAccountIsolator.isolate()` is serialized by
`LocalCommitGate`, which narrows the interleaving but does not obviously prevent it, because these
repository writers never take the gate.

**No fix and no claim.** This needs a deterministic editor-driven account-switch test before it can be
called either way; static suspicion is not evidence.

### Remaining H6 gaps (not claimed)

**H6 is NOT ready to close.** State of the account/session-race surface after D7 + D8:

**Covered by a deterministic test (4):** the post-transport local commit in `putNotes` — both the
"switch before the check" order (D7, 3 tests) and the "switch between check and write" order (D8, 1
test). Both orders are asserted for a colliding note id *and* a colliding attachment id.

**Guarded by a critical section but with no cross-account test (8 further sites):** `restoreNote`,
`deleteNote`, `mergeCloudTombstones`, `deleteAllCloudData`, `reconcileUploads`'s `markReconciled`,
`downloadAllNotes`'s revision-map block, and its `setKnownCloudIds` / `setLastMergedUserId` tail.
Same code shape as the tested one, but "same shape" is not evidence; each needs its own barrier test
before it can be called verified.

**Known NOT yet made atomic, and therefore still exposed:**

* `downloadAllNotes`'s reconciliation loops. `innerInsert(cloudNote)` / `innerUpdate(cloudNote)` and
  the tombstone branch's `noteDao.deleteNote` + `syncStateStore.markDeleted` are device-local writes
  that no `commitLocally` covers. Those loops deliberately interleave local writes with remote calls
  (`transport.deleteNote`, `transport.writeTombstone`), so wrapping them wholesale would hold the gate
  across a network round trip. Closing this needs the local and remote halves split apart first.
* `ensureLabel`'s `labelDao.insertLabel` in the same function, for the same reason.
* One bare `requireSameSession(uid)` remains as an early abort after `fetchCompleteSnapshot`. It is
  now redundant rather than load-bearing, but it is still a check with no critical section.

**Entirely unaudited:**

* **Web H6 — nothing has been inspected.** `hydrateFromRemote`, Supabase session-transition handling,
  the Zustand stores, realtime subscription callbacks, `ownerMeta` / `noteRevisions` /
  `knownCloudIds` / `lastRemoteRevision` writes, delete/restore retry, attachment retry and GC, and
  bootstrap. The brief's warning applies unchanged: an owner-keyed IndexedDB namespace does **not**
  by itself make callback logic safe, and the question "is the owner used at commit the *initiating*
  owner or the *currently authenticated* one?" has not been asked of a single Web path. No
  controlled-Promise tests exist.
* **Kotlin realtime callbacks** — whether a subscription created under A fires after B signs in, and
  whether its handler re-reads the current user at execution time. Not inspected.
* **Android `WorkManager` / `CloudNoteSyncCoordinator`** — whether a job captures an owner identity,
  re-reads auth midway, or can run while B is current. Not inspected, and not runnable here until the
  Robolectric harness gaps in §11 are resolved.
* **Attachment staging across a switch** — `AttachmentSyncService`'s upload / hydration / GC paths
  were not driven across an account switch; only the note-row merge consequence is covered, via D7.
* Scenario-matrix items 4–9 and 13–20 of the brief remain unreproduced.
