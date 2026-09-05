# Notelikeus

A notes app for **Android**, **Windows**, and the **web** — one product, three real clients, sharing a Kotlin Multiplatform core and a React PWA that speaks the same data model. Current release: **[1.0.3](https://github.com/shareef01/notelikeus/releases/tag/v1.0.3)**.

It started as the app I actually wanted to use: something as quick as Google Keep, that works with no signal and no account, and that doesn't hold my notes hostage in one vendor's cloud. Everything here follows from that — local storage first, sync as an optional layer on top, and a backup format I can read without the app.

**Live web app:** Cloudflare Pages (production domain is owner-configured; the old Firebase Hosting URL `notelike.web.app` is retired in source).

---

## Screenshots

### Windows

<p align="center">
  <img src="screenshots/desktop-notes.png" width="90%" alt="Notelikeus on Windows — pinned notes, labels, colour filters, and a collapsible side rail" />
</p>

### Web

<p align="center">
  <img src="screenshots/web-notes.png" width="49%" alt="Web app note grid with pinned notes, colour and label filters, and List / Grid / Compact toggles" />
  <img src="screenshots/web-editor.png" width="49%" alt="Web app note editor with rich-text toolbar" />
</p>

### Android

<p align="center">
  <img src="screenshots/android-notes.png" width="24%" alt="Android notes list with pinned cards, filters, and List / Grid / Compact" />
  <img src="screenshots/android-drawer.png" width="24%" alt="Android navigation drawer — Notes, Archive, Trash, and smart views" />
  <img src="screenshots/android-editor.png" width="24%" alt="Android note editor with rich-text toolbar" />
  <img src="screenshots/android-settings.png" width="24%" alt="Android settings — System / Light / Dark, accent, AMOLED, and app lock" />
</p>

---

## How it works

**Notes live on the device first.** Android and Windows use Room; on Android the database is encrypted with SQLCipher, keyed from the AndroidKeystore. Sync is a layer above that, not a prerequisite — sign in and your notes replicate through Supabase, or don't and the app is still fully usable offline.

**The web client is local-first.** Signed-in and guest notes persist in IndexedDB. A service worker provides the PWA install path and reminders. Supabase Auth, RPC sync, and Realtime are the remote layer.

**Conflicts resolve on a server revision**, not a client clock. A device with a skewed clock — or an imported backup with a hand-edited timestamp — can't overwrite a revision the server has already confirmed. Deletions propagate as tombstones, so a note deleted on one device stays deleted rather than being resurrected by another device syncing later.

**Reads that fail open are treated as suspect.** A cloud fetch returning nothing when notes were expected refuses the sync rather than concluding everything was deleted, on every client. That guard exists because the alternative is silent, unrecoverable data loss.

---

## Features

| | Android | Windows | Web |
|---|:---:|:---:|:---:|
| Notes, labels, checklists, colours | ✓ | ✓ | ✓ |
| Pin, archive, trash, search, filters | ✓ | ✓ | ✓ |
| List / grid / compact layouts | ✓ | ✓ | ✓ |
| Multi-select + bulk actions | ✓ | ✓ | ✓ |
| Swipe actions + undo | ✓ | ✓ | ✓ |
| Manual reorder (list view) | ✓ | ✓ | ✓ |
| Date-grouped sections | ✓ | ✓ | ✓ |
| Collapsible side rail (persisted) | — (drawer) | ✓ | ✓ |
| Appearance (System / Light / Dark, AMOLED, accent) | ✓ | ✓ | ✓ |
| Rich text (bold, italic, links, bullets) | ✓ | ✓ | ✓ |
| Reminders | System notifications | Tray (missed ones surface at next launch) | Service worker |
| Encrypted local database | ✓ SQLCipher | — | — |
| Biometric app lock | ✓ | — | — |
| Home-screen widget | ✓ Glance | — | — |
| Google sign-in + Supabase sync | Optional | Optional | Optional |
| Works with no account | ✓ | ✓ | ✓ |
| JSON backup import / export | ✓ | ✓ | ✓ |
| Installable PWA | — | — | ✓ |

---

## Stack

| Layer | Android / Windows | Web |
|---|---|---|
| UI | Compose Multiplatform | React 19, TypeScript, Tailwind |
| Architecture | MVVM + repositories (shared) | Hooks + Zustand stores |
| Local data | Room (SQLCipher on Android) | IndexedDB |
| Cloud | Supabase Auth + PostgreSQL RPC/Realtime | Supabase Auth + PostgreSQL RPC/Realtime |
| Attachments | Cloudflare Worker + R2 | Cloudflare Worker + R2 |
| Web hosting | — | Cloudflare Pages |
| DI / tooling | Koin, Coroutines, Flow | Vite 8, Vitest |
| Widget | Glance | — |

See [docs/BACKEND_ARCHITECTURE.md](docs/BACKEND_ARCHITECTURE.md) for auth, sync, RLS, and deployment.

---

## Testing

Roughly 250 automated checks, arranged so that each one can actually fail:

| Suite | Covers | Run with |
|---|---|---|
| JVM unit (~200) | Sync engine, mappers, repositories, backup, key management, populated Room upgrades | `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest` |
| Minified release APK | R8 + resource shrink; unsigned in CI | `./gradlew :androidApp:assembleRelease` |
| Instrumented (4) | Database quarantine and encryption migration, on a real device | `./gradlew :composeApp:connectedDebugAndroidTest` |
| pgTAP | RLS, RPC sync, tombstones, attachments | `npm run supabase:test` (needs Docker / `supabase start`) |
| Web unit | Merge logic, conflict resolution, backup parsing, search | `cd web && npm test` |
| Browser end-to-end | Built bundle in Chromium against local Supabase | `cd web && npm run test:e2e` |
| Attachments Worker | JWT auth, path isolation, size/MIME | `npm run test:attachments-worker` |
| Pages artifacts | `_headers` / `_redirects` in `web/dist` | `npm run pages:verify` |

The last web e2e suite needs a local Supabase (`npm run supabase:start`). Android CI minifies a release APK on every PR so an R8 keep-rule break cannot wait for a tag. The instrumented suite needs a connected device or emulator.

---

## Requirements

- Android 8.0+ (API 26) / Windows 10+
- JDK 17+ to build
- Node.js 24 LTS for the web app
- Docker (optional locally) for the Supabase CLI database suite

## Getting started

```bash
# Local backend
npm install
npm run supabase:start
npm run supabase:reset

# Android
./gradlew :androidApp:assembleDebug

# Windows
./gradlew :composeApp:run

# Web
cd web && npm install
cp .env.example .env
npm run dev
```

Production web builds need a hosted `VITE_SUPABASE_URL` and public `VITE_SUPABASE_ANON_KEY`. See [docs/BACKEND_ARCHITECTURE.md](docs/BACKEND_ARCHITECTURE.md).

## Repository layout

| Path | What it is |
|---|---|
| `androidApp/` | Android application (manifest, AppFunctions, widget metadata) |
| `composeApp/` | Shared Kotlin Multiplatform UI, domain, Room, and Windows desktop |
| `web/` | React PWA (IndexedDB + Supabase) |
| `supabase/` | Postgres migrations, seed, pgTAP |
| `workers/attachments/` | Cloudflare Worker + R2 authorization |
| `cloudflare/` | Pages header verification |
| `docs/` | Architecture, superseded migration notes, Pixel QA |
| `archive/` | Removed features kept with restore notes |

---

## Maintainer

Maintained by [@shareef01](https://github.com/shareef01).

## License

Private project — all rights reserved unless otherwise noted.
