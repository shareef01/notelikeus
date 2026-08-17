# Notelikeus Web (PWA)

React 19 + TypeScript PWA. Firestore-native, like the Android and Windows clients, but with the
SDK's offline cache standing in for their local databases. Lives at
[notelike.web.app](https://notelike.web.app).

The root [README](../README.md) covers the shared product/architecture; this file is about
working in `web/`.

## Setup

```bash
cd web
npm install
cp .env.example .env   # then fill in the values (see below)
```

`web/.env` is gitignored. Generate it from the Firebase CLI instead of copying values by hand:

```powershell
# from the repo root
./scripts/setup-web-env.ps1
```

Values are public app identifiers (API key, project id, app id) — the same ones Google ships in
every client config. The web API key is referrer-restricted to this app's domains.

## Everyday commands

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server on http://localhost:5173 |
| `npm run lint` | oxlint (`correctness` as errors) |
| `npm test` | Vitest unit suite (pure logic; no Firebase) |
| `npm run typecheck` | `tsc -b --noEmit` |
| `npm run build` | typecheck + production build |
| `npm run test:sync` | sync layer against a real Firestore emulator, production rules enforced |
| `npm run test:e2e` | builds the app, boots emulators, runs Playwright/Chromium against it |
| `npm run deploy` | **lint + tests + build**, then `firebase deploy --only hosting,firestore:rules` |

`deploy` is deliberately self-gating: it refuses to ship a tree that fails the quick local
checks. The emulator-backed suites (`test:sync`, `test:e2e`) are not part of it — run them
yourself around risky changes (Firebase upgrades, sync logic), or use
`scripts/ci-local.ps1` at the root, which runs the whole CI matrix locally.

E2e builds (`--mode e2e`) use `.env.e2e`, which points Firebase at local emulators with a fake
API key and enables the email/password test login — they can never reach a real project.

## Architecture map

- **State** — Zustand stores (`src/store/`): auth, notes, ui. Real-time updates flow from
  Firestore snapshots into the stores.
- **Firestore layer** (`src/lib/firestore/`) — subscribe/upsert/delete, the cloud mapper
  (field-parity with Android's `NoteCloudMapper.kt`), and the merge logic that decides
  local-vs-remote wins.
- **Rendering** — plain React text nodes throughout; no `dangerouslySetInnerHTML` anywhere.
  Markdown preview is a small custom renderer emitting React elements, with link hrefs
  scheme-sanitized (`toSafeHref`). Search highlighting builds `<mark>` nodes from a string
  split, never HTML.
- **Styling** — Tailwind CSS v4 (CSS-first config in `src/styles/globals.css`), theme-reactive
  colours via CSS variables so all six themes work without rebuilds, animations from
  `tw-animate-css`.
- **PWA** — `vite-plugin-pwa` injectManifest with a custom service worker (`src/sw.ts`):
  Workbox precache, SPA navigation route, and browser-notification reminders that persist in
  Cache Storage and catch up on wake. Same-origin checks on all postMessage handling.

## Android parity

The three clients must agree on the data model or sync corrupts it:

| Item | Value |
|------|--------|
| Firestore path | `users/{uid}/notes/{localId}` |
| Display timestamp field | `timestamp` |
| Conflict-resolution field | `serverUpdatedAt` (Firestore server timestamp; last write wins) |
| Note colors | Same ARGB integers as Android `Color.kt` |
| Backup format | JSON v3 (Android-compatible, importable both ways) |

Firestore security rules live at the repo root (`firestore.rules`) and are deployed by
`npm run deploy`. They are owner-scoped, schema-validated, and reject client-forged
`serverUpdatedAt`; `tests/firestore.rules.test.mjs` proves all of that against the emulator.

## Icons

Production PNG icons live in `public/icons/` (192 and 512). `favicon.svg` remains for browser
tabs.

## CI

`.github/workflows/web.yml` runs lint + unit tests + build on every PR;
`firebase.yml` runs the emulator-backed sync tests and Playwright e2e.
