# Notelikeus Web (PWA)

React 19 + TypeScript PWA. Local-first IndexedDB, with Supabase Auth + RPC sync when signed in.
Hosted on Cloudflare Pages.

The root [README](../README.md) covers the shared product; this file is about working in `web/`.
Architecture details: [docs/BACKEND_ARCHITECTURE.md](../docs/BACKEND_ARCHITECTURE.md).

## Setup

```bash
# from the repo root
npm run supabase:start

cd web
npm install
cp .env.example .env
```

`web/.env` is gitignored. Local `npm run dev` can use the default demo project from `supabase start`.
A production build needs a hosted `VITE_SUPABASE_URL` and a public `VITE_SUPABASE_ANON_KEY` (the `eyJ…` anon JWT, never a `service_role` or `sb_secret_…` key).

## Everyday commands

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server on http://localhost:5173 |
| `npm run lint` | oxlint (`correctness` as errors) |
| `npm test` | Vitest unit suite |
| `npm run typecheck` | `tsc -b --noEmit` |
| `npm run build` | typecheck + production build |
| `npm run test:e2e` | builds the app (`--mode e2e`), refuses non-localhost Supabase, runs Playwright |
| `npm run deploy:pages` | lint + tests + build, then Wrangler Pages deploy |
| `npm run verify:pages` | After `npm run build`, checks `dist/_headers` + `dist/_redirects` |

Repo root: `npm run pages:verify` builds and checks Pages headers. Optional CI deploy: GitHub Actions → **Cloudflare Pages CI** → **Run workflow** (requires `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`).

E2e builds (`--mode e2e`) use `.env.e2e`, which points only at local Supabase and enables email/password test login.

## Architecture map

- **State** — Zustand stores (`src/store/`): auth, notes, ui. IndexedDB is the durable local database.
- **Remote** — `src/lib/supabase/` RPC + Realtime; merge helpers in `src/lib/notes/remoteMerge.ts`.
- **Cloud mapper** — `src/lib/mappers/noteCloudMapper.ts` keeps field parity with Kotlin `Note` / backup JSON.
- **Rendering** — plain React text nodes throughout; no `dangerouslySetInnerHTML` anywhere.
- **Styling** — Tailwind CSS v4, theme-reactive colours via CSS variables.
- **PWA** — `vite-plugin-pwa` injectManifest with `src/sw.ts`: Workbox precache, SPA navigation, reminders.

## Android parity

The three clients must agree on the data model or sync corrupts it:

| Item | Value |
|------|--------|
| Note id | Client-generated string (`localId`) |
| Display timestamp field | `timestamp` |
| Authoritative sync field | server `revision` (bigint); `serverUpdatedAt` remains a display/conflict hint |
| Note colors | Same ARGB integers as Android `Color.kt` |
| Backup format | JSON v3 (Android-compatible, importable both ways) |

Row-level security and RPCs live under `supabase/`.

## Icons

Production PNG icons live in `public/icons/` (192 and 512). `favicon.svg` remains for browser tabs.

## CI

`.github/workflows/web.yml` runs lint + unit tests + build on every PR.
`.github/workflows/cloudflare-pages.yml` verifies Pages artifacts.
`.github/workflows/supabase.yml` applies migrations and runs pgTAP.
