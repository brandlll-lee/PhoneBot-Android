param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home'
$env:npm_config_cache = Join-Path $projectRoot '.tools/npm-cache'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools/android-user/avd'
$projectSdk = Join-Path $projectRoot '.tools/android-sdk'
if (Test-Path $projectSdk) { $env:ANDROID_HOME = $projectSdk }
& (Join-Path $projectRoot 'gradlew.bat') @Tasks
exit $LASTEXITCODE
