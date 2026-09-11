# Local encryption at rest — where it stands, and what it would take

Written 2026-09-08 after auditing the three clients; updated after Android and Desktop
attachment AES-GCM landed, Desktop notes-DB SQLCipher (Windows default), Web pending-attachment
sealing, and Web note-body sealing. Android notes DB and attachment bytes are encrypted at rest.
Desktop attachment bytes and the Windows notes database are sealed under DPAPI-backed keys. Web
note titles/bodies/checklists and staged pending attachment blobs are sealed with WebCrypto
AES-GCM (profile-at-rest only).

## What is true today

| Client | Note store | Encrypted at rest by the app? | Attachment bytes on disk |
|---|---|---|---|
| Android | Room + SQLCipher | **Yes.** 32-byte random passphrase, sealed by an AndroidKeyStore AES-GCM key (`DatabaseKeyManager`), with a legacy `EncryptedSharedPreferences` migration path. | **Yes (app-level).** AES-GCM under a dedicated Keystore alias (`AndroidAttachmentBytesProtector`); dual-read accepts legacy plaintext during migration. |
| Windows (Desktop) | Room + Willena `sqlite-jdbc` (SQLCipher v4) | **Yes (default on Windows).** 32-byte passphrase in `~/.notelikeus/notes-db.key`, sealed with DPAPI (`DesktopDatabaseKeyManager`); one-way plaintext→encrypted migration via `PRAGMA rekey`. Opt out with `notelikeus.desktop.jdbcSqlite=false`. | **Yes (app-level).** AES-GCM under a DPAPI-sealed AES key (`DesktopAttachmentBytesProtector`); dual-read accepts legacy plaintext during migration. |
| Web | IndexedDB (+ `localStorage` for preferences) | **Yes (app-level) for note bodies.** Title/content/checklist sealed (`NLN1`) under a non-extractable WebCrypto key; dual-read accepts legacy plaintext. Flags/colors/sync metadata stay readable. | **Yes (app-level) for staged pending blobs.** AES-GCM (`NLA1`) under a separate non-extractable WebCrypto key; dual-read accepts legacy plaintext. |

Two things on Windows *are* protected beyond OS ACLs: the Supabase session token file is sealed
with DPAPI (`platform/Dpapi.kt`, used by `DesktopSupabaseSessionPersistence`), attachment
image / staging bytes under `~/.notelikeus/` are AES-GCM sealed under a separate DPAPI-wrapped
key file (`attachment-aes.key`), and the notes Room database is SQLCipher-encrypted under
`notes-db.key` (also DPAPI) when running on Windows (the default).

`PRIVACY_POLICY.md` states platform encryption accurately — SQLCipher for Android notes,
AES-GCM attachments on Android and Desktop, Web/Desktop notes DB limits, and no E2E claim for
cloud sync. Update that file whenever a client gains or loses encryption.

## Android attachment bytes — implemented (AES-GCM + Keystore)

### Boundary

SQLCipher encrypts the Room database only. Attachment image bytes under
`files/attachments/` and staged pending bytes under `files/pending-attachments/` are sealed
separately by `AndroidAttachmentBytesProtector` (AES-GCM, dedicated Keystore alias
`notelikeus_attachment_aes`). Web staged pending attachment blobs are sealed separately (see below).

### On-disk format

`NLA1` (4-byte magic) || 12-byte IV || ciphertext+tag. The Keystore provider generates the IV
(randomized encryption required). AAD is the file basename for local attachments and
`ownerId/attachmentId` for staged `.bin` files, so a swapped ciphertext fails authentication.

### Migration

`AttachmentAtRestMigrator` runs when the Android attachment graph is first resolved in Koin:
plaintext files are sealed via temp+rename after a decrypt round-trip check; failures are moved
to a quarantine sibling directory and never deleted silently. Already-sealed files are skipped.
Read paths still accept legacy plaintext so an interrupted migration cannot strand the library.

## Desktop attachment bytes — implemented (AES-GCM + DPAPI-sealed key)

### Boundary

Same on-disk `NLA1` format and AAD rules as Android (`AttachmentBytesCodec` in shared `jvmMain`).
`DesktopAttachmentBytesProtector` holds a random AES-256 key in `~/.notelikeus/attachment-aes.key`,
wrapped with DPAPI under dedicated entropy (`com.aus.notelikeus/attachment-key/v1`) — never the
session-token entropy. Per-file DPAPI is intentionally not used.

### Migration and dual-read

Desktop Koin mirrors Android: migrate `~/.notelikeus/attachments` and
`pending-attachments` on first attachment-graph resolve, then seal all new writes. Reads still
accept legacy plaintext. A DPAPI unwrap failure on an existing key file does **not** mint a
replacement key (that would orphan sealed files).

### Threat model (honest limits)

