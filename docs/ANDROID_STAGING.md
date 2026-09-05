# Android / Desktop staging (Supabase)

Clients use the same Supabase configuration keys in debug and release. There is no Firebase fallback.

```bash
npm run kotlin:staging-properties
```

That merges these keys into `local.properties` and leaves `notelikeus.oauthClientSecret` / `sdk.dir` alone:

| Key | Source |
|-----|--------|
| `notelikeus.supabaseUrl` | `VITE_SUPABASE_URL` |
| `notelikeus.supabaseAnonKey` | public anon JWT (`eyJ…`, never `sb_…`) |
| `notelikeus.attachmentsWorkerUrl` | `VITE_ATTACHMENTS_WORKER_URL` |

The writer refuses secret API keys.

See `docs/BACKEND_ARCHITECTURE.md` for production env names (`NOTELIKEUS_SUPABASE_URL`, etc.).

## Android

`System.getenv` is usually empty on a device. `BuildConfig` in `:composeApp` is filled from `local.properties` or `NOTELIKEUS_*` environment variables at compile time.

1. Run `npm run kotlin:staging-properties`.
2. Rebuild and install (`./gradlew :androidApp:installDebug` or `assembleRelease` with env keys).
3. Sign in with Google (or a staging email user).

A properties change is compile-time for Android: rebuild after editing `local.properties`.

## Desktop

`./gradlew run` reads the same `local.properties` keys at runtime (env vars still win). Restart the process after changing the file.

## Tests

```bash
npm run test:kotlin-staging-properties
./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest
```
