# Pixel audit verification — 2026-11-06

Device: Pixel 7, Android 17 / API 37, connected through wireless ADB.
Source: `a2c6133504ae18d58a6c36bb8f042aa549f9bfdf` (PR #436 contents,
including the previously merged audit fixes), plus the retained device regressions.

## Results

All **22 instrumented tests passed**:

- `AttachmentRecoveryDeviceTest`: 3 tests with the real Android Keystore.
  Ciphertext survives protector recreation; wrong AAD and tampering are rejected.
  An interrupted attachment swap publishes verified ciphertext. An interrupted
  staging swap with another owner's ciphertext restores and reseals the original
  source under the correct owner identity.
- `PlaintextDatabaseMigratorTest`: 5 real SQLCipher migration/recovery tests.
- `BackupDocumentIoTest`: 6 backup byte/Unicode/bounds/failure tests.
- `MainActivityShareInstrumentationTest`: 6 image-share lifecycle tests.
- `PredictiveBackTest`: 2 predictive-back delivery tests.

Guest UI smoke checks also passed:

1. Create a note with title `AuditPixelSmoke` and body `durable-pixel-content`.
2. Save through the editor's Back action; force-stop the isolated app and launch it
   again. Both strings appear in the persisted note.
3. Reopen that note and clear both title and body. The editor reports
   "Saved on this device".
4. Leave, force-stop and relaunch again. The list retains an Untitled note;
   reopening it exposes two empty EditText fields. The previous content does not
   return (AUD-E11).

Fresh app/test APK builds and `:composeApp:detekt` passed. Existing build
deprecation warnings remain.

## Isolation and reproducibility

A temporary Gradle init script changed only the local app applicationId to
`com.aus.notelikeus.audit`; its test package was `.audit.test`. The shared
instrumentation APK was `com.aus.notelikeus.shared.test`. Package metadata was
verified before installing, and all three packages were installed without
replacement flags. Only synthetic guest notes and test cache files were used.
All three temporary packages and the device UI dump were removed afterward.
The original `com.aus.notelikeus` installation remained present, with its original
last-update timestamp unchanged. No production account was signed in or modified.

Local Java initially failed during Gradle startup with a Unix-domain socket
loopback error. Setting `JAVA_TOOL_OPTIONS` for these commands to
`-Djdk.net.unixdomain.tmpdir=C:/Users/shareef01/AndroidStudioProjects/notelikeus/scratch`
allowed normal Gradle execution. This was a command-local workaround, not a
repository or machine configuration change.

Local raw results are under ignored `scratch/pixel-shared-tests.log`,
`scratch/pixel-app-tests.log`, `scratch/pixel-build-isolated.log` and
`scratch/pixel-detekt.log`. The reproducible new test source is
`composeApp/src/androidInstrumentedTest/kotlin/com/aus/notelikeus/data/attachments/AttachmentRecoveryDeviceTest.kt`.

## Remaining limits

This verifies a real Pixel's Keystore, SQLCipher, backup/share/back surfaces and
guest editor persistence. It does not verify multi-device signed-in sync,
account-switch races against live Auth, storage exhaustion or process death at
every write boundary, reboot/reminder delivery, or a full accessibility and
performance matrix. The isolated app used debug packaging, so this run does not
substitute for testing a signed minified release artifact.
