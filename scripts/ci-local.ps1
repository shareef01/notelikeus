# Runs the full Notelikeus verification pipeline locally -- no GitHub Actions minutes.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1 -Emulator
#   powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1 -SkipSupabaseSuites

param(
    [switch]$Emulator,
    [switch]$SkipSupabaseSuites,
    [switch]$SkipEmulatorSuites,
    [switch]$NoInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$failed = @()
$skipRemote = $SkipSupabaseSuites -or $SkipEmulatorSuites

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

if (-not $skipRemote) {
    Step "Supabase pgTAP" {
        & npm run supabase:test
    }
    Step "browser e2e (Playwright)" {
        Push-Location web
        try { & npm run test:e2e } finally { Pop-Location }
    }
    Step "attachments worker" {
        & npm run test:attachments-worker
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
