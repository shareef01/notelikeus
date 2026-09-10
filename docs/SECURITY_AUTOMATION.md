# Security automation notes

Repository workflows can pin Actions and scan code. Some protections are account- or org-level
and cannot be expressed as source in this tree.

## In-repo

| Workflow | What it does |
|---|---|
| `.github/workflows/codeql.yml` | CodeQL `security-extended` for JavaScript/TypeScript and Java/Kotlin |
| Existing CI (`web.yml`, `android.yml`, `desktop.yml`, `supabase.yml`, …) | Lint, unit, build, pgTAP, Playwright |

Actions are pinned to immutable commit SHAs, consistent with the rest of this repository.

## Enable in GitHub settings, then re-add

Open [Code security and analysis](https://github.com/shareef01/notelikeus/settings/security_analysis) and ensure:

- **Dependency graph** is enabled (required before any dependency-review workflow can run)
- **Dependabot alerts** / security updates (optional; already useful without a PR Action)
- **Secret scanning** and push protection (already enabled on this repository as of 2026-09)

A `dependency-review` workflow was attempted and removed: GitHub returned
`Dependency review is not supported on this repository` until Dependency graph is on.
After enabling it, restore a SHA-pinned
`actions/dependency-review-action` workflow (see git history on
`security/audit-hardening-pass` / PR #194 for a ready template with `fail-on-severity: high`).

## Deferred

- **Gradle dependency verification** (`verification-metadata.xml`): valuable, but regenerating and
  maintaining checksums for the full Android/Desktop graph is a dedicated ops task and was not
  added in this pass to avoid breaking CI on every plugin bump.
- **Attachment file encryption at rest:** see `docs/LOCAL_ENCRYPTION_AT_REST.md`.
