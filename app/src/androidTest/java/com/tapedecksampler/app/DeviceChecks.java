package com.tapedecksampler.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class DeviceChecks extends Instrumentation {
    private MainActivity app;
    private int checks, textLayouts;
    private boolean buttonsOnly;
    private boolean tracksOnly;
    private boolean padPitchOnly;
    private boolean effectsOnly;
    private boolean pagesOnly;
    private boolean speechOnly;
    private boolean filtersOnly;
    private boolean robotOnly;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); filtersOnly = arguments != null && "filters".equals(arguments.getString("scope")); robotOnly = arguments != null && "robot".equals(arguments.getString("scope")); buttonsOnly = arguments != null && "buttons".equals(arguments.getString("scope")); tracksOnly = arguments != null && "tracks".equals(arguments.getString("scope")); padPitchOnly = arguments != null && "padpitch".equals(arguments.getString("scope")); effectsOnly = arguments != null && "effects".equals(arguments.getString("scope")); pagesOnly = arguments != null && "pages".equals(arguments.getString("scope")); speechOnly = arguments != null && "speech".equals(arguments.getString("scope")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            app = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ready();
            if (filtersOnly) {
                stage("Filter and distortion controls"); filterMenus(); stage("Pad lighting"); padLights(); stage("Text overlap audit"); textAudit();
            } else if (robotOnly) {
                stage("Robot voices"); robotMenus();
            } else if (speechOnly) {
                stage("Retro speech"); speech();
            } else if (pagesOnly) {
                stage("Secret flags"); flagSettings(); stage("Battery bay"); batteryBay(); stage("Sequence pages"); pages();
            } else if (effectsOnly) {
                stage("Live and pad effects"); effects(); stage("Pad Pitch with track Auto Tune"); padPitch(); stage("Text overlap audit"); textAudit();
            } else if (padPitchOnly) {
                stage("Pad Pitch with track Auto Tune"); padPitch();
            } else if (buttonsOnly) {
                stage("Corner buttons"); cornerButtons();
            } else if (tracksOnly) {
                stage("Track mute"); trackHolds();
            } else {
                stage("Controls"); controls(); stage("Track mute"); trackHolds(); stage("Swing"); swing(); stage("Clap sync"); sync(); stage("Timeline"); timeline(); stage("Import"); imports(); stage("Retro speech"); speech(); stage("Device capture import"); deviceCaptureImport();
                stage("Live and pad effects"); effects(); stage("System settings"); settings(); stage("Battery bay"); batteryBay(); stage("Named sessions"); sessions(); stage("Project import"); projectImport(); stage("Sequence pages"); pages(); stage("Microphone"); microphone(); stage("Text overlap audit"); textAudit();
            }
            main(() -> { app.mode = MainActivity.Mode.HOME; app.instrument.invalidate(); });
            capture("tape-preview.png");
            result.putString("stream", checks + " Android checks passed; " + textLayouts + " layouts audited for text overlap\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "FAILED: " + android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void filterMenus() throws Exception {
        main(() -> {
            app.mode = MainActivity.Mode.HOME; app.selectTrack(0); app.sequencer.stop(); app.sequencer.resetEffects(0);
            effectsMenu(); addGroup(Sequencer.Effects.BAND_PASS);
            check(app.effectGroup == Sequencer.Effects.BAND_PASS && app.effectItems().length == 3, "Band-pass opens center and resonance controls");
            app.selection = 1; app.confirm(); app.wheel(75); app.confirm();
            check(app.sequencer.tracks[0].bandResonance == 75, "Band-pass resonance edits reach the engine");
            effectParameter(MainActivity.FX_HIGH_RESONANCE); app.wheel(60); app.confirm();
            effectParameter(MainActivity.FX_LOW_RESONANCE); app.wheel(40); app.confirm();
            effectParameter(MainActivity.FX_DRIVE); app.wheel(10); app.confirm();
            check(app.sequencer.tracks[0].drive == 50, "Distortion drive is editable");
            effectParameter(MainActivity.FX_DISTORTION); app.wheel(TrackEffects.DISTORTIONS.length); app.confirm();
            check(!app.effectRowEnabled(1) && app.effectRowEnabled(2) && app.effectRowEnabled(3), "Bitcrush enables bit depth and rate and disables drive");
            app.selection = 2; app.confirm(); app.wheel(-3); app.confirm();
            app.selection = 3; app.confirm(); app.wheel(-2); app.confirm();
            check(app.sequencer.tracks[0].crushBits == 5 && app.sequencer.tracks[0].crushRate == 7000, "Bitcrush bit depth and sample rate edit independently");
            app.selection = 2; app.confirm(); app.wheel(100); app.cancel();
            check(app.sequencer.tracks[0].crushBits == 5, "Cancel restores bit depth");
        });
        ready(); restart();
        main(() -> {
            Sequencer.Track controls = app.sequencer.tracks[0];
            check(controls.bandPass > 0 && controls.bandResonance == 75 && controls.highResonance == 60 && controls.lowResonance == 40, "All filter settings survive reopening");
            check(controls.crushBits == 5 && controls.crushRate == 7000 && controls.drive == 50, "Distortion tuning survives reopening");
            try {
                java.lang.reflect.Method read = MainActivity.class.getDeclaredMethod("readEffects", org.json.JSONObject.class, Sequencer.Effects.class);
                read.setAccessible(true);
                org.json.JSONObject previous = new org.json.JSONObject().put("fxVersion", 2).put("highPass", 1000).put("lowPass", 8000)
                        .put("distortion", TrackEffects.BITCRUSH).put("drive", 40).put("effectPower", 0).put("effectPowerSet", 1 << Sequencer.Effects.HIGH_PASS)
                        .put("chain", new org.json.JSONArray(new int[]{Sequencer.Effects.DELAY, Sequencer.Effects.LOW_PASS, Sequencer.Effects.HIGH_PASS, Sequencer.Effects.DISTORTION, Sequencer.Effects.REVERB}));
                Sequencer.Effects migrated = new Sequencer.Effects(); read.invoke(app, previous, migrated);
                check(!migrated.powered(Sequencer.Effects.HIGH_PASS) && migrated.powered(Sequencer.Effects.LOW_PASS), "Five-effect projects retain separate filter power states");
                check(migrated.crushBits == TrackEffects.legacyCrushBits(40) && migrated.crushRate == TrackEffects.legacyCrushRate(40), "Old Bitcrush drive migrates to its original bit depth and rate");
                check(migrated.chain[0] == Sequencer.Effects.DELAY && migrated.chain[1] == Sequencer.Effects.LOW_PASS, "Saved effect order survives Band-pass migration");
            } catch (Exception e) { throw new AssertionError(e); }
            app.mode = MainActivity.Mode.EFFECTS; app.effectGroup = Sequencer.Effects.DISTORTION; app.selection = 0;
        });
        capture("distortion-controls.png");
    }

    private void padLights() throws Exception {
        java.lang.reflect.Method draw = InstrumentView.class.getDeclaredMethod("pads", Canvas.class);
        draw.setAccessible(true);
        main(() -> {
            app.audio.stop(); app.sequencer.stop(); app.mode = MainActivity.Mode.HOME; app.selectTrack(0);
            app.sequencer.tracks[0].steps = 0;
            app.sequencer.editStep(0, 1).reverb = 50;
            Bitmap bitmap = Bitmap.createBitmap(400, 860, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            try {
                int indicatorX = 161, indicatorY = (int) InstrumentView.PAD_TOP + 9;
                draw.invoke(app.instrument, canvas); int disabled = bitmap.getPixel(indicatorX, indicatorY);
                app.sequencer.resetStep(0, 1); draw.invoke(app.instrument, canvas);
                check(disabled == bitmap.getPixel(indicatorX, indicatorY), "Disabled pad effects use the same unlit indicator as an empty pad");
                app.sequencer.editStep(0, 1).reverb = 50; app.sequencer.tracks[0].steps = 1 << 1;
                draw.invoke(app.instrument, canvas);
                check(disabled != bitmap.getPixel(indicatorX, indicatorY), "Re-enabling the pad lights its effect indicator");
                app.mode = MainActivity.Mode.PAGES; app.sequencer.selectPage(1);
                int centerX = 153, centerY = (int) InstrumentView.PAD_TOP + 24;
                draw.invoke(app.instrument, canvas); int stopped = bitmap.getPixel(centerX, centerY);
                app.sequencer.play(); app.sequencer.render(new short[64]); draw.invoke(app.instrument, canvas);
                int playing = bitmap.getPixel(centerX, centerY);
                check(stopped != playing, "PAGES adds the normal soft glow to the playing page");
                app.sequencer.selectPage(2); draw.invoke(app.instrument, canvas);
                check(playing == bitmap.getPixel(centerX, centerY), "Page glow follows playback while another page is selected");
                app.sequencer.stop();
            } catch (Exception e) { throw new AssertionError(e); }
            finally { bitmap.recycle(); }
        });
    }

    private void speech() throws Exception {
        check(Arrays.asList(MainActivity.ADD_SAMPLE).contains("Text to speech"), "Speech is available in Add sample");
        check(Arrays.asList(RetroSpeech.INSTANCE.getVoices()).contains("Old lady") && !Arrays.asList(RetroSpeech.INSTANCE.getVoices()).contains("Klatt 2"), "Old lady replaces Klatt 2 in the voice menu");
        java.util.HashSet<Integer> hashes = new java.util.HashSet<>();
        for (int voice = 0; voice < RetroSpeech.INSTANCE.getVoices().length; voice++) {
            File wave = RetroSpeech.INSTANCE.synthesize(app, "Hello, tape deck sampler!", voice);
            try {
                SampleDecoder.Source source = SampleDecoder.inspect(app, Uri.fromFile(wave));
                check(source.frames > Tape.RATE / 2 && source.frames <= Tape.LENGTH, "Each voice produces bounded audio");
                short[] samples = SampleDecoder.decode(app, source, 0, source.frames);
                long energy = 0;
                for (short sample : samples) energy += Math.abs((int) sample);
                check(energy > samples.length, "Each voice produces audible PCM");
                hashes.add(Arrays.hashCode(samples));
            } finally { wave.delete(); }
        }
        check(hashes.size() == RetroSpeech.INSTANCE.getVoices().length, "All voice presets produce different speech");
        for (String input : new String[]{"", "   ", String.join("", java.util.Collections.nCopies(RetroSpeech.MAX_TEXT_LENGTH + 1, "a")), "\u2603"}) {
            boolean rejected = false;
            try { RetroSpeech.INSTANCE.synthesize(app, input, 0); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "Invalid speech input is rejected");
        }
        boolean tooLong = false;
        String limitResult;
        try {
            String alphabet = "a b c d e f g h i j k l m n o p q r s t u v w x y z ";
            File longSpeech = RetroSpeech.INSTANCE.synthesize(app, String.join("", java.util.Collections.nCopies(4, alphabet)), 0);
            try { limitResult = "Generated " + SampleDecoder.inspect(app, Uri.fromFile(longSpeech)).frames + " frames"; }
            finally { longSpeech.delete(); }
        } catch (java.io.IOException expected) {
            limitResult = expected.getMessage();
            tooLong = limitResult.contains("20 seconds");
        }
        check(tooLong, "Long speech is rejected without truncation: " + limitResult);
        File wave = RetroSpeech.INSTANCE.synthesize(app, "Ready to sample.", 0);
        try {
            main(() -> app.decode(Uri.fromFile(wave), "Retro speech test"));
            ready();
            check(app.mode == MainActivity.Mode.IMPORT_START && app.importSource != null, "Generated speech enters sample trimming");
            main(() -> app.confirm());
            main(() -> app.confirm());
            ready();
            check(app.mode == MainActivity.Mode.IMPORT_POSITION, "Generated speech reaches tape placement");
            main(() -> { app.cancel(); app.cancel(); app.cancel(); });
        } finally { wave.delete(); }
        speechMenus();
    }

    private void robotMigration() throws Exception {
        java.lang.reflect.Method read = MainActivity.class.getDeclaredMethod("readEffects", org.json.JSONObject.class, Sequencer.Effects.class);
        java.lang.reflect.Method mask = MainActivity.class.getDeclaredMethod("groupMask", org.json.JSONObject.class, String.class);
        java.lang.reflect.Method write = MainActivity.class.getDeclaredMethod("effectData", Sequencer.Effects.class);
        read.setAccessible(true); mask.setAccessible(true); write.setAccessible(true);
        int tune = 1 << Sequencer.Effects.AUTOTUNE, robot = 1 << Sequencer.Effects.ROBOT;
        org.json.JSONObject legacy = new org.json.JSONObject().put("fxVersion", 99).put("autoScale", PitchCorrector.SCALES.length)
                .put("autoKey", 4).put("autoOctave", 4).put("autoStrength", 100).put("autoMix", 100)
                .put("effectPower", tune).put("effectPowerSet", tune).put("custom", tune);
        Sequencer.Effects migrated = new Sequencer.Effects();
        read.invoke(app, legacy, migrated);
        check(migrated.hasRobot() && !migrated.hasAutotune(), "Legacy Robot moves out of Autotune");
        check(migrated.robotProfile == RobotEffect.ROBOT, "Migration selects the Robot voice");
        check(((Integer) mask.invoke(app, legacy, "custom") & robot) != 0, "Legacy pad Robot overrides remain independent");
        org.json.JSONObject saved = (org.json.JSONObject) write.invoke(app, migrated);
        Sequencer.Effects restored = new Sequencer.Effects(); read.invoke(app, saved, restored);
        check(restored.sameGroup(Sequencer.Effects.ROBOT, migrated) && restored.sameGroup(Sequencer.Effects.AUTOTUNE, migrated), "Migrated Robot survives saving and reopening");
        legacy.put("effectPower", 0); read.invoke(app, legacy, migrated);
        check(!migrated.hasRobot(), "Migration preserves disabled Robot power");
        int[] profiles = {RobotEffect.ROBOT, RobotEffect.ROBOT, RobotEffect.GIANT, RobotEffect.CHIP, RobotEffect.WHISPER, RobotEffect.ROBOT, RobotEffect.ROBOT, RobotEffect.ROBOT};
        for (int profile = 0; profile < profiles.length; profile++) {
            org.json.JSONObject previous = new org.json.JSONObject().put("robotProfile", profile).put("robotMix", 100);
            read.invoke(app, previous, migrated);
            check(migrated.robotProfile == profiles[profile] && migrated.hasRobot(), "Previous voice profiles migrate with Robot enabled");
            saved = (org.json.JSONObject) write.invoke(app, migrated);
            check(saved.getInt("robotVersion") == RobotEffect.PROFILE_VERSION, "Saving records the consolidated profile version");
            read.invoke(app, saved, restored);
            check(restored.sameGroup(Sequencer.Effects.ROBOT, migrated), "Consolidated voices survive saving and reopening");
        }
    }

    private void robotMenus() throws Exception {
        robotMigration();
        main(() -> {
            app.mode = MainActivity.Mode.HOME; app.selectTrack(0); app.sequencer.stop(); app.sequencer.resetEffects(0);
            app.sequencer.tracks[0].power(Sequencer.Effects.AUTOTUNE, true);
            app.mode = MainActivity.Mode.EFFECTS; app.effectGroup = Sequencer.Effects.AUTOTUNE; app.selection = 0;
            check(!app.effectRowEnabled(0), "Chromatic disables key");
            app.confirm();
            check(app.mode == MainActivity.Mode.EFFECTS, "Disabled key cannot be edited");
            check(app.effectItems().length == 6, "Autotune has only its own five controls and power");
            app.sequencer.tracks[0].power(Sequencer.Effects.ROBOT, true);
            app.effectGroup = Sequencer.Effects.ROBOT;
            check(app.effectItems()[0].contains("Robot"), "Robot opens with its own voice profile");
            check(app.effectItems().length == 4 && app.effectItems()[0].startsWith("Voice /") && app.effectItems()[1].startsWith("Morph /") && app.effectItems()[2].startsWith("Mix /"), "Robot lists Voice, Morph, Mix and Confirm");
            app.selection = 1; app.confirm(); app.wheel(100);
            check(app.effectValue == 100, "Robot morph has an upper limit");
            app.cancel();
            check(app.sequencer.tracks[0].robotMorph == RobotEffect.DEFAULT_MORPH, "Cancel restores the previous robot morph");
            app.confirm(); app.wheel(10); app.confirm();
        });
        ready();
        main(() -> {
            check(app.sequencer.tracks[0].robotMorph == RobotEffect.DEFAULT_MORPH + 10 && !app.sequencer.needsPreparation(), "Morph edits prepare the changed robot sample");
            InstrumentView view = new InstrumentView(app);
            view.auditText(true); view.layout(0, 0, 400, 880);
            Bitmap bitmap = Bitmap.createBitmap(400, 880, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            for (int row = 0; row < app.effectItems().length; row++) {
                app.selection = row; view.draw(canvas);
                check(view.textIssues().isEmpty(), "Robot menu fits the sampler screen: " + view.textIssues());
            }
            bitmap.recycle();
        });
        restart();
        main(() -> {
            check(app.sequencer.tracks[0].robotMorph == RobotEffect.DEFAULT_MORPH + 10 && app.sequencer.tracks[0].robotMix == 100, "Robot controls survive reopening the project");
            check(app.sequencer.tracks[0].autoStrength == 100, "Robot leaves Autotune independent");
            app.mode = MainActivity.Mode.EFFECTS; app.effectGroup = Sequencer.Effects.ROBOT; app.selection = 0;
        });
        capture("robot-menu.png");
    }

    private void speechMenus() throws Exception {
        String phrase = "Welcome to the tape deck sampler. Testing the different robot voices.";
        int selectedTrack = app.track;
        int steps = app.sequencer.tracks[selectedTrack].steps;
        main(() -> {
            app.mode = MainActivity.Mode.ADD_SAMPLE; app.selection = MainActivity.ADD_SPEECH; app.confirm();
            check(app.mode == MainActivity.Mode.SPEECH, "Speech opens in the sampler menu");
            app.wheelPlay();
            check(!app.busy && !app.sequencer.isPlaying(), "Empty text cannot start the sequencer or speech");
            app.selection = MainActivity.SPEECH_TEXT; app.confirm();
            check(app.mode == MainActivity.Mode.TEXT, "Text editing types onto the sampler screen");
        });
        ready();
        main(() -> {
            enter(phrase);
            check(app.mode == MainActivity.Mode.SPEECH && !app.busy, "Saving text returns to the sampler without generating");
            app.selection = MainActivity.SPEECH_PREVIEW; app.confirm();
        });
        ready();
        main(() -> {
            check(app.sequencer.isPreviewing() && !app.sequencer.isPlaying(), "Preview menu item auditions speech without playing the pattern");
            app.confirm();
            check(!app.sequencer.isPreviewing() && app.sequencer.hasSelectionPreview(), "Preview menu item pauses speech");
            app.wheelPlay();
            check(app.sequencer.isPreviewing(), "Play resumes speech");
            app.selectTrack(selectedTrack);
            check(!app.sequencer.isPreviewing() && app.sequencer.hasSelectionPreview(), "The selected track pauses speech");
            app.selectTrack(selectedTrack);
            check(app.sequencer.isPreviewing(), "The selected track resumes speech");
            app.selectTrack((selectedTrack + 1) % Sequencer.TRACKS);
            app.pad(0);
            check(app.track == selectedTrack && app.sequencer.tracks[selectedTrack].steps == steps, "Auditioning speech keeps the selected track and pattern");
            app.selection = MainActivity.SPEECH_VOICE; app.confirm();
            check(app.mode == MainActivity.Mode.SPEECH_VOICE, "Voices use the sampler menu");
            app.wheel(100);
            check(app.selection == RetroSpeech.INSTANCE.getVoices().length - 1 && !app.sequencer.hasSelectionPreview(), "Changing voices stops the previous preview");
            app.wheelPlay();
        });
        ready();
        main(() -> {
            check(app.mode == MainActivity.Mode.SPEECH_VOICE && app.sequencer.isPreviewing(), "Play previews the highlighted voice in its menu");
            app.selectTrack(selectedTrack);
            check(!app.sequencer.isPreviewing(), "Selected track pauses a voice audition");
            app.confirm();
            check(app.mode == MainActivity.Mode.SPEECH && app.speechItems()[MainActivity.SPEECH_VOICE].contains("Whisper"), "Confirming a voice returns to the speech menu");
            app.selection = MainActivity.SPEECH_TEXT; app.confirm();
        });
        ready();
        main(() -> {
            check(app.textValue.toString().equals(phrase), "Text remains editable after auditioning voices");
            enter("This is the updated speech text.");
            app.selectTrack(selectedTrack);
        });
        ready();
        main(() -> {
            check(app.sequencer.isPreviewing(), "Selected track generates and previews edited text");
            app.wheelPlay();
            app.selection = MainActivity.SPEECH_ADD; app.confirm();
        });
        ready();
        main(() -> {
            check(app.mode == MainActivity.Mode.IMPORT_START, "Add sample opens trimming for the auditioned speech");
            app.cancel();
            check(app.mode == MainActivity.Mode.SPEECH, "Returning from trimming opens the speech menu");
            app.wheelPlay();
            check(!app.busy && app.sequencer.isPreviewing(), "Returning from trimming reuses the generated preview");
            app.cancel();
            check(app.mode == MainActivity.Mode.ADD_SAMPLE && !app.sequencer.hasSelectionPreview(), "Leaving speech stops its preview");
        });
        capture("speech-menu.png");
    }

    private void cornerButtons() throws Exception {
        main(() -> {
            app.mode = MainActivity.Mode.HOME; app.sequencer.stop();
            play();
            check(app.sequencer.isPlaying(), "Triangular play button starts playback");
            play();
            check(!app.sequencer.isPlaying(), "Triangular play button pauses playback");
            touch(MotionEvent.ACTION_DOWN, app.instrument.playX(), InstrumentView.PLAY_Y);
            touch(MotionEvent.ACTION_UP, app.instrument.playX() + 60, InstrumentView.PLAY_Y);
            check(!app.sequencer.isPlaying(), "Releasing outside play cancels the press");
            touch(MotionEvent.ACTION_DOWN, app.instrument.playX(), InstrumentView.PLAY_Y);
            touch(MotionEvent.ACTION_CANCEL, app.instrument.playX(), InstrumentView.PLAY_Y);
            check(!app.sequencer.isPlaying(), "Cancelled play touches do not start playback");
            tap(app.instrument.playX() + 25, InstrumentView.PLAY_Y - 20);
            check(app.sequencer.isPlaying(), "Play button corner responds");
            play();
            app.mode = MainActivity.Mode.MENU;
            touch(MotionEvent.ACTION_DOWN, app.instrument.zoomX(), InstrumentView.ZOOM_Y);
            check(!app.instrument.zoomHeld(), "Unlit zoom button ignores holds");
            touch(MotionEvent.ACTION_UP, app.instrument.zoomX(), InstrumentView.ZOOM_Y);
            app.mode = MainActivity.Mode.HOME;
            touch(MotionEvent.ACTION_DOWN, app.instrument.zoomX(), InstrumentView.ZOOM_Y);
            check(app.instrument.zoomHeld(), "Lit zoom button holds");
            touch(MotionEvent.ACTION_UP, app.instrument.zoomX(), InstrumentView.ZOOM_Y);
            tap(app.instrument.playX() + 22, InstrumentView.PLAY_Y + 45);
            check(app.sequencer.isPlaying(), "Extended play button tail responds");
            play();
            app.mode = MainActivity.Mode.TEMPO;
            tap(InstrumentView.WHEEL_X, InstrumentView.WHEEL_Y - 104);
            check(app.mode == MainActivity.Mode.MENU && app.selection == 2, "Return goes to the parent menu");
            tap(InstrumentView.WHEEL_X - 25, InstrumentView.WHEEL_Y - 104 + 20);
            check(app.mode == MainActivity.Mode.HOME, "Return button corner responds");
            tap(InstrumentView.WHEEL_X, InstrumentView.WHEEL_Y);
            check(app.mode == MainActivity.Mode.MENU, "Center menu remains accessible");
            touch(MotionEvent.ACTION_DOWN, InstrumentView.WHEEL_X, InstrumentView.WHEEL_Y - 104);
            touch(MotionEvent.ACTION_UP, InstrumentView.WHEEL_X + 60, InstrumentView.WHEEL_Y - 104);
            check(app.mode == MainActivity.Mode.MENU, "Releasing outside return cancels the press");
            touch(MotionEvent.ACTION_DOWN, InstrumentView.WHEEL_X, InstrumentView.WHEEL_Y - 104);
            touch(MotionEvent.ACTION_CANCEL, InstrumentView.WHEEL_X, InstrumentView.WHEEL_Y - 104);
            check(app.mode == MainActivity.Mode.MENU, "Cancelled return touches do not navigate");
            int swing = app.sequencer.swing();
            app.mode = MainActivity.Mode.TEMPO; app.selection = 1; app.confirm();
            app.sequencer.swing(swing == 75 ? 50 : 75);
            tap(InstrumentView.WHEEL_X, InstrumentView.WHEEL_Y - 104);
            check(app.mode == MainActivity.Mode.TEMPO && app.sequencer.swing() == swing, "Return rolls back an unconfirmed edit");
            app.mode = MainActivity.Mode.HOME;
        });
        boolean dark = app.darkTheme;
        try {
            main(() -> { app.darkTheme = false; app.instrument.invalidate(); });
            capture("corner-buttons-light.png");
            main(() -> { app.darkTheme = true; app.instrument.invalidate(); });
            capture("corner-buttons-dark.png");
        } finally {
            main(() -> { app.darkTheme = dark; app.instrument.invalidate(); });
        }
    }

    private void controls() throws Exception {
        main(() -> {
            check(app.tape != null && app.audio != null, "Session and audio engine launch");
            check(Arrays.equals(MainActivity.MENU, new String[]{"Track effects", "Add sample", "Tempo", "Sync", "Project", "System", "Check the back"}), "Slicing is accessed through the wheel and the back view is last in the main menu");
            app.batteries.found = false;
            check(app.systemItems().length == 4 && !Arrays.asList(app.systemItems()).contains("Check the back"), "System contains device settings");
            root("Project"); app.selection = 3; app.confirm();
            check(app.mode == MainActivity.Mode.EXPORT && Arrays.equals(MainActivity.EXPORT_ITEMS, new String[]{"As MIDI", "As WAV", "As Project"}), "Export offers both file formats and the project");
            app.wheel(100); check(app.selection == 2, "Export stops at its last item");
            app.cancel(); check(app.mode == MainActivity.Mode.PROJECTS && app.selection == 3, "Export returns to the project menu");
            app.wheelSensitivity = 100; app.mode = MainActivity.Mode.HOME; app.track = 0;
            int bpm = app.sequencer.bpm(); app.wheel(1);
            check(app.mode == MainActivity.Mode.HOME && app.track == 1 && app.sequencer.bpm() == bpm, "Home wheel selects a track without opening the menu");
            tap(86, InstrumentView.TRACK_Y); check(app.track == 1, "Taller track buttons select tracks");
            int bits = app.sequencer.tracks[1].steps; tap(58, InstrumentView.PAD_TOP + 25);
            check(app.sequencer.tracks[1].steps == (bits ^ 1), "Sequence pads toggle steps"); tap(58, InstrumentView.PAD_TOP + 25);
            tap(200, InstrumentView.WHEEL_Y);
            check(app.mode == MainActivity.Mode.MENU && MainActivity.MENU[0].equals("Track effects"), "Track effects is the first main menu entry");
            app.cancel(); tap(96, InstrumentView.WHEEL_Y);
            check(app.mode == MainActivity.Mode.START, "Wheel left opens the sample start");
            tap(200, InstrumentView.WHEEL_Y - 104); check(app.mode == MainActivity.Mode.HOME, "Return cancels a slice");
            tap(304, InstrumentView.WHEEL_Y);
            check(app.mode == MainActivity.Mode.END && app.track == 1 && app.cursor == app.end, "Wheel right opens trimming at the sample end");
            tap(96, InstrumentView.WHEEL_Y);
            check(app.mode == MainActivity.Mode.START && app.cursor == app.start, "Wheel left selects the sample beginning");
            int pattern = app.sequencer.tracks[app.track].steps;
            long span = app.timelineSpan(); zoomIn();
            check(app.timelineSpan() < span && app.sequencer.tracks[app.track].steps == pattern, "Pad 13 zooms in without changing its step");
            zoomOut(); check(app.timelineSpan() == span && app.sequencer.tracks[app.track].steps == pattern, "Pad 14 zooms out without changing its step");
            tap(200, InstrumentView.WHEEL_Y + 104);
            check(app.mode == MainActivity.Mode.START && app.timelineSpan() == span, "Wheel bottom has no Back action");
            tap(200, InstrumentView.WHEEL_Y - 104); check(app.mode == MainActivity.Mode.HOME, "MENU cancels a slice opened from Home");
            app.confirm(); app.selection = 0; app.wheel(-100);
            check(app.selection == 0, "Menu stops at the first item");
            app.wheel(100); check(app.selection == MainActivity.MENU.length - 1, "Menu stops at the last item");
            app.cancel();
            root("Add sample");
            check(Arrays.equals(MainActivity.ADD_SAMPLE, new String[]{"Record microphone", "Record device", "Import sample"}), "Add sample offers microphone, device recording, then import");
            app.wheel(-100); check(app.selection == 0, "Add sample stops at its first item");
            app.wheel(100); check(app.selection == 2, "Add sample stops at its last item");
            app.selection = 1; app.confirm();
            check(app.mode == MainActivity.Mode.DEVICE_RECORD && !DeviceCaptureService.active(), "Device recording opens a capture consent screen before recording");
            app.cancel(); check(app.mode == MainActivity.Mode.ADD_SAMPLE && app.selection == 1, "Leaving device recording returns to Add sample");
            app.cancel(); app.cancel();
            app.mode = MainActivity.Mode.HOME; app.track = 0; scroll(-1); scroll(-1);
            check(app.track == 0, "Default mouse scroll waits for three notches"); scroll(-1);
            check(app.track == 1 && app.mode == MainActivity.Mode.HOME, "Third notch changes the track");
            app.mode = MainActivity.Mode.MENU; app.selection = 0; rotate();
            check(app.selection == 2, "One-radian drag advances two default menu items"); app.cancel();
        });
        AtomicReference<Boolean> opened = new AtomicReference<>(false);
        ActivityMonitor picker = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()) && "audio/*".equals(intent.getType())) {
                    opened.set(true); return new ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        addMonitor(picker);
        main(() -> root("Import sample")); check(opened.get(), "Add sample import opens the audio browser"); removeMonitor(picker);
        main(() -> app.mode = MainActivity.Mode.HOME);
    }

    private void flagSettings() {
        main(() -> {
            Arrays.fill(app.secretOn, false); Arrays.fill(app.secretUnlocked, false);
            app.batteries.found = false;
            app.mode = MainActivity.Mode.SYSTEM;
            check(app.systemItems().length == 4, "Flags stay hidden in the default order");
            app.sequencer.swapPages(0, 7); app.sequencer.swapPages(1, 13);
            check(app.systemItems().length == 5 && app.systemItems()[4].equals("Trans flag / Off"), "Blue then pink reveals the trans flag setting");
            app.sequencer.swapPages(1, 13);
            check(app.systemItems().length == 4, "The setting hides again until it has been turned on");
            app.sequencer.swapPages(1, 13);
            app.selection = 4; app.confirm();
            check(app.secretOn[MainActivity.SECRET_TRANS] && app.secretUnlocked[MainActivity.SECRET_TRANS], "Turning the trans flag on unlocks it");
            app.confirm(); check(!app.secretOn[MainActivity.SECRET_TRANS] && app.secretUnlocked[MainActivity.SECRET_TRANS], "Turning it off keeps the unlock");
            app.confirm();
            app.sequencer.swapPages(1, 13); app.sequencer.swapPages(0, 7);
            check(app.systemItems()[4].equals("Trans flag / On"), "The trans flag setting stays after reordering");
            for (int p = 0; p < 8; p++) app.sequencer.swapPages(p, p + 8);
            check(app.systemItems().length == 6 && app.systemItems()[5].equals("Pride flag / Off"), "Moving every page reveals the pride flag setting");
            app.selection = 5; app.confirm();
            check(app.secretOn[MainActivity.SECRET_PRIDE] && app.secretUnlocked[MainActivity.SECRET_PRIDE] && app.secretOn[MainActivity.SECRET_TRANS], "Turning the pride flag on unlocks it");
            for (int p = 0; p < 8; p++) app.sequencer.swapPages(p, p + 8);
            check(app.systemItems().length == 6, "The pride flag setting stays after reordering");
            int blue = -1, yellow = -1;
            for (int p = 0; p < 16; p++) { if (app.sequencer.page(p).color == 0xff2fa8f0) blue = p; if (app.sequencer.page(p).color == 0xffffd93d) yellow = p; }
            app.sequencer.swapPages(0, blue);
            for (int p = 0; p < 16; p++) if (app.sequencer.page(p).color == 0xffffd93d) yellow = p;
            app.sequencer.swapPages(1, yellow);
            check(Arrays.asList(app.systemItems()).contains("Swedish flag / Off"), "Blue 09 then yellow 03 on the first pages reveals the Swedish flag");
            app.instrument.invalidate();
            Arrays.fill(app.secretOn, false); Arrays.fill(app.secretUnlocked, false);
            android.content.SharedPreferences.Editor clean = app.getSharedPreferences("tapedecksampler", 0).edit();
            for (String key : new String[]{"transFlag", "prideFlag", "swedenFlag", "anarchy", "caseColors", "sneakyGuy"}) clean.remove(key).remove(key + "Unlocked");
            clean.apply();
            app.mode = MainActivity.Mode.HOME; app.selection = 0;
        });
    }

    private void batteryBay() {
        main(() -> {
            BatteryBay bay = app.batteries;
            boolean found = bay.found, use = bay.useBatteries, open = bay.lidOpen;
            int switches = bay.switches;
            app.mode = MainActivity.Mode.MENU; app.selection = MainActivity.MENU_BACK; app.confirm();
            check(app.mode == MainActivity.Mode.BACK, "Check the back is in the main menu");
            bay.lidChangedAt = 0; bay.lidOpen = false;
            app.instrument.backTap(200, 700);
            check(bay.lidOpen && bay.found, "Pressing the battery lid opens the compartment");
            bay.lidChangedAt = 0;
            for (int i = 0; i < BatteryBay.SLOTS; i++) bay.changedAt[i] = 0;
            app.sequencer.play();
            app.batteryPress(0);
            check(!app.powered() && !app.sequencer.isPlaying(), "Removing a battery turns the device off");
            app.cancel();
            check(app.mode == MainActivity.Mode.MENU && !app.powered(), "The front stays off without batteries");
            app.showBack();
            check(app.mode == MainActivity.Mode.BACK, "Pressing the dead front flips to the back");
            app.caseSwitch(1); app.caseSwitch(2);
            check(bay.switches == 1 && app.secretUnlocked[MainActivity.SECRET_COLORS], "Only red on recolors the case and counts as a secret");
            app.batteryPress(0);
            check(app.powered(), "Reinstalling the battery restores power");
            bay.useBatteries = true;
            for (int i = 0; i < BatteryBay.SLOTS; i++) bay.charge[i] = 0.0001f;
            check(bay.low() && bay.drain(60_000) && !app.powered(), "Batteries run out and turn the device off");
            app.batteryPress(2);
            check(bay.brand[2] != bay.brand[0] && bay.charge[2] == 1f, "Used batteries are replaced with a new brand");
            bay.useBatteries = use; bay.found = found; bay.switches = switches; bay.lidOpen = open;
            for (int i = 0; i < BatteryBay.SLOTS; i++) { bay.installed[i] = true; bay.charge[i] = 1f; }
            bay.save();
            secretReset("caseColors");
            app.mode = MainActivity.Mode.HOME; app.selection = 0; app.instrument.invalidate();
        });
    }

    private void secretReset(String key) {
        app.secretUnlocked[MainActivity.SECRET_COLORS] = false; app.secretOn[MainActivity.SECRET_COLORS] = false;
        app.getSharedPreferences("tapedecksampler", 0).edit().remove(key).remove(key + "Unlocked").apply();
    }

    private void pages() throws Exception {
        main(() -> {
            app.sequencer.stop(); app.mode = MainActivity.Mode.HOME; app.track = 0;
            app.sequencer.selectPage(0); app.sequencer.tracks[0].steps = 0x9001;
            app.sequencer.editStep(0, 0).reverb = 23;
            tap(200, InstrumentView.WHEEL_Y + 104);
            check(app.mode == MainActivity.Mode.PAGES && app.pageCursor == 0, "Wheel bottom opens pages on the current page");
            app.wheel(100); check(app.pageCursor == Sequencer.PAGE_COUNT - 1, "Page highlight stops at the last page"); app.wheel(-100);
            app.wheelSide(1); check(app.pageNote == MainActivity.PAGE_NOTE_NONE, "Right side does nothing in pages");
            app.wheelSide(-1);
            check(app.pageClipboard != null && app.pageClipboard.steps[0] == 0x9001 && app.pageClipboardSource == 0, "Copy snapshots the highlighted page");
            int original = app.sequencer.page(3).steps[0];
            check(app.pageCursor == 1, "Copy moves the paste target to the next page");
            app.wheel(2); check(app.mode == MainActivity.Mode.PAGES && app.pageCursor == 3 && app.sequencer.page(3).steps[0] == original, "Turning picks the paste target");
            app.confirm();
            check(app.sequencer.page(3).steps[0] == 0x9001 && app.sequencer.page(3).padEffects[0][0].reverb == 23 && app.pageNote == MainActivity.PAGE_NOTE_PASTED, "Paste includes every pad override");
            app.wheel(1); app.confirm();
            check(app.sequencer.page(4).steps[0] != 0x9001 && app.pageClipboard == null, "Pasting empties the clipboard");
            app.wheel(-1); int color = app.sequencer.page(3).color; app.wheelSide(1);
            check(app.moveSource == 3, "Move picks up the highlighted page");
            app.cancel(); check(app.mode == MainActivity.Mode.PAGES && app.moveSource == -1, "Return cancels a started move");
            app.wheelSide(1); app.wheel(4); app.wheelSide(1);
            check(app.moveSource == -1 && app.sequencer.page(7).color == color && app.pageNote == MainActivity.PAGE_NOTE_MOVED, "Move swaps two pages");
            app.sequencer.page(7).enabled = false; app.pad(7);
            check(app.mode == MainActivity.Mode.PAGES && app.sequencer.page(7).enabled, "A pad press lights an off page first");
        });
        SystemClock.sleep(1200);
        main(() -> {
            check(app.mode == MainActivity.Mode.HOME && app.sequencer.selectedPage() == 7 && app.sequencer.page(7).enabled, "Then the page opens");
            app.wheelPages();
            touch(MotionEvent.ACTION_DOWN, 337, InstrumentView.PAD_TOP + InstrumentView.PAD_PITCH + 25);
        });
        SystemClock.sleep(520);
        main(() -> {
            touch(MotionEvent.ACTION_UP, 337, InstrumentView.PAD_TOP + InstrumentView.PAD_PITCH + 25);
            check(!app.sequencer.page(7).enabled && app.sequencer.selectedPage() == 7 && app.mode == MainActivity.Mode.PAGES, "Holding a page pad disables it without navigating");
            touch(MotionEvent.ACTION_DOWN, 337, InstrumentView.PAD_TOP + InstrumentView.PAD_PITCH + 25);
        });
        SystemClock.sleep(520);
        main(() -> {
            touch(MotionEvent.ACTION_UP, 337, InstrumentView.PAD_TOP + InstrumentView.PAD_PITCH + 25);
            check(app.sequencer.page(7).enabled, "Holding a page pad again enables it");
            app.togglePage(7);
            app.cancel(); app.save();
        });
        restart();
        main(() -> {
            check(app.sequencer.selectedPage() == 7 && !app.sequencer.page(7).enabled, "Edited page and enabled states survive restart");
            check(app.sequencer.tracks[0].steps == 0x9001 && app.sequencer.tracks[0].padEffects[0].reverb == 23, "Page patterns and effects survive restart");
            check(app.sequencer.page(0).padEffects[0][0].reverb == 23, "Loading other pages preserves first-page effects");
            app.sequencer.selectPage(0); app.save();
        });
    }

    private void trackHolds() throws Exception {
        main(() -> {
            app.mode = MainActivity.Mode.HOME; app.sequencer.stop(); app.track = 1;
            app.sequencer.tracks[0].mute = false; app.save();
            touch(MotionEvent.ACTION_DOWN, 37, InstrumentView.TRACK_Y);
        });
        SystemClock.sleep(520);
        main(() -> {
            touch(MotionEvent.ACTION_UP, 37, InstrumentView.TRACK_Y);
            check(app.sequencer.tracks[0].mute && app.track == 1 && !app.sequencer.isPlaying(), "Track hold mutes without selecting or auditioning the track");
            touch(MotionEvent.ACTION_DOWN, 37, InstrumentView.TRACK_Y);
        });
        SystemClock.sleep(520);
        main(() -> {
            touch(MotionEvent.ACTION_UP, 37, InstrumentView.TRACK_Y);
            check(!app.sequencer.tracks[0].mute, "A second hold unmutes the track");
            touch(MotionEvent.ACTION_DOWN, 37, InstrumentView.TRACK_Y);
            touch(MotionEvent.ACTION_CANCEL, 37, InstrumentView.TRACK_Y);
        });
        SystemClock.sleep(520);
        float[] gain = new float[1];
        main(() -> {
            check(!app.sequencer.tracks[0].mute, "Cancelled track holds do not toggle mute");
            app.track = 0; app.save(); effectsMenu();
            check(!Arrays.asList(app.effectItems()).contains("Mute") && !Arrays.asList(app.effectItems()).contains("Unmute"), "Track effects no longer contains mute");
            gain[0] = app.sequencer.tracks[0].gain;
            effectParameter(MainActivity.FX_VOLUME); app.wheel(-10); app.toggleTrackMute(0);
            try {
                org.json.JSONArray stored = new org.json.JSONArray(app.getSharedPreferences(app.session.id, 0).getString("tracks", "[]"));
                check(stored.getJSONObject(0).getBoolean("mute") && Math.abs(stored.getJSONObject(0).getDouble("gain") - gain[0]) < .001, "Saving mute does not commit an unfinished effect edit");
            } catch (Exception e) { throw new AssertionError(e); }
            app.cancel();
            check(app.sequencer.tracks[0].mute && Math.abs(app.sequencer.tracks[0].gain - gain[0]) < .001, "Cancelling an edit retains the newly toggled mute");
        });
        restart();
        main(() -> {
            check(app.sequencer.tracks[0].mute && Math.abs(app.sequencer.tracks[0].gain - gain[0]) < .001, "Track mute persists independently of cancelled drafts");
            app.toggleTrackMute(0);
        });
    }

    private void swing() {
        int original = app.sequencer.swing();
        main(() -> {
            root("Tempo"); app.cancel();
            check(app.mode == MainActivity.Mode.TEMPO && app.tempoItems().length == 2, "Tempo offers BPM and swing");
            app.selection = 1; app.confirm(); app.wheel(65 - app.sequencer.swing()); app.confirm();
            check(app.sequencer.swing() == 65 && app.mode == MainActivity.Mode.TEMPO && app.selection == 1, "Select saves swing and returns to Tempo");
        }); restart();
        main(() -> {
            check(app.sequencer.swing() == 65, "Swing persists with the project");
            app.sequencer.swing(original); app.save();
        });
    }

    private void sync() throws Exception {
        main(() -> {
            root("Sync");
            check(app.mode == MainActivity.Mode.SYNC_CLAP && app.syncEnabled, "Sync opens the clap screen directly");
            app.cancel();
            check(app.mode == MainActivity.Mode.MENU && !app.syncEnabled && app.clapClock == null, "Leaving an unfinished clap turns sync off");
            check(Arrays.equals(app.syncItems(), new String[]{"Re-sync", "Turn sync off"}), "An established sync offers re-sync or off");
        });
        restart();
        main(() -> check(!app.syncEnabled && app.clapClock == null, "A fresh app launch starts with sync off"));
    }

    private void timeline() {
        main(() -> {
            slice(); app.zoom = false; app.toggleZoom(); int a = app.sequencer.tracks[app.track].start, b = app.sequencer.tracks[app.track].end;
            long revision = app.tape.revision();
            check(app.timelineSpan() == Tape.RATE * 10, "Slice starts at ten-second zoom"); zoomIn();
            check(app.timelineSpan() == Tape.RATE * 8 && app.timelineIncrement() == Tape.RATE * 80 / 1000, "Zoom and movement precision follow each other");
            app.cancel(); slice();
            check(app.timelineSpan() == Tape.RATE * 8, "Returning through the menus preserves timeline zoom");
            for (int i = 0; i < 22; i++) zoomIn();
            check(app.timelineIncrement() == 48, "Close zoom reaches millisecond movement");
            app.wheelSide(1);
            long framed = app.timelineSpan(), from = TimelineWindow.start(Tape.LENGTH, framed, app.end, app.start, app.end);
            check(from <= app.start && from + framed >= app.end && framed > app.end - app.start, "Wheel boundary selection fits the whole clip with context");
            app.toggleZoom();
            check(app.timelineSpan() == Tape.LENGTH && app.timelineIncrement() == Tape.RATE * 200 / 1000, "Full tape uses larger steps");
            app.toggleFine(); check(app.timelineIncrement() == 48, "Fine override remains available"); app.toggleFine();
            app.start = 0; app.end = Tape.RATE * 2; app.cursor = 0;
            play(); check(app.sequencer.isPreviewing() && !app.sequencer.isPlaying(), "Timeline plays only the uncommitted selection");
            play(); check(app.sequencer.hasSelectionPreview() && !app.sequencer.isPreviewing(), "Selection pauses");
            play(); check(app.sequencer.isPreviewing(), "Selection resumes");
            app.wheel(1); check(!app.sequencer.hasSelectionPreview(), "Boundary changes clear preview"); app.cancel();
            check(app.sequencer.tracks[app.track].start == a && app.sequencer.tracks[app.track].end == b, "Cancel restores the original live slice");
            check(app.tape.revision() == revision, "Slicing never writes the tape"); app.cancel();
            root("Tempo"); int bpm = app.sequencer.bpm(); app.wheel(20); app.cancel();
            check(app.sequencer.bpm() == bpm, "Tempo changes cancel"); app.cancel();
        });
    }

    private void imports() throws Exception {
        File source = new File(getTargetContext().getCacheDir(), "long-source.wav"); int frames = Tape.RATE * 90;
        byte[] header = Tape.header(); java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, frames * 2 + 36).putInt(40, frames * 2);
        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write(header); byte[] block = new byte[8192];
            for (int position = 0; position < frames;) {
                int count = Math.min(block.length / 2, frames - position);
                for (int i = 0; i < count; i++, position++) {
                    short value = position >= Tape.RATE * 70 && position < Tape.RATE * 73 ? (short) 12345 : (short) 1600;
                    block[i * 2] = (byte) value; block[i * 2 + 1] = (byte) (value >> 8);
                }
                out.write(block, 0, count * 2);
            }
        }
        main(() -> app.decode(Uri.fromFile(source), "long-source.wav")); ready();
        main(() -> {
            check(app.mode == MainActivity.Mode.IMPORT_START && app.importEnd == Tape.RATE * 5L, "Import defaults to a five-second selection");
            check(app.importSource.frames == frames, "Sources can extend beyond sixty seconds");
            app.importStart = Tape.RATE * 70L; app.importEnd = Tape.RATE * 72L + Tape.RATE / 2; play();
        }); ready();
        main(() -> {
            check(app.sequencer.isPreviewing() && app.imported == null, "Source selection previews before import");
            play(); check(!app.sequencer.isPreviewing() && app.sequencer.hasSelectionPreview(), "Source preview pauses");
            play(); check(app.sequencer.isPreviewing() && !app.busy, "Source preview resumes without decoding again");
            app.confirm(); check(!app.sequencer.hasSelectionPreview(), "Navigation stops audition");
            app.zoom = false; app.toggleZoom(); zoomIn(); long before = app.importEnd; app.wheel(-1);
            check(before - app.importEnd == Tape.RATE * 80 / 1000, "Source trimming follows zoom precision"); app.wheel(1); app.confirm();
        }); ready();
        main(() -> {
            check(app.mode == MainActivity.Mode.IMPORT_POSITION && app.imported.length == Tape.RATE * 5 / 2 && app.imported[0] == 12345, "Only the selected source segment is decoded");
            play(); check(app.sequencer.isPreviewing() && !app.sequencer.isPlaying(), "Placement previews the incoming clip"); play();
            app.wheel(app.cursor == 0 ? 1 : -1); check(!app.sequencer.hasSelectionPreview(), "Moving placement clears preview");
            app.wheel(100000); check(app.cursor + app.imported.length == Tape.LENGTH, "Clip fits fully at the tape end");
            app.cursor = Tape.RATE * 10; app.confirm();
        }); ready();
        main(() -> {
            check(app.tape.samples[Tape.RATE * 10] == 12345 && app.tape.samples[Tape.RATE * 25 / 2 - 1] == 12345, "Import overwrites exactly its selected span");
            check(app.regions.snapshot().stream().anyMatch(r -> r.start == Tape.RATE * 10 && r.end == Tape.RATE * 25 / 2), "Import saves sample colors and boundaries");
        });
    }

    private void deviceCaptureImport() throws Exception {
        long revision = app.tape.revision();
        short[] sample = new short[Tape.RATE * 7]; Arrays.fill(sample, (short) -4321);
        try (CaptureFile capture = new CaptureFile(DeviceCaptureService.path(getTargetContext()))) { capture.write(sample, sample.length); }
        getTargetContext().getSharedPreferences("deviceCapture", Context.MODE_PRIVATE).edit().putBoolean("ready", true).putString("error", "").commit();
        getTargetContext().sendBroadcast(new Intent(DeviceCaptureService.CHANGED).setPackage(getTargetContext().getPackageName()));
        await(() -> !app.busy && app.mode == MainActivity.Mode.IMPORT_START && app.importName.equals("Device recording"), 6000);
        main(() -> {
            check(app.importEnd == sample.length && app.tape.revision() == revision, "Device capture selects its complete recording without touching the tape");
            app.confirm(); app.confirm();
        }); ready();
        main(() -> {
            check(app.mode == MainActivity.Mode.IMPORT_POSITION && app.imported.length == sample.length, "Device capture uses the sample placement flow");
            app.cursor = Tape.RATE * 10; app.confirm();
        }); ready();
        main(() -> check(app.tape.samples[Tape.RATE * 10] == -4321 && app.tape.samples[Tape.RATE * 17 - 1] == -4321, "Placing device audio writes the selected destination range"));
    }

    private void padPitch() throws Exception {
        short[] source = new short[Tape.RATE * 2];
        for (int i = 0; i < source.length; i++) source[i] = (short) Math.round(10000 * Math.sin(2 * Math.PI * 430 * i / Tape.RATE));
        app.tape.write(0, source, source.length);
        main(() -> {
            app.mode = MainActivity.Mode.HOME; app.selectTrack(0); app.sequencer.stop(); app.sequencer.resetEffects(0);
            app.sequencer.setSlice(0, 0, Tape.RATE / 5);
            app.sequencer.tracks[0].pitch = -1;
            app.sequencer.tracks[0].power(Sequencer.Effects.AUTOTUNE, true);
            app.sequencer.tracks[0].steps = 0xffff;
        });
        app.sequencer.prepareAll();
        main(() -> { play(); app.openStepEffects(4); effectParameter(MainActivity.FX_PITCH); app.wheel(2); });
        liveReady();
        for (int i = 0; i < 40; i++) {
            int delta = i % 2 == 0 ? -2 : 2;
            main(() -> {
                check(app.sequencer.isPlaying(), "Playback continues during pad Pitch edits");
                check(app.sequencer.tracks[0].pitch == -1, "Pad Pitch leaves track Pitch unchanged");
                app.wheel(delta);
            });
            liveReady(); SystemClock.sleep(250);
        }
        main(() -> {
            check(app.sequencer.tracks[0].padEffects[4].pitch == 1, "Pad retains Pitch +1");
            check(app.sequencer.tracks[0].padEffects[4].hasAutotune(), "Pad retains inherited Auto Tune");
            app.confirm();
        });
        ready(); SystemClock.sleep(15000);
        main(() -> { check(app.sequencer.isPlaying(), "Playback continues after committing pad Pitch"); app.sequencer.stop(); });
    }

    private void effects() {
        main(() -> {
            app.mode = MainActivity.Mode.HOME; app.selectTrack(0); app.sequencer.resetEffects(0); app.sequencer.tracks[0].mute = false;
            app.sequencer.setSlice(0, 0, Tape.RATE); app.sequencer.tracks[0].steps = 1; app.sequencer.play();
            slice(); int before = app.sequencer.tracks[0].start; app.wheel(1);
            check(app.sequencer.tracks[0].start == app.start && app.start != before && app.sequencer.isPlaying(), "Slice changes reach the running sequencer before Select");
            app.cancel(); check(app.sequencer.tracks[0].start == before, "Live slice changes cancel");
            effectsMenu();
            check(app.effectItems().length == 1 && app.effectItems()[0].equals("Add effect"), "Track effects opens blank with Add effect");
            java.util.HashSet<Integer> parameters = new java.util.HashSet<>();
            for (int group = 0; group < MainActivity.FX_GROUP_PARAMETERS.length; group++) {
                check(MainActivity.FX_GROUP_PARAMETERS[group].length == MainActivity.FX_GROUP_LABELS[group].length, "Effect group labels match their controls");
                for (int parameter : MainActivity.FX_GROUP_PARAMETERS[group]) check(parameters.add(parameter), "Each effect appears in exactly one menu");
            }
            check(parameters.size() == MainActivity.FX_LABELS.length, "Grouped effects retain every control");
            app.confirm();
            check(app.mode == MainActivity.Mode.FX_ADD && !Arrays.asList(app.addItems()).contains("Reset track effects"), "Add effect lists only effects on a track");
            addEffect(Sequencer.Effects.REVERB); addEffect(Sequencer.Effects.DELAY);
            addGroup(Sequencer.Effects.CHANCE);
            check(app.mode == MainActivity.Mode.FX_VALUE && app.effectParameter == MainActivity.FX_CHANCE, "Adding a one-value effect opens that value");
            app.wheel(-5); app.cancel();
            check(app.effectStackPage() && app.effectStack()[app.selection] == Sequencer.Effects.CHANCE && app.sequencer.tracks[0].chance == 50, "Backing out keeps the effect and selects it");
            app.wheelSide(-1);
            addGroup(Sequencer.Effects.CHANCE); app.wheel(-5); app.confirm();
            check(app.effectStackPage() && app.effectStack()[app.selection] == Sequencer.Effects.CHANCE && app.sequencer.tracks[0].chance == 45, "Applying returns with the effect selected");
            app.wheelSide(-1);
            addGroup(Sequencer.Effects.ENVELOPE);
            check(app.mode == MainActivity.Mode.EFFECTS && app.effectGroup == Sequencer.Effects.ENVELOPE && app.selection == 0, "Adding a multi-value effect opens its value list");
            app.cancel();
            check(app.effectStackPage() && app.effectStack()[app.selection] == Sequencer.Effects.ENVELOPE, "Returning selects the new effect");
            app.wheelSide(-1);
            addEffect(Sequencer.Effects.AUTOTUNE);
            check(Arrays.equals(app.effectStack(), new int[]{Sequencer.Effects.AUTOTUNE, Sequencer.Effects.REVERB, Sequencer.Effects.DELAY}) && app.selection == 0, "Added effects appear in processing order and select their row");
            app.selection = 0; app.confirm();
            check(app.effectGroup == 1 && app.effectItems().length == 6 && app.effectItems()[0].startsWith("Key /") && app.effectItems()[5].equals("Confirm"), "Selecting an effect lists only its values");
            app.cancel(); check(app.effectStackPage() && app.selection == 0, "Return selects the parent effect");
            app.confirm(); app.selection = app.effectItems().length - 1; app.confirm();
            check(app.effectStackPage() && app.selection == 0, "Confirm exits an effect and keeps it selected");
            app.wheelPages(); check(app.mode == MainActivity.Mode.FX_OPTIONS, "Wheel bottom opens effect options");
            app.selection = MainActivity.OPTION_ORDER; app.confirm(); check(app.mode == MainActivity.Mode.FX_REORDER, "Options opens reorder mode");
            app.confirm(); check(app.effectHeld < 0, "Pinned effects cannot be picked up");
            check(MainActivity.FX_LOCK_REASONS[Sequencer.Effects.AUTOTUNE].equals(app.instrument.lockNotice()) && MainActivity.lockTitle(Sequencer.Effects.AUTOTUNE).equals("ALWAYS FIRST"), "Pinned effects explain why they cannot move");
            for (int group = 0; group < Sequencer.Effects.GROUPS; group++) check((MainActivity.FX_LOCK_REASONS[group] == null) == Sequencer.Effects.movable(group), "Every fixed effect has a reason");
            app.selection = 2; app.confirm(); check(app.effectHeld == Sequencer.Effects.DELAY, "Select picks up a movable effect");
            app.wheel(-1);
            check(Arrays.equals(app.effectStack(), new int[]{Sequencer.Effects.AUTOTUNE, Sequencer.Effects.DELAY, Sequencer.Effects.REVERB}) && app.selection == 1, "Turning moves the held effect and its selection");
            app.wheel(-5); check(app.effectStack()[0] == Sequencer.Effects.AUTOTUNE && app.selection == 1, "Movable effects cannot pass pinned ones");
            app.confirm(); app.wheelMenu();
            check(app.effectStackPage() && app.effectHeld < 0, "Return leaves reorder mode");
            app.wheelSide(-1); app.wheelSide(-1);
            check(app.effectStack().length == 1 && app.effectStack()[0] == Sequencer.Effects.AUTOTUNE && app.sequencer.tracks[0].reverb == 0, "Wheel left deletes the selected effect");
            app.wheelSide(1); app.wheelSide(1);
            check(Arrays.equals(app.effectStack(), new int[]{Sequencer.Effects.AUTOTUNE, Sequencer.Effects.DELAY, Sequencer.Effects.REVERB}) && app.sequencer.tracks[0].reverb == 25 && app.selection == 1, "Wheel right undoes deletions in their original positions");
            app.selection = app.effectStack().length; app.wheelSide(-1);
            check(app.effectStack().length == 3, "Delete ignores the Add effect row");
            app.selection = 0; app.wheelSide(-1); app.selection = 0; app.wheelSide(-1);
            int globalSwing = app.sequencer.swing(); app.sequencer.swing(65); app.sequencer.tracks[0].swingOverride = 0;
            effectParameter(MainActivity.FX_SWING);
            check(app.effectDisplay(MainActivity.FX_SWING, app.effectValue).equals("65%"), "Track Swing displays the inherited global value");
            check(app.effectValue == 65, "Swing editor starts at the effective global value");
            app.wheel(1); check(app.sequencer.tracks[0].swingOverride == 66, "Swing adjusts directly from the global value");
            app.cancel();
            app.selection = 0; app.wheelSide(-1);
            check(app.sequencer.tracks[0].swingOverride == 0 && app.sequencer.tracks[0].nudge == 0, "Deleting Swing returns to the global value");
            app.sequencer.swing(globalSwing);
            effectParameter(MainActivity.FX_ATTACK); app.wheel(3);
            check(app.effectValue == 3, "Attack moves in 1 ms steps"); app.cancel();
            effectParameter(MainActivity.FX_DECAY); app.wheel(3);
            check(app.effectValue == 3, "Decay moves in 1 ms steps"); app.cancel();
            effectParameter(MainActivity.FX_RELEASE); app.wheel(3);
            check(app.effectValue == 65, "Release keeps 5 ms steps"); app.cancel();
            app.cancel(); app.selection = 0; app.wheelSide(-1);
            effectParameter(MainActivity.FX_EVERY); check(app.effectValue == 2 && app.effectDisplay(MainActivity.FX_EVERY, 3).equals("3rd"), "Every Nth opens at 2nd"); app.wheel(100);
            check(app.effectValue == 16, "Every Nth stops at 16th"); app.cancel();
            app.selection = 0; app.wheelSide(-1);
            effectParameter(MainActivity.FX_CHANCE); check(app.effectValue == 50, "Chance opens at 50%"); app.cancel();
            app.selection = 0; app.wheelSide(-1);
            effectParameter(MainActivity.FX_WOBBLE_RATE);
            check(app.effectValue == 50 && app.effectDisplay(MainActivity.FX_WOBBLE_RATE, 55).equals("5.5 Hz"), "Wobble rate starts at 5 Hz");
            app.wheel(2); check(app.effectValue == 60, "Wobble rate moves in half hertz steps"); app.cancel();
            app.cancel(); app.selection = 0; app.confirm();
            check(app.effectGroup == Sequencer.Effects.WOBBLE && app.effectItems().length == 4 && app.effectItems()[0].equals("Mode / Volume") && app.effectItems()[3].equals("Confirm"), "Wobble lists mode, rate and depth");
            app.confirm(); app.wheel(5); check(app.effectValue == 2 && app.sequencer.tracks[0].wobbleMode == 2 && app.effectDisplay(MainActivity.FX_WOBBLE_MODE, 2).equals("Speed"), "Wobble mode stops at Speed"); app.cancel();
            app.cancel(); app.selection = 0; app.wheelSide(-1);
            effectParameter(MainActivity.FX_SPEED); app.wheel(-25);
            check(app.sequencer.tracks[0].speed == 75 && app.mode == MainActivity.Mode.FX_VALUE && !app.busy, "Live speed keeps the editor responsive");
        }); liveReady();
        main(() -> {
            check(!app.sequencer.needsPreparation() && app.mode == MainActivity.Mode.FX_VALUE && app.sequencer.isPlaying(), "Speed processing completes before Select while the beat runs");
            app.cancel(); check(app.sequencer.tracks[0].speed == 100, "Live speed cancels");
            check(app.effectStackPage() && app.effectStack()[app.selection] == Sequencer.Effects.SAMPLE, "Cancelling Speed returns to the effect list");
            effectParameter(MainActivity.FX_REVERB); app.wheel(10); check(app.sequencer.tracks[0].reverb == 35, "Reverb updates live"); app.cancel();
            check(app.sequencer.tracks[0].reverb == 25, "Reverb cancels back to its enabled setting");
            app.sequencer.tracks[0].reverb = 0; app.sequencer.tracks[0].power(Sequencer.Effects.REVERB, false);
            effectParameter(MainActivity.FX_VOLUME); app.wheel(-20); check(Math.round(app.sequencer.tracks[0].gain * 100) == 60, "Volume is inside Track effects"); app.cancel();
            app.cancel(); app.cancel();
            touch(MotionEvent.ACTION_DOWN, 64, InstrumentView.PAD_TOP + InstrumentView.PAD_PITCH + 25);
        });
        SystemClock.sleep(520);
        main(() -> {
            touch(MotionEvent.ACTION_UP, 64, InstrumentView.PAD_TOP + InstrumentView.PAD_PITCH + 25);
            check(app.mode == MainActivity.Mode.EFFECTS && app.stepTarget == 4 && app.sequencer.tracks[0].steps == 1, "Holding pad 05 opens its effects without toggling the beat");
            check(!Arrays.asList(app.addItems()).contains("Swing") && !Arrays.asList(app.addItems()).contains("Nudge"), "Pad effects omit track timing");
            effectParameter(MainActivity.FX_REVERB); app.wheel(45); app.confirm();
            check(app.effectStackPage() && app.effectStack()[app.selection] == Sequencer.Effects.REVERB, "Saving a single-value effect returns to the effect list");
            check(app.sequencer.tracks[0].padEffects[4].reverb == 45 && app.sequencer.tracks[0].padEffects[3] == null && app.sequencer.tracks[0].reverb == 0, "Pad effects are isolated from the track and neighboring pads");
            effectParameter(MainActivity.FX_SPEED); app.wheel(-40);
        }); liveReady();
        main(() -> { app.confirm(); app.save(); }); ready(); restart();
        main(() -> {
            check(app.sequencer.tracks[0].padEffects[4] != null && app.sequencer.tracks[0].padEffects[4].speed == 60 && app.sequencer.tracks[0].padEffects[4].reverb == 45, "Pad effects survive restart");
            check(app.sequencer.tracks[0].padEffects[4].powered(Sequencer.Effects.REVERB) && !app.sequencer.tracks[0].powered(Sequencer.Effects.REVERB), "Track and pad power states survive restart independently");
            effectsMenu(); app.sequencer.resetEffects(0);
            check(app.sequencer.tracks[0].padEffects[4] == null && app.sequencer.tracks[0].speed == 100 && app.effectStackPage(), "Track reset clears all pad effects"); app.wheelMenu(); check(app.mode == MainActivity.Mode.MENU, "Wheel top returns from the effects page"); app.cancel();
            app.sequencer.tracks[0].every = 3; app.mode = MainActivity.Mode.EXPORT; app.selection = 1; app.confirm();
            check(app.mode == MainActivity.Mode.EXPORT_LOOPS && app.selection == 1 && app.exportLoopItems()[0].equals("One loop") && app.exportLoopItems()[1].equals("All pads / 3 loops"), "WAV export offers a loop choice when pads play later");
            app.cancel(); check(app.mode == MainActivity.Mode.EXPORT && app.selection == 1, "Return leaves the loop choice");
            app.sequencer.tracks[0].every = 1; app.mode = MainActivity.Mode.HOME;
            effectsMenu(); effectParameter(MainActivity.FX_VOLUME); app.wheel(-10);
        });
        backgroundAndResume();
        main(() -> {
            check(app.mode == MainActivity.Mode.EFFECTS && Math.round(app.sequencer.tracks[0].gain * 100) == 80, "Backgrounding cancels an effect draft and returns to its parent");
            slice(); app.wheel(1);
        });
        backgroundAndResume();
        main(() -> check(app.mode == MainActivity.Mode.HOME && app.sequencer.tracks[0].start == 0, "Backgrounding restores slice bounds and returns Home"));
    }

    private void backgroundAndResume() {
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME), true);
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME), true);
        SystemClock.sleep(150);
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        await(() -> app.hasWindowFocus(), 6000); ready();
    }

    private void settings() {
        main(() -> {
            app.wheelSensitivity = 100; root("System"); app.selection = 1; app.confirm(); app.wheel(-10);
            check(app.mode == MainActivity.Mode.SENSITIVITY && app.wheelSensitivity == 50, "Sensitivity adjusts in five-percent steps");
            app.cancel(); check(app.wheelSensitivity == 100 && app.selection == 1, "Sensitivity cancel restores the old value");
            app.confirm(); app.wheel(1000); check(app.wheelSensitivity == 200, "Sensitivity has an upper bound");
            app.wheel(-1000); check(app.wheelSensitivity == 25, "Sensitivity cannot disable the wheel"); app.cancel();
            app.mode = MainActivity.Mode.MENU; app.selection = 0; app.wheelSensitivity = 50; rotate(); check(app.selection == 1, "Lower sensitivity reduces drag movement");
            app.selection = 0; app.wheelSensitivity = 200; rotate(); check(app.selection == 5, "Higher sensitivity increases drag movement");
            app.mode = MainActivity.Mode.MENU; app.selection = 0; app.wheelSensitivity = 50;
            scroll(-5); check(app.selection <= 1, "Lower sensitivity slows mouse scrolling");
            app.wheelSensitivity = 200; scroll(-3); check(app.selection >= 2, "Higher sensitivity also applies to the mouse");
            app.wheelSensitivity = 100; root("System"); app.selection = 1; app.confirm(); app.wheel(-5); app.confirm();
            check(app.getSharedPreferences("tapedecksampler", 0).getInt("wheelSensitivity", 0) == 75, "Sensitivity is saved globally");
            boolean old = app.darkTheme; app.selection = 2; app.confirm(); check(app.darkTheme != old, "Dark theme toggles");
            boolean oldHand = app.leftHandMode; app.selection = 3; app.confirm();
            check(app.leftHandMode != oldHand && app.instrument.playX() == (app.leftHandMode ? InstrumentView.ZOOM_X : InstrumentView.PLAY_X) && app.instrument.zoomX() == (app.leftHandMode ? InstrumentView.PLAY_X : InstrumentView.ZOOM_X), "Left hand mode swaps button positions");
            app.mode = MainActivity.Mode.HOME; app.sequencer.stop(); play();
            check(app.sequencer.isPlaying() || app.sequencer.isSyncWaiting(), "Play responds at its swapped position");
            play(); check(!app.sequencer.isPlaying() && !app.sequencer.isSyncWaiting(), "Swapped Play pauses playback");
            app.mode = MainActivity.Mode.START; app.zoom = false; app.toggleZoom(); app.mode = MainActivity.Mode.HOME;
            long span = app.timelineSpan(); int track = app.track;
            touch(MotionEvent.ACTION_DOWN, app.instrument.zoomX(), InstrumentView.ZOOM_Y);
            app.instrument.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            touch(MotionEvent.ACTION_UP, app.instrument.zoomX(), InstrumentView.ZOOM_Y);
            check(app.timelineSpan() < span && app.track == track, "Zoom responds at its swapped position");
            root("System");
            app.selection = 0; app.confirm(); app.wheel(-100); app.confirm(); check(app.vibrationIntensity == 0, "Vibration still switches off");
        }); restart();
        main(() -> check(app.wheelSensitivity == 75 && app.darkTheme == app.getSharedPreferences("tapedecksampler", 0).getBoolean("darkTheme", false) && app.vibrationIntensity == 0, "Global settings survive restart"));
        main(() -> check(app.leftHandMode == app.getSharedPreferences("tapedecksampler", 0).getBoolean("leftHandMode", false), "Left hand mode survives restart"));
    }

    private void projectImport() throws Exception {
        File archive = new File(getTargetContext().getCacheDir(), "import-test.tapedeck.zip");
        int count = app.sessions.size();
        main(() -> {
            try (FileOutputStream raw = new FileOutputStream(archive); java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(raw)) {
                org.json.JSONObject settings = new org.json.JSONObject().put("bpm", 97).put("track", 2);
                org.json.JSONObject project = new org.json.JSONObject().put("format", "tapedecksampler-project").put("version", 1).put("name", "Imported groove").put("color", "Toxic").put("settings", settings);
                zip.putNextEntry(new java.util.zip.ZipEntry("project.json")); zip.write(project.toString().getBytes("UTF-8")); zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("tape.wav")); app.tape.export(zip); zip.closeEntry();
            } catch (Exception e) { throw new AssertionError(e); }
            app.mode = MainActivity.Mode.PROJECTS; app.importProject(Uri.fromFile(archive));
        });
        ready(); await(() -> !app.instrument.cassetteBusy() && !app.busy, 8000); ready();
        main(() -> {
            check(app.sessions.size() == count + 1 && app.session.name().equals("Imported groove"), "Import adds the project as a new tape and loads it");
            check(app.sequencer.bpm() == 97 && app.track == 2 && MainActivity.CASSETTE_NAMES[app.colorIndex(app.session)].equals("Toxic"), "Import restores settings and cassette color");
        });
        File bad = new File(getTargetContext().getCacheDir(), "bad.zip");
        try (FileOutputStream raw = new FileOutputStream(bad)) { raw.write(new byte[]{1, 2, 3}); }
        main(() -> app.importProject(Uri.fromFile(bad))); ready();
        main(() -> check(app.sessions.size() == count + 1, "Invalid files do not create tapes"));
    }

    private void sessions() throws Exception {
        SessionStore.Entry original = app.session; String label = "  Midnight groove / session with a long name  ";
        int count = app.sessions.size();
        main(() -> {
            root("Project"); app.selection = 0; app.confirm();
            check(app.mode == MainActivity.Mode.TEXT, "New session types onto the sampler screen");
            check(app.textValue.toString().equals(SessionStore.nextDefaultName(app.sessions)), "New project prefills the next default name");
            app.textBackspace(); app.typeChar(' '); app.finishText();
            check(app.mode == MainActivity.Mode.TEXT && app.sessions.size() == count, "Blank names do not create sessions");
            app.textBackspace();
            for (char letter : label.toCharArray()) app.typeChar(letter);
        });
        capture("session-name-preview.png");
        main(() -> app.finishText());
        main(() -> { check(app.mode == MainActivity.Mode.PROJECT_COLOR && app.colorForNew, "Naming a tape asks for its color"); app.confirm(); }); ready();
        SessionStore.Entry created = app.session;
        main(() -> {
            check(created.name().equals(label.trim()) && !created.id.equals(original.id), "Named sessions have independent storage");
            check(app.sequencer.tracks[0].steps == 0 && app.wheelSensitivity == 75, "New session is empty and retains global sensitivity");
            app.sequencer.tracks[0].steps = 0x8080; app.save();
        }); restart();
        main(() -> {
            check(app.session.id.equals(created.id) && app.session.name().equals(label.trim()) && app.sequencer.tracks[0].steps == 0x8080, "Name and pattern survive restart"); app.switchSession(original);
        }); ready();
        main(() -> {
            check(app.session.id.equals(original.id) && app.wheelSensitivity == 75, "Switching sessions preserves global preferences");
            app.wheelSensitivity = 100; root("System"); app.selection = 1; app.confirm(); app.confirm(); app.mode = MainActivity.Mode.HOME;
        });
    }

    private void microphone() throws Exception {
        main(() -> {
            root("Record microphone"); app.cursor = Tape.LENGTH - Tape.RATE / 5; play();
            check(app.sequencer.isPreviewing() && !app.audio.isRecording(), "Recording position previews existing tape");
            app.confirm(); check(app.mode == MainActivity.Mode.RECORDING, "Select starts microphone recording");
        });
        await(() -> app.mode != MainActivity.Mode.RECORDING, 6000);
        main(() -> check(app.recordPosition == Tape.LENGTH && app.mode == MainActivity.Mode.HOME, "Recording stops at twenty seconds"));
        File export = new File(getTargetContext().getCacheDir(), "export.wav"); try (FileOutputStream out = new FileOutputStream(export)) { app.tape.export(out); }
        check(export.length() == 1_920_044, "Export stays one twenty-second WAV");
        check(SampleDecoder.decode(getTargetContext(), Uri.fromFile(export)).length == Tape.LENGTH, "Export reimports through Android decoding");
    }

    private void textAudit() {
        WaveSummary wave = new WaveSummary(); for (int i = 0; i < Tape.RATE * 90; i++) wave.accept((short) 1000); wave.finish();
        main(() -> {
            app.audio.stop();
            SampleDecoder.Source oldSource = app.importSource; short[] oldImported = app.imported; String oldName = app.importName; boolean oldTheme = app.darkTheme;
            SessionStore.Entry oldSession = app.session;
            app.session = app.sessions.get(app.sessions.size() - 1);
            app.sequencer.stop(); app.importSource = new SampleDecoder.Source(Uri.EMPTY, wave); app.imported = new short[Tape.RATE * 5]; app.importWave = wave;
            app.importName = "Very long imported sample name with Unicode ☾ and extra characters that must be clipped safely.wav";
            app.importStart = Tape.RATE * 70L; app.importEnd = Tape.RATE * 75L; app.start = Tape.RATE * 18; app.end = Tape.LENGTH; app.cursor = Tape.LENGTH;
            app.recordStart = Tape.RATE * 19; app.recordPosition = Tape.LENGTH; app.stepTarget = -1;
            app.effectGroup = -1;
            for (int group = 0; group < Sequencer.Effects.GROUPS; group++) app.sequencer.tracks[0].power(group, true);
            for (int[] size : new int[][]{{320, 640}, {360, 800}, {400, 880}, {1080, 2400}}) {
                InstrumentView view = new InstrumentView(app); view.auditText(true); view.layout(0, 0, size[0], size[1]);
                Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap);
                for (boolean dark : new boolean[]{false, true}) {
                    app.darkTheme = dark;
                    for (MainActivity.Mode mode : MainActivity.Mode.values()) {
                        app.mode = mode;
                        String[] items = auditItems(mode); int count = items == null ? mode == MainActivity.Mode.FX_VALUE ? MainActivity.FX_LABELS.length : 1 : items.length;
                        for (int i = 0; i < count; i++) {
                            app.selection = i;
                            if (mode == MainActivity.Mode.FX_VALUE) { app.effectParameter = i; app.effectValue = auditEffectValue(i); }
                            view.draw(canvas); ArrayList<String> issues = view.textIssues();
                            check(issues.isEmpty(), "Text " + mode + " row " + i + " " + Arrays.toString(size) + ": " + issues); textLayouts++;
                        }
                    }
                    app.mode = MainActivity.Mode.HOME; app.sequencer.playSynced(); view.draw(canvas);
                    check(view.textIssues().isEmpty(), "Syncing indicator does not overlap other text"); textLayouts++; app.sequencer.stop();
                }
                bitmap.recycle();
            }
            app.sequencer.resetEffects(0);
            app.session = oldSession; app.importSource = oldSource; app.imported = oldImported; app.importWave = null; app.importName = oldName; app.darkTheme = oldTheme; app.mode = MainActivity.Mode.HOME; app.selection = 0;
            app.audio.start();
        });
    }

    private int auditEffectValue(int parameter) {
        switch (parameter) {
            case MainActivity.FX_AUTO_KEY: return PitchCorrector.KEYS.length - 1;
            case MainActivity.FX_AUTO_SCALE: return PitchCorrector.SCALES.length - 1;
            case MainActivity.FX_ROBOT_PROFILE: return RobotEffect.PROFILES.length - 1;
            case MainActivity.FX_WOBBLE_MODE: return MainActivity.WOBBLE_MODES.length - 1;
            case MainActivity.FX_DELAY_TIME: return TrackEffects.DELAY_BEATS.length - 1;
            case MainActivity.FX_DELAY_FEEDBACK: return TrackEffects.MAX_ECHOES;
            case MainActivity.FX_DISTORTION: return TrackEffects.DISTORTIONS.length;
            case MainActivity.FX_CRUSH_BITS: return TrackEffects.MAX_CRUSH_BITS;
            case MainActivity.FX_CRUSH_RATE: return TrackEffects.MAX_CRUSH_RATE;
            case MainActivity.FX_PITCH: return -12;
            case MainActivity.FX_NUDGE: return 75;
            case MainActivity.FX_WOBBLE_RATE: return 2;
            default: return 100;
        }
    }

    private void enter(String value) {
        app.textBackspace();
        while (app.textValue.length() > 0) app.textBackspace();
        for (char letter : value.toCharArray()) app.typeChar(letter);
        app.finishText();
    }

    private String[] auditItems(MainActivity.Mode mode) {
        switch (mode) {
            case SYNTH: return app.synthItems();
            case FX_OPTIONS: return app.optionItems();
            case MENU: return MainActivity.MENU;
            case ADD_SAMPLE: return MainActivity.ADD_SAMPLE;
            case SPEECH: return app.speechItems();
            case SPEECH_VOICE: return RetroSpeech.INSTANCE.getVoices();
            case SYSTEM: return app.systemItems();
            case EXPORT: return MainActivity.EXPORT_ITEMS;
            case EXPORT_LOOPS: return app.exportLoopItems();
            case SYNC: return app.syncItems();
            case EFFECTS: case FX_REORDER: return app.effectItems();
            case FX_ADD: return app.addItems();
            case TEMPO: return app.tempoItems();
            case PROJECTS: return MainActivity.PROJECT_ITEMS;
            case PROJECT_EDIT_MENU: return MainActivity.PROJECT_EDIT_ITEMS;
            case PROJECT_OPEN: case PROJECT_EDIT: return new String[app.sessions.size()];
            case PROJECT_COLOR: return MainActivity.CASSETTE_NAMES;
            default: return null;
        }
    }

    private void root(String label) {
        app.mode = MainActivity.Mode.HOME; app.confirm();
        int sample = Arrays.asList(MainActivity.ADD_SAMPLE).indexOf(label);
        app.selection = Arrays.asList(MainActivity.MENU).indexOf(sample >= 0 ? "Add sample" : label);
        if (app.selection < 0) throw new AssertionError(label);
        app.confirm();
        if (sample >= 0) { app.selection = sample; app.confirm(); }
        else if (label.equals("Tempo")) app.confirm();
    }
    private void slice() { app.mode = MainActivity.Mode.HOME; app.wheelSide(-1); }
    private void effectsMenu() { root("Track effects"); }
    private void addEffect(int group) {
        if (app.mode != MainActivity.Mode.FX_ADD) { app.selection = app.effectStack().length; app.confirm(); }
        int[] groups = app.addGroups();
        app.selection = -1;
        for (int i = 0; i < groups.length; i++) if (groups[i] == group) app.selection = i;
        check(app.selection >= 0, "Effect is available to add");
        app.confirm();
        if (app.mode == MainActivity.Mode.FX_VALUE) app.confirm(); else app.cancel();
        check(app.effectStackPage() && app.effectStack()[app.selection] == group, "Adding returns with the new effect selected");
    }
    private void addGroup(int group) {
        app.selection = app.effectStack().length; app.confirm();
        int[] groups = app.addGroups();
        for (int i = 0; i < groups.length; i++) if (groups[i] == group) app.selection = i;
        app.confirm();
    }
    private void effectParameter(int parameter) {
        check(app.mode == MainActivity.Mode.EFFECTS, "A parameter opens from the effects menu");
        if (app.effectGroup >= 0) app.cancel();
        boolean found = false;
        for (int group = 0; group < MainActivity.FX_GROUP_PARAMETERS.length && !found; group++) {
            for (int i = 0; i < MainActivity.FX_GROUP_PARAMETERS[group].length; i++) {
                if (MainActivity.FX_GROUP_PARAMETERS[group][i] != parameter) continue;
                int[] stack = app.effectStack();
                int row = -1;
                for (int s = 0; s < stack.length; s++) if (stack[s] == group) row = s;
                if (row < 0) { addEffect(group); stack = app.effectStack(); for (int s = 0; s < stack.length; s++) if (stack[s] == group) row = s; }
                app.selection = row; app.confirm();
                if (app.mode != MainActivity.Mode.FX_VALUE) { app.selection = i; app.confirm(); }
                found = true;
                break;
            }
        }
        check(found, "Parameter has a parent group");
        check(app.mode == MainActivity.Mode.FX_VALUE && app.effectParameter == parameter, "Grouped control edits the intended effect");
    }
    private void zoomIn() { app.zoomByWheel(1); }
    private void zoomOut() { app.zoomByWheel(-1); }
    private void play() { tap(app.instrument.playX(), InstrumentView.PLAY_Y); }
    private void restart() {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false); main(() -> app.recreate());
        Activity replacement = waitForMonitorWithTimeout(monitor, 6000); removeMonitor(monitor); check(replacement instanceof MainActivity, "Activity recreates"); app = (MainActivity) replacement; ready();
    }
    private void ready() { await(() -> !app.busy, 15000); }
    private void liveReady() { await(() -> !app.sequencer.needsPreparation(), 15000); }
    private interface Condition { boolean ready(); }
    private void await(Condition condition, int timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (true) {
            AtomicReference<Boolean> done = new AtomicReference<>(); main(() -> done.set(condition.ready())); if (done.get()) return;
            if (SystemClock.uptimeMillis() > deadline) throw new AssertionError("App operation timed out"); SystemClock.sleep(30);
        }
    }
    private EditText input(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { EditText found = input(((ViewGroup) view).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(100); Bitmap screenshot = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } }); if (error.get() != null) throw new AssertionError(error.get());
    }
    private void check(boolean valid, String label) { if (!valid) throw new AssertionError(label); checks++; }
    private void stage(String label) { Bundle status = new Bundle(); status.putString("stream", label + "\n"); sendStatus(0, status); }
    private void tap(float x, float y) { touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y); }
    private void scroll(float amount) {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties(); pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords(); coords.setAxisValue(MotionEvent.AXIS_VSCROLL, amount); long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coords}, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0); app.instrument.dispatchGenericMotionEvent(event); event.recycle();
    }
    private void rotate() {
        touch(MotionEvent.ACTION_DOWN, 320, InstrumentView.WHEEL_Y);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 200 + (float) Math.cos(i * 0.1) * 120, InstrumentView.WHEEL_Y + (float) Math.sin(i * 0.1) * 120);
        touch(MotionEvent.ACTION_UP, 200 + (float) Math.cos(1) * 120, InstrumentView.WHEEL_Y + (float) Math.sin(1) * 120);
    }
    private void touch(int action, float x, float y) {
        if (y > InstrumentView.WHEEL_Y - InstrumentView.WHEEL_RADIUS - 40) { x = InstrumentView.deviceX(x); y = InstrumentView.deviceY(y); }
        InstrumentView view = app.instrument; float width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight(), height = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
        float scale = Math.min(width / 400, height / InstrumentView.UI_HEIGHT), left = view.getPaddingLeft() + (width - 400 * scale) / 2, top = view.getPaddingTop() + (height - InstrumentView.UI_HEIGHT * scale) / 2;
        long now = SystemClock.uptimeMillis(); MotionEvent event = MotionEvent.obtain(now, now, action, left + x * scale, top + y * scale, 0); view.dispatchTouchEvent(event); event.recycle();
    }
}
