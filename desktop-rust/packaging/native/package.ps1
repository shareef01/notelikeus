$ErrorActionPreference = 'Stop'

$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$repoRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot '..'))
$manifestPath = Join-Path $workspaceRoot 'Cargo.toml'
$cargo = Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
$rustc = Join-Path $env:USERPROFILE '.cargo\bin\rustc.exe'

if (-not (Test-Path -LiteralPath $cargo -PathType Leaf)) { throw 'Cargo was not found in the current user toolchain.' }
if (-not (Test-Path -LiteralPath $rustc -PathType Leaf)) { throw 'rustc was not found in the current user toolchain.' }

$hostLine = & $rustc -vV | Select-String '^host:' | Select-Object -First 1
$target = if ($hostLine) { $hostLine.Line.Split(':', 2)[1].Trim() } else { '' }
if ($target -notmatch 'windows-(msvc|gnu)$') {
    throw "The native package must be built on Windows; the active Rust target is '$target'."
}

$metadataText = & $cargo metadata --manifest-path $manifestPath --filter-platform $target --format-version 1
if ($LASTEXITCODE -ne 0) { throw 'cargo metadata failed.' }
$metadata = $metadataText | ConvertFrom-Json
$package = $metadata.packages | Where-Object { $_.name -eq 'nlk-native' } | Select-Object -First 1
if ($null -eq $package) { throw 'nlk-native is missing from the workspace metadata.' }

$outputRoot = Join-Path $workspaceRoot 'target\native-packages'
$outputPath = Join-Path $outputRoot "NotelikeusRust-$($package.version)-$target.zip"
$buildPath = Join-Path $workspaceRoot 'target\release\notelikeus.exe'
$stagingPath = Join-Path ([IO.Path]::GetTempPath()) "notelikeus-native-package-$([guid]::NewGuid().ToString('N'))"

