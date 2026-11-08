param([switch]$TestOnly)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$localJava = Get-ChildItem -LiteralPath "$PSScriptRoot\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJava) { $env:JAVA_HOME = $localJava.FullName }
if (-not $env:JAVA_HOME) { throw 'Install JDK 17 and set JAVA_HOME.' }
$env:GRADLE_USER_HOME = "$PSScriptRoot\.tools\gradle-home"
$env:ANDROID_USER_HOME = "$PSScriptRoot\.tools\android-user"
if (Test-Path -LiteralPath "$PSScriptRoot\.tools\sdk") { $env:ANDROID_HOME = "$PSScriptRoot\.tools\sdk" }
$gradle = '.\gradlew.bat'
& $gradle --no-daemon --console plain -q :app:compileReleaseJavaWithJavac
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$stdlib = Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib" -Recurse -Filter 'kotlin-stdlib-2.*.jar' | Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
if (-not $stdlib) { throw 'Kotlin stdlib not found in Gradle cache.' }
if (Test-Path -LiteralPath .tools\test-classes) { Remove-Item -Recurse -Force .tools\test-classes }
New-Item -ItemType Directory -Force .tools\test-classes | Out-Null
$cp = ".tools\test-classes;app\build\tmp\kotlin-classes\release;app\build\intermediates\javac\release\compileReleaseJavaWithJavac\classes;$($stdlib.FullName)"
& "$env:JAVA_HOME\bin\javac.exe" -cp $cp -d .tools\test-classes (Get-ChildItem tests\*.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -Xmx96m -cp $cp com.tapedecksampler.app.SampleProcessorStressTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -Xmx96m -cp $cp com.tapedecksampler.app.PadPitchTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -XX:-DoEscapeAnalysis -cp $cp com.tapedecksampler.app.AudioRenderTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.EffectsTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.CoreTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.RegionsTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.SyncTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.ClapTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.CaptureTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.PagesTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.ExportTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.PitchCorrectorTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.tapedecksampler.app.TimelineWindowTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if (-not $TestOnly) {
    & $gradle --no-daemon --console plain :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    New-Item -ItemType Directory -Force dist | Out-Null
    Copy-Item -LiteralPath app\build\outputs\apk\release\app-release.apk -Destination dist\TapeDeckSampler.apk -Force
}
