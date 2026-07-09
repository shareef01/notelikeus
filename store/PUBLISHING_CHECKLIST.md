# Play Store publishing checklist

Use this list before submitting Notelikeus to Google Play.

## Build

- [ ] Create release keystore and `signing.properties` (see README)
- [ ] Run `./gradlew :app:bundleRelease` and test the AAB on a physical device
- [ ] Run `./gradlew :app:testDebugUnitTest` and fix any failures
- [ ] Verify app lock, reminders, backup export/import, and widget on a real device

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

- [ ] **Privacy policy URL** — host `PRIVACY_POLICY.md` or link to repository raw file
- [ ] **Data safety** — complete form using `DATA_SAFETY.md`
- [ ] **Content rating** — complete IARC questionnaire (notes app, no user-generated public content)
- [ ] **Target audience** — set age group (likely 13+ or all ages; no child-directed content)
- [ ] **News app / COVID / government** — No

## Technical

- [ ] **App category** — Productivity
- [ ] **Contact email** — developer support address
- [ ] **Package name** — `com.aus.notelikeus` (cannot change after first upload)

## Post-launch

- [ ] Tag release in git: `v1.0`
- [ ] Update `CHANGELOG.md` for next version
- [ ] Increment `versionCode` / `versionName` in `app/build.gradle.kts`
