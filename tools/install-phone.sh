#!/usr/bin/env bash
# Builds the release app and installs it on a connected phone as a normal update, then pre-compiles it so the audio
# engine doesn't stutter at first. Checks first that installing can't cost any projects (same signing key, not an
# older version) and stops if it could; it never uninstalls the app. Linux version of install-phone.bat.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$root/tools/lib/common.sh"     # JDK 17, Android SDK, the phone, a local.properties that works here, terminal window
tds_open_terminal "$@"
cd "$root"
trap 'tds_restore_properties; tds_pause' EXIT
trap 'exit 130' INT TERM

apk="app/build/outputs/apk/release/app-release.apk"
tds_setup_java
tds_setup_sdk
tds_setup_adb
tds_pick_phone

echo "Building the release app for $tds_model. Nothing on the phone is changed yet..."
tds_use_local_sdk
rm -f "$apk"                             # so a failed build can't leave an old APK looking like a new one
bash ./gradlew --console plain -q :app:assembleRelease 2>&1 | grep -v '^w: \|^Note: \|warning:\|SDK processing' || true
[[ -f "$apk" ]] || stop "The build failed. Nothing on the phone was changed."
tds_restore_properties

tds_check_install "$apk"                 # stops if installing could cost any projects
green "$tds_install_note"

echo "Installing on $tds_model..."
adb install -r "$apk" > /dev/null || stop "Installing failed. The app on the phone and its projects are unchanged."
echo "Compiling the audio engine..."
adb shell cmd package compile -m speed -f "$tds_package" > /dev/null \
	|| yellow "Compiling failed: audio may stutter at first, until Android compiles the app by itself."
green "Done. Tape Deck Sampler (versionCode $tds_built_code) is installed on $tds_model."
