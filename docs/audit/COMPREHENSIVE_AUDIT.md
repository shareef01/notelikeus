# Comprehensive audit — October 2026

| | |
|---|---|
| Baseline | `main` @ `3baed663` (2026-10-14); branch rebased onto `main` @ `08eb7f9c` (after #316) |
| Work branch | `audit/principal-review-2026-10` (not pushed) |
| Companion docs | [FINDINGS.md](FINDINGS.md) · [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md) · [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) · [IMPLEMENTATION_RESULTS.md](IMPLEMENTATION_RESULTS.md) · [../design/UI_UX_REDESIGN.md](../design/UI_UX_REDESIGN.md) · [../design/DESIGN_SYSTEM.md](../design/DESIGN_SYSTEM.md) |

## 1. Executive summary

Notelikeus is a mature, heavily audited codebase: 64 prior findings closed, cross-client contract
fixtures, a sync chaos harness, account-isolation fencing, RLS on every table, a CSP pinned at
build time and verified in production. Most of what this audit was asked to check was already
sound and is recorded as re-verified rather than re-reported.

It nevertheless had **one class of silent, irreversible data loss on every platform**: when two
devices edited the same note — the ordinary offline case — whichever edit reached the cloud second
was overwritten without trace. Five distinct paths led there (inbound pull/snapshot/reconcile on web
and Kotlin, the upload-conflict path on both, and the open editor on both). No prior audit found it
because every test asserted *which* side wins a conflict, none asserted that the losing side's
words survive.

All five paths are now fixed and tested. The web inbound/upload paths were fixed concurrently on
`main` by #316 (F-06, pending-push markers); this branch was rebased onto it, dropped its own web
sync implementation in favour of #316's, and supplies the rest: the Kotlin sync and transport fixes,
the open-editor fix on both clients (the web one still reproduced after #316), a conflict marker on
both card implementations, and a contract fixture pinning the Kotlin rules. In every case an unsynced
local edit that collides with a newer cloud version (or a deletion elsewhere) is kept as a
"(conflicted copy)" note, uploaded, and marked in the UI. Alongside, the audit fixed
a measured search performance defect (≈0.5 s per keystroke at 10,000 notes → ≈18 ms), a WCAG AA
contrast failure in the Compose light theme, a cross-platform theme-default inconsistency, unreadable
card previews, garbled error text and inaccurate sign-in copy.

**Verdict:** with this branch merged *and* the database / e2e / device suites run (§9), the product
meets the data-integrity bar this audit set. Without the branch it does not.

## 2. Scope and limitations

Examined: repository docs and both prior audits; web sync engine, data source, local repository,
editor; Kotlin sync engine, transport, state stores, editor; Supabase migrations (RPC signatures,
RLS, definer functions); CSP and production headers; CI workflows; UI via headless captures.

Not executable here, and therefore **not verified in this pass**:

| Not run | Why | Command |
|---|---|---|
| pgTAP database suite | Docker daemon not running | `npm run supabase:start && npm run supabase:reset && npm run supabase:test` |
| Web e2e (Playwright vs local Supabase) | Same | `cd web && npm run test:e2e` |
| Android instrumented tests | No device/emulator | `./gradlew :composeApp:connectedDebugAndroidTest` |
| Windows MSI, desktop UI | Not packaged; no GUI capture of Compose | `./gradlew :composeApp:packageMsi` |
| Two real devices against the cloud | No authorisation to touch production data | Manual, roadmap M7 |

The attachment protocol (F46–F52) and backup bomb/traversal handling were audited in depth by prior
passes; this pass did not re-trace them and records them as unchanged.

## 3. Architecture

See [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md). Two runtimes (KMP + React) sharing a protocol,
not code, is the right call for this product; its cost is that rules and defects are duplicated —
this audit found the same conflict defect independently in both. The new
`contracts/sync/inbound-resolution.json` makes the Kotlin download rules a tested contract; the web
client reaches the same end states through #316's pending-push design, pinned by its durability
suite. A shared two-client fixture would need an adapter over that design (roadmap).

## 4. Data integrity

| Scenario (from the brief) | Before | After |
|---|---|---|
| Two devices edit the same note offline | Second-to-sync edit lost | Both kept (original + conflicted copy) |
| Delete on one device, edit on another | Edit lost | Edit kept as a copy; delete stands |
| Response lost after server commit | Retry conflicts; base adopted blindly | Base adopted only if the cloud holds the same words |
| Tab closes during pending save | Flushed on `pagehide`/`visibilitychange` (existing) | Unchanged |
| Snapshot arrives during a local edit | Edit overwritten | Transaction aborts and re-plans (web); copy (Kotlin) |
| Account switch with pending ops | Fenced by dataset epochs (existing) | Unchanged; baselines cleared with sync state |
| Device clock wrong | Server stamps decide (existing); clock-behind pending edit lost (Kotlin) | Baseline equality — clock-independent |
| Same op retried | Idempotent RPCs (existing) | Unchanged |
| Delete with unknown base revision | Not sent; note silently reappears | **Unchanged — AU-07, designed (M3)** |
| Long offline period | Reconcile on reconnect | Plus conflict preservation |

## 5. Security

Re-verified: RLS on all five tables; a single `SECURITY DEFINER` function with pinned
`search_path`; web RPC arity compatible with the server (14-arg overload retained, AU-17 notes the
parity gap); production CSP pins only the Supabase and Worker origins plus Google sign-in; the
email/password test login cannot ship (`DEV`/`VITE_E2E` gate; absent from the production bundle);
no raw-HTML sinks in the web client; link targets pass `toSafeHref`.

Encryption claims are accurate in the README: SQLCipher with Keystore/DPAPI keys on native, WebCrypto
sealing of note bodies on web as *at-rest profile* protection, explicitly not an XSS control, and
cloud sync is **not** end-to-end encrypted. Nothing in this pass changes those properties; the new
Kotlin `synced_timestamps` map stores timestamps only, no content.

## 6. Performance (measured)

| Search over | Before | After (first query / later keystrokes) |
|---|---|---|
| 1,000 notes | 49 ms | 16 ms / 1.7 ms |
| 10,000 notes | 469 ms | 133 ms / 17.5 ms |
| 1,000 notes × 2,000 words | 2,297 ms | 425 ms / 2.1 ms |

Vitest on a Ryzen 7 4800H, median of 7. Production JS (gzip): react-vendor 68 kB, supabase 55 kB,
app 49 kB, editor (lazy) 13 kB. Not measured: Android/desktop startup, IndexedDB load of 10k
sealed notes (each row decrypts individually — a candidate for a batched open), render cost of
10k cards (grid virtualisation not checked).

## 7. Accessibility

Fixed AU-19 (Compose light muted text 3.87:1 → 4.70:1) and added contrast sweeps over every theme
on both clients. Previously fixed items (focus traps, focus rings, named controls, toggle state,
reorder alternative) were spot-checked in the running app's accessibility tree; no regressions
found. Not audited: full screen-reader pass on Android TalkBack / Windows Narrator, RTL.

## 8. Code quality

The codebase's dominant style — long explanatory comments recording *why* each guard exists — is
an asset for a data-safety system and was followed in all changes. Notable risks: very large files
(`NoteSyncEngine.kt` 1,900+ lines, `EditorScreen.tsx` 830, `MainScreen.tsx` 755); the engine's
generation-fencing boilerplate makes each new rule long to express. Not refactored: stable code,
and a refactor without device tests would add risk, not remove it.

## 9. Testing and CI

CI runs lint, unit, coverage and build (which typechecks) for web; Playwright e2e against local
Supabase; Gradle unit and instrumented suites; pgTAP; Worker tests; CodeQL; dependency review. This
pass added 11 web tests (web suite 856 on the rebased branch) and 10 Kotlin test methods, incl. the Kotlin contract-fixture suite. Gap: no automated
*two-client* test — the contract fixture checks each client's rule in isolation, not their
interaction over a real backend (roadmap M4 e2e).

## 10. Prioritised remediation

1. Merge this branch after running §2's unrun suites (M7).
2. M3 verified deletes (AU-07).
3. M4 conflict review panel and Kotlin editor refresh.
4. M5 token single-source.
5. M6 attachment parity.

## 11. Residual risks

- A conflicted copy carries the images that had not uploaded yet, but not the uploaded ones: those stay
  on the original note in the cloud (AU-13).
- Pre-upgrade notes use a clock heuristic once; a fast-clock device may create one redundant copy
  (AU-14).
- With the Kotlin editor open during a download that already forked the row, continued typing can
  produce a second copy (safe; noisy) — AU-12.
- The fixes are verified by unit, integration-with-fakes and contract tests; they have not yet run
  against a real Postgres or on a physical device.
