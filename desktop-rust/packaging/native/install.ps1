$ErrorActionPreference = 'Stop'

$appSource = Join-Path $PSScriptRoot 'notelikeus.exe'
$uninstallerSource = Join-Path $PSScriptRoot 'uninstall.ps1'
if (-not (Test-Path -LiteralPath $appSource -PathType Leaf)) {
    throw 'notelikeus.exe is missing. Extract the full package before installing.'
}
if (-not (Test-Path -LiteralPath $uninstallerSource -PathType Leaf)) {
    throw 'uninstall.ps1 is missing. Extract the full package before installing.'
}

$installRoot = Join-Path $env:LOCALAPPDATA 'Programs\NotelikeusRust'
$installedExe = Join-Path $installRoot 'notelikeus.exe'
$running = Get-Process -Name 'notelikeus' -ErrorAction SilentlyContinue | Where-Object {
    try { [string]::Equals($_.Path, $installedExe, [StringComparison]::OrdinalIgnoreCase) } catch { $false }
}
if ($running) {
    throw 'Notelikeus Rust is currently running from the install directory. Close it from the tray or window, then re-run install.'
}

$programs = [Environment]::GetFolderPath('Programs')
$shortcutRoot = Join-Path $programs 'NotelikeusRust'
$shortcutPath = Join-Path $shortcutRoot 'Notelikeus Rust.lnk'
$uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\NotelikeusRust'
$version = '0.1.0'
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'version.txt')) {
    $version = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version.txt') -Raw).Trim()
}

New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
Copy-Item -LiteralPath $appSource -Destination $installedExe -Force
Copy-Item -LiteralPath $uninstallerSource -Destination (Join-Path $installRoot 'uninstall.ps1') -Force
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'licenses') -PathType Container) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'licenses') -Destination $installRoot -Recurse -Force
}

New-Item -ItemType Directory -Path $shortcutRoot -Force | Out-Null
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $installedExe
$shortcut.WorkingDirectory = $installRoot
$shortcut.IconLocation = "$installedExe,0"
$shortcut.Save()

$powershell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
$uninstallCommand = '"{0}" -NoProfile -ExecutionPolicy Bypass -File "{1}"' -f $powershell, (Join-Path $installRoot 'uninstall.ps1')
$sizeKb = [int][Math]::Ceiling(((Get-Item -LiteralPath $installedExe).Length) / 1024.0)
New-Item -Path $uninstallKey -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'DisplayName' -Value 'Notelikeus Rust' -PropertyType String -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'DisplayVersion' -Value $version -PropertyType String -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'Publisher' -Value 'Notelikeus' -PropertyType String -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'InstallLocation' -Value $installRoot -PropertyType String -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'DisplayIcon' -Value "$installedExe,0" -PropertyType String -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'UninstallString' -Value $uninstallCommand -PropertyType String -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'NoModify' -Value 1 -PropertyType DWord -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'NoRepair' -Value 1 -PropertyType DWord -Force | Out-Null
New-ItemProperty -Path $uninstallKey -Name 'EstimatedSize' -Value $sizeKb -PropertyType DWord -Force | Out-Null

Write-Host "Installed Notelikeus Rust $version for the current Windows user."
Write-Host "Start menu shortcut: $shortcutPath"
Write-Host "Encrypted notes and settings remain in $env:APPDATA\NotelikeusRust, including after uninstall."
