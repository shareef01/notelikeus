# Security automation notes

Repository workflows can pin Actions and scan code. Some protections are account- or org-level
and cannot be expressed as source in this tree.

## In-repo

| Workflow | What it does |
|---|---|
| `.github/workflows/codeql.yml` | CodeQL `security-extended` for JavaScript/TypeScript and Java/Kotlin |
| `.github/workflows/dependency-review.yml` | PR dependency review; fails on high+ severity advisories |
| `gradle/verification-metadata.xml` | sha256 pins for the Android/Desktop Gradle graph (see below) |
| Existing CI (`web.yml`, `android.yml`, `desktop.yml`, `supabase.yml`, …) | Lint, unit, build, pgTAP, Playwright |

Actions are pinned to immutable commit SHAs, consistent with the rest of this repository.

## Account / repo settings

Open [Code security and analysis](https://github.com/shareef01/notelikeus/settings/security_analysis):

- **Dependency graph** — required for dependency-review (enabled; the compare API is live)
- **Dependabot alerts** / security updates — optional companion to PR review
- **Secret scanning** and push protection — enabled on this repository as of 2026-09

## Deferred

- **Desktop notes DB SQLCipher:** still deferred (no JVM encrypted-SQLite driver in the Room stack).
- **Web attachment sealing:** still deferred — see `docs/LOCAL_ENCRYPTION_AT_REST.md` (must not be
  described as an XSS mitigation).

## Gradle dependency verification

`gradle/verification-metadata.xml` pins **sha256** checksums for artifacts resolved by the
Android/Desktop Gradle graph. Once this file is present, Gradle verifies every download.

**Regenerate after dependency / AGP / KGP bumps** (merge the updated file in the same PR):

```bat
gradlew.bat --no-configuration-cache --write-verification-metadata sha256 ^
  :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop ^
  :composeApp:desktopTest :composeApp:testDebugUnitTest ^
  :androidApp:assembleDebug :androidApp:assembleRelease help
```

Signatures (`verify-signatures`) stay off for now — many Android artifacts are not signed in a
way Gradle's keyring expects; checksum pinning is the control we want.
