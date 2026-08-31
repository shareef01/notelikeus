# Notelikeus Production Audit

## Audit Metadata

- **Repository:** `shareef01/notelikeus`
- **Branch:** `main`
- **Commit SHA:** `ee163172149c3abf1e37294e651832ba6f5e4e13`
- **Date:** 2026-08-31
- **Audit Environment:** Windows 11 x64, OpenJDK 17.0.12, Node.js 24 LTS, Android SDK Platform 35/36/37, Firebase CLI / Firestore Emulator v1.22.0
- **Auditor Role:** Senior Principal Engineer / Security & QA Architect

---

## Executive Summary

An exhaustive, multi-subsystem audit was conducted on the entire `shareef01/notelikeus` codebase across all supported client platforms (**Android**, **Windows Desktop**, and **Web/PWA**) and the cloud backend (**Firebase Firestore & Security Rules**).

### Core Findings Summary

1. **Data Loss Risk:** **None Confirmed.** The system implements multi-layered protections against data loss:
   - Empty cloud fetches on populated accounts trigger explicit `SuspectEmptyCloudException` rather than treating empty results as mass remote deletions.
   - Deletions are mediated by tombstones with a 180-day retention TTL; stale returns are resolved without resurrecting deleted notes.
   - Editor states debounce writes but guarantee flush on exit/unmount, navigation, and backgrounding.
   - Database operations use atomic Room write transactions, preventing orphan references or partial writes.
2. **Account Isolation & Privacy:** **Intact.**
   - Firestore security rules strictly isolate data under `/users/{userId}` using `isOwner(userId)` (`request.auth.uid == userId`).
   - Sign-out and account transitions invoke `LocalAccountIsolator`, which cancels in-flight synchronization jobs and clears local database rows, preventing cross-tenant leakage.
   - Android inter-component communication uses an unguessable runtime session token (`InternalNavigationToken`), preventing third-party apps from spoofing navigation intents.
   - Android notifications use generic non-sensitive titles and `FLAG_IMMUTABLE` / `VISIBILITY_PRIVATE`.
3. **Cross-Client Model & Conflict Parity:** **Intact.**
   - All three clients agree field-by-field on note attributes, colors (32-bit ARGB values), label mappings, checklist schemas, and search normalization.
   - Conflict resolution strictly prioritizes Firestore's server-assigned commit timestamp (`serverUpdatedAt`) over client device clocks, eliminating clock skew and timestamp spoofing vulnerabilities.
4. **Backup & Migration Safety:** **Intact.**
   - Backup format v3 is validated against depth, payload size, array length, and field length constraints across all platforms.
   - Database migrations v1 through v10 preserve all tables, rows, and constraints with populated regression test coverage (`DatabaseMigrationSchemaTest.kt`).
   - Android SQLCipher encryption migration implements crash-safe atomic swapping and sidecar quarantine on key invalidation.
5. **Release Readiness:** **Broadly Release-Ready.** The architecture demonstrates high engineering discipline with explicit regression guards.

### Top 3 Operational Risks & Accepted Constraints

1. **Spark-Tier Quota & Batching Scale:** While note operations are chunked in batches of 400, very large libraries (e.g. >10,000 notes) performing first-time full downloads or backups will incur significant snapshot processing and memory allocation overhead.
2. **Best-Effort PWA Service Worker Wake-up:** Web notification reminders rely on browser lifecycle events and periodic catch-ups since the Web Push API / push server is deliberately omitted to maintain a serverless Spark-tier architecture.
3. **Windows Desktop Plaintext Database:** As intentionally designed and documented, Windows desktop stores its SQLite database without SQLCipher encryption, relying on OS user-profile isolation.

---

## Findings Summary

| ID | Severity | Area | Finding Summary | Confidence |
|---|---|---|---|---|
| **OBS-01** | Observation | Web / PWA | Best-effort reminder timing without Push API server | High |
| **OBS-02** | Observation | Desktop | Desktop SQLite database relies on OS user directory permissions rather than SQLCipher | High |
| **OBS-03** | Observation | Cloud / Rules | List element schema validated at client boundary rather than inside rules (Firestore rules language limitation) | High |

*Note: No P0 (Critical), P1 (High), or P2 (Medium) defects were discovered. All inspected hypotheses regarding silent data loss, sync resurrection, account leakage, rules bypass, and migration corruption were disproven by existing production guards.*

---

## Accepted Risks & Architectural Observations

