#!/usr/bin/env bash
# Linux version of release.ps1. Publishes a new release: checks the repo, runs the tests, bumps versionCode and
# versionName, writes the F-Droid changelog, then commits, tags vX.Y.Z and pushes. GitHub Actions builds and uploads
# the APK. Usage: tools/release.sh [1.2.3]   (asks for the version when it isn't given)
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

stop_release() {
	printf '\033[31m%s\033[0m\n' "$1" >&2
	exit 1
}

# Runs git; on failure stops the release and shows git's own message.
# Use it as  var=$(git_or_stop ...)  so a failure inside the $( ) still ends the script.
git_or_stop() {
	local output
	if ! output=$(git "$@" 2>&1); then stop_release "git $* failed:"$'\n'"$output"; fi
	printf '%s' "$output"
}

gradle_file="$root/app/build.gradle"
current_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' "$gradle_file" | head -n 1)
[[ -n "$current_code" ]] || stop_release "Could not find versionCode in app/build.gradle."
latest_tag=$(git tag --list 'v*' --sort=-v:refname | head -n 1)
latest_version="${latest_tag#v}"
[[ -n "$latest_version" ]] || latest_version="0.0.0"
IFS=. read -r major minor patch <<< "$latest_version"
suggested="$major.$minor.$((patch + 1))"

echo "Latest release: $latest_tag"
version="${1:-}"
if [[ -z "$version" ]]; then
	read -rp "New version (Enter for $suggested): " version
	version="${version:-$suggested}"
fi
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || stop_release "Version must look like 1.2.3, got '$version'."
newest=$(printf '%s\n%s\n' "$version" "$latest_version" | sort -V | tail -n 1)
if [[ "$version" == "$latest_version" || "$newest" != "$version" ]]; then
	stop_release "Version $version must be newer than $latest_version."
fi
tag="v$version"

changes=$(git_or_stop status --porcelain)
[[ -z "$changes" ]] || stop_release "You have uncommitted changes. Commit or stash them first."
branch=$(git_or_stop rev-parse --abbrev-ref HEAD)
[[ "$branch" == "main" ]] || stop_release "Releases are made from main, but you are on '$branch'."
git_or_stop fetch origin main --tags > /dev/null
behind=$(git_or_stop rev-list --count 'HEAD..origin/main')
[[ "$behind" == "0" ]] || stop_release "GitHub has commits you do not have. Pull first."
existing=$(git_or_stop tag --list "$tag")
[[ -z "$existing" ]] || stop_release "Tag $tag already exists."

echo "Running tests..."
"$root/tools/build.sh" --test-only || stop_release "Tests failed. Nothing was released."

new_code=$((current_code + 1))
changelog_dir="$root/fastlane/metadata/android/en-US/changelogs"
changelog="$changelog_dir/$new_code.txt"
mkdir -p "$changelog_dir"
[[ -f "$changelog" ]] || : > "$changelog"

# Notepad on Windows; here: $VISUAL, $EDITOR, Kate (waits until you close the file), or nano in the terminal.
edit_changelog() {
	local editor=()
	if [[ -n "${VISUAL:-}" ]]; then read -ra editor <<< "$VISUAL"
	elif [[ -n "${EDITOR:-}" ]]; then read -ra editor <<< "$EDITOR"
	elif [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]] && command -v kate > /dev/null; then editor=(kate --block)
	else editor=(nano)
	fi
	"${editor[@]}" "$1" 2> /dev/null
}
echo "Write what changed in $version, then save and close the editor."
edit_changelog "$changelog"

notes=$(<"$changelog")
notes="${notes#"${notes%%[![:space:]]*}"}"
notes="${notes%"${notes##*[![:space:]]}"}"
max_changelog_chars=500
if [[ -z "$notes" ]]; then rm -f "$changelog"; stop_release "The changelog is empty. Nothing was released."; fi
if (( ${#notes} > max_changelog_chars )); then
	stop_release "The changelog is ${#notes} characters. F-Droid allows $max_changelog_chars. Shorten it and run again."
fi

sed -i -E \
	-e "s/^([[:space:]]*versionCode[[:space:]]+)[0-9]+/\1$new_code/" \
	-e "s/^([[:space:]]*versionName[[:space:]]+)'[^']*'/\1'$version'/" \
	"$gradle_file"
grep -qE "^[[:space:]]*versionCode[[:space:]]+$new_code" "$gradle_file" && grep -qE "versionName[[:space:]]+'$version'" "$gradle_file" \
	|| stop_release "Could not update the version in app/build.gradle."

echo "Releasing $tag (versionCode $new_code)..."
git_or_stop add -- "$gradle_file" "$changelog" > /dev/null
git_or_stop commit -m "Release $version" > /dev/null
git_or_stop tag -a "$tag" -m "Tape Deck Sampler $version" > /dev/null
git_or_stop push --atomic origin main "$tag" > /dev/null
printf '\033[32m%s\033[0m\n' "Done. GitHub is now building $tag; the APK appears under Releases in a few minutes."
