# Audit follow-up — 2026-11-17

All four findings in the [audit](REPORT.md) are fixed on branch `fix/audit-2026-11-17`. The audit
text is kept as written, so it still describes `59ef4fed`.

| Finding | What changed | Regression test |
| --- | --- | --- |
| AUD-G01 | `resolveConflictedPush` drops the note's image-removal intent in the transaction that clears its pending marker, so a refused edit can no longer delete an image the cloud version uses. An accepted edit still cleans up as before. | `web/src/lib/supabase/pendingPushes.conflictRemoval.test.ts` (refused and accepted) |
| AUD-G02 | `trashNote` bumps the draft revision, so a save already in flight no longer writes its older snapshot over the flag. `EditorScreen` shows "moved to trash" only when the stored note is trashed. | `AUD-G02` in `web/src/hooks/useNoteEditor.auditRegression.test.tsx` |
| AUD-G03 | `retryRemovedAttachmentCleanup` returns before reading any note when nothing is waiting, reads with `listStoredNotes` so one unreadable note no longer stops cleanup, and empty removal entries are deleted instead of kept (`putLocalEdit` no longer writes them). | Two `AUD-G03` tests in `web/src/lib/attachments/removedAttachmentCleanup.test.ts` |
| AUD-G04 | `supabase.yml`, `attachments-worker.yml` and `account-deletion-e2e.yml` each end in a gate job that always runs and fails unless `changes` succeeded and the tests either passed or were skipped because `changes` said `run=false`. The gate carries the required check's name (`database-tests`, `Worker verify`, `Account deletion e2e`); the job that does the work is now named `… (run)`. | None: see below |

## Verification

- `npx vitest run` in `web/`: 186 files, 1,492 tests, all passing. The AUD-G01 and AUD-G02 tests
  failed with the same assertions before the fixes (see the audit's appendix).
- `npm run typecheck` in `web/`: clean.
- The three workflow files parse as YAML and the job graph was read back: each gate needs
  `changes` and the test job.

## Not verified

- **AUD-G04 has not run on GitHub.** The gate logic was not exercised by a real pull request, and
  the failure case (a failing `changes` job) was not forced. The first pull request from this
  branch touches all three workflow files, so it runs every gate with `run=true`; a later pull
  request that touches none of their paths is the first test of the `run=false` branch.
- **Branch protection** was not read or changed. The gates reuse the existing required check
  names, so no change should be needed, but confirm on the first pull request that
  `database-tests`, `Worker verify` and `Account deletion e2e` report from the gate jobs and that
  nothing is left waiting on a check name that no longer reports.
- **AUD-G03 was not timed.** The test shows that a save with nothing to clean up no longer reads
  the library; how much time that saves on a large library was not measured.
- **Existing empty removal entries** written before this fix stay in a browser's owner metadata
  until that note is edited again. They are harmless: the cleanup skips them without reading notes.
- No Kotlin, database or Playwright test was run; nothing native or server-side changed.
