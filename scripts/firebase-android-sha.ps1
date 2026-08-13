# Print Android signing fingerprints and compare with Firebase registration.
# Usage: .\scripts\firebase-android-sha.ps1

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AndroidAppId = "1:404285880902:android:22326adaf6fca4e1996847"

Write-Host "Local signing fingerprints (debug/release):" -ForegroundColor Cyan
Push-Location $ProjectRoot
try {
    .\gradlew.bat :androidApp:signingReport
} finally {
    Pop-Location
}

Write-Host "`nFirebase registered SHA-1 hashes:" -ForegroundColor Cyan
firebase apps:android:sha:list $AndroidAppId --project notelikeus

Write-Host "`nIf Google Sign-In fails with code 10, add the debug SHA-1 above to Firebase:" -ForegroundColor Yellow
Write-Host "  firebase apps:android:sha:create $AndroidAppId <sha1-without-colons> --project notelikeus"
Write-Host "Then refresh androidApp\google-services.json:"
Write-Host "  firebase apps:sdkconfig ANDROID $AndroidAppId --project notelikeus --out androidApp\google-services.json.new"
Write-Host "  Move-Item -Force androidApp\google-services.json.new androidApp\google-services.json"
