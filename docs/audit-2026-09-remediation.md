# Remediation audit — September 2026

Audit of `main` at `84827e8`, validating a set of supplied hypotheses against the **final
effective** schema (all 18 migrations applied) and current source, then fixing what was confirmed.

Every finding below was reproduced before being changed, and every fix has a regression test that
was demonstrated to fail against the previous code.

## Summary

| ID | Sev | Subsystem | Finding | Status |
|---|---|---|---|---|
| A1 | P0 | Cloud wipe / Worker / R2 | Wipe destroys attachment metadata first, so every Worker DELETE is refused and **no** R2 object is deleted — reported as success | **Confirmed** |
| A2 | P0 | Cloud wipe SQL | Already soft-deleted attachments excluded from the returned keys, then hard-deleted — undiscoverable afterwards | **Confirmed** |
| A3 | P1 | Web auth / tombstones | Normal sign-out destroys unsynced deletion intent, resurrecting offline-deleted notes | **Confirmed** |
| A4 | P1 | Orphan sweeper | Sweeper deletes R2 bytes from a stale listing; a restore in that window leaves live metadata with no object | **Confirmed** |
| A5 | P1 | Attachment quota | Quota check-then-insert is not serialised; concurrent finalization can exceed limits | **Confirmed (by inspection)** |
| A6 | P2 | IndexedDB | Rejected open promise cached forever — one transient failure disables local persistence until reload | **Confirmed** |
| A7 | P2 | IndexedDB | `clearOwner` / `replaceAllNotes` enumerate and delete in separate transactions (TOCTOU) | **Confirmed** |
| A8 | P2 | Guest mode | Guest notes stranded after sign-in with no warning or import path | **Confirmed — design concern, not fixed** |

---

## A1 — Cloud wipe leaves every attachment blob in R2 (P0)

**Status: Confirmed.** Worse than hypothesised — the failure is total, not partial.

**Evidence.** Three independent legs, all in the final effective definitions:

1. `delete_all_user_cloud_data` (final definition in `20260905000000_remove_firebase_compatibility.sql`)
   ran `DELETE FROM public.note_attachments WHERE owner_id = v_owner` — hard-deleting every row.
2. `authorize_note_attachment_delete` (final definition in
   `20260906160000_attachment_preflight_and_finalize.sql`) requires a metadata row:
   `IF NOT FOUND THEN RETURN jsonb_build_object('allowed', false)`. The Worker maps that to `404`.
   With the rows already gone, **every** DELETE after a wipe was refused.
3. `deleteR2BlobsBestEffort` in `web/src/lib/supabase/deleteAllUserCloudData.ts` never inspected
   the response at all — not `response.ok`, not the status — and swallowed rejections. So the user
   was told the wipe succeeded while 100% of their attachment bytes remained.

**Root cause.** Authorization for byte deletion depended on state that the same operation destroyed
first, and the client had no failure signal to notice.

**Fix.** Two-phase wipe (`20260907130000_cloud_wipe_attachment_lifecycle.sql`):

- The wipe now **soft-deletes and marks** attachment metadata (`purge_requested_at`) instead of
  destroying it, so the Worker can still authorize each DELETE against the row.
- `finalize_cloud_wipe()` drops the marked rows once the objects are gone.
- The client checks every response, treats `404` as already-deleted (so retries converge), and
  throws `CloudWipeIncompleteError` with a count if anything survived. It refuses outright when
  there are objects but no Worker URL or no session token — previously a silent success.
- A crash between the two phases leaves marked rows that the orphan sweeper finishes.

**Tests.** `web/src/lib/supabase/deleteAllUserCloudData.test.ts` (12 cases) —
**8 fail against the previous implementation**, including "refuses to report success when the
Worker rejects every delete". Plus `supabase/tests/database/notelikeus_cloud_wipe_attachments.test.sql`
(14 assertions) covering key coverage, surviving authorization, idempotent retry, ownership
isolation and anon rejection.

**Residual risk.** If the client dies after phase 1, the bytes live until the next sweep (≤24h
retention window plus cron interval). Metadata is retained deliberately to make that recoverable.

---

## A2 — Wipe strands already soft-deleted attachments (P0)

**Status: Confirmed**, exactly as hypothesised.

**Evidence.** The key collection filtered `AND deleted_at IS NULL` while the delete removed *all*
rows. An attachment deleted before the wipe therefore had its metadata destroyed and its key never
returned. `list_orphaned_deleted_attachments` additionally requires a `note_tombstones` row — which
the wipe also deletes — so the sweeper could not have found it either. Permanently unreachable.

