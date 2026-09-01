# Notelikeus — Comprehensive Product, Security & Data-Integrity Audit

**Audit date:** 2026-09-01 (session closed 2026-09-02)  
**Repository:** `shareef01/notelikeus`  
**Final `main` commit:** `e8a2326`  
**Release under test:** `1.0.3`

### Shipped via pull request

| PR | Branch | Merged |
|---|---|---|
| [#141](https://github.com/shareef01/notelikeus/pull/141) | `cursor/audit-remediation-2026-09` | Audit remediation (AUD-01–AUD-08) |
| [#142](https://github.com/shareef01/notelikeus/pull/142) | `cursor/repo-governance` | `CONTRIBUTING.md`, `SECURITY.md`, `CODEOWNERS` |
| [#143](https://github.com/shareef01/notelikeus/pull/143) | `cursor/remove-run-app-skill` | Removed local run-app skill + `skills-lock.json` |

---

## Executive summary

An independent, evidence-based audit was performed across **Android**, **Windows Desktop**, **Web/PWA**, and **Firebase Firestore**. Prior audit documents (`docs/AUDIT_REPORT.md`, `docs/FINDINGS.md`, `docs/DECISIONS.md`) were read for context; every claim was re-checked against current code and executed tests rather than accepted on faith.

**No P0 (critical) or P1 (high) defects were confirmed.** Core product invariants — offline-first usage, server-timestamp conflict resolution, tombstone propagation, empty-cloud protection, account isolation, backup validation, and owner-scoped Firestore rules — were verified through automated regression suites and targeted static review.

Eight **P2/P3** issues were confirmed, remediated, and **merged to `main`** (AUD-01–AUD-08). All automated verification suites pass on the merged tree.

### Release verdict

**RELEASE READY BASED ON EXECUTED VERIFICATION**

With explicit limitations: full multi-viewport visual QA across all themes and clients was not executed end-to-end in this session (partial web smoke verification only). Manual Pixel checklist items (biometrics, widget) remain. See §7.

---

## Environment

| Item | Value |
|---|---|
| OS | Windows 11 (build 26200) |
| Node.js | v24.16.0 |
| JDK | OpenJDK 21.0.11 (Microsoft build) |
| Firebase CLI | 15.22.4 |
| Firestore emulator | Started successfully via `firebase emulators:exec` |
| Android device | **Pixel 7** (`panther`, API 36) — connected for instrumented tests |
| Browser automation | Chromium (Playwright + Cursor browser MCP) |
| Gradle | 9.7.1 |

---

## Test matrix (executed 2026-09-01 / 2026-09-02)

| Suite | Command | Result | Notes |
|---|---|:---:|---|
| Web lint | `cd web && npm run lint` | **PASS** | 0 errors; warnings only (style/perf, pre-existing) |
| Web typecheck | `cd web && npm run typecheck` | **PASS** | Re-verified on merged `main` |
| Web unit tests | `cd web && npm test` | **PASS** | 32 files, **271** tests |
| Web sync emulator | `cd web && npm run test:sync` | **PASS** | **9** tests |
| Web production build | `cd web && npm run build` | **PASS** | Editor chunk split; SW precache 49 entries |
| Web E2E | `cd web && npm run test:e2e` | **PASS** | **4** tests (boot, emulator wiring, signed-out interactivity, note lifecycle) |
| Firestore rules | `npm run test:rules` | **PASS** | **41** tests (incl. 4 new negative boundary tests) |
| Compose JVM unit | `./gradlew :composeApp:testDebugUnitTest` | **PASS** | |
| Compose desktop | `./gradlew :composeApp:desktopTest` | **PASS** | |
| Android app unit | `./gradlew :androidApp:testDebugUnitTest` | **PASS** | |
| Android release | `./gradlew :androidApp:assembleRelease` | **PASS** | R8 minify + resource shrink |
| Android instrumented (local) | `./gradlew :composeApp:connectedDebugAndroidTest` | **PASS** | **4/4** on Pixel 7 (API 36), audit-remediation branch |
| Android instrumented (CI) | GitHub Actions `instrumented (30)` + `(36)` | **PASS** | All three PRs (#141–#143) |
| Web coverage | `cd web && npm run test:coverage` | **NOT EXECUTED** | Not required for gate; existing unit suites cover critical paths |
| Manual Android visual QA | `docs/PIXEL_QA_CHECKLIST.md` | **NEEDS MANUAL DEVICE** | Last full pass: Aug 2026 on Pixel 7; not re-run this session |
| Manual Windows visual QA | Desktop app | **NOT EXECUTED** | No desktop launch in this session |
| PWA installed-mode QA | Installed PWA | **NOT EXECUTED** | Service worker logic reviewed statically; install flow not exercised |

---

## Findings

### Confirmed, fixed, and merged

| ID | Sev | Subsystem | Description | Fix | Regression test | Status |
|---|---|---|---|---|---|---|
| **AUD-01** | P2 | Kotlin / Room | `NoteDao.getNoteById` returned `@Relation` data without `@Transaction`, risking incomplete label/checklist hydration | Added `@Transaction` | Existing `NoteRepositoryImplTest` / sync tests exercise note reads | **MERGED** (#141) |
| **AUD-02** | P3 | Web / bundle | Static `EditorScreen` import prevented code-splitting | `lazy()` + `Suspense` in `MainScreen.tsx` | Build output shows separate `EditorScreen-*.js` chunk; E2E note lifecycle passes | **MERGED** (#141) |
| **AUD-03** | P3 | Web / build | `__dirname` in ESM Vite config | `fileURLToPath(new URL(...))` in `vite.config.ts` and `vitest.emulator.config.ts` | `npm run build`, `npm run test:sync` | **MERGED** (#141) |
| **AUD-04** | P3 | Desktop / Compose | Deprecated `Dialog` on desktop about/biometric prompts | `DialogWindow` in `DesktopAboutDialog.kt`, `DesktopBiometricPrompt.kt` | `:composeApp:desktopTest` | **MERGED** (#141) |
| **AUD-05** | P3 | Web / lint | `argb` helper shadowed import name in `colors.ts` | Renamed to `toArgb` | `npm run lint` (shadow warning gone) | **MERGED** (#141) |
| **AUD-06** | P3 | Web / perf | `useNoteActions` handlers re-created every render | Wrapped with `useCallback` | `npm test` | **MERGED** (#141) |
| **AUD-07** | P2 | Compose / a11y | Sidebar collapse control lacked `Role.Button`, `onClickLabel`, used non-mirrored arrow icon | Semantics + `Icons.AutoMirrored.Filled.KeyboardArrowLeft` | Desktop semantics tests; browser snapshot shows `"Collapse sidebar"` button name | **MERGED** (#141) |
| **AUD-08** | P2 | Firestore rules | Missing negative tests for title/checklist/attachment bounds and boolean type enforcement | 4 tests in `tests/firestore.rules.test.mjs` | `npm run test:rules` (41/41) | **MERGED** (#141) |

### Re-validated prior claims (no new defect)

| Area | Prior claim | Independent result |
|---|---|---|
| Silent data loss on empty cloud | `SuspectEmptyCloudException` / `previouslyKnownCloudIds` guard | **VERIFIED** — code + `NoteSyncEngineTest`, `notesSync.emulator.test.ts` |
| Conflict resolution parity | `serverUpdatedAt` wins over client `timestamp` | **VERIFIED** — `NoteSyncEngineTest`, `shouldUploadOverRemote` tests |
| Tombstone TTL / resurrection | 180-day retention; stale device cleanup | **VERIFIED** — sync engine + tombstone tests |
| Account isolation | `LocalAccountIsolator` / `clearLocalUserData()` | **VERIFIED** — `LocalAccountIsolatorTest`, `useAuth`/`useNotesSync` teardown |
| XSS / unsafe HTML | No `dangerouslySetInnerHTML` / `innerHTML` | **VERIFIED** — repo-wide search: 0 matches |
| URL sanitization | `toSafeHref` blocks `javascript:`, `data:`, `vbscript:` | **VERIFIED** — `markdown.test.ts`, Kotlin `SafeHrefTest` |
| Service worker origin check | `postMessage` rejects foreign origins | **VERIFIED** — `web/src/sw.ts:153–162` |
| Android intent spoofing | `InternalNavigationToken` on internal intents | **VERIFIED** — `NavigationIntentsTest` |
| App lock privacy | `FLAG_SECURE` when lock enabled | **VERIFIED** — `MainActivity.kt` |
| SQLCipher / keystore | Encrypted DB; quarantine on key invalidation | **VERIFIED** — `DatabaseKeyManagerTest`, `DatabaseKeyManagerRecoveryTest` |
| Migrations | v1–v10 non-destructive | **VERIFIED** — `DatabaseMigrationSchemaTest` |
| No destructive migration fallback | No `fallbackToDestructiveMigration` | **VERIFIED** — repo search |
| Secrets in git | No private keys / service accounts committed | **VERIFIED** — config uses env vars; only public Firebase client IDs in source |
| Firestore tenant isolation | `isOwner(userId)` on all paths | **VERIFIED** — 41 rule tests incl. cross-user denial |
| Backup validation | Depth/size/array caps on import | **VERIFIED** — `importBackup` tests (web), `NoteBackupImporterTest` (Android) |

### Accepted / documented constraints (unchanged)

| ID | Sev | Area | Description | Status |
|---|---|---|---|---|
| **OBS-01** | Observation | Web reminders | Service-worker timers are best-effort without Push API server | **ACCEPTED** — documented in `sw.ts`, `DECISIONS.md` |
| **OBS-02** | Observation | Desktop storage | Windows SQLite is not SQLCipher-encrypted; relies on OS user profile | **ACCEPTED** — documented in README |
| **OBS-03** | Observation | Firestore rules | Cannot validate checklist/label element schemas inside rules (CEL limitation) | **ACCEPTED** — clients coerce defensively; owner-scoped writes limit blast radius |
| **OBS-04** | Observation | Spark tier scale | Very large libraries (>10k notes) stress first-sync memory/time | **ACCEPTED** — batching exists (400) but not stress-tested at 10k in this session |
| **F2** | Low | Schema | Vestigial `isLocked` column retained to avoid CASCADE migration risk | **WON'T FIX** — `docs/FINDINGS.md` |

### Not executed / needs manual verification

| Item | Blocker |
|---|---|
| Biometric app lock on-device | Requires enrolled biometrics + manual pass |
| Android widget visual pass | Requires home-screen widget install |
| Full theme matrix (System/Light/Dark/AMOLED × 8 colors × 3 clients) | Automated contrast tests exist (`NotePaletteContrastTest`); full visual pass not run |
| Responsive layout at 320–1440px on all clients | Web smoke only at default desktop width |
| Production Firebase deploy / live CSP | Intentionally not run (safety rule) |

---

## Security assessment (summary)

### Android
- `allowBackup="false"`; only `MainActivity` and widget receiver exported.
- Internal navigation protected by session `InternalNavigationToken`.
- SQLCipher + AndroidKeyStore; passphrase quarantine on invalidation.
- App lock enables `FLAG_SECURE`.

### Web / PWA
- Strict CSP, HSTS, `X-Frame-Options: DENY`, `frame-ancestors 'none'` in `firebase.json`.
- React text rendering only; `toSafeHref` for links.
- SW validates message source origin before handling control messages.
- Sign-out clears local stores via `clearLocalUserData()`.

### Firestore
- Owner isolation, field whitelisting, size bounds, server timestamp enforcement.
- Adversarial tests cover cross-user access, forged timestamps, oversized payloads.

### Desktop
- Plaintext SQLite in user profile — documented residual risk (OBS-02).

---

## UI/UX assessment (this session)

### Verified (automated + smoke)
- Web auth screen: clear hierarchy, labeled form fields, Google + local paths.
- Web main screen: semantic landmarks (`searchbox`, layout `radio` group, color filter toggles).
- Web editor (lazy-loaded): labeled title/content fields, toolbar buttons, layout mode radios.
- Kotlin contrast tests cover note colors in light/dark.
- Compose desktop semantics tests cover editor sheet and navigation.

### Not fully verified
- Narrow mobile web (320–390px) overflow/keyboard overlap.
- Android drawer vs desktop side-rail parity at all breakpoints.
- Long-title truncation, RTL text, 200% browser zoom reflow.
- AMOLED theme on OLED hardware.

---

## Data-integrity assessment (summary)

| Concern | Mechanism | Evidence |
|---|---|---|
| Autosave races | Mutex + debounce + flush on exit/background | `EditorViewModelTest` |
| Multi-statement writes | Room `immediateTransaction` | `NoteSyncEngineTransactionTest` |
| Import timestamp spoofing | `serverUpdatedAt = null` on imports | sync conflict tests |
| Account switch mid-sync | Abort listeners + wipe local state | `LocalAccountIsolatorTest`, `useNotesSync` |
| Malformed backup | Depth/count/length validation | import tests all platforms |

---

## Remaining risk

This audit **does not** claim the product is bug-free or fully secure. It demonstrates that, for the environments and suites executed:

1. No confirmed data-loss, tenant-leak, or rules-bypass path was found.
2. Known architectural trade-offs (PWA reminders, desktop plaintext DB, rules element validation ceiling) remain documented.
3. Physical-device behavior beyond instrumented tests (biometrics, widgets, rotation, gesture insets) requires manual QA before treating mobile release as fully demonstrated.

---

## Recommendations (post-merge)

1. **Re-run** `docs/PIXEL_QA_CHECKLIST.md` on a physical device after any UI-touching release.
2. **Keep** Firestore rules tests updated whenever `firestore.rules` changes.
3. **Re-verify** post-merge `main` before tagging a release (`npm test`, `npm run test:rules`, `./gradlew :composeApp:testDebugUnitTest`).

---

*Auditor: autonomous senior engineering audit (Cursor agent). All test results above were produced in this session; nothing marked PASS was assumed from prior reports. Session closed with all remediation merged to `main` at `e8a2326`.*
