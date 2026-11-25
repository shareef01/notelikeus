# Features by platform

Moved out of the root README on 2026-11-18. The three clients share one data model (see
[`contracts/`](../contracts/) and [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md)); this table is
what each one adds on top.

| Capability | Android | Windows (Rust) | Web PWA |
|---|:---:|:---:|:---:|
| Works offline without an account | ✓ | ✓ | ✓ |
| Encrypted local storage | SQLCipher, key in Android Keystore | AES-GCM, data key protected by DPAPI | WebCrypto AES-GCM in IndexedDB |
| Rich text, checklists, labels | ✓ | ✓ | ✓ |
| Images (stored locally first, uploaded later) | ✓ | ✓ | ✓ |
| Conflict copies with side-by-side review | ✓ | ✓ | ✓ |
| Reminders | System alarms | Tray (while the app runs) | Service worker |
| Biometric app lock | BiometricPrompt | — | — |
| Home-screen widget | Glance | — | — |
| Sync (Supabase Postgres and Realtime) | Optional | Optional | Optional |
| Bot protection on sign-in codes | In-app Turnstile | Browser handshake | Turnstile widget |
| Backup import and export (`.json`, `.nlkbak`) | ✓ | ✓ | ✓ |
| Account deletion | ✓ | ✓ | ✓ |

Windows reminders fire only while the app is running; closing the window with a reminder pending
keeps it in the tray.
