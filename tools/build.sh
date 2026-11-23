#!/usr/bin/env bash
# Linux version of build.ps1: compiles the app, runs every test suite, and (unless --test-only)
# builds debug + release APKs, runs lint and copies the release APK to dist/TapeDeckSampler.apk.
set -euo pipefail

test_only=false
[[ "${1:-}" == "--test-only" ]] && test_only=true

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

# --- JDK 17 (Gradle 8.9 can't run on much newer JDKs) ---
# .tools/ may hold the Windows JDK and SDK (from build.ps1); only use them if they're Linux builds that actually run.
local_java=""
for candidate in "$root"/.tools/java/*/; do
	if [[ -x "${candidate}bin/java" ]] && "${candidate}bin/java" -version > /dev/null 2>&1; then local_java="${candidate%/}"; break; fi
done
if [[ -n "$local_java" ]]; then
	export JAVA_HOME="$local_java"
elif [[ -z "${JAVA_HOME:-}" || ! "$("${JAVA_HOME}/bin/java" -version 2>&1)" =~ \"17 ]]; then
	for candidate in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17*; do
		if [[ -x "$candidate/bin/javac" ]]; then export JAVA_HOME="$candidate"; break; fi
	done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
	echo "Install JDK 17 (e.g. 'sudo pacman -S jdk17-openjdk') or set JAVA_HOME." >&2
	exit 1
fi

# --- Android SDK ---
if [[ -x "$root/.tools/sdk/platform-tools/adb" ]]; then export ANDROID_HOME="$root/.tools/sdk"
elif [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Android/Sdk" ]]; then export ANDROID_HOME="$HOME/Android/Sdk"
fi

# local.properties may hold the Windows SDK path, which breaks Gradle here. If its sdk.dir doesn't exist on this
# machine, point it at the Linux SDK for this build only, and always put the original back when the script ends.
properties="$root/local.properties"
backup=""
restore_properties() { if [[ -n "$backup" ]]; then mv -f "$backup" "$properties"; backup=""; fi; }
trap restore_properties EXIT
trap 'exit 130' INT TERM
sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$properties" 2>/dev/null | tr -d '\r' || true)
if [[ -n "$sdk_dir" && ! -d "$sdk_dir" ]]; then
	if [[ -z "${ANDROID_HOME:-}" ]]; then echo "Android SDK not found. Set ANDROID_HOME." >&2; exit 1; fi
	mkdir -p "$root/.tools"
	backup="$(mktemp "$root/.tools/local.properties.XXXXXX")"
	cp -p "$properties" "$backup"
	printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$properties"
fi

# Through bash: gradlew isn't marked executable in git (it was committed from Windows).
gradle=(bash ./gradlew --no-daemon --console plain)
"${gradle[@]}" -q :app:compileReleaseJavaWithJavac

gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
stdlib=$(find "$gradle_home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.*.jar' ! -name '*sources*' 2>/dev/null | head -n 1 || true)
if [[ -z "$stdlib" ]]; then echo "Kotlin stdlib not found in the Gradle cache." >&2; exit 1; fi

classes=".tools/test-classes"
rm -rf "$classes"; mkdir -p "$classes"
app_cp="app/build/tmp/kotlin-classes/release:app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes:$stdlib"
"$JAVA_HOME/bin/javac" -nowarn -cp "$app_cp" -d "$classes" tests/*.java
cp="$classes:$app_cp"

run() { echo "== ${*: -1}"; "$JAVA_HOME/bin/java" "$@"; }
run -Xmx96m -cp "$cp" com.tapedecksampler.app.SampleProcessorStressTest
run -Xmx96m -cp "$cp" com.tapedecksampler.app.PadPitchTest
run -XX:-DoEscapeAnalysis -cp "$cp" com.tapedecksampler.app.AudioRenderTest
run -cp "$cp" com.tapedecksampler.app.EffectsTest
run -cp "$cp" com.tapedecksampler.app.CoreTest
run -cp "$cp" com.tapedecksampler.app.RegionsTest
run -cp "$cp" com.tapedecksampler.app.SyncTest
run -cp "$cp" com.tapedecksampler.app.ClapTest
run -cp "$cp" com.tapedecksampler.app.CaptureTest
run -cp "$cp" com.tapedecksampler.app.PagesTest
run -cp "$cp" com.tapedecksampler.app.ExportTest
run -cp "$cp" com.tapedecksampler.app.PitchCorrectorTest
run -Xmx96m -cp "$cp" com.tapedecksampler.app.RobotEffectTest
run -cp "$cp" com.tapedecksampler.app.TimelineWindowTest

if ! $test_only; then
	"${gradle[@]}" :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease
	mkdir -p dist
	cp -f app/build/outputs/apk/release/app-release.apk dist/TapeDeckSampler.apk
fi