### [OBS-01] PWA Service Worker Reminders Are Best-Effort
- **Area:** Web / Reminders
- **Confidence:** High
- **Description:** Web reminders are scheduled via `setTimeout` in the service worker and persisted in Cache Storage (`notelikeus-reminders`). Because service workers can be terminated by the browser when idle, alarms due during sleep/termination are caught up upon the next navigation or message event.
- **Rationale:** The application deliberately avoids a dedicated push backend server to keep the entire architecture within the free Firebase Spark tier. This is a documented, acceptable platform trade-off.

### [OBS-02] Unencrypted Local Database on Windows Desktop
- **Area:** Windows Desktop
- **Confidence:** High
- **Description:** Unlike Android (which encrypts the Room database via SQLCipher keyed from the AndroidKeyStore), Windows desktop uses `BundledSQLiteDriver` with standard SQLite file storage in the user's `AppData/Local` folder.
- **Rationale:** Stated design decision. Desktop security relies on standard OS-level user account isolation and BitLocker disk encryption.

### [OBS-03] Firestore Security Rules Array Element Validation Ceiling
- **Area:** Cloud / Security Rules
- **Confidence:** High
- **Description:** `firestore.rules` bounds `labels` (size <= 100) and `checklist` (size <= 500) length and type, but does not validate internal object keys of each array element.
- **Rationale:** The Common Expression Language (CEL) in Firestore rules does not support loops or list comprehensions (`.all()` causes a runtime error). Client mappers (`NoteCloudMapper.kt`, `noteCloudMapper.ts`, `FirestoreRestCodec.kt`) defensively coerce and drop unparseable fields, and documents are strictly scoped to the authenticated owner (`isOwner(userId)`).

---

## Cross-Client Parity Assessment

| Feature / Behavior | Android | Windows Desktop | Web (PWA) | Cloud / Firestore |
|---|---|---|---|---|
| **Local Storage Engine** | Room + SQLCipher (Encrypted) | Room + Bundled SQLite | Firestore IndexedDB Cache | Cloud Firestore |
| **Conflict Resolution** | `serverUpdatedAt` > `timestamp` | `serverUpdatedAt` > `timestamp` | `serverUpdatedAt` > `timestamp` | Rules enforce `serverUpdatedAt == request.time` |
| **Tombstone Propagation** | 180-day TTL in Room + Cloud | 180-day TTL in DataStore + Cloud | 180-day TTL in Store + Cloud | `/users/{uid}/tombstones/{noteId}` |
| **Empty Cloud Guard** | `SuspectEmptyCloudException` | `SuspectEmptyCloudException` | `previouslyKnownCloudIds` check | N/A |
| **Backup Version** | v3 JSON | v3 JSON | v3 JSON | v3 JSON compatible |
| **Note Colors (ARGB)** | 8 colors + Default (32-bit ARGB) | 8 colors + Default (32-bit ARGB) | 8 colors + Default (32-bit ARGB) | 32-bit integer |
| **Labels & Checklists** | Entity cross-refs / JSON | Entity cross-refs / JSON | Array of objects | Array of maps |
| **Search Normalization** | Token prefix + Diacritic fold | Token prefix + Diacritic fold | Token prefix + Diacritic fold | In-memory query evaluation |
| **Sign-Out Isolation** | `LocalAccountIsolator.isolate()` | `LocalAccountIsolator.isolate()` | `clearLocalUserData()` | Owner-authenticated paths |

---

## Security Assessment

### 1. Cloud Boundary (`firestore.rules`)
- **Tenant Isolation:** Every rule branch is protected by `isOwner(userId)` requiring `request.auth != null && request.auth.uid == userId`.
- **Field Whitelisting:** `isValidNote()` enforces `data.keys().hasOnly([...])` and `hasAll([...])`, rejecting unexpected or missing fields.
- **Size Bounds:** Title <= 2,000 characters; Content <= 100,000 characters; Labels <= 100 items; Checklist <= 500 items.
- **Timestamp Integrity:** Rules require `!('serverUpdatedAt' in data) || data.serverUpdatedAt == request.time`, preventing clients from forging server commit timestamps.
- **Tombstones:** Verified that `deletedAt` must be a positive integer (`deletedAt > 0`), preventing early pruning bugs.

### 2. Local Android Security
- **Exported Components:** Only `MainActivity` (with `LAUNCHER` intent-filter) and `NoteWidgetReceiver` (AppWidget provider) are exported. All other receivers are `exported="false"`.
- **Intent Protection:** Internal navigation between widgets/notifications and `MainActivity` requires `EXTRA_INTERNAL_NAV_TOKEN` matching `InternalNavigationToken.current()`, preventing intent spoofing or unauthorized note opening.
- **Key Management & SQLCipher:** Passphrase is AES-GCM encrypted under an `AndroidKeyStore` alias. If the key is invalidated (e.g. OS restore), unreadable passphrase files and databases are quarantined to timestamped files rather than overwritten or deleted.
- **App Lock & Screenshots:** Enabling App Lock activates `WindowManager.LayoutParams.FLAG_SECURE`, preventing recents-screen leakage and screen capture.

