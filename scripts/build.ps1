param([string[]]$Tasks = @(':core:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools\jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
}
if (-not $env:JAVA_HOME) { throw 'Install JDK 17 and set JAVA_HOME.' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
Push-Location $projectRoot
try {
    & .\gradlew.bat @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
