# Runs the full Notelikeus verification pipeline locally -- no GitHub Actions minutes.
#
# Everything GitHub CI does, on this machine:
#   gradle check           JVM unit tests (composeApp + androidApp) + desktop tests + lint
#   npm test               web unit tests
#   npm run typecheck      web tsc
#   npm run test:rules     Firestore rules vs the emulator (30 tests)
#   npm run test:sync      web sync layer vs the emulator (6 tests)
#   transport emulator     Android Firestore transport vs the no-rules emulator (3 tests)
#   npm run test:e2e       Playwright browser e2e (4 tests)
#   optional -Emulator     instrumented tests on a connected device/emulator
#
# The emulator-backed steps are sequential because they share port 8080 -- same
# constraint as CI, where they are separate steps in one job.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1 -Emulator
#   powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1 -SkipEmulatorSuites

param(
    # Also run the instrumented suite (connectedDebugAndroidTest) on an attached
    # device or a booted emulator.
    [switch]$Emulator,
    # Skip the Firebase-emulator-backed suites (rules/sync/transport/e2e).
    [switch]$SkipEmulatorSuites,
    # Don't re-run web dependency installs (npm ci) -- faster when nothing changed.
    [switch]$NoInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$failed = @()

function Step($name, [scriptblock]$body) {
    Write-Host ""
    Write-Host "=== $name ===" -ForegroundColor Cyan
    try {
        & $body
        if ($LASTEXITCODE -ne 0) { throw "$name exited with $LASTEXITCODE" }
        Write-Host "--- $name OK ---" -ForegroundColor Green
    } catch {
        Write-Host "--- $name FAILED: $_ ---" -ForegroundColor Red
        $script:failed += $name
    }
}

# Gradle needs a JDK. Prefer JAVA_HOME; fall back to Android Studio's bundled JBR
# (this machine has no java on PATH otherwise -- see .claude/skills/run-app/SKILL.md).
if (-not $env:JAVA_HOME) {
    $jbr = "$env:ProgramFiles\Android\Android Studio\jbr"
    if (Test-Path "$jbr\bin\java.exe") {
        $env:JAVA_HOME = $jbr
        Write-Host "JAVA_HOME set to $jbr"
    } else {
        throw "JAVA_HOME is not set and no Android Studio JBR found"
    }
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$androidHome = "$env:LOCALAPPDATA\Android\Sdk"

Step "JVM tests + desktop tests + lint" {
    & .\gradlew.bat check --console=plain
}

Step "web unit tests" {
    Push-Location web
    try {
        if (-not $NoInstall) { & npm ci }
        & npm test
        & npm run typecheck
    } finally { Pop-Location }
}

if (-not $SkipEmulatorSuites) {
    Step "Firestore security rules" {
        & npm run test:rules
    }
    Step "web sync layer vs emulator" {
        Push-Location web
        try { & npm run test:sync } finally { Pop-Location }
    }
    Step "Android Firestore transport vs emulator" {
        # Windows note: the CI step (Linux) uses ./gradlew with single quotes; cmd
        # passes those quotes through, so the same pattern here needs them dropped.
        & npx firebase emulators:exec --only firestore --config firebase.transport-test.json `
            --project notelikeus-c4-test `
            "gradlew.bat :composeApp:testDebugUnitTest --tests *FirestoreNoteTransportEmulatorTest* --console=plain"
    }
    Step "browser e2e (Playwright)" {
        Push-Location web
        try { & npm run test:e2e } finally { Pop-Location }
    }
}

if ($Emulator) {
    $targets = & $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] }
    if (-not $targets) {
        Write-Host "No device/emulator attached -- skipping instrumented tests" -ForegroundColor Yellow
    } else {
        foreach ($serial in $targets) {
            Step "instrumented tests on $serial" {
                $env:ANDROID_SERIAL = $serial
                $env:ANDROID_HOME = $androidHome
                & .\gradlew.bat :composeApp:connectedDebugAndroidTest --console=plain
            }
        }
    }
}

Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host "ALL STEPS PASSED" -ForegroundColor Green
    exit 0
} else {
    $msg = "FAILED: " + ($failed -join ", ")
    Write-Host $msg -ForegroundColor Red
    exit 1
}
