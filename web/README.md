# Notelikeus Web (PWA)

Progressive Web App twin of the Android Notelikeus client.

## Post–Step 4 — Settings & backup

- **Profile sheet** — layout, appearance (brand theme, true dark), sync status, Google account, auto-sync
- **Privacy policy** — in-app dialog (web-adapted copy)
- **Cloud sync** — sync now, restore from cloud, sign-out with optional cloud delete
- **Backup** — export/import JSON v3 (Android-compatible format)
- **Auth** — Google sign-in required (no guest mode)
- **Editor** — markdown toolbar (bold/italic/bullet/link), reminders with browser notifications
- **PWA icons** — `public/icons/icon-192.png` and `icon-512.png` from Android launcher art
- **Deploy** — `npm run deploy` from `web/` (Firebase Hosting + Firestore rules)

## Deploy (Firebase Hosting)

From the repository root (after `web/.env` is configured):

```bash
cd web
npm run build
cd ..
firebase deploy --only hosting,firestore:rules
```

Or from `web/`:

```bash
npm run deploy
```

Add your hosting domain (e.g. `notelikeus.web.app`) to Firebase Auth → Authorized domains.

## Step 4 — What's included

- **EditorScreen** — full-screen editor with note-colored background and dynamic text contrast
- **Debounced autosave** — 1000ms delay (matches Android), writes to Firestore offline cache via `upsertNote`
- **ChecklistEditor** — checked items sink to bottom with strikethrough; convert text ↔ checklist
- **EditorOptionsSheet** — color swatches, label toggles, create label, lock, delete
- **Lock overlay** — web unlock gate (biometric deferred to native apps)
- **Editor routing** — `openNewNote` / `openNote` / `closeEditor` in `uiStore`

## Step 3 — What's included

- **App shell** — slide-out drawer (Notes / Archive / Trash), sticky top search bar
- **Top bar** — scroll-aware elevation + divider, view-mode cycle (1–3 columns), profile button
- **Filter row** — border-only chips, color swatches, label chips, sort cycle
- **Masonry grid** — CSS multi-column staggered layout (responsive 1→2→3 columns)
- **List view** — single column with Material-style drag handle on leading edge
- **Note cards** — 16px padding, title/body typography, dynamic contrast, pinned sections
- **Empty states** — archive/trash/active variants with 72px muted icons
- **Google sign-in** — drawer + empty-state CTA (popup auth)

## Step 2 — What's included

- **Types** — `Note`, `Label`, `ChecklistItem`, `Attachment` (Android field parity)
- **Cloud mapper** — `noteToCloudMap` / `cloudMapToNote` (matches Android `NoteCloudMapper.kt`)
- **Firestore repository** — `subscribeToNotes` (`onSnapshot`), `upsertNote`, `deleteNote`, `uploadAllNotes`
- **Hooks** — `useAuth`, `useNotes` piping real-time updates into Zustand
- **Conflict rule** — last-write-wins on `timestamp`; locked notes excluded from upload

Firestore composite index: single-field `timestamp` ordering is automatic. No extra index required for Step 2.

## Step 1 — What's included

- **Vite + React 19 + TypeScript**
- **Tailwind CSS** — true dark (`#000000`), note palette tokens, self-hosted Inter typography
- **vite-plugin-pwa / Workbox** — offline asset caching, auto-updating service worker
- **Firebase v11** — Auth, Firestore (`persistentLocalCache` + multi-tab), Storage SDK

## Setup

```bash
cd web
npm install
cp .env.example .env
```

1. Open [Firebase Console](https://console.firebase.google.com/) → project **notelikeus**
2. Project settings → **Add app** → **Web** (`</>`)
3. Copy the `appId` into `.env` as `VITE_FIREBASE_APP_ID`
4. Authentication → Sign-in method → enable **Google**
5. Add authorized domain: `localhost` (and your production domain later)

## Run

```bash
npm run dev
```

Open http://localhost:5173 — you should see the Step 1 shell and a green Firebase ready banner when `.env` is configured.

## Build

```bash
npm run build
npm run preview
```

## PWA install

In Chrome/Edge: Application tab → Manifest / Service workers, or use the browser install prompt when served over HTTPS (or localhost).

## Android parity

| Item | Value |
|------|--------|
| Firestore path | `users/{uid}/notes/{localId}` |
| Timestamp field | `timestamp` (matches Android cloud mapper) |
| Note colors | Same ARGB integers as Android `Color.kt` |

## Icons

Production PNG icons live in `public/icons/` (192 and 512). `favicon.svg` remains for browser tabs.
