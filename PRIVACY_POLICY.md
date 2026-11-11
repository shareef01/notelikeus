# Privacy Policy — Notelikeus

**Last updated:** November 2026

Notelikeus is an offline-first notes application across Android, Windows (Desktop), and Web. You can use Notelikeus completely offline without creating an account or signing in. This policy describes how the app handles information locally on your device and when you optionally enable cloud sync.

## Summary

- **Offline-first by default:** On Android, Windows, and Web, notes are stored **locally on your device**. You can use the full application without creating an account or providing personal details.
- **Optional Cloud Sync:** When you sign in and enable cloud sync, note text, checklists, and metadata are synchronized to **Supabase (PostgreSQL)** under your authenticated identity. Attachment files may be stored in **Cloudflare R2**.
- **Local Data Isolation:** On Android and Windows, signing out removes the account's notes from the device; if some edits haven't been uploaded yet, the notes are kept on the device instead, encrypted and kept for that account only, out of reach of any other account that signs in there, and they upload when that account signs in there again. On Web, they stay in the browser, encrypted and kept for that account only, out of reach of any other account that signs in there; they are removed when a different account signs in (unless they include edits that haven't been uploaded) or when you clear the site's data.
- **Encryption:** Android local databases are encrypted at rest with **SQLCipher** backed by Android Keystore. Attachment image files and staged pending attachment bytes on Android are encrypted at rest with **AES-GCM** under a dedicated Android Keystore key (separate from the database key). On Windows Desktop, the notes database is encrypted at rest with **SQLCipher** (Multiple Ciphers / v4) under a **DPAPI**-sealed passphrase bound to the Windows user account; attachment image files and staged pending bytes are sealed with **AES-GCM** under a separate DPAPI-protected key. On Web, note titles, bodies, and checklists in IndexedDB, and staged pending attachment blobs, are sealed with **AES-GCM** under non-extractable WebCrypto keys (protects the browser profile at rest; it is not an XSS mitigation). See `docs/LOCAL_ENCRYPTION_AT_REST.md`.
- Synced cloud notes are **not end-to-end encrypted** by the app; they rely on TLS plus Supabase Auth, row-level security, authorized RPCs, and Worker JWT checks for attachments.
- **Diagnostics stay local:** an optional sync-diagnostics report shows counts and version numbers, never note content or credentials, and is never transmitted.
- The app does **not** include third-party tracking, analytics, or advertising SDKs.

## Information stored on your device

- **Android & Windows Desktop:**
  - Note titles, body text, colors, checklists, labels, and reminder timestamps
  - Stored in a local database (encrypted at rest with SQLCipher on Android and Windows Desktop)
  - Local app preferences (theme, view mode, app lock status)
- **Web:**
  - Note content, checklists, labels, and preferences stored in local browser storage (IndexedDB / localStorage)
  - Operates fully as a guest / local-first PWA without requiring sign-in

## Cloud sync (Optional)

When you choose to sign in and use sync:

- You sign in with your **email address**: Supabase Auth emails you a one-time code, and that address identifies your account. There is no password, and no third-party sign-in provider is involved
- Note content is stored in **Supabase** under your account
- Attachment binaries, when used, are stored in **Cloudflare R2** behind an authenticated Worker
- Signing out on Android or Windows **removes the account's notes from the device** so the next account cannot inherit them; edits that haven't been uploaded yet are kept on the device, encrypted and kept for that account only, and upload when it signs in there again. On Web they stay in the browser, encrypted and kept for that account only, until a different account signs in there or you clear the site's data. Either way, cloud data remains until you delete it (in-app “Sign out and delete cloud data”)

## Deleting your account

You can delete your account from **Settings → Delete account** in the Android, Windows and web apps. If you haven't signed in within the last ten minutes, the app first emails you a code to confirm it's you; the same applies to "Sign out and delete cloud data". Deletion takes effect immediately and cannot be undone:

- Your notes, deleted-note records and sync data are removed from Supabase at once
- Your images are removed from Cloudflare R2, and then your account, including your email address, is removed from Supabase Auth. This normally finishes within seconds; if a service is unavailable it is retried hourly until it succeeds
- The device you delete from removes its local copy straight away, including edits not yet uploaded. Other signed-in devices remove theirs the next time they sync. A device whose session has already expired stops syncing, and its local copy is removed when anyone next signs in on it or when the app is uninstalled
- For a short time afterwards the server keeps your account’s random internal id, with no email or note content, so that a device still signed in can be told the account is gone. It is removed by an hourly cleanup once the account has been gone for two hours and none of its images are left in storage: normally between two and three hours after deletion, later only while a service is unavailable

Supabase and Cloudflare may hold data in their own routine backups until those expire. Backup files you exported are yours and are not affected.

## Security

- **Android:** Notes are stored in a **SQLCipher-encrypted** Room database. Attachment bytes under the app’s attachments directory (and pending staging) are sealed with AES-GCM under Android Keystore. An optional app-wide lock uses the device’s biometric APIs to gate opening the app.
- **Windows Desktop:** The notes database is encrypted at rest with SQLCipher under a DPAPI-sealed passphrase (bound to the Windows user). Attachment bytes under `~/.notelikeus/` are sealed with AES-GCM under a separate DPAPI-protected key. The Supabase session token file is also DPAPI-sealed. Key loss (e.g. profile rebuild without a cloud account) makes the local database unrecoverable — signed-in users re-sync from the cloud; guests should keep a JSON / `.nlkbak` export.
- **Web:** Note titles, bodies, and checklists in IndexedDB, and staged pending attachment blobs, are sealed with AES-GCM under non-extractable WebCrypto keys (profile-at-rest only; not an XSS control). Other IndexedDB metadata (flags, colors, sync cursors) remains readable at the profile boundary.
- **Cloud Security:** PostgreSQL row-level security and authorized RPCs restrict read and write operations to the authenticated owner.

## Permissions

| Permission | Purpose |
|------------|---------|
| Internet | Authentication and cloud sync |
| Notifications | Deliver reminders you schedule for notes |
| Biometric | Unlock the app when app lock is enabled |

When you export or import backups, the system file picker is used; the app only accesses files you select. On the web, reminder notifications are best-effort and may be delayed or missed if the browser is fully closed or inactive. On Windows, reminders ring while Notelikeus is running: closing the window while a reminder is pending keeps the app in the system tray, and reminders do not ring after you choose Exit.

## Backups

Backup export and import are **manual**. Backup files are written to a location you choose. You are responsible for securing copied files.

Two formats:

- **JSON** — note titles, bodies, checklists, labels, colours and reminder times. No images.
- **`.nlkbak`** — a plain ZIP holding the same JSON document plus the **attachment
  image bytes stored on that device**. Available on Android, Windows Desktop, and Web. It is a
  normal archive: rename it to `.zip` and you can open it and see exactly what it contains.

Neither format contains sign-in tokens, session data, encryption keys, sync cursors, or your
account identifier. An export never contacts the network — images that exist only in the cloud are
left out and reported as skipped, rather than being downloaded to build the file.

## Diagnostics

The app can show a **sync diagnostics** report to help troubleshoot problems like a note not
appearing on another device. It contains counts and version numbers only: app and database
versions, how many notes are active, archived, trashed or pinned, how many are waiting to sync, the
last sync time, and a category name for the last error.

It does **not** contain note titles or text, checklist or label text, images, email addresses,
sign-in tokens, or your account identifier — your account appears only as a short scrambled tag
that cannot be turned back into an identity. Error messages are reduced to a fixed category name
so that no note content can travel inside one.

The report is shown to you on screen and is never transmitted anywhere. Copying it, and deciding
who to send it to, is entirely your choice.

## Links in notes

If you add links to notes, tapping them opens your default browser. Notelikeus does not track link usage.

## Third parties

- **Supabase** — authentication (including sending sign-in code emails) and note data when you enable cloud sync
- **Cloudflare** — web hosting (Pages) and attachment objects (R2 / Worker) when those features are used
- We do not sell your personal data or use analytics or advertising SDKs

## Children

Notelikeus is not directed at children under 13. We do not knowingly collect personal information from children.

## Changes

We may update this policy as the app evolves. Material changes will be reflected in the app’s privacy text and this document.

## Contact

For privacy questions, reach out via the app’s store listing or the project repository.
