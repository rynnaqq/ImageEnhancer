param([string]$Apk = 'app\build\outputs\apk\debug\app-debug.apk')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$apkPath = if ([IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $projectRoot $Apk }
if (-not (Test-Path -LiteralPath $apkPath)) { throw "APK not found: $apkPath" }
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$aapt = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Recurse -Filter aapt.exe | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $aapt) { throw 'Android build-tools aapt is required.' }
$permissions = & $aapt.FullName dump permissions $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK manifest.' }
if ($permissions -match 'android.permission.INTERNET|android.permission.ACCESS_NETWORK_STATE') { throw 'Network permission in APK.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $manifestEntry = $zip.GetEntry('assets/models/manifest.json')
    if (-not $manifestEntry) { throw 'Bundled model manifest is missing.' }
    $reader = [IO.StreamReader]::new($manifestEntry.Open())
    try { $modelManifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    foreach ($id in @('espcn-x3', 'yunet', 'face-swinir', 'color-ddcolor', 'repair-lama')) {
        if (-not ($modelManifest.models | Where-Object { $_.id -eq $id })) { throw ('Restoration model is not bundled: ' + $id) }
    }
    foreach ($model in $modelManifest.models) {
        $sha = [Security.Cryptography.SHA256]::Create()
        $totalBytes = 0L
        $parts = if ($model.parts) { @($model.parts) } else { @([PSCustomObject]@{ file=$model.file; bytes=$model.bytes; sha256=$model.sha256 }) }
        try {
            foreach ($part in $parts) {
                $entry = $zip.GetEntry('assets/models/' + $part.file)
                if (-not $entry -or $entry.Length -ne $part.bytes) { throw ('Model part missing/wrong size: ' + $model.id) }
                $stream = $entry.Open()
                $partSha = [Security.Cryptography.SHA256]::Create()
                try {
                    $buffer = [byte[]]::new(65536)
                    while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                        $totalBytes += $read
                        [void]$sha.TransformBlock($buffer, 0, $read, $buffer, 0)
                        [void]$partSha.TransformBlock($buffer, 0, $read, $buffer, 0)
                    }
                    [void]$partSha.TransformFinalBlock([byte[]]::new(0), 0, 0)
                    $partHash = [BitConverter]::ToString($partSha.Hash).Replace('-', '').ToLowerInvariant()
                    if ($partHash -ne $part.sha256) { throw ('Bundled part checksum mismatch: ' + $model.id) }
                } finally { $stream.Dispose(); $partSha.Dispose() }
            }
            [void]$sha.TransformFinalBlock([byte[]]::new(0), 0, 0)
            $actualHash = [BitConverter]::ToString($sha.Hash).Replace('-', '').ToLowerInvariant()
        } finally { $sha.Dispose() }
        if ($totalBytes -ne $model.bytes) { throw ('Assembled model wrong size: ' + $model.id) }
        if ($actualHash -ne $model.sha256) { throw ('Bundled checksum mismatch: ' + $model.id) }
        if (-not $zip.GetEntry('assets/' + $model.licenseAsset)) { throw ('Model license missing: ' + $model.id) }
        Write-Output ('Verified bundled model: ' + $model.id + ' SHA-256 ' + $actualHash)
    }
} finally { $zip.Dispose() }
Write-Output 'APK audit passed: models and licenses bundled; no network permissions.'
Write-Output ($permissions -join [Environment]::NewLine)
