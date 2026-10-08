# Play Store publishing checklist

Use this list before submitting Notelikeus to Google Play.

## Build

- [ ] Create the release keystore and `signing.properties` (see `signing.properties.example`).
      That key is the app's long-lived signing identity — back it up offline and never commit it.
      `.gitignore` already excludes `*.jks`, `*.keystore`, `*.p12` and `signing.properties`.
- [ ] For CI-signed releases, add the four `ANDROID_SIGNING_*` repository secrets and, optionally,
      the public `ANDROID_SIGNING_CERT_SHA256` repository variable (same file). A run for a release
      tag then signs, verifies and attaches the artifacts; without the secrets it still builds and
      attaches them unsigned, as every release so far has.
- [ ] Run `./gradlew :androidApp:bundleRelease` and test the AAB on a physical device
- [ ] Run `./gradlew :composeApp:testDebugUnitTest` and `./gradlew :androidApp:assembleRelease`
- [ ] Verify app lock, reminders, backup export/import, cloud sync, and widget on a real device

## Store listing (`store/listing/en-US/`)

- [ ] **Title** — `title.txt`
- [ ] **Short description** — `short_description.txt`
- [ ] **Full description** — `full_description.txt`
- [ ] **What's new** — `whats_new.txt`
- [ ] **App icon** — 512×512 PNG (use `ic_launcher` artwork)
- [ ] **Feature graphic** — 1024×500 PNG
- [ ] **Phone screenshots** — at least 2 (light + dark recommended)
- [ ] **7-inch / 10-inch tablet** screenshots (optional)

## Policy & compliance

- [ ] **Privacy policy URL** — `https://notelikeus.pages.dev/privacy.html` (or a custom domain once attached). The old Firebase Hosting URL is retired.
- [ ] **Data safety** — complete form using `DATA_SAFETY.md`
- [ ] **Content rating** — complete IARC questionnaire (notes app, no user-generated public content)
- [ ] **Target audience** — set age group (likely 13+ or all ages; no child-directed content)
- [ ] **News app / COVID / government** — No

## Technical

- [ ] **App category** — Productivity
- [ ] **Contact email** — developer support address
- [ ] **Package name** — `com.aus.notelikeus` (cannot change after first upload)

## Post-launch

- [ ] Tag release in git: `v1.0.0`
- [ ] Update `CHANGELOG.md` for next version
- [ ] Increment `versionCode` in `androidApp/build.gradle.kts` and `notelikeus.versionName` in
      `gradle.properties` (currently `6` / `2.0.0`)
