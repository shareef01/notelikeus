# Google Play — Data safety

Use these answers when completing the **Data safety** form in Play Console for Notelikeus.

## Overview

| Question | Answer |
|----------|--------|
| Does your app collect or share any of the required user data types? | **Yes** — Google account + note content synced to the user’s Firebase project |
| Is all user data encrypted in transit? | **Yes** (TLS to Firebase) |
| Do you provide a way for users to request data deletion? | **Yes** — delete notes in-app, “Sign out and delete cloud data”, or uninstall |

## Sign-in and sync

Google sign-in is **required** to use the app. Note content stays on device in an encrypted database; when auto-sync is enabled, eligible notes (not locked) are uploaded to Firestore under that Google account.

Signing out **clears local notes** on the device so the next account cannot inherit them. Cloud copies remain until the user deletes them in-app or via Firebase.

## Data types when signed in

| Data type | Collected | Shared | Purpose | Optional |
|-----------|-----------|--------|---------|----------|
| Personal info (email / Google account id) | Yes | No (Firebase Auth) | Account authentication | No — required to use the app |
| User-generated content (notes) | Yes | No (stored under the user’s Firebase account) | App functionality — sync across devices | Sync of note bodies can be disabled via auto-sync; locked notes are never uploaded |

**Not collected:** analytics events, advertising IDs, contacts, location, photos (attachment feature removed).

## Security practices

| Practice | Answer |
|----------|--------|
| Data encrypted at rest on device | **Yes** (SQLCipher) |
| Data encrypted in transit | **Yes** (HTTPS/TLS to Firebase) |
| Users can request data deletion | **Yes** (delete notes / sign out and delete cloud data / uninstall) |

## Permissions (declared in manifest)

- `INTERNET` — Firebase auth and cloud sync
- `POST_NOTIFICATIONS` — user-scheduled note reminders
- `USE_BIOMETRIC` — optional app/note unlock
- `SCHEDULE_EXACT_ALARM` — reminder alarms

## Backup

Android cloud backup excludes the encrypted database, legacy attachment paths, DataStore settings, encryption key preferences, sync tombstones, and pending sync queues. Users may export JSON backups manually.

## Privacy policy URL

Host [`PRIVACY_POLICY.md`](../PRIVACY_POLICY.md) on GitHub Pages or your website, or paste the same text into Play Console’s privacy policy field.
