# Privacy Policy — Notelikeus

**Last updated:** July 2026

Notelikeus is an offline-first notes application. This policy describes how the app handles information on your device and when you optionally enable cloud sync.

## Summary

- Notes are stored **locally on your device** in an encrypted database.
- **Optional cloud sync** uploads note text to **your Firebase project** when you sign in with Google and choose to sync.
- Locked notes are **not** uploaded to the cloud.
- The app does **not** include analytics or advertising SDKs.

## Information stored on your device

The app may store:

- Note titles, body text, colors, and positions
- Labels, checklists, and reminder times
- Settings (for example dark mode, app lock, and auto-sync preferences)

## Optional cloud sync

If you sign in with Google and use sync:

- Note content (except locked notes) is stored in **Google Firebase Firestore** under your Google account
- Data is governed by [Google’s privacy policy](https://policies.google.com/privacy) and your Firebase project settings
- You can sign out in Settings; cloud data remains until you delete it in Firebase or through a future in-app control

## Security

- Notes are stored in a **SQLCipher-encrypted** Room database locally.
- **Biometric lock** (optional) uses your device’s biometric APIs to protect the app or individual notes.
- Locked notes are not synced to the cloud.

## Permissions

| Permission | Purpose |
|------------|---------|
| Internet | Optional Firebase cloud sync |
| Notifications | Deliver reminders you schedule for notes |
| Biometric | Unlock the app or locked notes when enabled |
| Exact alarms | Fire reminders at the time you choose |

When you export or import backups, the system file picker is used; the app only accesses files you select.

## Backups

JSON backup export/import is **manual**. Backup files are written to a location you choose. You are responsible for securing copied files.

## Links in notes

If you add links to notes, tapping them opens your default browser. Notelikeus does not track link usage.

## Third parties

- **Google Firebase** — only when you enable cloud sync (Authentication + Firestore)
- We do not sell your personal data or use analytics or advertising SDKs

## Children

Notelikeus is not directed at children under 13. We do not knowingly collect personal information from children.

## Changes

We may update this policy as the app evolves. Material changes will be reflected in the app’s privacy text and this document.

## Contact

For privacy questions, reach out via the app’s store listing or the project repository.
