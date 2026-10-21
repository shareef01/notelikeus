# Android / Desktop staging (Supabase)

Clients use the same Supabase configuration keys in debug and release. There is no Firebase fallback.

```bash
npm run kotlin:staging-properties
```

That merges these keys into `local.properties` and leaves other keys such as `sdk.dir` alone:

| Key | Source |
|-----|--------|
| `notelikeus.supabaseUrl` | `VITE_SUPABASE_URL` |
| `notelikeus.supabaseAnonKey` | public client key: `sb_publishable_…`, or the legacy anon JWT `eyJ…` (D31); never `sb_secret_…` |
| `notelikeus.attachmentsWorkerUrl` | `VITE_ATTACHMENTS_WORKER_URL` |

The writer refuses secret API keys.

See `docs/BACKEND_ARCHITECTURE.md` for production env names (`NOTELIKEUS_SUPABASE_URL`, etc.).

## Android

`System.getenv` is usually empty on a device. `BuildConfig` in `:composeApp` is filled from `local.properties` or `NOTELIKEUS_*` environment variables at compile time.

1. Run `npm run kotlin:staging-properties`.
2. Rebuild and install (`./gradlew :androidApp:installDebug` or `assembleRelease` with env keys).
3. Sign in with your email: enter the 6-digit code Supabase emails you (debug builds also offer the email/password test login).

A properties change is compile-time for Android: rebuild after editing `local.properties`. `bundleRelease` refuses to build without a hosted URL, anon key and Worker URL, so a store bundle cannot ship with sync off.

## Desktop

The same keys are compiled into the desktop build (`CompiledBackendConfig`), which is what an installed MSI uses. At runtime env vars win, then the compiled values, then a `local.properties` found on disk. Rebuild after changing the file. Packaging an MSI refuses to run without all three values.

## Tests

```bash
npm run test:kotlin-staging-properties
./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest
```
