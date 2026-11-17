# Maintainer notes

## Windows scripts

**Build Tape Deck Sampler.bat** is in the project root; the other scripts are in this folder.

- **Build Tape Deck Sampler.bat** runs the unit tests in `tests/`, builds debug and release APKs, runs lint and copies the release APK to `dist/TapeDeckSampler.apk`. Run `tools/build.ps1 -TestOnly` to only run the tests.
- **Install Tape Deck Sampler to phone.bat** builds the release APK and installs it on one phone connected over USB with USB debugging on.
- **Release Tape Deck Sampler.bat** publishes a new release, see [Releasing](#releasing).
- **Render Previews.bat** renders the F-Droid and README screenshots in an emulator.
- **render-battery-sleeves.ps1** redraws the battery label artwork in `app/src/main/res/drawable-nodpi` and a preview sheet in `dist/battery-brands.png`.
- **Start Tape Deck Sampler.cmd** runs the built APK in an Android emulator. It needs the SDK, emulator and the `android-35` x86_64 system image in `.tools/sdk`. **Restart Tape Deck Sampler audio.cmd** restarts that emulator to reconnect Windows audio.

The scripts build with `gradlew` and use a JDK or SDK in the local `.tools/` folder if one is there (it is ignored by git). Otherwise the build script falls back to `JAVA_HOME` and `ANDROID_HOME`. The phone and emulator scripts expect `adb` and the emulator in `.tools/sdk`.

## Release signing

To sign release builds with your own key, create a keystore in `signing/` and copy `signing/keystore.properties.example` to `signing/keystore.properties` with your values:

```sh
keytool -genkeypair -v -keystore signing/release.jks -alias tapedecksampler -keyalg RSA -keysize 4096 -validity 10000
```

Both `keystore.properties` and `*.jks` are ignored by git. If `keystore.properties` is missing, release builds are signed with the debug key instead.

## Releasing

Run **Release Tape Deck Sampler.bat** on a clean `main`. It asks for the new version, runs the tests and opens Notepad for the changelog: write what changed, save and close Notepad. It then bumps `versionCode` and `versionName`, commits, tags `vX.Y.Z` and pushes. The GitHub Actions workflow in `.github/workflows/release.yml` builds the signed APK and publishes it under Releases. Versions starting with `0.` are marked as pre-releases.

Changelogs live in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters) and are used both for the GitHub release and by F-Droid. The store description and screenshots are in the same `fastlane` folder.

The workflow needs these repository secrets (GitHub > Settings > Secrets and variables > Actions):

- `KEYSTORE_BASE64`: the keystore file as base64. In PowerShell from the project root: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("signing\release.jks")) | Set-Clipboard`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: the values from `signing/keystore.properties`

## F-Droid

The metadata draft for the F-Droid submission is in `fdroid/`.
