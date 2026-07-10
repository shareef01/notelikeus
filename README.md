# Notelikeus

A Google Keep–style notes app for **Android** and **web** (PWA). Notes are stored locally with SQLCipher encryption on Android (localStorage on web), with optional cloud sync to your own Firebase account via Google Sign-In.

## Web PWA

The progressive web app lives in [`web/`](web/) and is hosted at **https://notelike.web.app** (Firebase Hosting).

| Feature | Web |
|---------|-----|
| Notes, labels, checklists, colors | Yes |
| Archive, trash, pin, search, filters | Yes |
| Multi-select + bulk actions | Yes |
| Swipe actions + undo toasts | Yes |
| Manual reorder (list view) | Yes |
| Date-grouped sections (Today, Yesterday) | Yes |
| Recent search history | Yes |
| Smart editor (auto bullets, list continue) | Yes |
| Google Sign-In + real-time Firestore sync | Yes |
| Guest mode (local only) | Yes |
| JSON backup import/export | Yes |
| Reminders | Browser + service worker notifications |
| Offline mode + install prompt | Yes |
| Per-note lock | Unlock gate (no biometric on web) |

```bash
cd web
npm install
cp .env.example .env   # set VITE_FIREBASE_APP_ID from Firebase Console
npm run dev            # http://localhost:5173
npm run build
```

Deploy from repo root:

```bash
firebase deploy --only hosting:notelike,firestore:rules
```

See [`web/README.md`](web/README.md) for full PWA setup and architecture notes.

## Features

- **Notes** — titles, rich text (bold, italic, links, bullets), checklists, colors, and labels
- **Organization** — pin, archive, trash, search, color/label filters, list/grid layout, drag-to-reorder (list view)
- **Security** — SQLCipher-encrypted Room database, per-note biometric lock, optional app-wide lock
- **Cloud sync** — optional Firestore sync when signed in with Google (Spark/free tier friendly; text only)
- **Reminders** — date/time notifications that open the note
- **Backup** — export and import notes as JSON from Settings
- **Widget** — home screen glance widget with pinned/recent notes
- **Undo** — archive, trash, and delete actions on the main list and in the editor

## Tech stack

| Layer | Choice |
|-------|--------|
| UI | Jetpack Compose, Material 3 |
| DI | Hilt |
| Data | Room + SQLCipher |
| Cloud | Firebase Auth + Firestore |
| Settings | DataStore Preferences |
| Widget | Glance |
| Tests | JUnit, Turbine, MockK, Compose UI Test |

## Requirements

- Android 8.0+ (API 26)
- Android Studio Ladybug or newer recommended
- JDK 11+

## Build and run

```bash
./gradlew :app:assembleDebug
```

Install the debug APK from `app/build/outputs/apk/debug/`, or run directly from Android Studio.

## Firebase setup (optional cloud sync)

1. Create a Firebase project and add the Android app (`com.aus.notelikeus`).
2. Download `google-services.json` into `app/`.
3. Enable **Google** sign-in under Authentication.
4. Create a **Firestore** database and publish rules from `firestore.rules`.
5. Add debug and release SHA-1 fingerprints in Firebase project settings.

Cloud sync uses Firestore only (no Storage) so it fits the **Spark (free)** plan.

## Tests

```bash
# Unit tests
./gradlew :app:testDebugUnitTest

# Instrumented tests (device/emulator required)
./gradlew :app:connectedDebugAndroidTest
```

## Rich text syntax

Stored as lightweight markdown:

| Syntax | Result |
|--------|--------|
| `**text**` | Bold |
| `_text_` | Italic |
| `[label](https://url)` | Link |
| `https://...` | Auto-linked URL |
| `• item` | Bullet list |

The editor hides markers while typing (WYSIWYG). Note cards render formatted text.

## Backup format

JSON v3 files (`notelikeus_backup_YYYYMMDD.json`) include notes, labels, and checklists. Locked notes are exported with redacted content. Import is append-only and matches labels by name.

## Release builds

Debug builds work out of the box. For a signed release APK or App Bundle:

1. Create a keystore (once):

```bash
keytool -genkey -v -keystore release.keystore -alias notelikeus -keyalg RSA -keysize 2048 -validity 10000
```

2. Copy `signing.properties.example` to `signing.properties` and fill in paths/passwords (both files are gitignored).

3. Build:

```bash
./gradlew :app:assembleRelease
# or
./gradlew :app:bundleRelease
```

The output APK is at `app/build/outputs/apk/release/`.

CI runs unit tests and builds debug/release on every push to `main`/`master` (unsigned release if no signing file is present).

## Play Store listing

Draft listing copy for Google Play Console lives in [`store/listing/en-US/`](store/listing/en-US/):

- `title.txt` — app name (30 chars max)
- `short_description.txt` — short promo (80 chars max)
- `full_description.txt` — full store description
- `whats_new.txt` — release notes for the first upload

See [`store/PUBLISHING_CHECKLIST.md`](store/PUBLISHING_CHECKLIST.md) and [`store/DATA_SAFETY.md`](store/DATA_SAFETY.md) before submitting.

## Privacy

The in-app **Settings → Privacy policy** dialog matches [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md). Notes are stored locally by default. Optional cloud sync uploads note text to **your** Firebase project when you sign in with Google. No analytics or advertising SDKs.

## Archived features

Image attachments were removed to stay on Firebase Spark (free). Source is preserved in [`archive/attachments-feature/`](archive/attachments-feature/).

## Project structure

```
app/src/main/java/com/aus/notelikeus/   # Android app
web/src/                                # PWA (React + Vite + Firebase)
store/                                  # Play Store listing drafts
firebase.json                           # Hosting (web/dist) + Firestore rules
```

### Android layout

```
app/src/main/java/com/aus/notelikeus/
├── data/          # Room, SQLCipher, repositories, backup, Firebase, reminders
├── domain/        # Models and repository interfaces
├── di/            # Hilt modules
├── ui/
│   ├── main/      # Note list, filters, settings sheet
│   ├── editor/    # Note editor, rich text, reminders
│   ├── components/# Shared composables (cards, grid)
│   ├── navigation/
│   ├── theme/
│   └── widget/
└── MainActivity.kt
```

## License

Private project — all rights reserved unless otherwise noted.
