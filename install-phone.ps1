Set-Location $PSScriptRoot
$adb = "$PSScriptRoot\.tools\sdk\platform-tools\adb.exe"
$apk = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk"

$devices = & $adb devices
$phones = @($devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$' -and $Matches[1] -notlike 'emulator-*') { $Matches[1] } })
if ($devices -match '\sunauthorized$') { Write-Host 'Phone is unauthorized. Accept the USB debugging prompt on it, then retry.'; exit 1 }
if ($phones.Count -eq 0) { Write-Host 'No phone found. Connect it over USB with USB debugging on.'; exit 1 }
if ($phones.Count -gt 1) { Write-Host "More than one phone connected: $($phones -join ', '). Leave only one connected."; exit 1 }
$phone = $phones[0]

$localJava = Get-ChildItem -LiteralPath "$PSScriptRoot\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJava) { $env:JAVA_HOME = $localJava.FullName }
if (-not $env:JAVA_HOME) { Write-Host 'Install JDK 17 and set JAVA_HOME.'; exit 1 }
$env:GRADLE_USER_HOME = "$PSScriptRoot\.tools\gradle-home"
$env:ANDROID_USER_HOME = "$PSScriptRoot\.tools\android-user"
if (Test-Path -LiteralPath "$PSScriptRoot\.tools\sdk") { $env:ANDROID_HOME = "$PSScriptRoot\.tools\sdk" }
$gradle = '.\gradlew.bat'

& $gradle --no-daemon --console plain :app:assembleRelease
if ($LASTEXITCODE -ne 0) { Write-Host 'Build failed.'; exit $LASTEXITCODE }
Remove-Item Env:ANDROID_USER_HOME

Write-Host "Installing on $phone..."
& $adb -s $phone install -r $apk
if ($LASTEXITCODE -ne 0) { Write-Host 'Install failed. Your installed app and its projects are unchanged.'; exit $LASTEXITCODE }
Write-Host 'Compiling audio engine...'
& $adb -s $phone shell cmd package compile -m speed -f com.tapedecksampler.app
if ($LASTEXITCODE -ne 0) { Write-Host 'Compile failed. Audio may stutter until Android compiles the app.'; exit $LASTEXITCODE }
Write-Host 'Done.'
