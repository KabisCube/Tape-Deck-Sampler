#!/usr/bin/env bash
# Renders the README / F-Droid screenshots (1.png front, 2.png back) on a connected Android phone.
# Builds the app and a screenshot helper, checks whether installing could cost any projects, explains what it will
# do and asks before changing anything on the device. Usage: tools/render-previews.sh [height in px, default 1500]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$root/tools/lib/common.sh"     # JDK 17, Android SDK, a local.properties that works here, terminal window
tds_open_terminal "$@"
cd "$root"

height="${1:-1500}"
package="$tds_package"
helper="$package.test"
out_dir="$root/fastlane/metadata/android/en-US/images/phoneScreenshots"
app_apk="app/build/outputs/apk/release/app-release.apk"
helper_apk="app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"

work="$(mktemp -d)"
helper_installed=false
cleanup() {
	tds_restore_properties
	if $helper_installed; then adb uninstall "$helper" > /dev/null 2>&1 || true; fi
	rm -rf "$work"
	tds_pause
}
trap cleanup EXIT
trap 'exit 130' INT TERM

tds_setup_java
tds_setup_sdk
tds_setup_adb
image=$(command -v magick || command -v convert || true)
[[ -n "$image" ]] || stop "ImageMagick is needed to resize the screenshots (e.g. 'sudo pacman -S imagemagick')."

tds_pick_phone
model="$tds_model"

# --- Build (nothing on the device changes yet) ---
echo "Building the app and the screenshot helper for $model. Nothing on the device is changed yet..."
tds_use_local_sdk
rm -f "$app_apk" "$helper_apk"           # so a failed build can't leave old APKs looking like new ones
bash ./gradlew --console plain -q -I tools/lib/preview-runner.init.gradle :app:assembleRelease :app:assembleReleaseAndroidTest 2>&1 \
	| grep -v '^w: \|^Note: \|warning:\|SDK processing' || true
[[ -f "$app_apk" && -f "$helper_apk" ]] || stop "The build failed. Nothing on the device was changed."
tds_restore_properties

# --- Could installing cost any projects? (stops if so) ---
tds_check_install "$app_apk"
built_code="$tds_built_code"
risk="$tds_install_note"

# --- Explain and ask ---
echo
echo "This will make new screenshots on $model ($ANDROID_SERIAL):"
echo "  1. Install this build of Tape Deck Sampler (versionCode $built_code) and a small screenshot helper."
echo "  2. The helper backs up the app's settings, sets demo values (usage stats on the back, light theme,"
echo "     secrets hidden), creates a demo project called \"Late Night Loop\", and draws the front and back."
echo "  3. It then deletes the demo project and puts all your settings back, also if something fails."
echo "  4. The pictures are copied to this computer, resized to ${height}px tall, and saved over"
echo "     fastlane/metadata/android/en-US/images/phoneScreenshots/1.png and 2.png."
echo "  5. The helper is removed from the device. The app stays updated to this build."
echo
green "$risk"
yellow "Good to know:"
yellow "  - The app restarts during the run; anything playing stops. Your open project is saved first."
yellow "  - If you have your own project called \"Late Night Loop\", the helper stops without touching it."
yellow "  - If the run is cut off (cable pulled), the demo settings stay until you run this again, which"
yellow "    puts your settings back first. A leftover demo project is removed then too."
yellow "  - As with any update, exporting important projects first (Projects > Export) never hurts."
echo
read -rp "Continue? [y/N] " answer || answer=""
[[ "$answer" =~ ^[Yy]$ ]] || { echo "Cancelled. Nothing on the device was changed."; exit 0; }

# --- Install, render, collect ---
echo "Installing..."
adb install -r "$app_apk" > /dev/null || stop "Installing the app failed. Your projects are untouched."
if ! adb install -r "$helper_apk" > "$work/helper-install.txt" 2>&1; then
	# An old helper signed with another key can be removed safely: it's only the helper, it holds no projects.
	grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE "$work/helper-install.txt" || stop "Installing the helper failed: $(cat "$work/helper-install.txt")"
	adb uninstall "$helper" > /dev/null
	adb install -r "$helper_apk" > /dev/null || stop "Installing the helper failed."
fi
helper_installed=true

echo "Rendering..."
result=$(adb shell am instrument -w "$helper/$package.PreviewShots" | tr -d '\r')
echo "$result" | grep -v '^INSTRUMENTATION_CODE' || true
grep -q "Previews rendered" <<< "$result" || stop "The screenshots weren't made. Your settings are put back by the helper; if it was cut off, run this again."

files="/sdcard/Android/data/$package/files"
mkdir -p "$out_dir"
for pair in "preview-front.png:1.png" "preview-back.png:2.png"; do
	source_name="${pair%%:*}"; target="$out_dir/${pair##*:}"
	adb pull "$files/$source_name" "$work/$source_name" > /dev/null 2>&1 || stop "Could not copy $source_name from the device."
	"$image" "$work/$source_name" -filter Lanczos -resize "x$height" "$target"
	adb shell rm -f "$files/$source_name" > /dev/null || true
	echo "  $target ($("$image" identify -format '%wx%h' "$target" 2> /dev/null || identify -format '%wx%h' "$target"))"
done
green "Done. The screenshots are saved; the helper is being removed from $model."
