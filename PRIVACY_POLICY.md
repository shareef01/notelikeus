# Privacy Policy — Notelikeus

**Last updated:** September 2026

Notelikeus is an offline-first notes application across Android, Windows (Desktop), and Web. You can use Notelikeus completely offline without creating an account or signing in. This policy describes how the app handles information locally on your device and when you optionally enable cloud sync.

## Summary

- **Offline-first by default:** On Android, Windows, and Web, notes are stored **locally on your device**. You can use the full application without creating an account or providing personal details.
- **Optional Cloud Sync:** When you sign in and enable cloud sync, note text, checklists, and metadata are synchronized to **Supabase (PostgreSQL)** under your authenticated identity. Attachment files may be stored in **Cloudflare R2**.
- **Local Data Isolation:** Signing out clears local cached notes from the active session so a subsequent user cannot inherit your data.
- **Encryption:** Android local databases are encrypted at rest with **SQLCipher** backed by Android Keystore.
- Synced cloud notes are **not end-to-end encrypted** by the app; they rely on TLS plus Supabase Auth, row-level security, authorized RPCs, and Worker JWT checks for attachments.
- The app does **not** include third-party tracking, analytics, or advertising SDKs.

## Information stored on your device

- **Android & Windows Desktop:**
  - Note titles, body text, colors, checklists, labels, and reminder timestamps
  - Stored in a local database (encrypted at rest with SQLCipher on Android)
  - Local app preferences (theme, view mode, app lock status)
- **Web:**
  - Note content, checklists, labels, and preferences stored in local browser storage (IndexedDB / localStorage)
  - Operates fully as a guest / local-first PWA without requiring sign-in

## Cloud sync (Optional)

When you choose to sign in and use sync:

- Note content is stored in **Supabase** under your account
- Attachment binaries, when used, are stored in **Cloudflare R2** behind an authenticated Worker
- Signing out **clears locally cached notes on this device** so the next account cannot inherit them; cloud data remains until you delete it (in-app “Sign out and delete cloud data”)

## Security

- **Android:** Notes are stored in a **SQLCipher-encrypted** Room database. An optional app-wide lock uses the device’s biometric APIs to gate opening the app.
- **Windows Desktop & Web:** Local storage is bound to the user's OS / browser profile permissions.
- **Cloud Security:** PostgreSQL row-level security and authorized RPCs restrict read and write operations to the authenticated owner.

## Permissions

| Permission | Purpose |
|------------|---------|
| Internet | Authentication and cloud sync |
| Notifications | Deliver reminders you schedule for notes |
| Biometric | Unlock the app when app lock is enabled |

When you export or import backups, the system file picker is used; the app only accesses files you select. On the web, reminder notifications are best-effort and may be delayed or missed if the browser is fully closed or inactive.

## Backups

JSON backup export/import is **manual**. Backup files are written to a location you choose. You are responsible for securing copied files.

## Links in notes

If you add links to notes, tapping them opens your default browser. Notelikeus does not track link usage.

## Third parties

- **Supabase** — authentication and note data when you enable cloud sync
- **Cloudflare** — web hosting (Pages) and attachment objects (R2 / Worker) when those features are used
- **Google** — optional Google sign-in identity; Google OAuth is not the note database
- We do not sell your personal data or use analytics or advertising SDKs

## Children

Notelikeus is not directed at children under 13. We do not knowingly collect personal information from children.

## Changes

We may update this policy as the app evolves. Material changes will be reflected in the app’s privacy text and this document.

## Contact

For privacy questions, reach out via the app’s store listing or the project repository.
