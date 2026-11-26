# Renders the README / F-Droid screenshots (1.png front, 2.png back) on a connected Android phone.
# Builds the app and a screenshot helper, checks whether installing could cost any projects, explains what it will
# do and asks before changing anything on the device. Run it with tools\render-previews.bat.
param([int]$Height = 1500)

$ErrorActionPreference = "Continue"
$root = Resolve-Path "$PSScriptRoot/../.."   # tools\lib -> project folder
Set-Location $root
$package = "com.tapedecksampler.app"
$helper = "$package.test"
$outDir = Join-Path $root "fastlane\metadata\android\en-US\images\phoneScreenshots"
$appApk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$helperApk = Join-Path $root "app\build\outputs\apk\androidTest\release\app-release-androidTest.apk"

function Stop-Previews([string]$reason) { Write-Host $reason -ForegroundColor Red; exit 1 }

# --- Tools: JDK 17, Android SDK, adb, apksigner ---
$localJava = Get-ChildItem -LiteralPath "$root\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJava) { $env:JAVA_HOME = $localJava.FullName }
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$env:LOCALAPPDATA\Programs" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME) { Stop-Previews "Install JDK 17 and set JAVA_HOME." }
$sdk = if (Test-Path "$root\.tools\sdk\platform-tools\adb.exe") { "$root\.tools\sdk" } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$env:ANDROID_HOME = $sdk
$adb = "$sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { Stop-Previews "adb not found in $sdk\platform-tools." }
$apksigner = Get-ChildItem "$sdk\build-tools\*\apksigner.bat" -ErrorAction SilentlyContinue | Sort-Object { [version]$_.Directory.Name } | Select-Object -Last 1
if (-not $apksigner) { Stop-Previews "apksigner not found in $sdk\build-tools." }