$buildConfigNames = @('NLK_SUPABASE_URL', 'NLK_SUPABASE_KEY', 'NLK_ATTACHMENTS_URL', 'NLK_CAPTCHA_PAGE_URL')
$savedBuildConfig = @{}
foreach ($name in $buildConfigNames) {
    $savedBuildConfig[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    Remove-Item "Env:$name" -ErrorAction SilentlyContinue
}

try {
    Push-Location $workspaceRoot
    try {
        & $cargo build --release --manifest-path $manifestPath -p nlk-native
        if ($LASTEXITCODE -ne 0) { throw 'The native release build failed.' }
    }
    finally {
        Pop-Location
        foreach ($name in $buildConfigNames) {
            if ($null -eq $savedBuildConfig[$name]) {
                Remove-Item "Env:$name" -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($name, $savedBuildConfig[$name], 'Process')
            }
        }
    }

    if (-not (Test-Path -LiteralPath $buildPath -PathType Leaf)) { throw 'The release executable was not produced.' }
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $stagingPath | Out-Null
    Copy-Item -LiteralPath $buildPath -Destination (Join-Path $stagingPath 'notelikeus.exe')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install.ps1') -Destination $stagingPath
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'uninstall.ps1') -Destination $stagingPath
    Set-Content -LiteralPath (Join-Path $stagingPath 'version.txt') -Value $package.version -NoNewline -Encoding utf8

    $fontLicense = Join-Path $repoRoot 'licenses\Inter-OFL.txt'
    if (-not (Test-Path -LiteralPath $fontLicense -PathType Leaf)) { throw 'The Inter font license is missing.' }
    $licensesRoot = Join-Path $stagingPath 'licenses'
    New-Item -ItemType Directory -Path $licensesRoot | Out-Null
    Copy-Item -LiteralPath $fontLicense -Destination (Join-Path $licensesRoot 'Inter-OFL.txt')

    # Include license texts for the compiled dependency graph, including Slint's royalty-free terms.
    $packagesById = @{}
    foreach ($item in $metadata.packages) { $packagesById[$item.id] = $item }
    $nodesById = @{}
    foreach ($node in $metadata.resolve.nodes) { $nodesById[$node.id] = $node }
    $pendingIds = [Collections.Generic.Stack[string]]::new()
    $pendingIds.Push($package.id)
    $visitedIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    while ($pendingIds.Count -gt 0) {
        $id = $pendingIds.Pop()
        if (-not $visitedIds.Add($id)) { continue }
        if ($nodesById.ContainsKey($id)) {
            foreach ($dependency in $nodesById[$id].deps) { $pendingIds.Push($dependency.pkg) }
        }
    }

    $noticeLines = [Collections.Generic.List[string]]::new()
    $dependencyIds = $visitedIds | Sort-Object { $packagesById[$_].name }, { $packagesById[$_].version }
    foreach ($id in $dependencyIds) {
        $dependencyPackage = $packagesById[$id]
        if ($null -eq $dependencyPackage -or $null -eq $dependencyPackage.source) { continue }
        $dependencyRoot = Split-Path -Parent $dependencyPackage.manifest_path
        $licenseFiles = @()
        $declaredLicenseFile = $dependencyPackage.license_file
        if ($declaredLicenseFile -and -not [IO.Path]::IsPathRooted($declaredLicenseFile)) {
            $declaredLicenseFile = Join-Path $dependencyRoot $declaredLicenseFile
        }
        if ($declaredLicenseFile -and (Test-Path -LiteralPath $declaredLicenseFile -PathType Leaf)) {
            $licenseFiles += Get-Item -LiteralPath $declaredLicenseFile
        } else {
            $licenseFiles += Get-ChildItem -LiteralPath $dependencyRoot -File | Where-Object {
                $_.Name -match '^(LICENSE|COPYING|NOTICE|COPYRIGHT|UNLICENSE)([-_.].*)?$'
            }
            $licenseDirectory = Join-Path $dependencyRoot 'LICENSES'
            if (Test-Path -LiteralPath $licenseDirectory -PathType Container) {
                $licenseFiles += Get-ChildItem -LiteralPath $licenseDirectory -Recurse -File
            }
        }
        $licenseLabel = if ($dependencyPackage.license) { $dependencyPackage.license } else { 'license expression not declared' }
        $noticeLines.Add("$($dependencyPackage.name) $($dependencyPackage.version) - $licenseLabel")
        if ($licenseFiles.Count -gt 0) {
            $dependencyLicenseRoot = Join-Path $licensesRoot ("third-party\{0}-{1}" -f $dependencyPackage.name, $dependencyPackage.version)
            New-Item -ItemType Directory -Path $dependencyLicenseRoot -Force | Out-Null
            $index = 0
            foreach ($licenseFile in ($licenseFiles | Sort-Object FullName -Unique)) {
                $index++
                $destinationName = '{0:D2}-{1}' -f $index, $licenseFile.Name
                Copy-Item -LiteralPath $licenseFile.FullName -Destination (Join-Path $dependencyLicenseRoot $destinationName)
            }
        } else {
            $noticeLines.Add('  No license text file was present in the Cargo package source.')
        }
    }
    Set-Content -LiteralPath (Join-Path $licensesRoot 'THIRD-PARTY-NOTICES.txt') -Value $noticeLines -Encoding utf8

    $projectLicense = Join-Path $repoRoot 'LICENSE'
    if (Test-Path -LiteralPath $projectLicense -PathType Leaf) {
        Copy-Item -LiteralPath $projectLicense -Destination (Join-Path $licensesRoot 'Notelikeus-License.txt')
    }

    $readme = @"
Notelikeus Rust $($package.version) for Windows ($target)

Install for the current Windows user:
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\install.ps1

The installer adds a Start menu shortcut and an entry in Windows Installed apps. It does not need
administrator rights. Uninstall from Installed apps or run uninstall.ps1 from the installed folder.

Your notes and saved sign-in are stored separately in %APPDATA%\NotelikeusRust. Uninstall keeps
that data. Inter's OFL license, dependency license texts where provided, and the full dependency
notice list are in the licenses folder. The About screen displays the Slint attribution.

Sync is configured at runtime with NLK_SUPABASE_URL and NLK_SUPABASE_KEY. This package is unsigned.
"@
    Set-Content -LiteralPath (Join-Path $stagingPath 'README.txt') -Value $readme -Encoding utf8

    $zipSafeTimestamp = [DateTime]::new(2020, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
    Get-ChildItem -LiteralPath $stagingPath -File -Recurse -Force | ForEach-Object {
        $_.LastWriteTimeUtc = $zipSafeTimestamp
    }
    Compress-Archive -Path (Join-Path $stagingPath '*') -DestinationPath $outputPath -CompressionLevel Optimal -Force
    $hash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash
    Write-Host "Package: $outputPath"
    Write-Host "SHA-256: $hash"
}
finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (Test-Path -LiteralPath $stagingPath -PathType Container) {
        $resolvedStage = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $stagingPath).Path)
        if ($resolvedStage.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedStage -Recurse -Force
        }
    }
}
