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
    foreach ($model in $modelManifest.models) {
        $entry = $zip.GetEntry('assets/models/' + $model.file)
        if (-not $entry -or $entry.Length -ne $model.bytes) { throw ('Model missing/wrong size: ' + $model.id) }
        $stream = $entry.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $actualHash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose(); $sha.Dispose() }
        if ($actualHash -ne $model.sha256) { throw ('Bundled checksum mismatch: ' + $model.id) }
        if (-not $zip.GetEntry('assets/' + $model.licenseAsset)) { throw ('Model license missing: ' + $model.id) }
        Write-Output ('Verified bundled model: ' + $model.id + ' SHA-256 ' + $actualHash)
    }
} finally { $zip.Dispose() }
Write-Output 'APK audit passed: models and licenses bundled; no network permissions.'
Write-Output ($permissions -join [Environment]::NewLine)
