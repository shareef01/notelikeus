# Audit 2026

An independent audit of `main` at `0f45a2d6`, followed by the fixes, tests and product work it
justified. Everything below was checked against the code at that commit rather than inferred from
`README.md` or from [`FINDINGS.md`](FINDINGS.md).

---

## 1. Audit summary

### Current architecture

Three clients over one Supabase schema:

| | Android | Windows | Web |
|---|---|---|---|
| UI / domain | Compose Multiplatform, shared `commonMain` | same | React 19 + Zustand, separate implementation |
| Local store | Room + SQLCipher | Room + SQLCipher (Windows default) | IndexedDB |
| Sync engine | `NoteSyncEngine` (shared) | same | `supabaseSyncEngine.ts` + `notesSyncService.ts` |
| Attachments | `AttachmentSyncService` → Worker → R2 | same | `attachmentSyncService.ts` → Worker → R2 |

The authoritative source for each state transition:

| Transition | Authority |
|---|---|
| Local save | The local database. Acknowledged before, and independently of, any upload. |
| Cloud mutation | `apply_note_change` / `apply_note_delete` RPCs under RLS. Never a direct table write. |
| Conflict resolution | The server-assigned `revision` / `server_updated_at`. The client clock is a tie-break only, and only when both sides carry the *same* confirmed revision. |
| Deletion | A tombstone row. Local tombstone first, then the cloud one. |
| Restore | A restore marker written before anything else, cleared only when the live remote note is confirmed. |
| Attachment upload | `finalize_note_attachment_put`, under a per-owner advisory lock, against the bytes that actually arrived. |
| Attachment deletion | `begin_note_attachment_delete` — the claim — before a byte moves. Terminal by CHECK constraint. |
| Realtime | Nothing. It is a wake-up; the pull that follows is the authority. |

### Overall risk assessment

**The system is in good shape, and unusually well documented for its size.** The hard parts — the
revision protocol, tombstones, the attachment claim/finalize ordering, the refusal to read a failed
fetch as an empty account — are all present, correct in the cases they were designed for, and
covered by tests. The historical corpus in `FINDINGS.md` reflects real fixes, not aspirations:
every item I spot-checked (F26, F46, F48, F49, F52) is genuinely fixed at HEAD.

The defects found are of one kind: **places where the second implementation drifted from the
first, or where a guard was correct in principle but did not use a fact it already had.** Both are
exactly what the absence of cross-client contract tests predicts, and both are now covered.

Two are high severity and were reproduced before being fixed:

- **A-1** — a backup exported from the web client could not be imported on Android or Windows at
  all, in any circumstance. Silent, total, and present since the web exporter was written.
- **A-2** — deleting every cloud note from another device left this device **permanently unable to
  sync**, with no way out short of clearing app data.

### What was inspected

| Area | Depth |
|---|---|
| `NoteSyncEngine` and the whole Kotlin sync path | Read in full; modelled with a new chaos harness |
| `supabaseSyncEngine.ts`, `notesSyncService.ts`, `supabaseRemoteNotesDataSource.ts` | Read in full |
| Backup export/import on all three clients | Read in full; new cross-client fixtures |
| `workers/attachments/*` — the whole request pipeline | Read in full |
| All 23 Supabase migrations | Grants and `search_path` audited programmatically; protocol read |
| All 6 GitHub workflows | Read in full |
| IndexedDB layer, staging stores, owner namespacing | Read in full |
| Reminders on all three clients | Read in full |
| Room entities, DAOs, mappers, migrations | Read |
| Editor view models and local-save paths | Read |
| `README.md`, `BACKEND_ARCHITECTURE.md`, `DECISIONS.md`, `FINDINGS.md`, `LOCAL_ENCRYPTION_AT_REST.md`, `PRIVACY_POLICY.md`, `SECURITY.md`, `CONTRIBUTING.md` | Read in full |

Baseline before any change: **756 Kotlin tests, 462 web tests, 110 Worker tests — all green.**

---

## 2. Confirmed findings

### A-1 — A backup exported by the web client cannot be imported on Android or Windows

| | |
|---|---|
| **Severity** | P1 — the documented cross-platform backup path is completely broken in one direction |
| **Component** | `data/backup/BackupData.kt`, `NoteBackupExporter.kt`; `web/src/lib/backup/exportBackup.ts` |