$temp = Join-Path $env:TEMP "tds-previews"
New-Item -ItemType Directory -Force $temp | Out-Null
$helperInstalled = $false
try {
    # --- Which device ---
    $devices = @((& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "^\S+\s+device$" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($env:ANDROID_SERIAL) {
        if ($devices -notcontains $env:ANDROID_SERIAL) { Stop-Previews "Device $env:ANDROID_SERIAL isn't connected." }
    } elseif ($devices.Count -eq 0 -and ((& $adb devices) -match "unauthorized$")) {
        Stop-Previews "Your phone is connected but hasn't allowed this computer yet: tap Allow on the USB debugging prompt on the phone, then run this again."
    } elseif ($devices.Count -eq 0) {
        Stop-Previews "No phone found. Connect your phone with USB debugging on (Settings > Developer options)."
    } elseif ($devices.Count -eq 1) {
        $env:ANDROID_SERIAL = $devices[0]
    } else {
        Write-Host "Several devices are connected:"
        for ($i = 0; $i -lt $devices.Count; $i++) { Write-Host "  $($i + 1)) $($devices[$i])  $((& $adb -s $devices[$i] shell getprop ro.product.model).Trim())" }
        $pick = Read-Host "Which one?"
        if (-not ($pick -match '^\d+$') -or [int]$pick -lt 1 -or [int]$pick -gt $devices.Count) { Stop-Previews "No device chosen." }
        $env:ANDROID_SERIAL = $devices[[int]$pick - 1]
    }
    $model = (& $adb shell getprop ro.product.model).Trim()

    # --- Build (nothing on the device changes yet) ---
    Write-Host "Building the app and the screenshot helper for $model. Nothing on the device is changed yet..."
    Remove-Item -Force $appApk, $helperApk -ErrorAction SilentlyContinue   # so a failed build can't leave old APKs looking like new ones
    & .\gradlew.bat --console plain -q -I tools\lib\preview-runner.init.gradle :app:assembleRelease :app:assembleReleaseAndroidTest
    if ($LASTEXITCODE -or -not (Test-Path $appApk) -or -not (Test-Path $helperApk)) { Stop-Previews "The build failed. Nothing on the device was changed." }

    # --- Could installing cost any projects? ---
    function Get-Certificate([string]$apk) { ((& $apksigner.FullName verify --print-certs $apk 2>$null) | Select-String "Signer #1 certificate SHA-256 digest: (\S+)").Matches.Groups[1].Value }
    $builtCode = [int]([regex]::Match((Get-Content "app\build.gradle" -Raw), '(?m)^\s*versionCode\s+(\d+)').Groups[1].Value)
    $installed = ((& $adb shell pm path $package 2>$null) | Select-Object -First 1) -replace '^package:', ''
    if (-not $installed) {
        $risk = "Tape Deck Sampler isn't installed on $model yet, so it will be installed fresh. There are no projects to lose."
    } else {
        & $adb pull $installed.Trim() "$temp\installed.apk" | Out-Null
        $deviceCode = [int](((& $adb shell dumpsys package $package) | Select-String "versionCode=(\d+)" | Select-Object -First 1).Matches.Groups[1].Value)
        if ((Get-Certificate "$temp\installed.apk") -ne (Get-Certificate $appApk)) {
            Write-Host "STOPPED: the app on $model is signed with a different key than this build." -ForegroundColor Red
            Write-Host "Installing over it is only possible by uninstalling it first, and that DELETES ALL ITS PROJECTS." -ForegroundColor Red
            Write-Host "This script never does that. Nothing on the device was changed." -ForegroundColor Red
            Write-Host "To go ahead: build with the same signing key as the installed app (signing\keystore.properties),"
            Write-Host "or export your projects in the app (Projects > Export), uninstall it yourself, and run this again."
            exit 1
        }
        if ($deviceCode -gt $builtCode) {
            Write-Host "STOPPED: $model has a newer version of the app (versionCode $deviceCode) than this build ($builtCode)." -ForegroundColor Red
            Write-Host "Android only allows going back a version by uninstalling, which DELETES ALL ITS PROJECTS. Nothing was changed." -ForegroundColor Red
            Write-Host "Update this folder to the latest code (git pull) and run this again."
            exit 1
        }
        $risk = "The app on $model has the same signing key, so it's updated in place like a normal update: your projects stay."
    }

    # --- Explain and ask ---
    Write-Host ""
    Write-Host "This will make new screenshots on $model ($env:ANDROID_SERIAL):"
    Write-Host "  1. Install this build of Tape Deck Sampler (versionCode $builtCode) and a small screenshot helper."
    Write-Host "  2. The helper backs up the app's settings, sets demo values (usage stats on the back, light theme,"
    Write-Host "     secrets hidden), creates a demo project called `"Late Night Loop`", and draws the front and back."
    Write-Host "  3. It then deletes the demo project and puts all your settings back, also if something fails."
    Write-Host "  4. The pictures are copied to this computer, resized to ${Height}px tall, and saved over"
    Write-Host "     fastlane\metadata\android\en-US\images\phoneScreenshots\1.png and 2.png."
    Write-Host "  5. The helper is removed from the device. The app stays updated to this build."
    Write-Host ""
    Write-Host $risk -ForegroundColor Green
    Write-Host "Good to know:" -ForegroundColor Yellow
    Write-Host "  - The app restarts during the run; anything playing stops. Your open project is saved first." -ForegroundColor Yellow
    Write-Host "  - If you have your own project called `"Late Night Loop`", the helper stops without touching it." -ForegroundColor Yellow
    Write-Host "  - If the run is cut off (cable pulled), the demo settings stay until you run this again, which" -ForegroundColor Yellow
    Write-Host "    puts your settings back first. A leftover demo project is removed then too." -ForegroundColor Yellow
    Write-Host "  - As with any update, exporting important projects first (Projects > Export) never hurts." -ForegroundColor Yellow
    Write-Host ""
    $answer = Read-Host "Continue? [y/N]"
    if ($answer -notmatch '^[Yy]$') { Write-Host "Cancelled. Nothing on the device was changed."; exit 0 }

    # --- Install, render, collect ---
    Write-Host "Installing..."
    & $adb install -r $appApk | Out-Null
    if ($LASTEXITCODE) { Stop-Previews "Installing the app failed. Your projects are untouched." }
    $helperOutput = (& $adb install -r $helperApk 2>&1) -join "`n"
    if ($LASTEXITCODE) {
        # An old helper signed with another key can be removed safely: it's only the helper, it holds no projects.
        if ($helperOutput -notmatch "INSTALL_FAILED_UPDATE_INCOMPATIBLE") { Stop-Previews "Installing the helper failed: $helperOutput" }
        & $adb uninstall $helper | Out-Null
        & $adb install -r $helperApk | Out-Null
        if ($LASTEXITCODE) { Stop-Previews "Installing the helper failed." }
    }
    $helperInstalled = $true

    Write-Host "Rendering..."
    $run = & $adb shell am instrument -w "$helper/$package.PreviewShots"
    $run | Where-Object { $_ -notmatch '^INSTRUMENTATION_CODE' } | Write-Host
    if (-not ($run -match "Previews rendered")) { Stop-Previews "The screenshots weren't made. Your settings are put back by the helper; if it was cut off, run this again." }

    Add-Type -AssemblyName System.Drawing
    $files = "/sdcard/Android/data/$package/files"
    New-Item -ItemType Directory -Force $outDir | Out-Null
    foreach ($pair in @(@("preview-front.png", "1.png"), @("preview-back.png", "2.png"))) {
        $raw = Join-Path $temp $pair[0]
        & $adb pull "$files/$($pair[0])" $raw | Out-Null
        if ($LASTEXITCODE) { Stop-Previews "Could not copy $($pair[0]) from the device." }
        $source = [Drawing.Image]::FromFile($raw)
        $width = [int][Math]::Round($source.Width * $Height / $source.Height)
        $bitmap = New-Object Drawing.Bitmap $width, $Height
        $g = [Drawing.Graphics]::FromImage($bitmap)
        $g.InterpolationMode = "HighQualityBicubic"; $g.PixelOffsetMode = "HighQuality"
        $g.DrawImage($source, 0, 0, $width, $Height)
        $out = Join-Path $outDir $pair[1]
        $bitmap.Save($out, [Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bitmap.Dispose(); $source.Dispose()
        & $adb shell rm -f "$files/$($pair[0])" | Out-Null
        Write-Host "  $out ${width}x$Height"
    }
    Write-Host "Done. The screenshots are saved; the helper is being removed from $model." -ForegroundColor Green
} finally {
    if ($helperInstalled) { & $adb uninstall $helper | Out-Null }
    Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
}
