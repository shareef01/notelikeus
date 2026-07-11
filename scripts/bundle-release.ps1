# Builds a signed or unsigned Play Store AAB (Android App Bundle).
# Requires signing.properties at repo root for a signed bundle — see signing.properties.example

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$SigningFile = Join-Path $Root "signing.properties"
if (-not (Test-Path $SigningFile)) {
    Write-Warning "signing.properties not found — output will be unsigned (fine for local testing)."
    Write-Warning "Copy signing.properties.example to signing.properties before Play Store upload."
}

Write-Host "Running unit tests..."
& .\gradlew.bat :app:testDebugUnitTest --quiet
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Building release bundle..."
& .\gradlew.bat :app:bundleRelease --quiet
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$Aab = Join-Path $Root "app\build\outputs\bundle\release\app-release.aab"
if (Test-Path $Aab) {
    Write-Host ""
    Write-Host "Release bundle ready:"
    Write-Host "  $Aab"
    Write-Host ""
    Write-Host "Next: upload to Play Console (Production or Internal testing)."
    Write-Host "Privacy policy URL: https://notelike.web.app/privacy.html"
} else {
    Write-Error "AAB not found at expected path."
}
