param([switch]$Restart)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = Join-Path $PSScriptRoot '.tools\sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$image = Join-Path $sdk 'system-images\android-35\default\x86_64'
$apk = Join-Path $PSScriptRoot 'dist\TapeDeckSampler.apk'
$avdName = 'TapeDeckWindows'
$serial = 'emulator-5560'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = Join-Path $PSScriptRoot '.tools\android-user'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
$java = Get-ChildItem -LiteralPath "$PSScriptRoot\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($java) { $env:JAVA_HOME = $java.FullName }
foreach ($required in @($adb, $emulator, $image, $apk)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing $required. This launcher needs the local Android tools and built APK." }
}
if (-not $env:JAVA_HOME) { throw 'JDK 17 is required. Set JAVA_HOME or run from this prepared workspace.' }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
$avdPath = Join-Path $env:ANDROID_AVD_HOME "$avdName.avd"
$config = Join-Path $avdPath 'config.ini'
if (-not (Test-Path -LiteralPath $config)) {
    Write-Host 'Preparing your Windows preview...'
    $manager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'
    if (-not (Test-Path -LiteralPath $manager)) { $manager = Join-Path $sdk 'cmdline-tools\bin\avdmanager.bat' }
    'no' | & $manager create avd --name $avdName --package 'system-images;android-35;default;x86_64' --device pixel_6 --path $avdPath
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the Windows preview emulator.' }
}
$settings = Get-Content -LiteralPath $config -Raw
$settings = [regex]::Replace($settings, '(?m)^image\.sysdir\.1\s*=.*$', "image.sysdir.1=$image\")
$settings = [regex]::Replace($settings, '(?m)^hw\.keyboard\s*=.*$', 'hw.keyboard=yes')
Set-Content -LiteralPath $config -Value $settings -Encoding ascii

function Invoke-Device {
    param([string[]]$Command)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $adb -s $serial @Command 2>&1
        return @{ Code = $LASTEXITCODE; Text = ($output | Out-String).Trim() }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

$state = Invoke-Device -Command @('get-state')
if ($state.Code -eq 0) {
    $name = Invoke-Device -Command @('emu', 'avd', 'name')
    if ($name.Text -notmatch "(?m)^$avdName\s*$") { throw "Emulator port 5560 is in use by another device: $($name.Text)" }
    if ($Restart) {
        Write-Host 'Saving Tape Deck Sampler and reconnecting Windows audio...'
        $homeScreen = Invoke-Device -Command @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        if ($homeScreen.Code -ne 0) { throw "Could not pause Tape Deck Sampler before restarting: $($homeScreen.Text)" }
        $shutdown = Invoke-Device -Command @('emu', 'kill')
        if ($shutdown.Code -ne 0) { throw "Could not close the preview: $($shutdown.Text)" }
        $shutdownDeadline = (Get-Date).AddSeconds(45)
        do {
            Start-Sleep -Milliseconds 250
            $state = Invoke-Device -Command @('get-state')
            if ((Get-Date) -gt $shutdownDeadline) { throw 'The emulator is still closing. Reopen the launcher after its window closes.' }
        } while ($state.Code -eq 0 -or (Test-Path -LiteralPath (Join-Path $avdPath 'hardware-qemu.ini.lock')))
    }
}
if ($state.Code -ne 0) {
    Write-Host 'Opening Tape Deck Sampler. Startup can take a minute...'
    $arguments = @('-avd', $avdName, '-port', '5560', '-no-boot-anim', '-no-snapshot-load', '-gpu', 'swiftshader_indirect', '-memory', '2048', '-audio', 'dsound', '-allow-host-audio')
    $process = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Normal -PassThru -RedirectStandardOutput "$PSScriptRoot\.tools\windows-emulator.log" -RedirectStandardError "$PSScriptRoot\.tools\windows-emulator-error.log"
}
$deadline = (Get-Date).AddMinutes(3)
do {
    $boot = Invoke-Device -Command @('shell', 'getprop', 'sys.boot_completed')
    if ($boot.Code -eq 0 -and $boot.Text -eq '1') { break }
    if ($process -and $process.HasExited) { throw 'The emulator closed. See .tools\windows-emulator-error.log.' }
    if ((Get-Date) -gt $deadline) { throw 'Emulator startup timed out. See .tools\windows-emulator.log.' }
    Start-Sleep -Milliseconds 750
} while ($true)
$microphone = Invoke-Device -Command @('emu', 'avd', 'hostmicon')
if ($microphone.Code -ne 0 -or $microphone.Text -match 'KO:') { Write-Warning 'Host microphone is unavailable. Check the emulator microphone controls and Windows microphone settings.' }
Write-Host 'Installing the latest Tape Deck Sampler build...'
$install = Invoke-Device -Command @('install', '-r', $apk)
if ($install.Code -ne 0 -and $install.Text -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Write-Warning 'The preview has a build signed with a different key. Replacing it; preview data is reset.'
    $null = Invoke-Device -Command @('uninstall', 'com.tapedecksampler.app')
    $install = Invoke-Device -Command @('install', '-r', $apk)
}
if ($install.Code -ne 0) { throw "Installation failed: $($install.Text)" }
$null = Invoke-Device -Command @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
$null = Invoke-Device -Command @('shell', 'wm', 'dismiss-keyguard')
$null = Invoke-Device -Command @('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
$launch = Invoke-Device -Command @('shell', 'am', 'start', '-W', '-n', 'com.tapedecksampler.app/.MainActivity', '-f', '0x20000000')
if ($launch.Code -ne 0 -or $launch.Text -match 'Error:') { throw "Launch failed: $($launch.Text)" }
Write-Host 'Tape Deck Sampler is ready. Click the pads; use the mouse wheel or arrow keys to scroll.'
Write-Host 'Enter = confirm, Esc = back, Space = play/stop. Close the emulator window when finished.'
