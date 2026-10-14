# Findings register — principal review, October 2026

Baseline: `main` @ `3baed663` (2026-10-14), rebased onto `main` @ `08eb7f9c` (after #316). Branch: `audit/principal-review-2026-10`.

**Rebase note.** #316 landed a separate fix for the web half of AU-01 (F-06: durable pending-push markers, inbound applies that skip pending rows, conflicts resolved at push time). Two dirty-tracking mechanisms on one client would fight, so this branch's web sync implementation was dropped in favour of #316's; rows below say which design each fix now lives in.
Companion to the historical register [`docs/FINDINGS.md`](../FINDINGS.md) (F1–F64); IDs here are `AU-nn`
so the two never collide.

**Severity.** P0 — irreversible loss of user content or a cross-account breach. P1 — high-impact
reliability, security or core-usability defect. P2 — material quality/usability issue. P3 — polish
or maintainability.

**Status vocabulary.** *Fixed and tested* (regression test written, shown failing on the old code where
noted, passing on the fix). *Open — designed* (fix specified, not implemented). *Open* / *Accepted risk*
/ *Requires runtime verification*.

## Register

| ID | Sev | Platform | Finding | Evidence | Impact | Fix | Test | Status |
|---|---|---|---|---|---|---|---|---|
| AU-01 | P0 | Web | An unsynced local edit was overwritten by a newer cloud revision on every inbound path (incremental pull, first snapshot, reconcile) | Pre-#316 `pullIncrementalChanges` upserted remote rows unconditionally; reconcile kept the cloud winner | Two devices editing one note offline: the edit that syncs second disappears | **Fixed on `main` by #316 (F-06)**: pending-push markers; copy kept at push time. This branch adds a delete-elsewhere test to its suite | `offlineEditDurability.test.ts` (#316) + "keeps the edit as a copy when another device deleted the note" (this branch) | Fixed and tested (by #316) |
| AU-02 | P0 | Android, Windows | Same defect in `NoteSyncEngine.downloadAllNotes`; additionally a cloud tombstone or a vanished id deleted an edited row, and a pending edit on the *same* revision lost the client-clock tie-break when the local clock ran behind | `NoteSyncEngine.kt` `cloudWins -> innerUpdate`, `purgeLocalTombstonedNotes`, previously-known-id purge; `cloudWinsConflict` tie-break | As AU-01, plus edits erased by a delete on another device | `ConflictResolution.kt`; copy inserted in the same commit as the overwrite/delete; same-revision pending edit is pushed | `ConcurrentEditPreservationTest` (6 cases; 4 shown failing on baseline) | Fixed and tested |
| AU-03 | P0 | Android, Windows | An upload conflict adopted the server's revision as the next base, so the *next* upload of the stale copy passed the revision check and erased the other device's edit | `SupabaseNoteTransport.kt` `putNotes` conflict branch | Silent loss of the other device's edit | Adopt only when the cloud already holds the same words. (Web still adopts, but its pending marker keeps the original base, so the adoption no longer reaches a push.) | `ConcurrentEditPreservationTest` | Fixed and tested — Kotlin |
| AU-04 | P0 | Web | The editor ignored remote updates to the open note while uploads took the *latest* base revision, so typing after another device's revision arrived replaced that revision wholesale. **Still present after #316**: `putLocalEdit` takes a fresh marker's base from the revision store at save time | `useNoteEditor.ts` load effect short-circuits on `loadedRouteRef`; `notesLocalRepository.ts` `putLocalEdit` | Other device's edit erased while you type | Clean editor refreshes to the newer version; dirty editor saves the draft as a conflicted copy and shows an inline notice | `useNoteEditor.remoteChange.test.tsx` (shown failing on `main` after #316 too) | Fixed and tested |
| AU-05 | P0 | Android, Windows | Same as AU-04: `EditorState` carries no stamp and the repository preserves the row's *current* stamp on save | `NoteRepositoryImpl.applyExistingNoteUpdateLocally`; `EditorViewModel.persistNote` | As AU-04 | Compare row stamp with loaded stamp before update; own echo adopted, otherwise conflicted copy + snackbar | `EditorViewModelTest` ×2 | Fixed and tested |
| AU-06 | P1 | Web | Search re-folded every note's full text on every keystroke | Measured: 469 ms/keystroke at 10,000 notes; 2,297 ms at 1,000 long notes (Ryzen 7 4800H, vitest) | Typing in search freezes the UI on large libraries | ASCII fast path in `foldForSearch`; per-note-object `WeakMap` memo | `searchText.equivalence.test.ts` (oracle = original implementation, 2,000 random strings) | Fixed and tested  (17.5 ms / 2.1 ms after) |
| AU-07 | P2 | All | A delete issued without a known base revision is never sent; the next snapshot lets the cloud win and the note silently reappears | Web `supabaseRemoteNotesDataSource.deleteNote` (null base), `notesSyncService.partitionTombstoned`; Kotlin `deleteNoteWithToken` `else` branch | Non-destructive, but the user's deletion is undone without explanation | Record an authored-content digest at delete time; on the next snapshot delete at the observed revision only if the digest matches, else restore *and tell the user* (see roadmap M3) | — | Open — designed |
| AU-08 | P2 | All | Conflicts are now preserved but there is no compare/resolve view | — | User must reconcile two notes by hand | Conflict review panel (UI_UX_REDESIGN.md §7) | — | Open — designed |
| AU-09 | P2 | Web | Snapshot apply in replace mode could delete a note created while the snapshot was in flight | Pre-#316 `applyRemotePageAtomically` | Narrow-window loss of a new note | Superseded: #316 writes edits with a marker in the same transaction and replacement skips pending rows | #316 suite | Fixed (by #316) |
| AU-10 | P2 | Web | Default theme ignored the OS (pure black) while Android/Windows follow it | `settingsStore.ts` `DEFAULT_THEME` vs `ThemePreference.kt` | Inconsistent first run; light-mode users get black | Default `system`; CSS-only pre-mount shell follows OS (CSP-safe) | `uiPolish.test.ts` | Fixed and tested |
| AU-11 | P2 | Web | Card previews collapsed line breaks and showed raw `- [ ]` task syntax | Baseline screenshot `docs/design/screenshots/baseline/notes-desktop-1440-light.png` | Lists and addresses unreadable on cards | `whitespace-pre-line`; task markers → ☐/☑ | `uiPolish.test.ts`; after screenshots | Fixed and tested |
| AU-12 | P2 | Android, Windows | The Kotlin editor does not refresh when a download replaces the open note; the save forks instead | `EditorViewModel` has no row observation | Safe, but the user can type over stale text and get an extra copy | Observe the row while open; refresh when clean (as web) | — | Open |
| AU-13 | P2 | All | A conflicted copy carries no attachments (objects are keyed by the original note id) | `conflictedCopyOf` | Images stay on the original note; the copy is text-only | Copy staged blobs under the new id before referencing them | — | Open — accepted for this release |
| AU-14 | P3 | All | Notes synced before baselines existed fall back to a clock heuristic once; a fast-clock device can produce one redundant copy | `isLocallyEdited` fallback | One duplicate, never a loss | Baselines are recorded on the first apply/upload after upgrade | `conflictResolution.test.ts` | Accepted risk |
| AU-15 | P3 | All | 53 mis-decoded em dashes (`ÔÇö`) incl. user-visible sync error text | e.g. `supabaseRemoteNotesDataSource.ts:117` | Garbled banner text | Replaced | existing suites | Fixed |
| AU-16 | P3 | Web | Sign-in copy said sync is for "Android devices" | `AuthScreen.tsx:157` | Misleading | Corrected | screen tests | Fixed |
| AU-18 | — | Web | (Divergences in this branch's own web resolver, found by the contract fixture) | — | — | Moot: the resolver was dropped in the rebase; the fixture now pins the Kotlin rules only | — | Withdrawn |
| AU-19 | P2 | Android, Windows | Muted text (`onSurfaceVariant`, ~20 call sites) failed WCAG AA in the light theme: 3.87:1 on `surfaceVariant`, 4.28:1 on background | `Color.kt` `MutedLight = #717171`; measured by `ThemeTextContrastTest` | Placeholders and timestamps hard to read for low-vision users | `#646464` (4.70:1 / 5.19:1); sweep test over every theme combination on both clients | `ThemeTextContrastTest`, `themeContrast.test.ts` | Fixed and tested |
| AU-17 | P3 | Web | Web uses the 14-argument `apply_note_change` (no attachment commit set) while Kotlin uses 15; the server keeps a compatibility overload | `noteToSupabaseRpcArgs` vs `SupabaseNoteTransport.kt:392`; overload in `20260910000000_attachment_commitment.sql` | Parity gap in attachment commitment semantics | Move web to the 15-arg form with its attachment ids | — | Requires runtime verification |

## Prior findings re-verified in this pass

Only claims checked against current source are listed; the rest of F1–F64 and A-1…A-6 were not
re-verified here and keep their recorded status.

| Prior ID | Claim | Verdict | Evidence |
|---|---|---|---|
| A-2 / F54 | Empty-cloud guard explains missing ids by tombstones | Fixed and tested | `unexplainedMissingCloudIds` used by `loadBaseline`, `syncNotesWithCloud`, `uploadAllNotes`; Kotlin `SuspectEmptyCloudException` |
| A-4 / F56 | Every IndexedDB transaction settles on abort | Fixed in source | `putNotes`, `applyRemotePageAtomically`, `clearOwner`, `replaceAllNotes` all set `onabort` (re-read in this pass, and extended by AU-09) |
| F51 | Web note bodies sealed at rest | Fixed in source | `notesSealing.ts` seals title/content/checklist (`NLN1`); metadata incl. `timestamp` stays readable — relied on by AU-09 |
| §10 re-audit | Single `SECURITY DEFINER` function, `search_path` pinned | Confirmed | Only `20250904010000_verified_firebase_uid_link.sql` contains `SECURITY DEFINER` |
| — | RLS on every table | Confirmed | `notes`, `note_tombstones`, `note_attachments`, `sync_meta`, `firebase_uid_mappings` all `ENABLE ROW LEVEL SECURITY` |
| — | Production CSP pins Supabase and Worker origins | Fixed and deployed | `curl -I https://notelikeus.pages.dev/` (2026-10-14) shows the pinned `connect-src`; the build refuses to finish without a pin-able origin |
| — | Test email/password login cannot ship | Confirmed | `testLoginFlag.ts` gates on `DEV`/`VITE_E2E`; production bundle `index-CgDmRNNl.js` contains no "Test login" |
| F28/F31 | Editor flush/route races | Not reproducible in tests | `EditorScreen.saveRetry`/`durability` suites pass; AU-04 was a different, unreported path |
