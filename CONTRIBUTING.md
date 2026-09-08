# Contributing

Notelikeus is a personal project with one maintainer, [@shareef01](https://github.com/shareef01). Issues and pull requests are welcome, and I review them when I have time.

## Issues

Open a GitHub issue for bugs and suggestions. Include the platform (Android, Windows, web), the app version, and what you expected to happen. For anything security related, follow [SECURITY.md](SECURITY.md) instead.

## Pull requests

Fork, branch, and keep the change focused on one thing. Before opening the PR, run whichever suites your change touches:

```bash
cd web && npm run lint && npm run typecheck && npm test
npm run supabase:start && npm run supabase:reset && npm run supabase:test
npm run test:attachments-worker
./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest :androidApp:testDebugUnitTest
```

A few invariants the codebase depends on, so changes that break them will not merge:

- Local storage is written before cloud sync, and a note is never reported as saved before the local write lands.
- Remote conflicts resolve on the server revision, and deletions propagate as tombstones.
- A failed cloud read is never treated as an empty account.
- Tenant isolation is enforced in Postgres (row-level security and authorized RPCs), not only in client code.
- No analytics, telemetry, or tracking SDKs.

Describe what you changed and how you tested it in the PR body.
