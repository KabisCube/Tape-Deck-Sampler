# Shared parts of the Linux tool scripts in tools/ (sourced by them, not run on its own):
#  - opens the calling script in a terminal when it was double-clicked, and keeps that window open at the end;
#  - finds JDK 17 and the Android SDK;
#  - points local.properties at the Linux SDK for one run when it holds a path from another machine (such as Windows).
# Callers run `tds_restore_properties` and `tds_pause` when they exit, e.g.  trap 'tds_restore_properties; tds_pause' EXIT

tds_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Double-clicked in a file manager there's no terminal to type into, so the script reopens itself in Konsole.
# Only then: started from a file manager or app launcher (the parent process), not from IDE tasks or other scripts.
# Call it first thing with "$@". TDS_WINDOW marks that window, so tds_pause keeps it open until Enter is pressed.
tds_open_terminal() {
	local parent
	parent=$(ps -o comm= -p "$PPID" 2> /dev/null || true)
	[[ "$parent" =~ ^(dolphin|nautilus|nemo|thunar|pcmanfm|pcmanfm-qt|caja|plasmashell|krunner|kioworker|kioslave5?|xdg-open|gio-launch-desktop)$ ]] || return 0
	if [[ ! -t 0 && -z "${TDS_WINDOW:-}" ]] && command -v konsole > /dev/null; then
		exec konsole -e env TDS_WINDOW=1 bash "$(cd "$(dirname "$0")" && pwd)/$(basename "$0")" "$@"
	fi
}

tds_pause() {
	if [[ -n "${TDS_WINDOW:-}" ]]; then read -rp "Press Enter to close..." _ || true; fi
}

