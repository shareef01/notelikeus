# Notelikeus

A local-first, cross-platform notes application for Android, Windows, and the Web. Built with Kotlin Multiplatform, Compose, and React, featuring encrypted storage at rest, optimistic offline-first sync, and serverless media authorization.

- **Live Web App:** <https://notelikeus.pages.dev>
- **Latest Release:** [v2.4.1](https://github.com/shareef01/notelikeus/releases/tag/v2.4.1) (Android APK & Windows MSI)
- **Architecture Documentation:** [docs/BACKEND_ARCHITECTURE.md](docs/BACKEND_ARCHITECTURE.md)
- **Decisions Record:** [docs/DECISIONS.md](docs/DECISIONS.md)

---

## Previews

### Desktop & Web

| Windows (Compose Multiplatform) | Web PWA (React & Tailwind) |
|---|---|
| <img src="screenshots/windows-notes.png" alt="Notelikeus Windows application preview" width="480" /> | <img src="screenshots/web-notes.png" alt="Notelikeus Web note grid" width="480" /> |

### Mobile (Android)

<p align="center">
  <img src="screenshots/android-notes.png" width="22%" alt="Android Notes List" />
  <img src="screenshots/android-editor.png" width="22%" alt="Android Note Editor" />
  <img src="screenshots/android-drawer.png" width="22%" alt="Android Navigation Drawer" />
  <img src="screenshots/android-settings.png" width="22%" alt="Android Settings" />
</p>

---

## Key Features

| Capability | Android | Windows | Web PWA |
|---|:---:|:---:|:---:|
| **Local-First Core** (usable offline without an account) | ✓ | ✓ | ✓ |
| **Encrypted Local Storage** | SQLCipher (Keystore) | SQLCipher (DPAPI) | WebCrypto AES-GCM |
| **Rich Text, Checklists & Labels** | ✓ | ✓ | ✓ |
| **Image Attachments** (staged locally, deferred upload) | ✓ | ✓ | ✓ |
| **Conflict Handling** (side-by-side branch review) | ✓ | ✓ | ✓ |
| **Scheduled Reminders** | System Alarms | System Tray | Service Worker |
| **Biometric App Lock** | BiometricPrompt | — | — |
| **Home-screen Glance Widget** | Android Glance | — | — |
| **Cross-Platform Sync** (Supabase Postgres + Realtime) | Optional | Optional | Optional |
| **Automated Bot Abuse Deterrence** | In-app Turnstile | Browser Handshake | Turnstile Widget |
| **Lossless Backup Import/Export** (`.json` & `.nlkbak` ZIPs) | ✓ | ✓ | ✓ |

---

## Technical Architecture

```
┌────────────────────────┐      ┌────────────────────────┐      ┌────────────────────────┐
│      Android App       │      │      Windows App       │      │        Web PWA         │
│  (Kotlin Compose Multi)│      │  (Kotlin Compose Multi)│      │  (React 19 + TypeScript│
│ Room + SQLCipher (Key) │      │ Room + SQLCipher(DPAPI)│      │ IndexedDB + WebCrypto) │
└───────────┬────────────┘      └───────────┬────────────┘      └───────────┬────────────┘
            │                               │                               │
            └───────────────────────┬───────┴───────────────────────────────┘
                                    │
                                    ▼
                     ┌─────────────────────────────┐
                     │     Supabase / Postgres     │
                     │  • Sequence-based OCC       │
                     │  • Row-Level Security (RLS) │
                     │  • Idempotent sync RPCs     │
                     │  • Wipe-generation fence    │
                     └──────────────┬──────────────┘
                                    │
                                    ▼
                     ┌─────────────────────────────┐
                     │ Cloudflare Worker + R2 Edge │
                     │  • Bearer JWT verification  │
                     │  • Owner-isolated keys      │
                     │  • Provisional claim sweep  │
                     └─────────────────────────────┘
```

### Local-First & Synchronization Design
1. **Decoupled Local Persistence:** Local databases are completely authoritative for unsaved and offline work. Network outages or cloud synchronization failures are treated as pending sync queues rather than application errors.
2. **Deterministic Sequence-Based Concurrency:** Cloud synchronization avoids flawed client-clock comparisons. Mutations acquire optimistic locks against a server-managed global sequence (`p_base_revision`). Conflicting concurrent writes preserve both edits by generating explicit conflict branches with a side-by-side review workflow.
3. **Tombstone Convergence:** Deletions propagate via tombstones. Unconfirmed deletes reconcile on subsequent pulls, ensuring deleted items are permanently removed across all connected devices.
4. **Wipe-Generation Fencing:** Server-side wipe generations ensure that a stale or newly reconnected device cannot replay obsolete local records over an intentional account wipe.
5. **Two-Phase Deferred Attachment Protocol:** Images are AES-GCM sealed locally and uploaded via a Cloudflare Worker (`/v2/attachments`) with a provisional status. The storage row only transitions to confirmed when an authorized note mutation explicitly references it. Unreferenced or orphaned uploads are reclaimed automatically via background sweeper tasks.

### Security & Privacy Highlights
- **Storage Encryption at Rest:** Mobile keys are sealed inside hardware-backed Android Keystore; desktop keys are protected via Windows DPAPI; web client bodies, titles, and checklist payloads are sealed in IndexedDB via WebCrypto AES-GCM.
- **Strict Authorization Boundary:** Database queries execute via `SECURITY INVOKER` functions relying strictly on `auth.uid()`. Cloudflare Workers reconstruct canonical R2 paths exclusively from the verified token (`owners/{owner_id}/notes/{note_id}/attachments/{id}`), preventing cross-tenant access.
- **Bot & Resource Abuse Mitigation:** Automated Cloudflare Turnstile verification guards email one-time code requests across all platforms.
- **Zero Tracking:** No analytics SDKs, trackers, or telemetry exist in any client.

---

## Technology Stack

- **Mobile & Desktop Core:** Kotlin Multiplatform 2.1, Jetpack Compose Multiplatform 1.8, Room 2.8 with SQLCipher, AndroidX Glance, Koin Dependency Injection, Kotlinx Coroutines & Serialization.
- **Web PWA:** React 19, TypeScript, Tailwind CSS, Vite, Zustand, Workbox Service Worker.
- **Cloud Backend:** Supabase (PostgreSQL 15, PostgREST, Realtime, Row-Level Security, pgTAP test suite).
- **Edge Storage:** Cloudflare Workers (TypeScript) with Cloudflare R2 bucket storage.
- **CI/CD & Tooling:** GitHub Actions, Detekt, Vitest, Playwright, Node.js test runner.

---

## Getting Started

### Prerequisites
- JDK 21 (for Gradle daemon) and JDK 17 (build target).
- Node.js 24+.
- Docker (optional, required for local Supabase test emulator).

### Local Setup

```bash
# Clone the repository
git clone https://github.com/shareef01/notelikeus.git
cd notelikeus

# Install root dependencies
npm install

# Start local Supabase development stack (requires Docker)
npm run supabase:start
npm run supabase:reset
```

### Running the Applications

#### Web Client
```bash
cd web
npm install
npm run dev
```

#### Windows Desktop
```bash
./gradlew :composeApp:run
```

#### Android Debug Build
```bash
npm run kotlin:staging-properties
./gradlew :androidApp:assembleDebug
```

---

## Testing & Quality Assurance

The codebase is backed by rigorous automated test suites, cross-platform fixtures, and static analysis:

| Test Suite | Scope & Coverage | Command |
|---|---|---|
| **Kotlin Static Analysis** | Detekt code quality gates against baseline | `./gradlew detekt` |
| **Multiplatform Unit Tests** | Sync engine, crypto migrators, Room, conflict resolution | `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest :androidApp:testDebugUnitTest` |
| **Web Unit Tests** | Merge logic, backup parsing, reactive stores, contrast | `npm test --prefix web` |
| **Attachment Worker Tests** | JWT authentication, object key isolation, rate limits, sweep | `npm test --prefix workers/attachments` |
| **Cross-Client Contracts** | Canonical JSON fixtures asserted across Kotlin and TypeScript | Part of Kotlin and Web test runs |
| **Deployment & Secret Scans** | Artifact verification, CSP pinning, leak prevention | `npm run test:pages-bundle-secrets` |

---

## Project Structure

```
├── androidApp/          # Android-specific entry point, Glance widget, and AppFunctions
├── composeApp/          # Shared Kotlin Multiplatform domain, Room DB, Compose UI, Desktop target
├── web/                 # React 19 PWA client (TypeScript, Zustand, Tailwind)
├── supabase/            # PostgreSQL migrations, schema definitions, and pgTAP suites
├── workers/attachments/ # Cloudflare Worker authorizing R2 attachment transactions
├── cloudflare/          # Edge security headers and verification scripts
├── contracts/           # Cross-client JSON schemas and interoperability fixtures
├── docs/                # Architecture specifications, ADRs (DECISIONS.md), and runbooks
└── scripts/             # Operational automation, release packagers, and secret validators
```

---

## Limitations

- **Not End-to-End Encrypted in Transit:** Cloud sync relies on server-enforced access controls (Row Level Security and authenticated RPCs). Data is encrypted locally at rest, but decrypted on the server during database storage and transaction evaluation.
- **Desktop System Tray:** Reminder alerts on Windows are active while the background application is running.

---

## License

All rights reserved. Copyright &copy; 2026.
