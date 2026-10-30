# Notelikeus — Remaining Work After the Audit

State as of 2026-10-29. Findings are defined in `AUDIT_REPORT.md`. Items are ordered by what blocks users first.

## Where things stand

| Area | State |
|---|---|
| F-01 to F-12, FU-1, FU-2 | Fixed, merged in [#316](https://github.com/shareef01/notelikeus/pull/316) |
| Cloud-wipe gap (web kept its local copy after "Delete cloud data") | Fixed, merged in [#317](https://github.com/shareef01/notelikeus/pull/317) on 2026-10-15 |
| Cloud wipe and other devices (§1.4 of the previous version) | Fixed, merged in [#370](https://github.com/shareef01/notelikeus/pull/370) on 2026-10-26: a server-side wipe generation every write must present, so a stale device cannot upload its copy back |
| F-14 (restore overwrote newer edits), FU-4, FU-7, FU-8 | Fixed. F-14, FU-4 and FU-8 in #370; FU-7 (`dependency-submission` in `android.yml`) earlier |
| FU-5 | Already implemented; the finding was corrected in `AUDIT_REPORT.md` |
| Supabase migrations | **Deployed** through `20261020000000` (run 40267662426, 2026-10-26) |
| Attachments Worker | Deployed 2026-10-21. #370 did not change it |
| Editor picture loading | Fixed, merged in [#378](https://github.com/shareef01/notelikeus/pull/378) on 2026-10-28: pictures decode off the main thread, downsampled and cached; the card thumbnail shows first |
| Picture-preview follow-ups (audit of 2026-10-28) | Fixed, merged in [#379](https://github.com/shareef01/notelikeus/pull/379) on 2026-10-29: Android previews capped at 1280 px wide; web previews reload after a quick sign-out and sign-in |
| Web app (Cloudflare Pages) | **Live with #378** (run 40766631457, 2026-10-28). The #379 deploy (run 40799238573) is **waiting for production approval** |
| Android / Windows | Latest release **v2.4.0** (2026-10-26), which contains #370. **19 commits on `main` are unreleased**, including #378 and #379 |

---

## 1. Decisions and actions only you can take

### 1.1 Approve the web deploy of #379
Pages run 40799238573 waits for manual production approval in GitHub Actions. After it goes live, the service worker keeps serving the previous build until a reload.

### 1.2 Release Android and Windows after v2.4.0
v2.4.0 shipped #370 (the wipe protection, F-14, FU-8), so v2.3.2 devices are now the only ones on the pre-#370 protocol and are refused for a wiped account until they update. The picture-loading fixes (#378, #379) reach Android and Windows users only with the next release (Android: push a `v*` tag; Windows: dispatch `desktop.yml` from `main`).

### 1.3 F-05: remote delete with no known base revision (settled)
Fixed, Option B, as the code shows. The records now agree: `AUDIT_REPORT.md` §F-05 and the 2026-10-19 and 2026-10-21 audits were corrected on 2026-10-26. The same change fixed the web UI's handling of an unconfirmed delete: it showed "Delete failed" and dropped Undo.

### 1.4 `docs/DEPLOYMENT_RUNBOOK.md`
Written before 2.1.0. Its header now says so, but its release steps (PR numbers, the version to tag) need rewriting before anyone follows them again.

---

## 2. Open findings (P3: not release blockers)

| ID | Platform | Issue | Suggested fix | Size |
|---|---|---|---|---|
| FU-6 | Web | The Supabase refresh token is in `localStorage` | No change unless the CSP is ever loosened | none |
| DEP-1 | Build | 11 open Dependabot alerts (Bouncy Castle 1 critical and 1 high, jose4j, jdom2, Guava, commons-lang3, httpclient, Kotlin Gradle plugin), all on the Gradle build classpath from `settings.gradle.kts`, none in shipped apps | Upgrade AGP and KGP (Kotlin is 2.1.21; the KGP advisory's only fix is 2.4.20-Beta1) | medium |

---

## 3. Verification never done (needs a device, emulator or real data)

- **Screen reader and font scaling** on Windows (Narrator, 200% scale).
- **Performance with a large library**: thousands of notes, recomposition counts.
- **F-07 on a real device**: import a large backup (hundreds of MB of images) on a low-memory Android device. The streaming path is unit-tested only.
- **Local test runs on this machine**: Gradle cannot open a loopback connection here and Docker was not running, so the Kotlin and database tests only run in CI.

`AUDIT_REPORT.md` records what the third pass did verify on a Pixel 7 and an emulator (cold start, persistence, large font, the instrumented suites).

---

## 4. Suggested order

1. Approve the #379 web deploy (§1.1).
2. Release Android and Windows (§1.2).
3. Rewrite the release steps in `docs/DEPLOYMENT_RUNBOOK.md` (§1.4).
4. Upgrade the build plugins to clear DEP-1 (§2).
5. Device verification (§3) as scheduled work.
