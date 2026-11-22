$ErrorActionPreference = 'Stop'

$expectedInstallRoot = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Programs\NotelikeusRust')).TrimEnd('\')
$scriptRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSCommandPath)).TrimEnd('\')
if (-not [string]::Equals($scriptRoot, $expectedInstallRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to uninstall from an unexpected folder.'
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