# JDK 17 (Gradle 8.9 can't run on much newer JDKs). .tools/ may hold the Windows JDK and SDK (from build.ps1);
# they're only used if they're Linux builds that actually run.
tds_setup_java() {
	local candidate
	for candidate in "$tds_root"/.tools/java/*/; do
		if [[ -x "${candidate}bin/java" ]] && "${candidate}bin/java" -version > /dev/null 2>&1; then
			export JAVA_HOME="${candidate%/}"; return 0
		fi
	done
	if [[ -n "${JAVA_HOME:-}" && "$("${JAVA_HOME}/bin/java" -version 2>&1)" =~ \"17 ]]; then return 0; fi
	for candidate in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17*; do
		if [[ -x "$candidate/bin/javac" ]]; then export JAVA_HOME="$candidate"; return 0; fi
	done
	echo "Install JDK 17 (e.g. 'sudo pacman -S jdk17-openjdk') or set JAVA_HOME." >&2
	return 1
}

tds_setup_sdk() {
	if [[ -x "$tds_root/.tools/sdk/platform-tools/adb" ]]; then export ANDROID_HOME="$tds_root/.tools/sdk"
	elif [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Android/Sdk" ]]; then export ANDROID_HOME="$HOME/Android/Sdk"
	fi
	if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then echo "Android SDK not found. Set ANDROID_HOME." >&2; return 1; fi
}

# local.properties may hold the Windows SDK path, which breaks Gradle here. If its sdk.dir doesn't exist on this
# machine, point it at the Linux SDK; tds_restore_properties puts the original back.
tds_properties_backup=""
tds_use_local_sdk() {
	local properties="$tds_root/local.properties" sdk_dir
	sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$properties" 2> /dev/null | tr -d '\r' || true)
	if [[ -n "$sdk_dir" && ! -d "$sdk_dir" && -z "$tds_properties_backup" ]]; then
		mkdir -p "$tds_root/.tools"
		tds_properties_backup="$(mktemp "$tds_root/.tools/local.properties.XXXXXX")"
		cp -p "$properties" "$tds_properties_backup"
		printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$properties"
	fi
}

tds_restore_properties() {
	if [[ -n "$tds_properties_backup" ]]; then mv -f "$tds_properties_backup" "$tds_root/local.properties"; tds_properties_backup=""; fi
}

# --- Messages ---
red() { printf '\033[31m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
stop() { red "$*" >&2; exit 1; }

# --- The phone ---
tds_package="com.tapedecksampler.app"

tds_setup_adb() {
	tds_adb="$ANDROID_HOME/platform-tools/adb"
	[[ -x "$tds_adb" ]] || stop "adb not found in $ANDROID_HOME/platform-tools."
	tds_apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2> /dev/null | sort -V | tail -n 1)
	[[ -n "$tds_apksigner" ]] || stop "apksigner not found in $ANDROID_HOME/build-tools."
}

# adb shell reads stdin: without "< /dev/null" it swallows what you type (or pipe in) while a script runs.
adb() { "$tds_adb" "$@" < /dev/null; }

# Chooses the phone to work on (asks when several are connected) and sets ANDROID_SERIAL and tds_model.
tds_pick_phone() {
	local devices pick i
	mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
	if [[ -n "${ANDROID_SERIAL:-}" ]]; then
		printf '%s\n' "${devices[@]}" | grep -qx "$ANDROID_SERIAL" || stop "Device $ANDROID_SERIAL isn't connected."
	elif (( ${#devices[@]} == 0 )) && adb devices | grep -q 'unauthorized$'; then
		stop "Your phone is connected but hasn't allowed this computer yet: tap Allow on the USB debugging prompt on the phone, then run this again."
	elif (( ${#devices[@]} == 0 )); then
		stop "No phone found. Connect your phone with USB debugging on (Settings > Developer options)."
	elif (( ${#devices[@]} == 1 )); then
		ANDROID_SERIAL="${devices[0]}"
	else
		echo "Several devices are connected:"
		for i in "${!devices[@]}"; do
			echo "  $((i + 1))) ${devices[$i]}  $(adb -s "${devices[$i]}" shell getprop ro.product.model | tr -d '\r')"
		done
		read -rp "Which one? " pick || pick=""
		[[ "$pick" =~ ^[0-9]+$ ]] && (( pick >= 1 && pick <= ${#devices[@]} )) || stop "No device chosen."
		ANDROID_SERIAL="${devices[$((pick - 1))]}"
	fi
	export ANDROID_SERIAL
	tds_model=$(adb shell getprop ro.product.model | tr -d '\r')
}

# Checks whether installing the APK $1 over the app on the phone could cost any projects. Stops (in red) when the
# signing key differs or the phone has a newer version: both would need an uninstall, which deletes all projects, and
# these scripts never do that. Otherwise sets tds_install_note (why it's safe) and tds_built_code.
tds_check_install() {
	local apk="$1" installed device_code copy installed_key built_key
	tds_built_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' "$tds_root/app/build.gradle" | head -n 1)
	installed=$(adb shell pm path "$tds_package" 2> /dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1 || true)  # pm fails when not installed
	if [[ -z "$installed" ]]; then
		tds_install_note="Tape Deck Sampler isn't installed on $tds_model yet, so it will be installed fresh. There are no projects to lose."
		return 0
	fi
	copy="$(mktemp)"
	adb pull "$installed" "$copy" > /dev/null 2>&1
	installed_key=$("$tds_apksigner" verify --print-certs "$copy" 2> /dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
	built_key=$("$tds_apksigner" verify --print-certs "$apk" 2> /dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
	rm -f "$copy"
	device_code=$(adb shell dumpsys package "$tds_package" | tr -d '\r' | sed -nE 's/.*versionCode=([0-9]+).*/\1/p' | head -n 1)
	if [[ -z "$installed_key" || "$installed_key" != "$built_key" ]]; then
		red "STOPPED: the app on $tds_model is signed with a different key than this build."
		red "Installing over it is only possible by uninstalling it first, and that DELETES ALL ITS PROJECTS."
		red "This script never does that. Nothing on the phone was changed."
		echo "To go ahead: build with the same signing key as the installed app (signing/keystore.properties),"
		echo "or export your projects in the app (Projects > Export), uninstall it yourself, and run this again."
		exit 1
	fi
	if [[ -n "$device_code" && "$device_code" -gt "$tds_built_code" ]]; then
		red "STOPPED: $tds_model has a newer version of the app (versionCode $device_code) than this build ($tds_built_code)."
		red "Android only allows going back a version by uninstalling, which DELETES ALL ITS PROJECTS. Nothing was changed."
		echo "Update this folder to the latest code (git pull) and run this again."
		exit 1
	fi
	tds_install_note="The app on $tds_model has the same signing key, so it's updated in place like a normal update: your projects stay."
}
