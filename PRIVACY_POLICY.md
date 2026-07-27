# Privacy Policy — Notelikeus

**Last updated:** July 2026

Notelikeus is offline-first on Android; the web app requires a Google account and keeps notes in the cloud. This policy describes how the app handles information on your device and when you use cloud sync with Google sign-in.

## Summary

- **Android:** Notes are stored **locally on your device** in an encrypted database. Cloud sync uploads note text to **your Firebase account** when auto-sync is enabled.
- **Web:** Google sign-in is required to use the app. Note text is stored only in **your Firebase account**; edits sync automatically, with no separate offline copy on the device.
- The app does **not** include analytics or advertising SDKs.

## Information stored on your device

**Android** may store:

- Note titles, body text, colors, and positions
- Labels, checklists, and reminder times
- Settings (for example dark mode, app lock, and auto-sync preferences)

**Web** stores only app preferences on your device (theme, layout, reminder scheduling) — note text lives in Firestore, not the browser.

## Cloud sync

When you sign in with Google and use sync:

- Note content is stored in **Google Firebase Firestore** under your Google account
- Data is governed by [Google’s privacy policy](https://policies.google.com/privacy) and your Firebase project settings
- Signing out **clears locally cached data on this device** (Android's local database; on web, any local labels and app preferences tied to the account) so the next account cannot inherit them; cloud data remains until you delete it (in-app “Sign out and delete cloud data” or Firebase console)

## Security

- **Android:** Notes are stored in a **SQLCipher-encrypted** Room database. An optional app-wide lock uses the device’s biometric APIs to gate opening the app.
- **Web:** Notes are not stored on the device — they live in Firestore, protected by your Google account and Firestore's access rules. The browser keeps only a temporary in-memory copy while the app is open, plus a Firestore-managed offline cache for continuity without a connection.

## Permissions

| Permission | Purpose |
|------------|---------|
| Internet | Firebase auth and cloud sync |
| Notifications | Deliver reminders you schedule for notes |
| Biometric | Unlock the app when app lock is enabled |

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
