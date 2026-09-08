# Local encryption at rest — where it stands, and what it would take

Written 2026-09-08 after auditing the three clients. Nothing here has been implemented; this is
the engineering case for and against doing it, so the decision is a decision and not a default.

## What is true today

| Client | Note store | Encrypted at rest by the app? |
|---|---|---|
| Android | Room + SQLCipher | **Yes.** 32-byte random passphrase, sealed by an AndroidKeyStore AES-GCM key (`DatabaseKeyManager`), with a legacy `EncryptedSharedPreferences` migration path. |
| Windows (Desktop) | Room + `BundledSQLiteDriver` | **No.** Plain SQLite file under the user's data directory. |
| Web | IndexedDB (+ `localStorage` for preferences) | **No.** Plain records in the browser profile. |

Two things on Windows *are* protected: the Supabase session token file is sealed with DPAPI
(`platform/Dpapi.kt`, used by `DesktopSupabaseSessionPersistence`), and the OS enforces the
profile's file permissions. Note bodies, titles, checklists, labels, reminders, and staged
attachment bytes are not.

`PRIVACY_POLICY.md` already states this accurately — it claims SQLCipher for Android only, and
says Windows and Web rely on "OS / browser profile permissions". No policy change is required to
leave things as they are; a policy change *is* required if either client gains encryption, and
the wording must not overstate what it buys (see the threat model below).

## Windows: SQLCipher + a DPAPI-protected random key

### Threat model it addresses

Offline access to the database file by someone who is not the logged-in Windows user:

- a stolen or disposed laptop without BitLocker, where the disk can be read directly;
- a backup, sync folder, or support bundle that copies `notelikeus.db` off the machine;
- another local account, or a process running as a different user, reading the file path;
- a support engineer or family member browsing the profile directory.

### What DPAPI actually protects

`CryptProtectData` with `CRYPTPROTECT_UI_FORBIDDEN` seals the key to the *current Windows user
account*, deriving from the user's credentials. Decryption requires being logged in as that user
on that machine (or having a domain roaming profile that carries the master key). So a copied
database file plus a copied key file, opened on another machine or under another account, is
unreadable.

### What it does not protect

This is where the honest limits are, and they are large:

- **Any code running as that user.** Malware, an infostealer, or a script in the user's session
  can call `CryptUnprotectData` exactly as the app does. DPAPI is not a boundary against
  same-user code — it never was.
- **A running app.** While Notelikeus is open the passphrase is in process memory and the
  database is decrypted.
- **Cloud data.** Synced notes live in Supabase and R2 under server-side controls; local
  encryption says nothing about them.
- **Attachment staging.** `pending-attachments/<ownerId>/<attachmentId>` files are written
  outside the database and would stay plaintext unless separately handled.
- **Backups the user exports.** JSON export is deliberately plaintext and stays that way.

In short it converts "anyone who gets the file gets the notes" into "anyone who gets the file
*and* the account gets the notes". That is a real improvement against device theft and stray
copies, and no improvement at all against malware.

### Why it is not a small change

The desktop target opens Room through `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`,
wired in `desktopMain/di/PlatformModule.kt`). That driver bundles upstream SQLite, which has no
encryption extension, and `net.zetetic:sqlcipher-android` is Android-only — there is no JVM
artifact to swap in. Encrypting the desktop database therefore means:

1. Choosing a JVM-capable encrypted SQLite build (SQLite Multiple Ciphers / `sqlite-jdbc-crypt`,
   or shipping a self-built SQLCipher native library per architecture).
2. Writing a Room KMP `SQLiteDriver` for it, because Room 2.8's desktop path expects the
   `androidx.sqlite` driver API, not JDBC.
3. Shipping and signing that native library inside the MSI/packaged distribution, per
   architecture, and keeping it current with CVEs.
4. Owning the Room-vs-SQLite version pairing the repo already flags in
   `gradle/libs.versions.toml` — a second native SQLite makes that pairing harder, not easier.

That is a new native dependency in the release artifact plus a custom driver. It is a project, not
a patch, and it is why this document is a recommendation rather than a diff.

### Migration strategy, if it is adopted

Mirror what Android already does, because that code has been through this once:

