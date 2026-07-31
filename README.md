# Notelikeus

A notes app for **Android** and the **web** (PWA), inspired by the speed and simplicity of Google Keep.

**Live web app:** [https://notelike.web.app](https://notelike.web.app)

- **Android** — offline-first: notes live in an SQLCipher-encrypted Room database. Google Sign-In is optional; when signed in, notes sync to Firestore (auto-sync can be toggled in Settings).
- **Web** — Google Sign-In required. Notes live in Firestore with offline caching and installable PWA support.

## Overview

Notelikeus is a dual-platform notes app built primarily for **personal use** and as a **learning / portfolio project**. It focuses on a fast note-taking flow, offline-friendly behavior, and a matching experience across Android and the web.

## Highlights

- Shared product direction across **native Android** and **web/PWA**
- Secure local-first Android storage with **Room + SQLCipher**
- Optional Google sign-in on Android, required auth on web for clean sync boundaries
- Firestore sync designed to stay within the **Firebase Spark** plan
- Import/export support using an Android-compatible JSON backup format
- UX hardening around sign-out, offline recovery, boot failures, and reminder expectations

## Features

| | Android | Web |
|---|:---:|:---:|
| Notes, labels, checklists, colors | ✓ | ✓ |
| Pin, archive, trash, search, filters | ✓ | ✓ |
| List / grid / compact layouts | ✓ | ✓ |
| Multi-select + bulk actions | ✓ | ✓ |
| Swipe actions + undo | ✓ | ✓ |
| Manual reorder (list view) | ✓ | ✓ |
| Date-grouped sections | ✓ | ✓ |
| Themes (light, dark, OLED, midnight, forest, auto) | ✓ | ✓ |
| Theme-aware note color palette | ✓ | ✓ |
| Rich text (bold, italic, links, bullets) | ✓ | ✓ |
| Reminders | System notifications | Browser / service worker |
| Encrypted local database (SQLCipher) | ✓ | — |
| Optional biometric app lock | ✓ | — |
| Home-screen widget | ✓ | — |
| Google Sign-In + Firestore sync | Optional | Required |
| JSON backup import / export | ✓ | ✓ |
| Installable PWA | — | ✓ |

## Tech stack

| Layer | Android | Web |
|-------|---------|-----|
| UI | Jetpack Compose, Material 3 | React 19, TypeScript, Tailwind |
| Architecture | MVVM + repositories | Hooks + Zustand stores |
| Local data | Room + SQLCipher | Firestore offline cache; UI prefs in `localStorage` |
| Cloud | Firebase Auth + Firestore | Firebase Auth + Firestore |
| DI / tooling | Hilt, Coroutines, Flow | Vite, Vitest |
| Widget | Glance | — |
| Tests | JUnit, Turbine, MockK, Compose UI | Vitest, Playwright smoke |

## Requirements

- Android 8.0+ (API 26)
- Android Studio Ladybug or newer
- **JDK 17+** to build the Android app (bytecode target is Java 11)
- **JDK 21+** for Firestore rules unit tests (`npm run test:rules`)
- Node.js 20 LTS (web + rules tests)

## Getting started

### Android

```bash
./gradlew :app:assembleDebug
# or open the project in Android Studio and Run
```

Place `google-services.json` in `app/` (from Firebase Console).

### Web

```bash
cd web
npm install
cp .env.example .env   # set VITE_FIREBASE_APP_ID (and optional App Check key)
npm run dev            # http://localhost:5173
```

Deploy hosting + rules from `web/`:

```bash
cd web
npm run deploy
```

See [`web/README.md`](web/README.md) for PWA details.

### Firestore rules tests

```bash
npm install
npm run test:rules   # requires JDK 21+
```

## Firebase setup

1. Create a Firebase project and add the Android app (`com.aus.notelikeus`).
2. Download `google-services.json` into `app/`.
3. Enable **Google** sign-in under Authentication.
4. Create a **Firestore** database and publish rules from `firestore.rules`.
5. Register debug and release SHA-1 / SHA-256 fingerprints.
6. (Recommended) App Check — Android Play Integrity / debug token; web reCAPTCHA via `VITE_APPCHECK_RECAPTCHA_SITE_KEY`. Keep Console enforcement **off** until tokens are healthy on both clients.
7. (Optional) Email/Password auth for debug builds.

Cloud sync uses Firestore only (no Storage) so it fits the **Spark (free)** plan.

## Tests

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # device/emulator

cd web && npm test
npm run test:rules
```

## Rich text

Stored as lightweight markdown (WYSIWYG in the editor; cards render formatted previews):

| Syntax | Result |
|--------|--------|
| `**text**` | Bold |
| `_text_` | Italic |
| `[label](https://url)` | Link |
| `https://...` | Auto-linked URL |
| `• item` | Bullet list |

## Backup

JSON v3 (`notelikeus_backup_YYYY-MM-DD.json`) includes notes, labels, and checklists. Import is append-only and matches labels by name.

## Release builds

```bash
keytool -genkey -v -keystore release.keystore -alias notelikeus -keyalg RSA -keysize 2048 -validity 10000
cp signing.properties.example signing.properties   # fill paths/passwords (gitignored)
./gradlew :app:assembleRelease   # or :app:bundleRelease
```

CI runs unit tests and debug/release builds on pushes to `main` / `master` (unsigned release if no signing file is present).

## Play Store

Draft listing copy lives in [`store/listing/en-US/`](store/listing/en-US/). See [`store/PUBLISHING_CHECKLIST.md`](store/PUBLISHING_CHECKLIST.md) and [`store/DATA_SAFETY.md`](store/DATA_SAFETY.md).

## Privacy

The in-app **Settings → Privacy policy** matches [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md). On Android, notes stay on-device by default in an encrypted local database. Cloud sync and the web app upload note text to **your** Firebase project when you sign in with Google. Synced notes are not end-to-end encrypted by the app. No analytics or advertising SDKs.

## Project structure

```
app/src/main/java/com/aus/notelikeus/   # Android
web/src/                                # PWA (React + Vite + Firebase)
store/                                  # Play Store listing drafts

firebase.json                           # Hosting (web/dist) + Firestore rules
```

```
app/.../com/aus/notelikeus/
├── data/          # Room, SQLCipher, repositories, backup, Firebase, reminders
├── domain/        # Models and repository interfaces
├── di/            # Hilt modules
├── ui/
│   ├── main/      # Note list, filters, settings sheet
│   ├── editor/    # Note editor, rich text, reminders
│   ├── components/
│   ├── navigation/
│   ├── theme/
│   └── widget/
└── MainActivity.kt
```

## Archived features

Image attachments were removed to stay on Firebase Spark. Source is preserved in [`archive/attachments-feature/`](archive/attachments-feature/).

## License

Private project — all rights reserved unless otherwise noted.