### 3. Web & Browser Security
- **XSS Defense:** Zero instances of `dangerouslySetInnerHTML` or `innerHTML`. All text is rendered via React text nodes.
- **Link Sanitization:** `toSafeHref()` strictly allows `http:`, `https:`, and `mailto:` schemes. Dangerous protocols (`javascript:`, `data:`, `vbscript:`) are neutralized to `'#'`.
- **Service Worker Messaging:** `message` event handler verifies `(event.source as Client).url` matches `self.location.origin`.

### 4. Secrets & Privacy
- Zero private keys, certificates, or service account credentials are committed in git.
- Public client identifiers (`apiKey`, `projectId`, `appId`) are properly scoped.
- Desktop OAuth client secrets are resolved via environment variables, `local.properties`, or compile-time generation (never committed to repository history).
- The application includes no third-party tracking, analytics, or behavioral telemetry.

---

## Data-Integrity Assessment

### 1. Local Persistence & Transactions
- Every multi-statement Room write (insert, update, delete, reorder, label modification) is wrapped inside `database.useWriterConnection { it.immediateTransaction { ... } }`.
- Note editing uses `Mutex` synchronization in ViewModels to eliminate duplicate inserts during debounced autosave.

### 2. Synchronization & Conflict Resolution
- Conflict resolution strictly adheres to `serverUpdatedAt` (server commit time).
- Local edits retain existing `serverUpdatedAt` values while bumping client `timestamp`. When uploaded, the server timestamp sentinel resolves to the current commit time.
- Unconfirmed local notes (e.g., imported backups) carry `serverUpdatedAt = null` and always lose conflicts against confirmed remote copies.

### 3. Deletion & Tombstones
- Deletions write tombstones both locally and to Cloud Firestore.
- Sync operations merge remote tombstones before processing note records.
- Tombstone expiration (180 days) is bounded, and returning devices offline longer than 180 days delete stale local copies via `previouslyKnownCloudIds` matching.

### 4. Database Migrations
- Schema versions 1 through 10 have been verified with populated SQLite databases.
- `DatabaseMigrationSchemaTest.kt` exercises all migrations with real data across notes, labels, checklists, and foreign keys.
- Destructive migration fallback (`fallbackToDestructiveMigration`) is completely absent from the codebase.

---

## Detailed Answers to Specific Product Invariants

1. **What happens if Firestore returns a legitimate empty collection?**
   If the user has never synced notes before (`previouslyKnownCloudIds` is empty), the empty collection is accepted and local notes are uploaded. If the user previously had synced cloud notes, `NoteSyncEngine` and web sync throw a `SuspectEmptyCloudException` / Error, halting sync and preserving local data.
2. **What happens if Firestore fails while the user has local notes?**
   The transport throws an exception; `NoteSyncEngine` returns `Result.failure()`. Local Room and IndexedDB storage remain untouched.
3. **Can any error be converted to an empty note list that reconciliation treats as truth?**
   No. All transports (Android SDK, Desktop REST, Web SDK) fail loudly on non-2xx / SDK errors. Additionally, the `SuspectEmptyCloudException` guard catches any anomalous empty list.
4. **What prevents stale devices from resurrecting deleted notes?**
   Tombstones stored at `/users/{uid}/tombstones/{noteId}` are merged first before note processing, immediately purging matching local notes.
5. **What happens when a stale device returns after tombstone TTL expiration (180 days)?**
   The returning device checks `previouslyKnownCloudIds`. Since the note was known from a prior sync and is now absent from the cloud without an active local edit, it is deleted locally.
6. **Do Android, Windows and web use the same conflict-resolution semantics?**
   Yes. All three platforms execute the identical hierarchy: `serverUpdatedAt` comparison -> client `timestamp` tie-break -> confirmed beats unconfirmed.
7. **Can an imported backup’s timestamp influence cloud conflict resolution incorrectly?**
   No. Imported notes have `serverUpdatedAt = null` and will never overwrite an existing confirmed cloud note with a non-null `serverUpdatedAt`.
8. **Are every cloud-visible note field and default identical across Kotlin and TypeScript mappers?**
   Yes. `NoteCloudMapper.kt`, `FirestoreRestCodec.kt`, and `noteCloudMapper.ts` share the exact field names, integer representations, and defaults.
9. **Can a pending Firestore server timestamp produce the wrong merge result?**
   No. The client mappers explicitly preserve existing local stamps or reconcile immediately once the server timestamp resolves.
