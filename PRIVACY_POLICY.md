# Privacy Policy — Notelikeus

**Last updated:** July 2026

Notelikeus is an offline-first notes application. This policy describes how the app handles information on your device and when you use cloud sync with Google sign-in.

## Summary

- Notes are stored **locally on your device** in an encrypted database.
- **Google sign-in is required.** Cloud sync uploads note text to **your Firebase account** when auto-sync is enabled.
- Locked notes are **not** uploaded to the cloud.
- The app does **not** include analytics or advertising SDKs.

## Information stored on your device

The app may store:

- Note titles, body text, colors, and positions
- Labels, checklists, and reminder times
- Settings (for example dark mode, app lock, and auto-sync preferences)

## Cloud sync

When you sign in with Google and use sync:

- Note content (except locked notes) is stored in **Google Firebase Firestore** under your Google account
- Data is governed by [Google’s privacy policy](https://policies.google.com/privacy) and your Firebase project settings
- Signing out **clears local notes on this device** so the next account cannot inherit them; cloud data remains until you delete it (in-app “Sign out and delete cloud data” or Firebase console)

## Security

- **Android:** Notes are stored in a **SQLCipher-encrypted** Room database. Biometric lock (optional) uses the device’s biometric APIs for the app or individual notes.
- **Web:** Unlocked notes are stored in browser local storage. Hidden (locked) notes encrypt title, body, and checklist at rest (AES-GCM with a device-local key cleared on sign-out).
- Locked notes are not synced to the cloud on either platform.

## Permissions

| Permission | Purpose |
|------------|---------|
| Internet | Firebase auth and cloud sync |
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
