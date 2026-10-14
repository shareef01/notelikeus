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
| Web unit (`npx vitest run`) | **856 / 856** |
| Attachments Worker | **137 / 137** |
| Kotlin desktop | **776 / 776** |
| Kotlin Android JVM | **627 / 627** |
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

## Not done / not verified

- pgTAP, Playwright e2e, MSI — no Docker locally; CI runs the first two.
- Native UI not visually captured.
- AU-07, AU-08 (review panel), AU-12, AU-13, AU-17 — designed, not implemented (roadmap M3–M6).