**Evidence.** Reproduced against the real importer, not inferred:

```
java.lang.AssertionError: A backup written by the web client must import on Android and Windows
expected:<Success(notesImported=3, labelsCreated=2)>
but was:<Error(throwable=kotlinx.serialization.json.JsonDecodingException:
  Unexpected JSON token at offset 136: Unexpected symbol 'l' in numeric literal
  at path: $.labels[0].id
  JSON input: .....labels": [ { "id": "label-travel", "name": ".....)>
```

**Root cause.** `BackupData.labels` was typed `List<Label>`, and the domain `Label.id` is `Long?`.
The web client writes its own label ids into the same field, and those are slugs
(`"label-travel"`). `ignoreUnknownKeys` does not help with a *type* mismatch, so the decode throws
and `importFromJson`'s outer `catch (e: Exception)` turns it into a generic
`BackupImportResult.Error`. The user saw "Import failed" with no reason.

**User impact.** Every user who exported on the web and tried to restore on a phone or on Windows
lost the transfer, with no diagnosis. `README.md`'s feature table claims JSON backup import/export
on all three clients.

**Fix.** A dedicated `LabelBackupDto` whose `id` is a raw `JsonElement`, which accepts a number and
a string alike. Neither importer has ever *read* that field — labels are matched by name — so it is
carried rather than dropped, and what Kotlin *writes* is byte-for-byte unchanged, so a file written
by this build still imports into the previous one.

Separately, a genuinely malformed file now reports the decoder's own path
(`"Backup file could not be read: … at path: $.notes[3].timestamp"`) instead of an unexplained
failure.

**Regression test.** `CrossClientContractTest.importsAWebExportedBackupIntoTheSharedNormalForm`,
against `contracts/backup/v3-web-export.json`. It fails on the old code with the error above.

**Verification.** `./gradlew :composeApp:desktopTest --tests "*CrossClientContractTest*"` — 10/10.

---

### A-2 — Sync breaks permanently once the cloud is legitimately emptied

| | |
|---|---|
| **Severity** | P1 — unrecoverable sync failure; every later sync fails, upload included |
| **Component** | `data/sync/NoteSyncEngine.kt` — `downloadAllNotes`, `uploadAllNotes` |

**Evidence.** Reproduced:

```
AssertionError: an empty cloud whose every missing id has a tombstone is explained, not suspect:
got SuspectEmptyCloudException: Cloud returned no notes but 1 were expected —
refusing to delete local copies. Check the connection or sign in again.
```

**Root cause.** `SuspectEmptyCloudException` exists to stop a fetch that *fails open* — an expired
token answering with an empty list rather than an error — from being read as "everything was
deleted elsewhere". Its own comment states the distinguishing fact:

> a genuine remote delete leaves tombstones, which `mergeCloudTombstones` has already applied above

The guard never used it. It compared the whole `knownCloudIds` set against an empty fetch without
subtracting the ids those tombstones explain.

**And it does not heal.** `setKnownCloudIds` only runs at the *end* of a successful download, so
the set that trips the guard is never updated. `uploadAllNotes` carries the same check, so creating
a new note does not clear it either. The device is stuck until app data is cleared.

**User impact.** Delete your last note — or empty the trash — on another device, and this device
reports "Check the connection or sign in again" on every sync, forever.

**Cross-client note.** The web client was already correct: `supabaseRemoteNotesDataSource.ts`
computes `unexplained = knownIds.filter(id => !snapshotIds.has(id) && !tombstoneIds.has(id))` and
has the test `'lets a snapshot empty by deletion through once every note is tombstoned'`. This was
Kotlin-only drift from a decision the web side had already made — the exact failure mode the new
contract tests exist to catch.

**Fix.** One helper, `unexplainedMissingCloudIds`, used by both guards. The safety property is
untouched: a failed-open read leaves the previously-known ids unexplained, so it still refuses.

**Regression tests.** `EmptyCloudAfterDeletingEverythingTest` — four cases, two of which fail
against the old code and two of which (`anUnexplainedEmptyCloudIsStillRefused`,
`anEmptyCloudWithOnlySomeDeletionsExplainedIsRefused`) would fail against a naive "just drop the
check" fix.

**Verification.** `./gradlew :composeApp:desktopTest --tests "*EmptyCloud*"` — 4/4.

