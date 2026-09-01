# Contributing to Notelikeus

Thank you for your interest in Notelikeus.

## Maintainership

Notelikeus is an independently maintained personal project by [@shareef01](https://github.com/shareef01).

## Reporting Issues & Feedback

- If you encounter a bug or have a feature suggestion, please open an issue on GitHub.
- For security vulnerabilities, please refer to [SECURITY.md](SECURITY.md).

## Submitting Pull Requests

1. Fork the repository and create a feature branch.
2. Ensure all existing tests and builds pass before submitting:
   - Web: `cd web && npm test && npm run lint && npm run typecheck`
   - Rules: `npm run test:rules`
   - Android & Desktop: `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest`
3. Maintain existing invariants:
   - Offline-first architecture and fast local capture
   - Strict tenant isolation and server timestamp conflict resolution
   - Zero telemetry and zero analytics
4. Open a pull request with a concise description of the changes and testing performed.
