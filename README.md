<p align="center">
  <img src="docs/preview1.png" alt="Tape Deck Sampler front" width="45%">
  <img src="docs/preview2.png" alt="Tape Deck Sampler back" width="45%">
</p>

# Tape Deck Sampler

Is an easy to use Android sampler with eight-tracks, sixteen-step sequencer with up to 16 pages that share a 20 second, 48kHz mono WAV "Tape" for all the samples. 

Record from your mic, another app, or load samples directly and slice them into your 20 second sample tape.

Tape Deck Sampler supports Project, MIDI and WAV exports.

Each track plays one sample at a time. Triggering another pad on that track cuts off its previous sample and starts the new one. Samples on other tracks keep playing.


## Clap & Sync

Starting a jam is as simple as it should be.
Set the same **Tempo** on every device. Select **Sync** and just Clap once when all devices are ready. No network settings or passwords needed.

Devices can pause and play individually. When pressing **Play** a small delay will occur to get your device back in sync.
If you want to change the BPM, just change it on all devices at any time.
If another device wants to join, just Resync the devices, no data is lost.


## Recording and importing

**Add sample > Record microphone** Simple mic recording. Select where on your main 20 second sample you want to start recording from, and start recording. The recording can then be trimmed before committing it to the main 20 second sample.

**Add sample > Record device** captures audio from other apps on Android 10 or later. Start recording and switch to another app, play its audio, return and stop the recording. Recording can also be stopped through the notifications. The sample can then be trimmed and placed in your main 20 second sample.
Android only permits capture from apps that allow it; so some sources may produce silence.

**Add sample > Import sample** Import any audio sample directly from your storage without needing to record anything. The sample can then be trimmed and placed into your main 20 second sample.

**Add sample > Text to speech** opens the sampler's speech menu. **Text** opens the Android keyboard and types straight onto the sampler screen; **Voice** selects Klatt, Old lady, Robot, Croak, Female or Whisper. Use **Preview**, the play/pause button or the selected track button to audition speech. You can also preview highlighted voices inside the voice menu, then return to edit the text. **Add sample** opens trimming and tape placement; returning from trimming takes you back to the speech menu. Speech is generated offline with eSpeak NG and must fit within 20 seconds. Old lady is eSpeak's Grandma preset with drawn-out stressed vowels and a wider melody. These are eSpeak voices, not the original S.A.M. presets.

**Add sample > Synth** builds a tone from a **Wave** (sine, triangle, saw, square or noise), **Note**, **Length**, **Attack**, **Release** and a pitch **Sweep**. Select a row to edit it with the wheel; every change is auditioned. **Add sample** opens trimming and tape placement.

Tape names and speech text are typed with the Android keyboard and appear on the sampler screen. **Done** (center) or the keyboard's Done key finishes, **Return** cancels and tapping the screen brings the keyboard back.


## Autotune

For a hard, stepped vocal sound, use a solo singing sample, select the song's key and scale, and set **Strength to 100%**, **Retune to 0 ms** and **Mix to 100%**. Longer retune times make note changes smoother; lower strength retains more of the original pitch movement. Autotune works best on one voice at a time.

**Chromatic** allows all twelve notes, so changing the key has no effect in that mode. Choose Major, Minor or a pentatonic scale for key-based tuning. Pentatonic scales leave wider gaps between allowed notes, making pitch changes more pronounced.

Key is grayed out in Chromatic mode because every note is allowed.

## Robot

Add **Robot** from the track or pad effect menu. **Voice** selects **Robot**, **Giant**, **Chip** or **Whisper**. The synthetic voice follows the pitch of the recording instead of forcing a note; Giant sits an octave below and Chip an octave above. Use **Morph** to shift the voice's formants. Whisper uses noise. Older projects map to these four voices automatically. Add **Autotune** if you want the notes corrected.

At **Mix 100%**, only the resynthesized sound is played. Lower Mix blends the recording back in. This is a filter-bank vocoder driven by the sample's audio, including consonants, percussion and noise; it does not transcribe speech or generate replacement words. Robot is separate from Autotune. Existing projects using the earlier Robot scale migrate automatically to the new effect.

These are original profiles inspired by vintage speech synthesis, not Pocket Operator firmware or exact hardware emulations. The Robot DSP is part of this app under **GPL-3.0-or-later**, with no additional runtime libraries.


## Filters and distortion

**High-pass**, **Low-pass** and **Band-pass** each have a frequency and **Resonance** control. Resonance emphasizes the cutoff or center frequency. Band-pass removes frequencies on both sides of its center. These effects can be reordered and applied to tracks or individual pads. On the effect list, **Options** (wheel bottom) copies one effect or the whole stack, pastes it onto another track or pad, and opens **Order**.

