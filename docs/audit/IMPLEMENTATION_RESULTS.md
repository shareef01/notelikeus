# Implementation results

Branch `audit/principal-review-2026-10`, rebased onto `main` @ `08eb7f9c` (after #316). Nothing
deployed or run against production.

## Rebase onto #316

`main`'s history was rewritten after this work started (the starting commit `3baed663` is
`d012b1bb` there, identical tree), and #316 landed a separate fix for the web half of the
concurrent-edit loss (F-06: durable pending-push markers, inbound applies skip pending rows,
conflicts resolved at push time into "(conflicted copy)" notes). The branch was replayed onto it:

- **Dropped:** this branch's web sync implementation (synced-timestamp baselines, `resolveIncoming`,
  in-transaction race guard). Two dirty-tracking mechanisms on one client would fight; #316's
  design is the more complete one.
- **Kept, re-verified on top of #316:** the web editor fix (the open-editor overwrite still
  reproduced on `main` — its test fails there), all Kotlin fixes (`main` did not change the Kotlin
  engine or editor), UI, search, accessibility and text fixes.
- **Adapted:** the web push queue now builds its conflicted copies with the shared
  `conflictResolution.ts` (one suffix, titles capped at the server's 2,000 characters); the sync
  contract fixture is Kotlin-only, with the web equivalent pinned by #316's durability suite.

## Commits

| Commit | Change | Findings |
|---|---|---|
| `9227d7f7` | Kotlin: conflicted copies in `downloadAllNotes`, tombstone purge, vanished ids; transport adopts a revision only on same words; baselines in the state stores | AU-02, AU-03 |
| `0d50787f` | Kotlin editor: stamp check before update; conflicted copy + snackbar | AU-05 |
| `f558f3c2` | Compose: conflict chip on the card | marker for AU-08 |
| `e57c80d4` | Compose `MutedLight` contrast; theme contrast sweeps on both clients | AU-19 |
| `947448e9` | Web search: ASCII fast path, per-note memo | AU-06 |
| `9193c43b` | Sign-in copy | AU-16 |
| `6c13a262` | Web editor: refresh when clean, conflicted copy + notice when dirty; shared `conflictResolution.ts`, used by the push queue too | AU-04 |
| `a6b5b696` | Web: OS theme default + CSS pre-mount shell; card line breaks and ☐/☑; conflict chip; screenshots | AU-10, AU-11 |
| `cd20ca11` | Web test: offline edit vs delete on another device (passes on #316's design) | AU-01 coverage |
| `0611913e` | Mis-decoded em dashes, redone against `main` (53) | AU-15 |
| `51fde68f` | `contracts/sync/inbound-resolution.json` + Kotlin consumer; `ConflictResolution.decide` | contract |
| `a2052d63`, `5a2bfda8` | Verified deletes: fingerprint at delete time, delete completed only against identical words (web, Kotlin) | AU-07 |
| `515c2635` | detekt fixes on this branch's Kotlin | CI |
| `7d021995` | Light-theme danger text and search hint failed WCAG AA (surfaced by the e2e axe scans once the theme followed the OS) | a11y |
| `a23b567d` | Kotlin editor follows its row and refreshes when nothing is unsaved | AU-12 |
| `b7cfe63f` | Android/Windows notice when a deleted note came back | AU-07 |

## Behaviour changes users can see

- Android/Windows: a note edited on two devices at once appears twice — the original with the
  version that synced first, and "<title> (conflicted copy)" with the other — instead of one
  version vanishing. (Web already did this after #316.)
- Editing a note that another device changes underneath you: on web the editor shows the newer
  version if nothing is unsaved, otherwise your text becomes a copy and the editor says so; on
  Android/Windows the copy and a snackbar.
- Conflict copies carry a "Conflict copy" chip on both card implementations.
- Web follows the OS light/dark setting on first run (stored choices are kept).
- Cards keep line breaks; `- [ ]` shows as ☐. Search stays responsive on large libraries.
- Compose light theme muted text is darker (#717171 → #646464).
- Web editor status line uses the §5.1 vocabulary — Saving…, Saved on this device, Waiting to
  sync, Syncing…, Synced, Offline — changes saved here, Couldn’t sync — and says "Synced" only
  after the server accepted the note's latest edit (AU-27). The main-screen banner counts edits
  waiting offline and says when a push left edits behind, with Retry.
- Web: reconnecting pushes edits made offline straight away, even right after a sync (AU-26).

## Persisted-state changes

| Client | Added | Compatibility |
|---|---|---|
| Android | SharedPreferences key `synced_timestamps` in `note_sync_state` | Ignored by older builds; cleared by `clear()` |
| Windows | DataStore key `sync_synced_timestamps` | Same |
| Web | none (this branch's web baselines were dropped; #316's markers are used) | — |

No schema migration, no backup-format change, no RPC change.

## Tests executed on the rebased branch (2026-10-15)

| Suite | Result |
|---|---|
| Web lint / typecheck | pass / pass |
| Web unit (`npx vitest run`) | **862 / 862** |
| Attachments Worker | **137 / 137** |
| Kotlin detekt | pass |
| Kotlin desktop | **781 / 781** |
| Kotlin Android JVM | **635 / 635** |
| Android app module | **26 / 26** |
| Android instrumented (`connectedDebugAndroidTest`, Pixel 7, Android 16, wireless ADB) | **5 / 5** — `PlaintextDatabaseMigratorTest` only; it does not exercise the sync or editor changes |

Shown failing on the unfixed code: Kotlin `ConcurrentEditPreservationTest` (4 cases, against the
original engine, which `main` has not changed); web `useNoteEditor.remoteChange` (2 cases,
against `main`'s own `useNoteEditor.ts` after #316).

## Measurements

Search (median of 7, Ryzen 7 4800H): 10,000 notes 469 ms → 18 ms per keystroke (133 ms first
query); 1,000 × 2,000-word notes 2,297 ms → 2 ms (425 ms first). Compose light muted on
surfaceVariant 3.87 → 4.70:1.

## Screenshots

`docs/design/screenshots/baseline/` and `.../after/` (16 each), captured before the rebase with
headless Playwright in guest mode. The changes they show (theme default, previews, chip) were
replayed unchanged.

Native, on a Pixel 7 (Android 16, wireless ADB): the branch's debug build installed beside the owner's
app as `com.aus.notelikeus.audit` (temporary Gradle init script, not committed), guest mode, notes
entered through the UI. `android-pixel7-notes-{light,dark}.png` (status bar cropped) show the
"Conflict copy" chip, line-preserving previews and the darker muted text in both themes. The test app
was uninstalled afterwards.

## AU-13 — conflicted copies keep images not uploaded yet

When a conflict turns the local version into a copy, each image of it that exists only on this device
is staged again under the copy with a new attachment id before the copy is written (web
`withPendingAttachments`, Kotlin `AttachmentSyncService.stageForCopy`), and the copy uploads it like
any other pending image. Kotlin also carries `file:` images, as staged copies. The original's own
staging is released once nothing references it; an image that cannot be read or staged stays on the
original rather than vanishing. Tests: web full suite (121 files, 880 tests) incl. a push-conflict and an
editor-fork case on real IndexedDB (fake-indexeddb); Kotlin `ConflictCopyAttachmentsTest` (download
fork, cloud delete, unreadable bytes) on the real file staging store, and an `EditorViewModelTest`
fork case.

## AU-17 — the web on the deferred attachment protocol (with AU-23..AU-25)

Web uploads go to the Worker's `/v2/` route, which leaves the row provisional, and every
`apply_note_change` names the version's `r2:` attachments (`p_attachment_ids`), as Kotlin does;
`restore_note` keeps its 14 arguments. An upload is now invisible until a commit names it, so:
hydration keeps the uploads of a note whose edit is queued; a conflicted push reclaims an upload it
was to name (`reclaimUncommittedUploads` fetches it back for the copy, or the conflict waits for the
next flush); a same-words conflict commits again naming the uploads (AU-25, which also ended spurious
copies of notes with images). Making the test fake return attachment-less payloads, as the server
does, exposed AU-23 (cloud versions written over a local row dropped its unuploaded images) on the
web, and the same review found AU-24 on Kotlin (hydration dropping an unconfirmed note's upload; a
fork stranding an uncommitted one). Tests: web 122 files / 890 tests; Kotlin `DeferredUploadCommitTest`
(the two engine cases fail without the fix).

## Later verification (Docker available)

- **pgTAP** on this branch: `supabase start`, `db reset`, `supabase test db` — 29 files, 298 tests, all
  pass.
- **AU-17 against a real Worker:** `wrangler dev` running `workers/attachments` (local R2) against
  the local Supabase, driven by the web client's own `r2AttachmentBlobStore` and `applyNoteChange`,
  in `saveNote`'s order. The note was pushed, the image was uploaded on `/v2/` (200) and stayed out
  of `list_user_attachments` until committed, the 15-argument commit named it, the snapshot hydrated
  it, and `/v1/` returned the same bytes. A throwaway test, not committed: it needs a running Worker.
- **AU-20** fixed (see FINDINGS); new unit test and e2e case, both failing without the fix.
- **Web e2e** locally: 171 passed, 1 skipped, 2 failed. The two failures are the same reminders case
  (`unstubbed, the app refuses without permission`) on both projects. It fails identically on the
  base commit with this container's older Chromium (build 1194; the pinned Playwright expects
  1243) and passes in CI.

## Sync status (AU-26, AU-27)

`syncStatusStore` is fed by the queue (`notesLocalRepository` publishes its pending ids after every
marker change, and on `clearOwner`) and by `runFlush` (in flight; whether the last flush left an
edit behind). `noteSyncStatus` turns that, plus the local save state and connectivity, into one §5.1
state for the editor; `SyncStatusBanner` (was `OfflineBanner`) covers offline and stuck pushes and
stands aside while the existing reconcile warning, which has its own Retry, is up.

Writing `sync-status.spec.ts` against the real app found AU-26: after reconnecting, the offline edit
was still unpushed 30 s later, because the reconcile the `online` event runs was inside its throttle.
The old status line would have said "Synced" throughout. Tests: web 126 files / 910 tests; the e2e case
passes on both projects, and screenshots of the banner and status line were checked in light and
dark at 1280 px and on Pixel 5.

## Not done / not verified

- MSI and Windows desktop UI: not packaged here.
- Two-device conflict on Android not exercised end to end: Google sign-in is bound to the real
  application id, and the owner's installed build is signed with another key, so the branch build
  could not replace it without deleting its local notes.
- AU-17 against the *deployed* Worker: not checked; it needs the production URL. Run
  `curl -i -X PUT <worker>/v2/attachments/a/b`: an up-to-date Worker answers 401 or 415, one that
  predates the deferred protocol answers 404.
