# Implementation roadmap

Ordered by risk reduction, then user value. M1 and M2 are done on branch
`audit/principal-review-2026-10`; the rest are reviewable follow-ups.

## M1 — Concurrent-edit preservation · **done**
- **Objective:** no unsynced edit is ever overwritten or deleted by an inbound cloud change.
- **Scope:** AU-02…AU-05 (AU-01 and AU-09 on web were fixed by #316). Kotlin engine, transport,
  state stores, editor; web editor; Kotlin contract fixture.
- **Acceptance:** regression tests fail on the unfixed code and pass on the new (Kotlin engine; web
  editor against `main` after #316); 13 contract cases pass; all suites green.
- **Rollback:** revert the commits; the only persisted additions are the `synced_timestamps` keys
  (Android prefs / desktop DataStore). Older builds ignore them; no migration to undo.

## M2 — Search performance, readability, accessibility · **done**
- AU-06, AU-10, AU-11, AU-15, AU-16, AU-19. Rollback per commit; no data impact.

## M3 — Verified deletes without a base revision (AU-07) · **done**
- **Objective:** a delete issued without a known revision is either completed safely or visibly
  undone — never silently.
- **Design:** at delete time store `{deletedAt, contentDigest}` (SHA-256 of the normalised authored
  content, reusing `authoredContentEqual`'s fields) in the tombstone store. When the next snapshot
  contains the note: digest equal → `apply_note_delete` at the snapshot's revision (the user decided
  against exactly these words); digest different → restore and show "A note you deleted was changed
  on another device, so it's back" with *Delete again*.
- **Affected:** web `tombstoneStore`, `notesSyncService.partitionTombstoned`; Kotlin
  `NoteSyncStateStore`, `deleteNoteWithToken`, download tombstone branch. Add cases to
  `contracts/sync/`.
- **Risk:** low; adopting a revision only after a content match is the same rule as AU-03.
- **Tests:** digest equal / different / missing; account switch clears digests.

## M4 — Conflict review panel (AU-08) and Kotlin editor refresh (AU-12) · **done**
- **Objective:** resolve a conflict in one place without data loss.
- **Scope:** UI_UX_REDESIGN.md §7.2. "Keep one" moves the other version to Trash. Kotlin editor
  observes its row while open and refreshes when clean (web behaviour).
- **Dependencies:** M1. **Risk:** UI only; resolution actions reuse existing archive/trash paths.
- **Tests:** component tests per action; e2e: two contexts editing one note (Playwright, local
  Supabase).

## M5 — Token single-source (design drift)
- **Objective:** the same note looks the same on every device.
- **Scope:** move `globals.css` and `Color.kt` to `docs/design/tokens.json` values (or move the file
  to `contracts/design/` and read it in tests on both sides, as with the sync contract).
- **Risk:** visual change on every screen → capture before/after with `captureUiScreens.mjs` and
  run both contrast sweeps. **Rollback:** revert.

## M6 — Attachment parity (AU-13, AU-17)
- Web moves to the 15-argument `apply_note_change` with its committed attachment ids; conflicted
  copies re-stage the original's local blobs under the new id.
- **Requires:** local Supabase (pgTAP + e2e), Worker tests. **Risk:** medium — attachment
  commitment is the most intricate protocol in the system; do not start without the database suite.

## M7 — Verification debt
Run what this environment could not, before merging M1:
```bash
npm run supabase:start && npm run supabase:reset && npm run supabase:test
cd web && npm run test:e2e
./gradlew :composeApp:connectedDebugAndroidTest
./gradlew :composeApp:packageMsi
```
Plus a manual two-device check: edit one note offline on a phone and on the web, reconnect both one after the other;
expect the note to hold the text of the device that synced first and a "(conflicted copy)" note with the other device's text, on all devices.

## Deferred / not recommended
- Version history per note: valuable, but it needs server-side storage of old revisions; the
  conflict copy covers the data-loss case now. Revisit after M4.
- Templates, AI summarisation: no evidence of user need in this audit; both add surface without a
  safety or speed gain.