**Distortion** has **Drive** for Soft clip, Hard clip and Foldback. **Bitcrush** instead has independent **Bits** (2–16 bits) and **Rate** (1,000–48,000 Hz) controls. Lower values give a rougher sound. **Mix** blends each distortion with the original audio.

In **PAGES**, turn the wheel to highlight a page. **Copy** (left) copies it, **Paste** (right) replaces the highlighted page with the copy and **Move** (bottom) swaps two pages. Pressing a pad opens its page and holding a pad turns the page on or off. The playing page uses the same soft pad glow as normal playback. A pad's blue effect indicator lights only while that pad is enabled; disabling it keeps its effect settings.

## Project

Every project is its own tape cassette with the samples and pad data stored on it.
As you make changes, the tape automatically saves your changes live. 


## Export

**Menu > Project > Export**, **As MIDI**, **As WAV** and **As Project**.
**As MIDI** exports the enabled sequence pages and track timing.
**As WAV** renders one pass through the enabled pages in numerical order, or if you use chance effects, it can use enough passes to reach every pad sound once: WAV exports are 48 kHz mono PCM16.
**As Project** exports a `.tapedeck.zip` with the tape and all pad data so it can be imported on another device.


## Building

### Requirements

- JDK 17 (with `JAVA_HOME` set)
- Android SDK with platform 35 (with `ANDROID_HOME` set, or `sdk.dir` in `local.properties`)
- Android NDK 28.2.13676358 and CMake 3.22.1 (Android Studio or Gradle can install them)
- An Android 8.0+ device (API 26) to run it on

### Command line

```sh
./gradlew :app:assembleDebug      # Windows: gradlew.bat :app:assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. Install it with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also open the project in Android Studio and press Run.

### Windows scripts

- **Build Tape Deck Sampler.bat** runs the unit tests in `tests/`, builds debug and release APKs, runs lint and copies the release APK to `dist/TapeDeckSampler.apk`. Run `build.ps1 -TestOnly` to only run the tests.
- **Install Tape Deck Sampler to phone.bat** builds the release APK and installs it on one phone connected over USB with USB debugging on.
- **tools/render-battery-sleeves.ps1** redraws the battery label artwork in `app/src/main/res/drawable-nodpi` and a preview sheet in `dist/battery-brands.png`.
- **Start Tape Deck Sampler.cmd** runs the built APK in an Android emulator. It needs the SDK, emulator and the `android-35` x86_64 system image in `.tools/sdk`.

The scripts build with `gradlew` and use a JDK or SDK in the local `.tools/` folder if one is there (it is ignored by git). Otherwise the build script falls back to `JAVA_HOME` and `ANDROID_HOME`. The phone and emulator scripts expect `adb` and the emulator in `.tools/sdk`.

### Release signing

To sign release builds with your own key, create a keystore and copy `keystore.properties.example` to `keystore.properties` with your values:

```sh
keytool -genkeypair -v -keystore release.jks -alias tapedecksampler -keyalg RSA -keysize 4096 -validity 10000
```

Both `keystore.properties` and `*.jks` are ignored by git. If `keystore.properties` is missing, release builds are signed with the debug key instead.


## License

Tape Deck Sampler is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

Tape Deck Sampler is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [LICENSE](LICENSE) file for details.


## Credits

The Album Marker font (`app/src/main/assets/fonts/AlbumMarker01-Regular.ttf`) is made by KabisCube and licensed under the same GNU GPL v3-or-later license as the rest of this project.

Built with [Kotlin](https://kotlinlang.org/) by JetBrains. The Kotlin standard library is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Retro speech uses [eSpeak NG 1.52.0](https://github.com/espeak-ng/espeak-ng/tree/1.52.0), including its English pronunciation data, voice variants and UCD Tools, under **GPL-3.0-or-later**. Its Unicode data uses the **Unicode Data Files and Software License**. Both are compatible with this app's GPL-3.0-or-later license. License texts and attribution ship in `app/src/main/assets/licenses/`.

The native compiler runtime uses **Apache-2.0 with LLVM exceptions**, with legacy compiler-rt portions available under **MIT**. Its notices are included in `LLVM-compiler-rt.txt` alongside the speech licenses.

The complete upstream source is retained in `third_party/espeak-ng/espeak-ng-1.52.0.tar.gz` and compiled locally for Android. That source archive also includes unused Android bindings under **Apache-2.0** and Windows compatibility code under **BSD-2-Clause**; their original notices are preserved. The app does not build those components, MBROLA, Sonic or SpeechPlayer. See `third_party/espeak-ng/NOTICE.txt` for provenance and build details.

