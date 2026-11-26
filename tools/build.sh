#!/usr/bin/env bash
# Linux version of build.ps1: compiles the app, runs every test suite, and (unless --test-only)
# builds debug + release APKs, runs lint and copies the release APK to dist/TapeDeckSampler.apk.
set -euo pipefail

test_only=false
[[ "${1:-}" == "--test-only" ]] && test_only=true

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$root/tools/lib/common.sh"     # JDK 17, Android SDK, a local.properties that works here, terminal window
tds_open_terminal "$@"
cd "$root"
trap 'tds_restore_properties; tds_pause' EXIT
trap 'exit 130' INT TERM
tds_setup_java
tds_setup_sdk
tds_use_local_sdk

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
