# Google Play — Data safety

Use these answers when completing the **Data safety** form in Play Console for Notelikeus.

## Overview

| Question | Answer |
|----------|--------|
| Does your app collect or share any of the required user data types? | **Yes** — when the user opts into cloud sync |
| Is all user data encrypted in transit? | **Yes** (TLS to Firebase) |
| Do you provide a way for users to request data deletion? | **Yes** — delete notes in-app, sign out, or uninstall |

## When cloud sync is **not** used

If the user never signs in with Google or never syncs, note content stays on device only. You may answer **No** data collected for users who do not use cloud features — clarify in the form notes that collection is optional.

## When cloud sync **is** used

| Data type | Collected | Shared | Purpose | Optional |
|-----------|-----------|--------|---------|----------|
| User-generated content (notes) | Yes | No (stored in user’s Firebase project) | App functionality — sync across devices | Yes — requires Google sign-in + user action |

**Not collected:** analytics events, advertising IDs, contacts, location, photos (attachment feature removed).

## Security practices

| Practice | Answer |
|----------|--------|
| Data encrypted at rest on device | **Yes** (SQLCipher) |
| Data encrypted in transit | **Yes** (HTTPS/TLS to Firebase) |
| Users can request data deletion | **Yes** (delete notes / uninstall; cloud copy in Firebase until user deletes project data) |

## Permissions (declared in manifest)

- `INTERNET` — optional Firebase cloud sync
- `POST_NOTIFICATIONS` — user-scheduled note reminders
- `USE_BIOMETRIC` — optional app/note unlock
- `SCHEDULE_EXACT_ALARM` — reminder alarms

## Backup

Android cloud backup excludes the encrypted database, legacy attachment paths, DataStore settings, and encryption key preferences. Users may export JSON backups manually.

## Privacy policy URL

Host the policy at **https://notelike.web.app/privacy.html** (also in `web/public/privacy.html`).