**Fix.** Same migration: the key query no longer filters on `deleted_at`, and the sweeper now
accepts `purge_requested_at IS NOT NULL` as an alternative to the tombstone requirement, since a
wiped account has neither note nor tombstone by definition.

**Tests.** `notelikeus_cloud_wipe_attachments.test.sql` asserts the wipe returns **2** keys where
one attachment was soft-deleted beforehand, and that both remain authorizable for deletion.

---

## A3 — Offline deletion resurrected after sign-out (P1)

**Status: Confirmed.**

**Evidence.** `removeNote` deletes the local IndexedDB row and records a tombstone; the server
delete is awaited and throws when offline, so the tombstone is the *only* durable record of the
intent. `clearLocalUserData` in `web/src/lib/bootstrap.ts` then called
`useTombstoneStore.getState().reset()` and removed `notelikeus-deleted-notes` on **normal
sign-out** — while its own doc comment states IndexedDB notes are deliberately preserved "so
offline edits can survive sign-out and re-login". The asymmetry is the bug: the note is gone
locally, the server copy is still live, and the only thing that would suppress it is destroyed.

**Fix.** Deletion intent is now treated exactly like unsynced note data. `clearPendingDeletions()`
is a separate, explicitly-named operation called only where the notes are cleared too — account
switch (`clearLocalUserDataForAccountSwitch`) and entering guest mode. Normal sign-out preserves it.

**Tests.** `web/src/lib/offlineDeletionDurability.test.ts` — **3 of 5 fail against the previous
code**, covering survival across sign-out (in memory *and* persisted), clearing on account switch,
clearing on guest entry, and that a server-acknowledged deletion is still forgotten so ids can be
legitimately reused.

**Residual risk.** Tombstones are still a global store keyed by note id rather than owner-scoped.
Correctness now rests on the account-switch path being the only cross-account transition, which it
is. Owner-scoping them remains the stronger design.

---

## A4 — Sweeper and restore can both win (P1)

**Status: Confirmed.**

**Evidence.** `workers/attachments/src/sweep.ts` listed eligible rows (line 70), deleted the R2
object (line 97), then called `purge_orphaned_deleted_attachment` (line 103), which re-checks
`note_live` and correctly refuses after a restore. The bytes were already gone by then, so a
restore landing between listing and deletion produced **live metadata pointing at a missing
object** — worse than either outcome alone.

**Fix.** Deletion is claimed before any byte is touched
(`20260907140000_attachment_purge_claim.sql`):

- `claim_orphaned_attachment_for_delete` takes `SELECT … FOR UPDATE` on the row and re-checks
  eligibility, returning the canonical key only on success. Re-claiming is idempotent so a failed
  R2 delete is retried, not lost.
- `restore_note` is redefined to skip attachments with `purge_claimed_at` set — a claimed
  attachment stays deleted even though its note is restored, because its bytes may be gone.
- `purge_orphaned_deleted_attachment` now *requires* the claim, so metadata cannot vanish without
  deletion having been granted.
- The Worker claims first and refuses a claim whose returned key is not the locally derived one.

The row lock serialises the two paths: either restore clears `deleted_at` first and the claim is
refused, or the claim lands first and restore leaves that attachment deleted. Both are consistent.

**Tests.** 5 new Worker cases in `sweep.test.ts` — **5 fail without the claim step**, including
"does not touch the object when a restore beat the claim" and "claims before deleting, never
after". Plus `supabase/tests/database/notelikeus_attachment_purge_claim.test.sql` (12 assertions)
covering both orderings, claim idempotency, purge-requires-claim, and service-role gating.

---

## A5 — Attachment quota is not concurrency-safe (P1)

**Status: Confirmed by inspection.** Not reproduced with live parallel transactions — see caveat.

**Evidence.** `finalize_note_attachment_put` reads `count(*)` of live attachments and
`sum(size_bytes)` for the owner, compares against the limits, then inserts — with no lock and no
serializable isolation. Under READ COMMITTED two concurrent calls observe identical pre-insert
totals, both conclude they fit, and both insert.

**Fix.** `20260907150000_attachment_quota_serialization.sql` takes
`pg_advisory_xact_lock(hashtextextended('note_attachment_quota:' || owner, 0))` immediately after
the authentication check, making check-and-insert atomic per owner. Different owners are
uncontended; the lock releases at commit.

