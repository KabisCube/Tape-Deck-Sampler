#!/usr/bin/env bash
# Linux version of "Release Tape Deck Sampler.bat": runs tools/release.sh and keeps the window open at the end.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Double-clicked in the file manager there's no terminal to type the version into, so reopen inside Konsole.
if [[ ! -t 0 ]] && command -v konsole > /dev/null; then
	exec konsole -e bash "$here/$(basename "${BASH_SOURCE[0]}")" "$@"
fi

cd "$here/.."
bash "$here/release.sh" "$@"
result=$?
read -rp "Press Enter to close..." _
exit "$result"
