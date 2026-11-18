# Audit, fixes and polish pass — 2026-11-18

Baseline: `main` at `4c0fe71d` (v2.7.0 released, after the 2026-11-17 audit fixes #486 and the Worker
key fix #492). Work is on branch `polish/audit-2026-11-18`. Findings already recorded in earlier
audits (`docs/audits/2026-11-05`, `-11`, `-17`, `docs/audit/AUDIT_REPORT.md`) were not re-reported.

**Result: 3 confirmed defects fixed with regression tests (one P2 sync, two P2 web UX), one P3
cross-platform doc mismatch corrected, and one UX change.** Two candidates turned out to be
artefacts of the test environment and are listed as such, not as bugs.

## Issue register

| ID | Sev | Area | Finding | Evidence | Status |
| --- | --- | --- | --- | --- | --- |
| AUD-H01 | P2 | Web sync | An unconfirmed local note could overwrite a confirmed cloud edit on the second sync | Failing test, then fixed | Fixed `04fd2d3f` |
| AUD-H02 | P2 | Web UX | Guest mode was forgotten on every reload; notes looked gone behind the sign-in gate | Reproduced in Playwright; e2e encoded it | Fixed `ccc8094c` |
| AUD-H03 | P2 | Web UX | The filter row always showed nine colour swatches, overflowing on phones | Screenshots at 390 px | Changed `fcdd3b25` |
| AUD-H04 | P3 | Docs | Kotlin `NoteSyncEngine` claimed to mirror the web's one-side-confirmed rule; it does not | Code read | Comment corrected `04fd2d3f` |
| AUD-H05 | P3 | Test | `palette-contrast.spec.ts` would measure a swatch mid-transition | Failed 1 of 3 themes, varying | Fixed `fcdd3b25` |

### AUD-H01 — unconfirmed local note overwrites a confirmed cloud edit

Location: `web/src/lib/supabase/supabaseRemoteNotesDataSource.ts` (`syncNotesWithCloud`, the loop over
local notes the cloud also holds) and `web/src/lib/notes/remoteMerge.ts:9-25`.

Root cause. `shouldUploadOverRemote` lets a local note with `serverUpdatedAt: null` win against a
confirmed cloud copy when its client `timestamp` is later than the server's stamp (rule NEW-03 from
2026-09-18, which predates the durable pending-push queue of 2026-10-12). For such a note that is
*not* in the pending queue — legacy-store migrations and notes written by pre-queue builds — the
reconcile pushed it with the revision this browser had learned for the id:

1. First sync: no revision known yet, so the push is refused. `applyNoteChange` records the cloud's
   revision on refusal, and the `catch` swallowed the conflict while `merged` still held the local
   copy (the comment said it kept the remote winner; it did not).
2. Second sync: the same note is pushed against the recorded revision and accepted. The confirmed
   cloud edit is overwritten, with no conflicted copy, on the strength of a client clock.

Reachability: needs a non-queued, never-stamped local note with the same id as a cloud note and a
clock ahead of the server. Rare, but the result is silent loss of a confirmed edit.

Fix. A note the server never stamped is pushed with no base revision, so the server refuses it
against any existing row. A refusal now keeps both versions — the cloud's on the note, the local
words as a "(conflicted copy)" uploaded as a new note — the same outcome as a refused queued edit.
Network or server errors still leave the note for the next sync.

Regression test: `web/src/lib/notes/unconfirmedLocalOverwrite.test.ts` (failed with
`expected 'older local words' to be 'newer cloud edit'` before the fix).

Not changed on purpose: the NEW-03 clock rule itself. `migrateLegacyLocalNotes` feeds legacy notes
through this path without the queue, so dropping the rule would let the cloud silently win over a
legacy offline edit instead. With the fix the rule only decides what is *tried*; the server decides.

### AUD-H02 — guest mode forgotten on reload

Location: `web/src/store/authStore.ts` (`guestMode` initialised to `false`), `web/src/App.tsx:131`.

The auth screen promises guest notes "will still be waiting next time", and they were in IndexedDB,
but after any reload the app opened the mandatory sign-in gate. A new user saw their library vanish
until they pressed "Continue without an account" again. `e2e/rich-text.spec.ts` and
`e2e/image-ingestion.spec.ts` clicked through the gate after reload, so the suite treated it as
intended.