1. On first launch after the upgrade, generate 32 random bytes, seal with DPAPI, publish the key
   file **by atomic rename** (Android's `publishByRename` exists because a half-written key file
   reads as corrupt and orphans the database).
2. Open the existing plaintext database, `ATTACH` an encrypted target, copy with
   `sqlcipher_export`, verify row counts, then swap files atomically.
3. **Quarantine, never delete**, any database that cannot be opened with the current key —
   `PlaintextDatabaseMigrator` sets that precedent on Android and it is the reason a key mishap
   there is recoverable.
4. Keep the migration one-way and idempotent: interrupted at any point, the next launch either
   finds the plaintext original or the finished encrypted file, never a half-converted one.

### Recovery, backup, and key loss

- **Key loss is data loss.** DPAPI master keys do not survive a Windows password reset performed
  by an administrator (as opposed to a user-initiated change), a profile rebuild, or moving the
  file to a new machine. Without the key the database is gone.
- The mitigation is the cloud: a signed-in user re-syncs from Supabase. A guest-mode user has no
  copy anywhere else, so for them encryption converts a recoverable file into an unrecoverable
  one. That trade is a **product decision**, not an engineering one.
- File-level backup tools (File History, OneDrive, a copied folder) keep working but produce
  backups that only restore onto the same account. Users must be told this; today a copied
  `.db` file restores anywhere.
- The JSON export path becomes the only portable backup, and it is plaintext — so encrypting the
  database while advertising export as the backup story needs a matching warning.

### Testing requirements

- Migration: plaintext → encrypted, with row-count and content equality assertions.
- Interrupted migration at each step (before the copy, mid-copy, after copy before swap), each
  resuming cleanly on the next launch.
- Missing key file, corrupt key file, and a key file DPAPI refuses — each must quarantine rather
  than delete, and each must be asserted.
- A database from another Windows account must fail to open, proving the protection is real.
- Fresh install, upgrade-with-data, and guest-mode paths.
- The native library must be exercised on every architecture that ships, in CI, on Windows.

Note that Windows-only DPAPI tests cannot run on the Linux CI runners this repo uses today; a
Windows job is part of the cost.

### Recommendation

Do it **only** alongside a decision about guest-mode data loss, and only with the Windows CI job
and the migration tests above in place. Ordering: pick the driver, land the driver behind a flag
with no format change, then the migration, then flip the default. Do not ship the key handling and
the driver swap in one release.

## Web: say what it protects, and do not overstate it

Browser-side encryption of IndexedDB is a fundamentally weaker proposition, and the distinction
must stay explicit in any user-facing text:

- **Against the profile at rest** — someone with the disk or the browser profile directory, but
  not the running browser — encrypting records with a key held in a non-extractable `CryptoKey`
  does help, because the key is stored by the browser's own key store rather than as a readable
  value. That is genuinely the same class of protection DPAPI gives on Windows, and it is bounded
  the same way.
- **Against same-origin XSS it does nothing.** This is the point that must not be blurred. An
  attacker who can execute JavaScript on the application's origin, while the key is available to
  the page, can simply ask the page to decrypt — a non-extractable key still performs decryption
  for whoever calls it. Encryption at rest is not an XSS mitigation, and describing it as one
  would be false.

The real controls against that attacker are the ones already in place and worth keeping tight:
the CSP in `web/public/_headers` with no `script-src 'unsafe-inline'` (pinned to the build's
Supabase and Worker hosts by `pinAttachmentsCspPlugin`), no `dangerouslySetInnerHTML` anywhere in
`web/src`, and a service worker that precaches static assets only and never caches an
authenticated response.

### Recommendation

Do not add web encryption at rest on the strength of an XSS argument, because it does not answer
one. If it is added for the profile-at-rest case, the privacy policy must say precisely that, and
must not claim protection against a compromised page. Keeping the current honest wording is
preferable to shipping encryption that reads stronger than it is.

## Summary

| | Android | Windows | Web |
|---|---|---|---|
| Today | SQLCipher + Keystore | Plaintext DB, DPAPI-sealed session token | Plaintext IndexedDB |
| Proposed | no change | SQLCipher + DPAPI-sealed random key | no change for now |
| Blocker | — | no JVM encrypted-SQLite driver in the current Room stack; guest-mode key-loss trade | protects the profile at rest only; not an XSS control |