DPAPI binds the key to the Windows user account. It helps against offline disk copies and other
local accounts; it does **not** stop malware running as that user.

## Windows: SQLCipher + a DPAPI-protected random key

### Status (2026-09-11)

| Slice | State |
|---|---|
| 1. DPAPI-sealed 32-byte passphrase (`DesktopDatabaseKeyManager`, `~/.notelikeus/notes-db.key`) | **Landed** |
| 2. Custom Room `SQLiteDriver` over `sqlite-jdbc-crypt` (sqlcipher cipher), flag off / plaintext path | **Landed** |
| 3. One-way plaintext → encrypted migration + quarantine | **Landed** |
| 4. Flip default on Windows; opt-out via flag; Linux CI stays bundled | **Landed** — default `useJdbcSqlite()` is `isWindows()` |
| 5. Windows CI job for real DPAPI + `desktopTest` | **Landed** — `windows-crypto` in `.github/workflows/desktop.yml` |

**Chosen driver (decision).** Use Willena / community `sqlite-jdbc-crypt` (SQLite3 Multiple Ciphers)
with `cipher=sqlcipher` and a custom `androidx.sqlite.SQLiteDriver` adapter — there is no
`sqlcipher-android` JVM artifact, and `BundledSQLiteDriver` cannot take a passphrase. Guest mode
gets the same encryption as signed-in users (same as Android); key loss for guests is unrecoverable
except via prior JSON / `.nlkbak` export — cloud re-sync covers signed-in accounts.

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
- **Attachment staging.** Attachment and pending-staging files under `~/.notelikeus/` are
  sealed separately (`DesktopAttachmentBytesProtector`); this section is about the notes DB only.
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

## Web pending attachments — implemented (AES-GCM + non-extractable WebCrypto key)

### Boundary

The web client does not keep a durable `file:` attachment tree; only **staged pending** blobs live
in IndexedDB (`pendingAttachments`). Those blobs are sealed by
`pendingAttachmentRepository` using the shared `NLA1` layout and AAD `ownerId/attachmentId`.
Note titles, bodies, and checklists are sealed separately (see Web notes below). Labels and
attachment metadata on the note shell remain readable at the profile boundary.

### Key

A dedicated IndexedDB database (`notelikeus-attachment-crypto`) holds a non-extractable
AES-GCM-256 `CryptoKey`. It is **not** the legacy note-lock key in `notelikeus-crypto`. If a key
record already exists but is unusable, the app does **not** mint a replacement (that would orphan
sealed blobs).

### Migration and dual-read

New writes are sealed when WebCrypto is available. Reads accept legacy plaintext and rewrite to
sealed form when possible. Callers always receive plaintext `Blob`s.

### Threat model (honest limits)

Same class as DPAPI: helps against offline copies of the browser profile directory. **Same-origin
XSS can still decrypt** while the page holds the key — encryption at rest is not an XSS mitigation.
CSP, no `dangerouslySetInnerHTML`, and the service-worker cache policy remain the XSS controls.

## Web notes — implemented (AES-GCM + non-extractable WebCrypto key)

### Boundary

`notesLocalRepository` seals **title**, **content**, and **checklist** into an `NLN1` blob on the
stored note (`sealedBody`), with AAD `ownerId/noteId`. A dedicated key DB
(`notelikeus-notes-crypto`) holds a non-extractable AES-GCM-256 key — never the attachment key or
the legacy lock key. Flags, colors, positions, timestamps, labels, and attachment refs stay on the
plaintext shell so sync and listing still work if decrypt fails.

### Migration and dual-read

Same pattern as pending attachments: seal on write when WebCrypto is available; dual-read legacy
plaintext; rewrite when possible. Callers always receive a full plaintext `Note`.

### Threat model

Profile-at-rest only. **Not an XSS mitigation.** Cloud-synced note text remains plaintext on the
server by product design (no E2E claim).

## Web: say what it protects, and do not overstate it

Browser-side encryption of IndexedDB is a fundamentally weaker proposition than OS keystores, and
the distinction must stay explicit in any user-facing text:

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

Web pending-attachment and note-body sealing are implemented for the profile-at-rest case. Privacy
copy must keep saying that precisely, and must not claim protection against a compromised page.

## Summary

| | Android | Windows | Web |
|---|---|---|---|
| Notes DB today | SQLCipher + Keystore | SQLCipher v4 + DPAPI `notes-db.key` (Windows default) | IndexedDB: title/content/checklist sealed (`NLN1`) |
| Attachments today | AES-GCM + Keystore | AES-GCM + DPAPI | AES-GCM (`NLA1`) pending blobs + non-extractable WebCrypto key |
| Proposed next | no change | no change (Windows CI covers DPAPI + JDBC) | no change |
| Blocker | — | — | profile-at-rest only / not XSS |