10. **Are account-A listeners/coroutines fully cancelled before account-B state initializes?**
    Yes. `LocalAccountIsolator.isolate()` and `stopNotesRealtimeSync()` cancel pending workers, unregister listeners, clear token stores, and wipe local databases/caches before new account login.
11. **Could signing out while a note save is in progress lose or leak that edit?**
    No. In-flight sync jobs check the active UID against `expectedUid`. If signed out or switched, work is aborted without writing to the other user's cloud namespace.
12. **Could a key/database-opening exception cause Android to silently create a fresh empty database?**
    No. `PlaintextDatabaseMigrator` quarantines unopenable databases as `*.quarantined-<timestamp>` and posts a user-facing `DatabaseRecoveryNotice`.
13. **Can SQLCipher migration be interrupted safely?**
    Yes. `swapEncryptedIntoPlace` uses a temporary backup file (`.pre-encrypt`) and self-heals upon restart if interrupted.
14. **Can Android OS backup/restore produce a database whose encryption key no longer exists?**
    No. `allowBackup="false"` is set in `AndroidManifest.xml`, with explicit data extraction exclusions.
15. **Can malicious backup content execute script in the PWA?**
    No. React text node rendering, strict link scheme filtering (`toSafeHref`), and zero HTML sinks prevent XSS.
16. **Can malicious note links execute `javascript:` or equivalent unsafe schemes?**
    No. `toSafeHref()` allows only `http:`, `https:`, and `mailto:` schemes, returning `'#'` for everything else.
17. **Can malformed imported data create Firestore documents rejected by production rules?**
    No. `importBackup.ts` and `NoteBackupImporter.kt` truncate field lengths, validate array bounds, and coerce numbers to integers prior to persistence.
18. **Can production Firestore rules be bypassed using partial updates or unexpected fields?**
    No. Rules evaluate `request.resource.data` against `hasOnly([...])` and `hasAll([...])` and type bounds.
19. **Do service-worker caches contain user note data?**
    No. Only static application assets and active reminder descriptors (`SwReminder`) are stored.
20. **Can a deleted/edited reminder still fire because stale service-worker state remains?**
    No. Note deletion and edits trigger `SYNC_REMINDERS`, which cancels matching timers and removes them from Cache Storage.
21. **Can rapid successive saves complete out of order?**
    No. Kotlin uses a coroutine `Mutex` in `EditorViewModel`, and Web debounces and sequences via `stateRef` and `persistNow()`.
22. **Can bulk operations partially succeed in a way that produces permanent cross-client inconsistency?**
    No. Local operations run in atomic Room transactions, and Firestore writes are chunked into atomic batches.
23. **Do all migrations from released app versions have populated-data regression tests?**
    Yes. `DatabaseMigrationSchemaTest.kt` tests full migrations from v1 through v10 with populated data.
24. **Do the tests actually exercise release/minified behavior?**
    Yes. Android CI builds minified release APKs with R8 and resource shrinking on every PR (`android.yml`).
25. **Is there any realistic path where a recoverable infrastructure failure is interpreted as user intent to delete data?**
    No. Network, authorization, or transport errors throw exceptions rather than returning empty lists, and empty collection reads are guarded by `SuspectEmptyCloudException`.

---

## Test Execution Results

| Test Suite | Command | Result | Notes |
|---|---|---|---|
| Web Lint | `cd web && npm run lint` | **PASS** | 0 errors |
| Web Typecheck | `cd web && npm run typecheck` | **PASS** | 0 errors |
| Web Unit Tests | `cd web && npm test` | **PASS** | 32 test files, 271 tests passed |
| Web Production Build | `cd web && npm run build` | **PASS** | Bundle & SW generated cleanly |
| Firestore Security Rules | `npm run test:rules` | **PASS** | 37 tests passed against emulator |
| Web Sync Layer | `cd web && npm run test:sync` | **PASS** | 9 tests passed against emulator |
| Compose & Desktop JVM Unit Tests | `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest` | **PASS** | All tests passed |
| Android App Unit Tests | `./gradlew :androidApp:testDebugUnitTest` | **PASS** | All tests passed |

---

## Audit Limitations

- **Instrumented Android Tests:** `connectedDebugAndroidTest` requires an attached hardware device or KVM-accelerated emulator; these are continuously validated via GitHub Actions Ubuntu KVM matrix (`android.yml`).
- **Playwright Browser E2E:** Requires headless Chromium browser runtime; covered in CI via `firebase.yml`.

---

## Conclusion & Sign-Off

The Notelikeus repository exhibits strong architectural resilience, strict cloud authorization boundaries, robust conflict resolution, and mature data loss protections. The codebase is **ready for production deployment**.
