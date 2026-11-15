# Builds the watched Android app with the portable toolchain on E:\.
# Usage:  .\build.ps1            -> release + debug APKs
#         .\build.ps1 -Task assembleDebug
param([string]$Task = "assembleRelease assembleDebug")

$tools = "E:\YOUTUBE AUTOMATION\_tools"
$env:JAVA_HOME        = "$tools\jdk-17"
$env:ANDROID_HOME     = "$tools\android-sdk"
$env:GRADLE_USER_HOME = "$tools\gradle-home"
$env:TEMP = "$tools\temp"; $env:TMP = $env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null

Push-Location $PSScriptRoot
try {
    # Prefer the extracted Gradle on E: (no re-download); fall back to the wrapper.
    $gradle = "$tools\gradle-8.11.1\bin\gradle.bat"
    if (-not (Test-Path $gradle)) { $gradle = ".\gradlew.bat" }
    & $gradle --no-daemon ($Task -split " ")
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    Write-Host ""
    Write-Host "APKs:" -ForegroundColor Green
    Get-ChildItem -Recurse app\build\outputs\apk -Filter *.apk | ForEach-Object { "  $($_.FullName)  ($([math]::Round($_.Length/1MB,1)) MB)" }
} finally {
    Pop-Location
}
