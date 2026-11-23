$ErrorActionPreference = 'Stop'

$expectedInstallRoot = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Programs\NotelikeusRust')).TrimEnd('\')
$scriptRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSCommandPath)).TrimEnd('\')
if (-not [string]::Equals($scriptRoot, $expectedInstallRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to uninstall from an unexpected folder.'
}

$installedExe = Join-Path $expectedInstallRoot 'notelikeus.exe'
$running = Get-Process -Name 'notelikeus' -ErrorAction SilentlyContinue | Where-Object {
    try { [string]::Equals($_.Path, $installedExe, [StringComparison]::OrdinalIgnoreCase) } catch { $false }
}
if ($running) {
    throw 'Notelikeus Rust is currently running. Close it from the tray or window before uninstalling.'
}

$programs = [Environment]::GetFolderPath('Programs')
$programsRoot = [IO.Path]::GetFullPath($programs).TrimEnd('\')
$shortcutRoot = [IO.Path]::GetFullPath((Join-Path $programsRoot 'NotelikeusRust')).TrimEnd('\')
if ($shortcutRoot.StartsWith("$programsRoot\", [StringComparison]::OrdinalIgnoreCase) -and
    (Test-Path -LiteralPath $shortcutRoot -PathType Container)) {
    Remove-Item -LiteralPath $shortcutRoot -Recurse -Force
}

$uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\NotelikeusRust'
if (Test-Path -LiteralPath $uninstallKey) {
    Remove-Item -LiteralPath $uninstallKey -Recurse -Force
}

if (Test-Path -LiteralPath $scriptRoot -PathType Container) {
    $resolvedInstallRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $scriptRoot).Path).TrimEnd('\')
    if ([string]::Equals($resolvedInstallRoot, $expectedInstallRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedInstallRoot -Recurse -Force
    }
}

Write-Host 'Notelikeus Rust was removed. Its notes, saved sign-in, and settings were kept.'
