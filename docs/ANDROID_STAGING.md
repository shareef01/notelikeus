# Android / Desktop staging (Supabase)

Debug clients can use the same staging stack as https://notelikeus-dev.pages.dev/ without flipping production allow flags. Firebase remains the default for release APKs and Play builds.

**Do not** set `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION` or `VITE_ALLOW_SUPABASE_PRODUCTION`.

## One-time: write gitignored properties

Requires `web/.env.staging` from `npm run setup:staging`:

```bash
npm run kotlin:staging-properties
```

That merges these keys into `local.properties` and leaves `notelikeus.oauthClientSecret` / `sdk.dir` alone:

| Key | Source |
|-----|--------|
| `notelikeus.remoteBackend` | `VITE_REMOTE_BACKEND` (`supabase`) |
| `notelikeus.supabaseUrl` | `VITE_SUPABASE_URL` |
| `notelikeus.supabaseAnonKey` | public anon JWT (`eyJ…`, never `sb_…`) |
| `notelikeus.attachmentsWorkerUrl` | `VITE_ATTACHMENTS_WORKER_URL` |

The writer refuses secret API keys and never writes an allow-production key.

## Android

`System.getenv` is usually empty on a device. Debug `BuildConfig` in `:composeApp` is filled from `local.properties` (or `NOTELIKEUS_*` env at compile time). Release fields stay empty, so R8/Play builds stay on Firebase.

1. Run `npm run kotlin:staging-properties`.
2. Rebuild and install the **debug** APK (`./gradlew :androidApp:installDebug`).
3. Sign in with Google (or a staging email user) and import `scripts/ops/fixtures/backup.rehearsal.example.json` from Profile.

A properties change is compile-time: rebuild after editing `local.properties`.

## Desktop

`./gradlew run` reads the same `local.properties` keys at runtime (env vars still win). Restart the process after changing the file. Packaged MSI builds do not read this file and stay on Firebase unless a future cutover build sets the production allow flag.

## Tests

```bash
npm run test:kotlin-staging-properties
./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest
```


## Production cutover (Android)

`assembleRelease` alone can never reach Supabase, and that is deliberate. A release APK sees no
process environment, so a cutover build has to carry its configuration in `BuildConfig` — and if
that were sourced from `local.properties`, an ordinary release on any machine holding staging keys
would silently ship users onto Supabase.

So the release variant reads **environment only**, and every Supabase field is gated on one flag:

```bash
NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION=true NOTELIKEUS_REMOTE_BACKEND=supabase NOTELIKEUS_SUPABASE_URL=https://<project>.supabase.co NOTELIKEUS_SUPABASE_ANON_KEY=<public anon JWT> NOTELIKEUS_ATTACHMENTS_WORKER_URL=https://<worker>.workers.dev ./gradlew :androidApp:assembleRelease
```

Without `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION`, all five fields generate empty, `firstNonBlank`
resolves them to null, and `isSupabaseRemoteSelected` returns false on its first line — Firebase.

Verified against generated `BuildConfig.java` on 2026-09-05, with staging keys present in
`local.properties` throughout:

| Build | `ALLOW_SUPABASE_PRODUCTION` | Other four fields |
| --- | --- | --- |
| Ordinary `assembleRelease` | `""` | all `""` |
| Cutover (env set) | `"true"` | all populated |

Plus six unit tests in `SupabaseCutoverSelectionTest` covering the combinations that must *not*
flip: backend without the allow flag, allow flag without a backend, and a cutover pointed at
localhost.

**The debug variant never carries the allow flag.** `isDebug` already enables Supabase, so baking
it there would let a debug artifact behave like a cutover build.
