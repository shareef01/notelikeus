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

## Not configurable from this repository

Enable these in the GitHub organization / repository settings (Security → Code security):

- **Secret scanning** and push protection
- **Dependabot alerts** / security updates (optional; dependency-review covers PR diffs)
- **Private vulnerability reporting**

## Deferred

- **Gradle dependency verification** (`verification-metadata.xml`): valuable, but regenerating and
  maintaining checksums for the full Android/Desktop graph is a dedicated ops task and was not
  added in this pass to avoid breaking CI on every plugin bump.
- **Attachment file encryption at rest:** see `docs/LOCAL_ENCRYPTION_AT_REST.md`.