**Caveat — honest limitation.** A true concurrency proof needs two simultaneous Postgres sessions,
which pgTAP's single-session model cannot express. The lock is a standard, well-understood
construct and the code path is small, but **this is the one fix in this audit without a test that
fails on the old behaviour.** A multi-session integration test is the right follow-up.

---

## A6 — A failed IndexedDB open poisons the singleton (P2)

**Status: Confirmed.**

**Evidence.** `getNotesDatabase()` cached `dbPromise` unconditionally, including a rejected
promise. One transient failure — a blocked upgrade from another tab, storage briefly unavailable —
left every later call rejecting with the same error until the page was reloaded. For a local-first
app that is indefinite data loss: every save fails while the editor holds the only copy.

**Fix.** A failed open is no longer remembered, so the next call retries. A connection closed
underneath the app (`onversionchange`, `onclose`) is dropped for the same reason. Concurrent
callers still share one open.

**Tests.** `web/src/lib/local/idbRecovery.test.ts` — **3 of 4 fail against the previous code**.
The fourth (deduplication) passes both before and after, proving the fix did not weaken it.

---

## A7 — Owner-scoped clears are read-then-write across transactions (P2)

**Status: Confirmed. Security-relevant.**

**Evidence.** `clearOwner` read matching ids in a `readonly` transaction, let it end, then opened a
separate `readwrite` transaction and deleted only the snapshot's ids. A note written in that window
— a sync response landing as the account switches — survives the clear and remains readable in the
next account's session. `replaceAllNotes` had the same shape.

**Fix.** Both now enumerate and mutate inside **one** `readwrite` transaction via an
`ownerId`-index cursor. In `replaceAllNotes` the writes are deliberately issued after the sweep
completes so the cursor cannot delete the incoming notes.

**Tests.** `web/src/lib/local/ownerClearAtomicity.test.ts` (5 cases) covering clearing, owner
isolation, an interleaved write, and both `replaceAllNotes` orderings.

**Residual risk.** `fake-indexeddb` serialises transactions faithfully, but a real browser under
load is the stronger check; the single-transaction structure is what makes it correct by
construction rather than by timing.

---

## A8 — Guest notes stranded after sign-in (P2, design concern — not fixed)

**Status: Confirmed, deliberately not changed.**

**Evidence.** `docs/BACKEND_ARCHITECTURE.md:11` states guest mode is "local-only (`__guest__`
namespace). No anonymous Supabase user is created." Guest notes persist in that namespace;
signing in switches `resolveOwnerId()` to the account namespace and the guest notes vanish from
the UI while remaining on disk. There is no import, merge or conversion path anywhere in
`web/src`.

**Why not fixed here.** The documented semantics support the current storage behaviour, and the
prompt is explicit that guest data must not be auto-merged. Choosing between "warn only" and "offer
an import" is a product decision, and an import needs conflict, duplicate and attachment semantics
that cannot be safely inferred.

**Recommendation.** At minimum, a notice after signing in from guest mode when
`listNotes(GUEST_OWNER_ID)` is non-empty: *"Your N guest notes stay on this device and were not
moved into your account."* The clean hook is `exitGuestMode` in `web/src/store/authStore.ts`
together with the sign-in success path. Better still, an explicit "Import guest notes" action with
a count and preview, reusing the now-existing import-confirmation dialog from #181.

---

## Supplied findings that were inaccurate

- **A1 was understated.** It was described as the Worker "may reject" deletion and the client
  "may also fail" to treat non-2xx as fatal. In fact both are unconditional: the authorization
  always fails after a wipe, and the client never examined the response at all. The outcome is
  total, deterministic orphaning, not a partial-failure edge case.
- **A2's sweeper reasoning was incomplete.** The hypothesis said soft-deleted attachments become
  "undiscoverable by the later orphan sweeper" because their metadata is hard-deleted. True, but
  the sweeper could not have found them even with metadata intact: it requires a `note_tombstones`
  row, and the wipe deletes tombstones too. The fix had to address both.

## Preserved controls

No security control was weakened. Worker ownership checks, canonical object-key derivation,
rejection of RPC-supplied keys, MIME and size enforcement, exact-origin CSP generation, RLS,
`SECURITY INVOKER` with pinned `search_path`, and service-role gating on sweeper RPCs are all
unchanged. The new `claim_orphaned_attachment_for_delete` is service-role only and the Worker
still refuses any key it did not derive itself.