---

### A-3 — The web reminder picker pre-filled a time in the past, by the user's UTC offset

| | |
|---|---|
| **Severity** | P2 — a reminder set through the default value silently never fires |
| **Component** | `web/src/components/editor/ReminderPickerDialog.tsx` |

**Evidence.** The null branch of `toInputValue` was:

```ts
const nextHour = new Date();
nextHour.setMinutes(0, 0, 0);
nextHour.setHours(nextHour.getHours() + 1);
return nextHour.toISOString().slice(0, 16);   // UTC
```

A `datetime-local` input's value is **local wall-clock time**; `toISOString()` is UTC. Measured in
`Asia/Kolkata` (UTC+5:30), the pre-filled value parsed back as **more than 240 minutes in the
past**. The non-null branch was correct, so this only affected setting a *new* reminder — the
common case.

**User impact.** A user east of UTC opens the picker, accepts the offered time, and presses Set.
`buildSwReminders` filters `fireAt > now`, so the reminder is dropped without a word. West of UTC
the reminder lands hours later than offered.

**Fix.** `web/src/lib/reminders/reminderTime.ts` builds the input value from the local getters, and
parses it back as local time. Same family as F25 ("every typed date in search resolved a day late
east of UTC"), different site.

**Regression tests.** `reminderTime.test.ts` — 16 cases across `Asia/Kolkata`, `Pacific/Niue`,
`America/New_York`, `Europe/Berlin` and `Australia/Eucla` (a :45 offset, where offset arithmetic
lands on the wrong minute).

---

### A-4 — `putNotes` could hang forever instead of failing

| | |
|---|---|
| **Severity** | P2 — a local write that never settles is worse than one that fails |
| **Component** | `web/src/lib/local/notesLocalRepository.ts` |

**Root cause.** `putNotes` handled `tx.onerror` but not `tx.onabort`. An IndexedDB transaction can
abort with no request having errored — the browser reclaiming storage, or an internal fault — and
`error` does not fire for those. The promise then never settled.

`withStore`, `clearOwner`, `replaceAllNotes` and `applyRemotePageAtomically` all already handled
both; `putNotes` was the one that did not.

**User impact.** `hydrateFromRemote` awaits it before the app reports ready, so a hang leaves the
app stuck on boot. `applyNotes` rolls the optimistic UI back in `.catch`, so a hang leaves the
store claiming a durable write that never happened.

**Fix.** An `onabort` handler, plus `abortNextPutNotesForTests` following the file's existing
`abortNextRemotePageApplyForTests` convention.

---

### A-5 — Web and Kotlin disagreed about an already-elapsed reminder on import

| | |
|---|---|
| **Severity** | P3 — cross-client divergence, visible as a reminder chip for an alarm that cannot fire |
| **Component** | `web/src/lib/backup/importBackup.ts` vs `NoteBackupImporter.kt` |

Kotlin dropped a `reminderTimestamp` already in the past (`takeIf { it > currentTimeMillis() }`);
web kept it. The same backup therefore produced different notes on different platforms.

**Converged on Kotlin's behaviour**, which is the older and better-reasoned one: a reminder that
has already passed either fires immediately on import or shows a chip that will never do anything.
Pinned in `contracts/backup/v3-expected-notes.json` as a third note whose reminder is in the past.

---

### A-6 — `npm run pages:verify` could not run on Windows

| | |
|---|---|
| **Severity** | P3 — a documented verification command unusable on the platform the desktop app targets |
| **Component** | `package.json` |

The script used a `VAR=value command` prefix. npm runs scripts through `cmd.exe` on Windows, where
that is not an assignment but an unrecognised command:

```
'VITE_SUPABASE_URL' is not recognized as an internal or external command
```

CI runs on ubuntu, so it stayed green and hid this. Replaced with
`scripts/ops/verify-pages.mjs`, which sets the placeholders itself — no new dependency, runnable
from a fresh clone. Verified on Windows.

---

## 3. Rejected and suspected findings

Things that looked worth suspecting and turned out to be correct. Recorded so the next audit does
not spend the same time.

| Investigated | Verdict |
|---|---|
| **`SECURITY DEFINER` functions without `SET search_path`** | **Safe.** Programmatically checked all 23 migrations: every `SECURITY DEFINER` function sets `search_path`. Zero exceptions. |
| **RPCs reachable by `anon`** | **Safe.** All 44 `public.` functions created across the migrations have an explicit `REVOKE … FROM PUBLIC, anon`. Checked programmatically, not by sampling — the comment in `20250904000000` explains why the earlier `REVOKE … FROM PUBLIC` alone was insufficient, and the fix is complete. |
| **CI secret exposure on pull requests** | **Safe.** No `pull_request_target` anywhere. Every workflow declares `permissions: contents: read` at the top. Deploy jobs are gated on `github.event_name == 'push'` or `workflow_dispatch`. The Pages `verify` job builds with placeholder credentials specifically so PRs never need secrets — and a comment says so, and says the resulting artifact must never be deployed. |
| **Unpinned GitHub Actions** | **Safe.** Every `uses:` is pinned to a full commit SHA with the version in a trailing comment. |
| **Worker trusting a returned `object_key`** | **Safe.** All four call sites compare the RPC's `object_key` against the locally derived key and 403 on mismatch. The key is derived from the *authenticated* user id, never from input. |
| **Attachment delete/PUT race (F46, F52 follow-up)** | **Fixed at HEAD.** The claim-first ordering, the `delete_claimed_at` / `purge_claimed_at` terminal markers, the CHECK constraint making it structural, and the `terminally_deleted` value-not-exception are all present and covered by pgTAP, including ordered interleavings in `notelikeus_attachment_delete_concurrency.test.sql`. |
| **Web `syncNoteAttachments` releasing staged bytes before the note is persisted** | **Safe, though weaker than Kotlin's.** The release happens after R2 has the bytes *and* `finalize_note_attachment_put` committed, so the metadata row exists and `hydrateNotesWithAttachments` restores the reference on the next pull. Kotlin's `confirmCommittedAttachments` is stricter (it waits for the note revision), but the web path is recoverable rather than lossy. Not changed. |
| **`applyRemotePageAtomically` dropping a stale page silently** | **Correct by design.** `page.lastRemoteRevision < currentCursor` means the page is behind the durable cursor; applying it would move state backwards. |
| **Attachment `pending:`/`r2:` prefix handling across clients** | **Consistent.** Same prefixes, same semantics, same staging-before-reference invariant on all three. |

---

## 4. Architecture improvements

### Cross-client contracts (`contracts/`)

The audit's two P1 findings were both drift between two implementations of one format. There was
no mechanism that could have caught either. There is now: a directory of canonical JSON read by
**both** test suites.

| Fixture | Pins |
|---|---|
| `backup/v3-kotlin-export.json` | A v3 backup exactly as `NoteBackupExporter` writes it |
| `backup/v3-web-export.json` | A v3 backup exactly as `exportNotesBackup` writes it |
| `backup/v3-expected-notes.json` | The notes both importers must produce from **either** file |
| `backup/v4-bundle-manifest.json` | The `.nlkbak` manifest |
| `cloud/note-rpc-args.json` | The `apply_note_change` argument object |
| `cloud/note-row.json`, `note-row-sparse.json` | A note row, full and minimal, and the note both mappers must produce |
| `cloud/tombstone-row.json` | A tombstone row and its deleted-at map |
| `diagnostics/owner-tag-vectors.json` | The redacted account tag both clients derive |

Consumed by `CrossClientContractTest.kt` (10 tests, running on both the Android and desktop
targets) and `crossClientContract.test.ts` (9 tests). No code generation: a directory of JSON plus
a test on each side was sufficient, and is the thing a maintainer can actually read.

**The sparse-row fixture is the one that will earn its keep.** It pins what each client falls back
to for an absent field — the drift that is invisible until a note comes back with the wrong colour.

### A deterministic chaos harness for sync (`SyncChaosHarness`)

The engine's hard cases are all about what survives an interruption between two writes that are not
one transaction. None are reachable from a test that lets every call succeed. Three mechanisms:

- `FaultInjectingTransport.failOnce("writeTombstone")` — fails one named call and then works,
  which is what a dropped connection actually looks like.
- `restartProcess()` — rebuilds the engine over the **same** DAO and state store. In-memory state
  is gone; durable state is not. That is process death from the engine's side.
- Per-account clouds, because that is what RLS produces. A single shared map let account A's
  tombstones reach account B, which would have made cross-account isolation tests pass for the
  wrong reason.

`SyncInvariantTest` states 14 invariants and asserts only user-visible state — never call order or
which branch ran. An engine that reaches the same end state by another route is not a regression,
and a test that says otherwise makes the engine harder to fix.

| Invariant |
|---|
| A locally acknowledged save survives process death during upload |
| An unsynced note is still uploaded after a restart |
| An empty cloud read cannot delete local notes |
| An empty cloud read cannot overwrite remote notes on upload |
| A remote delete is not resurrected by the local copy |
| A delete interrupted before its cloud tombstone stays deleted |
| A restore interrupted mid-flight is not undone by the stale tombstone |
| A delete after a restore wins |
| A newer server revision beats a skewed client clock |
| An account switch carries neither notes nor tombstones |
| Syncing as a different account with leftover state is refused |
| Guest notes upload on a first sign-in |
| Repeating a sync is idempotent |
| The device converges after a storm of failed syncs |

The last one is deliberately deterministic rather than random: a concurrency test you cannot
re-run to the same failure is not worth having.

---

## 5. Product extensions

### Exact reminders (Phase 5)

**Before:** Android and Windows offered three presets and no way to name a moment. Web offered a
`datetime-local` field and no presets. Neither had the other's affordance.

**Now, on all three:** the three quick presets **and** an exact date-and-time choice.

| | Android / Windows | Web |
|---|---|---|
| Presets | In 1 hour, Tomorrow 9:00, Next week | the same three, resolving to the same instants |
| Exact | "Choose date & time" → Material date picker → time picker | `datetime-local`, now in local time |
| Existing reminder | Pre-populates both pickers; confirming unchanged is a **no-op** | same |
| Remove | Yes | Yes |

The shared logic is `ReminderTime.kt` and `reminderTime.ts`, deliberately extracted so the
time-zone behaviour is testable without a UI.

**The hazard this exists for.** Material3's `DatePickerState.selectedDateMillis` is midnight
**UTC** on the selected civil date. Handing that to a local-calendar conversion is a day-boundary
bug waiting for the first user west of UTC: their `2026-07-08T00:00Z` reads as the evening of
July 7 locally, and the reminder lands a day early. `ReminderTime.exactReminder` recovers the civil
date from the picker value **in UTC** — exact, because the value is UTC midnight by contract — and
only then hands it to the platform's local-midnight conversion, reusing the already-tested
`DateUtils.startOfDay(year, month, day)` and `combineDateAndTime`.

**Time-zone and DST coverage** (`ReminderTimeTest`, 12 tests):

| Zone | What it catches |
|---|---|
| `Pacific/Kiritimati` (UTC+14) | the day boundary at the far east |
| `Pacific/Niue` (UTC−11) | the day boundary at the far west — where the picker's units bite |
| `America/New_York` | 02:30 on a spring-forward day (does not exist), 01:30 on a fall-back day (happens twice) |
| `America/Santiago` | a zone where local **midnight itself** is missing on transition day |
| `Europe/Berlin` | rolling onto the next day just before midnight |

A skipped local time resolves *forward* to the first instant that exists, matching what
`combineDateAndTime` already does for typed search dates. The web suite covers the same zones.

**A reminder must not silently shift after restart** is tested directly:
`initialPickerStateRoundTripsAnExistingReminder` asserts that opening an existing reminder and
confirming it unchanged produces the identical instant, and
`ReminderDialogTest.anExistingReminderPrePopulatesTheExactPicker` asserts it through the real UI.

Accessibility and keyboard operation are asserted in `ReminderDialogTest`: the exact-picker row
announces as a button, Next is disabled while no date is selected, Back returns without
confirming, and no new cloud dependency was added.

**Windows** keeps its existing tray/runtime limitation — the picker is identical, but delivery
still requires the app to be running. Not changed here; it is an architecture question, not a
picker question.

### Backup with attachments (Phase 6)

A new `.nlkbak` bundle: a ZIP containing `manifest.json` and `media/<attachmentId>` entries.

**The one structural decision:** `manifest.backup` is a **v3 backup document, verbatim** —
byte-for-byte what the JSON exporter already writes. Everything follows from it:

- Import reuses the **unchanged** v3 importer, so every cap, coercion and validation it already
  performs applies to a bundle too. There is no second note parser to keep in agreement.
- A user with only an older build can rename the file to `.zip`, open it, and lift
  `manifest.json` → `backup` out as a working JSON backup. The new format is not a trap.
- Attachments are additive: they can fail, be dropped, or be absent, and the notes still import.

| Requirement | How |
|---|---|
| Existing JSON v3 imports unchanged | The v3 path is untouched; format is chosen by the file's own **ZIP signature**, not its extension |
| No destructive migration | Nothing is rewritten. JSON export is still offered, beside the bundle |
| Import additive by default | Same rule as v3 — new local ids, and attachment ids **re-minted**, so a second import cannot collide with the first or point two notes at one blob |
| Repeated import is deterministic | Tested: importing the same bundle twice yields two independent copies with disjoint attachment ids |
| Corrupt attachment ≠ lost note | Tested three ways: tampered bytes, missing media entry, unusable id. The note always imports |
| Intelligible partial failure | Every drop carries a reason; the count reaches the confirm dialog *and* the completion toast |
| No cloud access | Only locally staged bytes are read. Cloud-only images are reported as skipped rather than fetched — an export must work offline |
| No secrets | Asserted: the manifest is scanned for `access_token`, `refreshToken`, `supabase`, `eyJhbGciOi`, `knownCloudIds`, `lastRemoteRevision`, `noteRevisions`, `ownerId`, `owners/`, `apikey` |
| Decompression bombs | Entry count, per-entry size, total size, and ratio caps — enforced against the archive's **declared** numbers and again against the bytes that actually arrive, because a hostile archive lies in its headers |
| Zip-slip | **Structurally impossible**: a media entry is addressed as `media/` + an id validated against `^[A-Za-z0-9_-]{1,128}$`. The manifest's `path` field is informational and never used to locate anything. Tested |

Entries are written **stored** (no compression) — attachments are already-compressed images, so
deflating saves almost nothing and not deflating removes a class of failure from the write path.
Reading accepts stored *and* deflated, so a bundle re-zipped by the operating system still imports.

**Staging is per account.** `buildBundleFromNotes` and `applyBundle` take the owner namespace
explicitly rather than resolving it ambiently — reading the wrong one would silently export a
bundle with no images, or, far worse, with the previous account's.

#### Staged roadmap — what is and is not implemented

Implemented as a **complete vertical slice on the web client**, with the format and its contracts
implemented on both. Stated plainly rather than pretended:

| Platform | Export bundle | Import bundle | Import an extracted `manifest.json` |
|---|:---:|:---:|:---:|
| Web | ✅ | ✅ | ✅ |
| Android | ❌ staged | ❌ staged | ✅ (notes recovered; images reported as skipped) |
| Windows | ❌ staged | ❌ staged | ✅ (notes recovered; images reported as skipped) |

The Kotlin clients understand the **manifest** today: `NoteBackupImporter` recognises the wrapper
by `formatVersion`, imports the embedded v3 document through the unchanged path, refuses a newer
format by number, and reports `attachmentsSkipped` so the images are named rather than silently
lost. That is real capability, not a stub, and it makes the format's "the notes are always
recoverable" promise true on every platform today.

**What archive I/O on Kotlin still needs**, and why it was not bundled into this change:
`commonMain` has no ZIP reader. `java.util.zip` is available on both JVM targets but not from
`commonMain`, so it needs either an intermediate `jvmShared` source set (a build change) or an
`expect`/`actual` pair with duplicated implementations. Both are defensible; neither should be
decided in the same change as the format itself, and shipping a second hand-rolled ZIP
implementation without the format having been exercised in the field would be the wrong order.

### Local diagnostics (Phase 7)

A privacy-preserving report on both clients — a settings row that shows the report, then offers to
copy it. Shown before it can be copied, deliberately: asking someone to hand a file to a maintainer
without letting them read it first is the wrong shape for an app whose selling point is that
nothing leaves the device unasked.

**Included:** app version and platform; storage kind, schema version and whether it is encrypted at
rest; account state and a **redacted** account tag; note counts by state; last remote revision;
known-cloud-id count; pending mutation count; tombstone and pending-restore counts; staged
attachment count and bytes; pending upload count; unresolved cleanup count; last successful sync;
last error **category**; realtime subscription status; service-worker state (web).

**Excluded, and enforced:** tokens, JWTs, passwords, note titles and bodies, checklist and label
text, attachment bytes and paths, email addresses, OAuth profile data, and the account id itself.

Two design points worth keeping:

- **The error category, never the message.** `apply_note_change`'s conflict error embeds the remote
  note's *title*. `categorizeSyncError` maps failures onto a fixed enum, and a test asserts that a
  conflict over a note called "Divorce paperwork" produces `conflict` and nothing else.
- **The leak guard throws rather than scrubs.** Silently removing a leaked field would leave the
  code that put it there in place, and the next field it adds would be the one nobody checks. The
  guard runs against the *serialized* report, so a nested object added later is covered without
  anyone remembering to extend a list of paths. Both clients scan for the same needles — including
  `owners/`, `pending:` and `r2:`, caught by structure rather than only by the identifier inside.

The account tag is FNV-1a over the account id, and **both clients produce the same tag**, pinned by
`contracts/diagnostics/owner-tag-vectors.json`. A user troubleshooting on a phone and in a browser
produces reports that can be matched to each other and told apart from someone else's, without
either carrying anything identifying.

---

## 6. Remaining risks

Everything not verified in this environment, with the exact command to run it.

| Risk | Why not verified | Command / procedure |
|---|---|---|
| **Supabase pgTAP suite (24 files)** | Docker daemon not running in this environment; Docker Desktop was launched but did not come up in time | `npm run supabase:start && npm run supabase:reset && npm run supabase:test` |
| **Web Playwright e2e** | Needs local Supabase, i.e. Docker | `cd web && npm run test:e2e` |
| **`backup-import.spec.ts`** | Same. **One assertion in it was edited** to match the new dialog copy (`"notes-only JSON backup"`) and has not been executed | Included in the command above. Re-run before merging |
| **Android instrumented tests** | Need a connected device or emulator | `./gradlew :composeApp:connectedDebugAndroidTest` |
| **Windows MSI packaging** | Not run here | `./gradlew :composeApp:packageMsi` |
| **The Compose date/time pickers on a real phone** | Desktop Compose UI tests exercise the logic and semantics, not touch targets or the platform picker's own rendering | Manual, per `docs/PIXEL_QA_CHECKLIST.md` |
| **Reminder delivery across a real DST change** | The arithmetic is tested in five zones; actual AlarmManager and service-worker delivery across a live transition is not something a test can assert | Manual |
| **Bundle import in a real browser** | `fake-indexeddb` and happy-dom back the tests; `DecompressionStream` and real `Blob`/`File` behaviour are not exercised | Manual, or a new e2e spec |
| **Private/incognito quota behaviour** | Not modelled | Manual |
| **Production Supabase, Cloudflare zone config** | Owner-operated | See `BACKEND_ARCHITECTURE.md` |
| **Desktop/Web encryption at rest** | Deliberately untouched — see below | — |

### On Phase 8 (desktop/web encryption at rest)

Not implemented, and **not** because it was skipped for time. `docs/LOCAL_ENCRYPTION_AT_REST.md`
already contains the feasibility assessment the brief asks for — the Room KMP driver problem, the
native packaging cost, the plaintext-migration path, DPAPI key lifecycle, lost-key behaviour, the
Windows CI job, and the explicit statement that browser-side encryption must **not** be described
as an XSS mitigation. That analysis is current and correct at HEAD.

Nothing found in this audit changes its conclusion, and the recovery semantics for a guest-mode
user (key loss is unrecoverable data loss, with no account to recover from) remain the blocking
question. `D-F51` stands. Adding an encryption library and declaring it done is exactly what the
brief warns against.

---

## 7. Verification matrix

| Command | Result |
|---|---|
| `cd web && npm test` | ✅ **540 passed** (71 files) — was 462 |
| `cd web && npx tsc -b --noEmit` | ✅ exit 0 |
| `cd web && npm run lint` (oxlint) | ✅ 0 errors (warnings pre-existing and unchanged in kind) |
| `./gradlew :composeApp:testDebugUnitTest` | ✅ **455 passed** — was 415 |
| `./gradlew :composeApp:desktopTest` | ✅ **382 passed** — was 324 |
| `./gradlew :androidApp:testDebugUnitTest` | ✅ **17 passed** |
| `npm run test:attachments-worker` | ✅ **110 passed** (5 files) |
| `npm run pages:verify` | ✅ builds, artifacts verified, headers parity verified — **and now runs on Windows** |
| `./gradlew :androidApp:assembleDebug` | ✅ APK produced |
| `./gradlew :androidApp:assembleRelease` | ✅ R8 + resource shrinking, release APK produced |
| `npm run supabase:test` | ⛔ **not run** — Docker daemon unavailable. No migration was changed in this work |
| `cd web && npm run test:e2e` | ⛔ **not run** — needs local Supabase |
| `./gradlew :composeApp:connectedDebugAndroidTest` | ⛔ **not run** — needs a device |

**Totals: 854 Kotlin tests (was 756), 540 web tests (was 462), 110 Worker tests. All green.**

One caveat, recorded honestly. On a web run that shared the machine with an R8 release build — 220s
instead of the usual 24s — a single assertion in `partialRemoteSnapshot.test.ts` failed. That file
is untouched by this work; it uses a fixed real-time `settle(ms)` sleep where the two tests beside
it use `vi.waitFor`, so it is load-sensitive by construction. It passes 7/7 in isolation twice, and
the full suite passes 540/540 with nothing competing. Logged as F61 — a CI hazard rather than a
product defect, because a suite that goes red under load and green on rerun is a suite people stop
reading.

No Supabase migration, RLS policy, grant, or Worker authorization path was modified by this work,
so the un-run database and Worker-integration suites are unaffected by it. They should still be run
before merging, because that is the standing rule and not because anything here suspects them.

---

## 8. Recommended next three projects

Prioritised by user value × risk reduction. Each is scoped, not a gesture.

### 1. Kotlin archive I/O, completing the bundle across all three clients — **DONE**

Landed as F60: shared `jvmMain` codec (`BackupBundleCodec`) plus Android SAF / Desktop chooser
wiring (`BackupBundleTransfer`). See [`FINDINGS.md`](FINDINGS.md) F60 and PRs #200 / #201.

### 2. Make `fetchNotes` on Kotlin as untrusting as the web snapshot is — **DONE**

Landed in `67080b3c` (`IncompleteCloudSnapshotException` + `authoritativeNoteCount`). See
[`FINDINGS.md`](FINDINGS.md) F59.

### 3. A pgTAP concurrency pass over `restore_note` versus attachment deletion — **DONE**

Landed in PR #202 as ordered single-connection interleavings
(`notelikeus_attachment_delete_concurrency.test.sql`): claim→restore, claim→finalize same id,
and confirmed-delete→restore, asserting the CHECK invariant after each step. True two-session
concurrency still needs committed fixtures plus `dblink`/`pg_background`; pgTAP's outer
`begin`/`rollback` hides uncommitted rows from a second session.

---

## 9. Recommended next three projects (post–audit close-out)

The original three in §8 are done. Next priorities by risk × feasibility:

### 1. Gradle dependency verification (`verification-metadata.xml`) — **DONE**

Landed in PR #203: `gradle/verification-metadata.xml` (sha256 pins), regenerate-on-bump documented
in [`SECURITY_AUTOMATION.md`](SECURITY_AUTOMATION.md). Linux CI platform artifacts (`aapt2`,
Skiko, Compose JDK probe, plugin BOM parents) are pinned alongside Windows generation.

### 2. Desktop notes DB SQLCipher + DPAPI key — **DONE**

Landed across PRs #205–#208: DPAPI key, JDBC driver, `PRAGMA rekey` migration, and **default on
for Windows** (`DesktopSqliteFlags.useJdbcSqlite()` → `isWindows()`). Linux/mac Desktop CI keeps
`BundledSQLiteDriver`. Opt out with `notelikeus.desktop.jdbcSqlite=false`. See
[`LOCAL_ENCRYPTION_AT_REST.md`](LOCAL_ENCRYPTION_AT_REST.md) and D25.

### 3. Web attachment sealing (honest threat model) — **DONE**

**Why.** Closes the last client gap for attachment bytes at rest. Must **not** be marketed as an
XSS mitigation — browser JS can always unwrap a key it can use.

**Landed.** WebCrypto AES-GCM (`NLA1`) for IndexedDB pending blobs + dual-read migration;
privacy / diagnostics / LOCAL_ENCRYPTION copy updated for profile-at-rest only.