Fix: `rememberGuestMode` / `forgetGuestMode` / `wasGuestLastLoad` in `lib/auth/sessionHint.ts`,
beside the existing signed-in hint. The store restores guest mode at load without re-running
`enterGuestMode`'s clean-up (which would wipe the guest's own labels and tombstones). Signing in
(`exitGuestMode`) and `reset` forget it, and a signed-in session hint always wins.

Tests: `web/src/lib/auth/guestModeHint.test.ts` (4 cases); the two e2e specs now assert the gate does
*not* appear after reload.

### AUD-H03 — colour swatches crowd the filter row

All nine swatches plus "All colors" were always in the filter row. On a 390 px phone they pushed the
row into a horizontal scroller even for a library with no coloured notes. The row now offers only the
colours notes use (plus any selected), and no colour control when none is used; the Filters sheet
keeps the full palette. Swatch names are attached in palette order before filtering, so a filtered
row still names "Blue" correctly. Unit tests in `FilterRow.test.tsx`.

### Checked and not a bug

- **New note does not take focus / first typing lost** in the desktop app's browser pane.
  `requestAnimationFrame` never fires in that pane (a 1-second probe timed out with the page
  reporting `visible`), so the editor's rAF focus move never ran. Playwright in real Chromium focuses
  the body as `dialog-a11y.spec.ts` asserts.
- **Editor body looked bold** in a downscaled screenshot. Computed `font-weight` is 400.
- **New notes land at the end in Manual order.** Same on Kotlin (`MAX(position) + 1`, ascending).
  Changing it is a cross-platform product decision, not a defect.
- **Production build without a Supabase URL fails.** Intended: the URL pins the CSP. Development
  builds fall back to the local demo project, and guest mode works with the backend down (verified
  with Docker stopped).
- **Settings structure.** Already grouped with Sign out and Delete account last; no change needed.

## Performance

Measured, not guessed. `searchNotes` over synthetic libraries (60-word notes, a fifth labelled), mean
of 10 runs in Vitest on this machine:

| Notes | Empty query | Text | `label:` operator | Typo |
| --- | --- | --- | --- | --- |
| 1,000 | 0.2 ms | 0.1 ms | 0.2 ms | 0.0 ms |
| 5,000 | 0.2 ms | 0.2 ms | 0.3 ms | 0.2 ms |
| 20,000 | 0.7 ms | 0.7 ms | 1.3 ms | 0.7 ms |

No search work is warranted. Bundle (production build, gzip): `main` 66.7 kB, `react-vendor`
68.3 kB, `supabase` 55.2 kB (loaded only for returning users), `EditorScreen` 144 kB lazy (TipTap).
Unchanged by this pass. Not measured: keystroke latency and rerender counts in a real browser, and
IndexedDB timings with a large encrypted library.

## Verification

Run on this machine (Windows 11, Node 24) at the branch tip:

| Check | Result |
| --- | --- |
| `npm run lint` (web) | Pass (warnings only, none new) |
| `npm run typecheck` (web) | Pass |
| `npm test` (web: tooling + Vitest) | 188 files, 1,501 tests, all pass |
| `npm run test:audit-ui` (a11y, reflow, contrast, touch targets; 4 locales) | 136 of 136 pass |
| `npm --prefix workers/attachments test` | 175 of 175 pass |
| Full Playwright suite, Chromium desktop + Pixel 5 | 286 pass, 5 skipped, 17 fail: 16 are specs that create a test account (`account-switch`, `note-lifecycle`, `save-failure`, `sync-status`) and need local Supabase, which was not running; 1 (`journey-f-actions` pin reload) expected the old gate and passed after `e93e20dd` |

Not run on this machine: the signed-in Playwright suites and pgTAP (Docker Desktop was not running), Gradle/Kotlin
tests (only a comment changed in Kotlin), Firefox and WebKit (the configured projects are Chromium
desktop and Pixel 5), and any Android or Windows device check.

## Remaining risks

- The NEW-03 clock rule still decides what the web *shows* for an unconfirmed note until the server
  refuses it. Routing legacy migrations through the pending queue would let it be removed.
- Signed-in sync scenarios (two devices, wipe while offline, account switch mid-request) are covered
  by existing unit tests with fake clouds, not re-run end to end here.
