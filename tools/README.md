# Maintainer notes

Everything you run is directly in this folder; `lib/` holds the parts those scripts use (don't run them on their own).

## Windows scripts

- **build.bat** runs the unit tests in `tests/`, builds debug and release APKs, runs lint and copies the release APK to `dist/TapeDeckSampler.apk`. Run `tools/lib/build.ps1 -TestOnly` to only run the tests.
- **install-phone.bat** builds the release APK and installs it on one phone connected over USB with USB debugging on.
- **release.bat** publishes a new release, see [Releasing](#releasing).
- **render-previews.bat** renders the F-Droid and README screenshots on a connected phone, see [Screenshots](#screenshots).
- **render-battery-sleeves.ps1** redraws the battery label artwork in `app/src/main/res/drawable-nodpi` and a preview sheet in `dist/battery-brands.png`.

The `.bat` files start the PowerShell scripts in `lib/`. The scripts build with `gradlew` and use a JDK or SDK in the local `.tools/` folder if one is there (it is ignored by git). Otherwise the build script falls back to `JAVA_HOME` and `ANDROID_HOME`. The phone scripts expect `adb` in `.tools/sdk` or the Android SDK.

## Linux scripts

Run them from a terminal, or double-click them: they open in Konsole and wait for Enter before closing.

- **build.sh** works like **build.bat**; `tools/build.sh --test-only` only runs the tests.
- **release.sh** works like **release.bat**, but opens the changelog in `$VISUAL`, `$EDITOR`, Kate or nano instead of Notepad. `tools/release.sh 1.2.3` skips the version question.
- **render-previews.sh** works like **render-previews.bat**.
- **install-phone.sh** works like **install-phone.bat**, but first checks that installing can't cost any projects (same signing key, not an older version) and stops if it could.

They need JDK 17 (found through `JAVA_HOME`, `/usr/lib/jvm/java-17-*` or a Linux JDK in `.tools/java`) and the Android SDK (`ANDROID_HOME`, `~/Android/Sdk` or a Linux SDK in `.tools/sdk`); Windows copies of the JDK or SDK in `.tools/` are skipped. If `local.properties` points at an SDK path that doesn't exist on this machine (such as the Windows one), the build uses the Linux SDK for that run and puts the original file back afterwards. The shared parts are in `lib/common.sh`.

## Screenshots

**render-previews.bat** (Windows) and **render-previews.sh** (Linux) make `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png` (front) and `2.png` (back) on a connected phone with USB debugging on. They build the release app plus a screenshot helper (`app/src/androidTest/.../PreviewShots.java`), then check before changing anything:

- If the installed app is signed with a different key, or is a newer version, they stop: installing would need an uninstall, which deletes all projects. They never uninstall the app.
- Otherwise they explain what will happen and ask before going on.

The helper backs up the app's settings, sets demo values, creates a demo project called "Late Night Loop", draws both sides, then deletes the demo project and restores the settings (if a run is cut off, the next run restores them first). It refuses to run if you have your own project with that name. The pictures are resized to 1500 px tall; pass another height as the first argument.

## Release signing

To sign release builds with your own key, create a keystore in `signing/` and copy `signing/keystore.properties.example` to `signing/keystore.properties` with your values:

```sh
keytool -genkeypair -v -keystore signing/release.jks -alias tapedecksampler -keyalg RSA -keysize 4096 -validity 10000
```

Both `keystore.properties` and `*.jks` are ignored by git. If `keystore.properties` is missing, release builds are signed with the debug key instead.

## Releasing

Run **release.bat** on a clean `main`. It asks for the new version, runs the tests and opens Notepad for the changelog: write what changed, save and close Notepad. It then bumps `versionCode` and `versionName`, commits, tags `vX.Y.Z` and pushes. The GitHub Actions workflow in `.github/workflows/release.yml` builds the signed APK and publishes it under Releases. Versions starting with `0.` are marked as pre-releases.

To try a build without releasing, run the **Test build** workflow (GitHub > Actions > Test build > Run workflow, or `gh workflow run test-build.yml`). It runs the tests and builds a signed APK from the latest commit on the chosen branch, attached to the run as an artifact for 14 days. Nothing is tagged, bumped or published.

Changelogs live in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters) and are used both for the GitHub release and by F-Droid. The store description and screenshots are in the same `fastlane` folder.

The workflow needs these repository secrets (GitHub > Settings > Secrets and variables > Actions):

- `KEYSTORE_BASE64`: the keystore file as base64. In PowerShell from the project root: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\signing\release.jks")) | Set-Clipboard`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: the values from `signing/keystore.properties`

## F-Droid

The metadata draft for the F-Droid submission is in `fdroid/`.
