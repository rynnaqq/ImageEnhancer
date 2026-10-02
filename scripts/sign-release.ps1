param(
    [string]$Apk = 'app\build\outputs\apk\release\app-release-unsigned.apk',
    [string]$OutputApk = 'dist\LocalPhotoEnhancer-v0.1.0.apk',
    [switch]$InitializeKey
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
function Resolve-ProjectPath([string]$Path) {
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $projectRoot $Path))
}
$inputPath = Resolve-ProjectPath $Apk
$outputPath = Resolve-ProjectPath $OutputApk
if (-not (Test-Path -LiteralPath $inputPath)) { throw "Unsigned APK not found: $inputPath" }
if ($inputPath -eq $outputPath) { throw 'Use a separate signed output APK.' }
if (-not $env:JAVA_HOME) {
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools\jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
}
if (-not $env:JAVA_HOME) { throw 'Install JDK 17 and set JAVA_HOME.' }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android SDK build-tools are required.' }
if ([version]$buildTools.Name -lt [version]'36.0.0') { throw 'Install Android SDK build-tools 36+ for release signing.' }
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$signingDir = Join-Path $projectRoot '.signing'
$keystorePath = Join-Path $signingDir 'imageenhancer-release.p12'
$passwordPath = Join-Path $signingDir 'password.clixml'
$alias = 'imageenhancer-release'
$hasKey = Test-Path -LiteralPath $keystorePath
$hasPassword = Test-Path -LiteralPath $passwordPath
if ($hasKey -ne $hasPassword) { throw 'Signing files are incomplete. Restore the existing key/password; do not replace the key.' }
if (-not $hasKey -and -not $InitializeKey) { throw 'No release key. Use -InitializeKey once to create one, then retain it for all updates.' }
if (-not $hasKey) {
    New-Item -Path $signingDir -ItemType Directory -Force | Out-Null
    # Restrict private files to the current Windows account before writing secrets.
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $signingDir
    $acl.SetAccessRuleProtection($true, $false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $signingDir -AclObject $acl
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $securePassword = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    [Array]::Clear($bytes, 0, $bytes.Length)
    # Export-Clixml encrypts SecureString with Windows DPAPI for this user/machine.
    $securePassword | Export-Clixml -LiteralPath $passwordPath
} else {
    $securePassword = Import-Clixml -LiteralPath $passwordPath
    if ($securePassword -isnot [Security.SecureString]) { throw 'Invalid encrypted signing password.' }
}
$bstr = [IntPtr]::Zero
try {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $env:IMAGEENHANCER_SIGNING_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if (-not $hasKey) {
        & $keytool -genkeypair -keystore $keystorePath -storetype PKCS12 -alias $alias `
            -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 `
            -dname 'CN=ImageEnhancer' -storepass:env IMAGEENHANCER_SIGNING_PASSWORD `
            -keypass:env IMAGEENHANCER_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed. Preserve signing files for recovery.' }
    }
    $outputDir = Split-Path -Parent $outputPath
    New-Item -Path $outputDir -ItemType Directory -Force | Out-Null
    $alignedPath = Join-Path $outputDir '.release-aligned.apk'
    & $zipalign -P 16 -f 4 $inputPath $alignedPath
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    & $apksigner sign --ks $keystorePath --ks-key-alias $alias `
        --ks-pass env:IMAGEENHANCER_SIGNING_PASSWORD --key-pass env:IMAGEENHANCER_SIGNING_PASSWORD `
        --debuggable-apk-permitted false --v4-signing-enabled false --alignment-preserved true `
        --out $outputPath $alignedPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $apksigner verify --verbose --print-certs $outputPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & $zipalign -c -P 16 4 $outputPath
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
    & (Join-Path $PSScriptRoot 'verify-apk.ps1') -Apk $outputPath
    $hash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksumPath = Join-Path $outputDir 'SHA256SUMS.txt'
    $checksum = $hash + '  ' + [IO.Path]::GetFileName($outputPath) + "`n"
    [IO.File]::WriteAllText($checksumPath, $checksum, [Text.UTF8Encoding]::new($false))
    Write-Output "Signed APK: $outputPath"
    Write-Output "SHA-256: $hash"
    Write-Output 'Keep .signing private and retain the same key for every app update.'
} finally {
    if ($bstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    Remove-Item Env:IMAGEENHANCER_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    if ($securePassword) { $securePassword.Dispose() }
}
