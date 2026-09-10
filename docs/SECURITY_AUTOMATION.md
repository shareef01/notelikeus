# Security automation notes

Repository workflows can pin Actions and scan code. Some protections are account- or org-level
and cannot be expressed as source in this tree.

## In-repo

| Workflow | What it does |
|---|---|
| `.github/workflows/codeql.yml` | CodeQL `security-extended` for JavaScript/TypeScript and Java/Kotlin |
| `.github/workflows/dependency-review.yml` | PR dependency review; fails on high+ severity advisories |
| Existing CI (`web.yml`, `android.yml`, `desktop.yml`, `supabase.yml`, …) | Lint, unit, build, pgTAP, Playwright |

Actions are pinned to immutable commit SHAs, consistent with the rest of this repository.

## Account / repo settings

Open [Code security and analysis](https://github.com/shareef01/notelikeus/settings/security_analysis):

- **Dependency graph** — required for dependency-review (enabled; the compare API is live)
- **Dependabot alerts** / security updates — optional companion to PR review
- **Secret scanning** and push protection — enabled on this repository as of 2026-09

## Deferred

- **Gradle dependency verification** (`verification-metadata.xml`): valuable, but regenerating and
  maintaining checksums for the full Android/Desktop graph is a dedicated ops task and was not
  added in this pass to avoid breaking CI on every plugin bump.
- **Attachment file encryption at rest:** see `docs/LOCAL_ENCRYPTION_AT_REST.md`.
