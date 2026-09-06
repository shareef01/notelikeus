# Google Play — Data safety

Use these answers when completing the **Data safety** form in Play Console for Notelikeus.

These answers must stay consistent with [`PRIVACY_POLICY.md`](../PRIVACY_POLICY.md), which is the
text users actually see. The backend is Supabase (Auth + PostgreSQL) with attachment bytes in
Cloudflare R2 behind an authenticated Worker; there is no Firebase or Firestore in the product.

## Overview

| Question | Answer |
|----------|--------|
| Does your app collect or share any of the required user data types? | **Yes** — Google account identity and note content synced to Supabase |
| Is all user data encrypted in transit? | **Yes** (TLS to Supabase and to the Cloudflare attachments Worker) |
| Do you provide a way for users to request data deletion? | **Yes** — delete notes in-app, “Sign out and delete cloud data”, or uninstall |

## Sign-in and sync

Google sign-in is **optional on Android**. Note content stays on device in an encrypted database by
default; when the user signs in and enables sync, notes are uploaded to Supabase PostgreSQL under
that user's Supabase account, isolated by row-level security.

Signing out **clears local notes** on the device so the next account cannot inherit them. Cloud
copies remain until the user deletes them in-app (“Sign out and delete cloud data” calls the
`delete_all_user_cloud_data` RPC, which removes notes, tombstones, sync metadata and attachment
records for that account).

## Data types when signed in

| Data type | Collected | Shared | Purpose | Optional |
|-----------|-----------|--------|---------|----------|
| Personal info (email / account id) | Yes | No (Supabase Auth, processor only) | Account authentication | Yes — only when the user chooses Google sign-in |
| User-generated content (notes) | Yes | No (stored under the user's own Supabase rows) | App functionality — sync across devices | Yes — sync of note bodies can be disabled via auto-sync |
| Photos / images (note attachments) | Yes, when attachments are configured | No (stored under the user's own Cloudflare R2 prefix) | App functionality — images attached to notes | Yes — only when the user attaches an image |

Attachments are a build-time capability: they exist only when the build is configured with
`NOTELIKEUS_ATTACHMENTS_WORKER_URL`. Omit the photos/images row when shipping a build without it.

**Not collected:** analytics events, advertising IDs, contacts, location, device identifiers.

## Security practices

| Practice | Answer |
|----------|--------|
| Data encrypted at rest on device | **Yes** (SQLCipher, key wrapped by the Android Keystore) |
| Data encrypted in transit | **Yes** (HTTPS/TLS to Supabase and the attachments Worker) |
| Users can request data deletion | **Yes** (delete notes / sign out and delete cloud data / uninstall) |

Cloud notes are **not end-to-end encrypted**; they are protected by TLS, Supabase Auth, row-level
security, authorized RPCs, and the Worker's bearer-token check for attachment bytes. State this the
same way the privacy policy does — do not claim E2E encryption.

## Permissions (declared in manifest)

- `INTERNET` — Supabase authentication and cloud sync, and attachment upload/download
- `POST_NOTIFICATIONS` — user-scheduled note reminders
- `USE_BIOMETRIC` — optional app unlock
- `RECEIVE_BOOT_COMPLETED` — reschedule reminders after a device restart

## Backup

`android:allowBackup="false"`, so Android cloud backup and adb backup capture nothing. The backup
rule files stay wired to encode the exclusions that must hold if backup is ever enabled — the
SQLCipher database and the Keystore-wrapped passphrase must never leave the device together. Users
may export JSON backups manually.

## Privacy policy URL

Host [`PRIVACY_POLICY.md`](../PRIVACY_POLICY.md) on GitHub Pages or your website, or paste the same
text into Play Console's privacy policy field.
