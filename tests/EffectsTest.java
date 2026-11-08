package com.tapedecksampler.app;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public final class EffectsTest {
    private static int checks;
    private static final int[] OPTIONAL = {Sequencer.Effects.AUTOTUNE, Sequencer.Effects.HIGH_PASS, Sequencer.Effects.LOW_PASS, Sequencer.Effects.DELAY, Sequencer.Effects.DISTORTION, Sequencer.Effects.ENVELOPE, Sequencer.Effects.REVERB};

    public static void main(String[] args) throws Exception {
        short[] tone = tone(440, Tape.RATE * 2);
        check(SampleProcessor.process(tone, 100, 0) == tone, "Neutral transform is exact");
        for (int speed : new int[]{25, 50, 75, 150, 200, 400}) {
            short[] result = SampleProcessor.process(tone, speed, 0);
            check(result.length == Math.round(tone.length * 100f / speed), "Speed sets duration at " + speed);
            check(Math.abs(frequency(result) - 440) < 5, "Speed preserves pitch at " + speed + ": " + frequency(result));
        }
        for (int pitch : new int[]{-24, -12, -7, 7, 12, 24}) {
            short[] result = SampleProcessor.process(tone, 100, pitch);
            double expected = 440 * Math.pow(2, pitch / 12.0);
            check(result.length == tone.length, "Pitch preserves duration at " + pitch);
            check(Math.abs(frequency(result) - expected) < 6, "Pitch sets frequency at " + pitch + ": " + frequency(result));
        }
        short[] combined = SampleProcessor.process(tone, 75, 7);
        check(combined.length == 128000 && Math.abs(frequency(combined) - 659.255) < 6, "Speed and pitch remain independent together");
        for (int speed : new int[]{25, 50, 200, 400}) {
            for (int pitch : new int[]{-24, -12, 12, 24}) {
                short[] result = SampleProcessor.process(tone, speed, pitch);
                check(result.length == Math.round(tone.length * 100f / speed), "Combined transform duration at " + speed + "/" + pitch);
                check(Math.abs(frequency(result) - 440 * Math.pow(2, pitch / 12.0)) < 6, "Combined transform pitch at " + speed + "/" + pitch);
            }
        }
        Thread.currentThread().interrupt();
        boolean cancelled = false;
        try { SampleProcessor.process(tone, 25, -24); }
        catch (IllegalStateException expected) { cancelled = true; }
        finally { Thread.interrupted(); }
        check(cancelled, "Superseded sample processing stops promptly");
        for (int hz : new int[]{55, 220, 1000}) {
            short[] result = SampleProcessor.process(tone(hz, Tape.RATE * 2), 150, 0);
            check(Math.abs(frequency(result) - hz) < 5, "Time stretch preserves frequency at " + hz + " Hz");
        }
        for (int length : new int[]{1, 2, 48, 400, 2048}) {
            short[] result = SampleProcessor.process(tone(440, length), 50, 12);
            check(result.length == length * 2, "Short slices process safely at " + length);
        }
        TrackEffects effects = new TrackEffects();
        check(effects.process(0.125f, 0, 100, 0) == 0.125f, "Dry effects are transparent");
        for (int type = 1; type <= 4; type++) {
            effects.reset();
            double difference = 0;
            for (int i = 0; i < 4000; i++) {
                float input = tone[i] / 32768f;
                float output = effects.process(input, type, 85, 0);
                checkFinite(output);
                difference += Math.abs(output - input);
            }
            check(difference > 10, "Distortion type " + type + " changes the signal");
        }
        effects.reset();
        check(effects.process(.25f, 2, 100, 0, 0) == .25f, "Zero distortion mix preserves the dry signal exactly");
        for (int type = 1; type <= 4; type++) {
            for (int blend : new int[]{25, 50, 100}) {
                effects.reset();
                double dryPower = 0, wetPower = 0, difference = 0;
                for (int i = 0; i < tone.length; i++) {
                    float dry = tone[i] / 32768f;
                    float wet = effects.process(dry, type, 85, blend, 0);
                    checkFinite(wet);
                    if (i >= Tape.RATE / 2) { dryPower += dry * dry; wetPower += wet * wet; difference += Math.abs(wet - dry); }
                }
                check(Math.abs(Math.sqrt(wetPower / dryPower) - 1) < .03, "Distortion preserves RMS volume for type " + type + " at " + blend + "% mix");
                check(difference > 10, "Mix retains the distortion character");
            }
        }
        effects.reset();
        double early = 0, late = 0;
        for (int i = 0; i < Tape.RATE * 3; i++) {
            float output = effects.process(i == 0 ? 1 : 0, 0, 0, 80);
            checkFinite(output);
            if (i > 1024 && i < Tape.RATE) early += output * output;
            if (i > Tape.RATE * 2) late += output * output;
        }
        check(early > 0.01 && late < early * 0.01, "Reverb produces a decaying tail");
        effects.reset();
        check(effects.process(0, 0, 0, 80) == 0, "Reset removes reverb tails");
        extendedEffects();
        powerSwitches();
        groupResets();
        effectStack();
        File file = Files.createTempFile("tape-effects", ".wav").toFile();
        try (Tape tape = new Tape(file)) {
            tape.write(0, tone, tone.length);
            Sequencer sequencer = new Sequencer(tape);
            Sequencer.Track track = sequencer.tracks[0];
            track.start = 0; track.end = tone.length;
            sequencer.apply(0, sequencer.prepare(0, 75, 7));
            check(!sequencer.needsPreparation() && track.speed == 75 && track.pitch == 7, "Prepared sample is ready for playback");
            check(Arrays.equals(tone, tape.slice(0, tone.length)), "Effects preserve the original tape");
            tape.write(0, new short[]{1000}, 1);
            check(sequencer.needsPreparation(), "Tape edits invalidate transformed samples");
            sequencer.prepareAll();
            check(!sequencer.needsPreparation(), "Edited tape is reprocessed");
            Sequencer clean = new Sequencer(tape);
            sequencer.tracks[1].start = clean.tracks[1].start = 0;
            sequencer.tracks[1].end = clean.tracks[1].end = tone.length;
            track.reverb = 80; track.distortion = 3; track.drive = 100;
            sequencer.preview(1); clean.preview(1);
            short[] a = new short[12000], b = new short[12000];
            sequencer.render(a); clean.render(b);
            check(Arrays.equals(a, b), "Effects on track 1 leave track 2 untouched");
            sequencer.resetEffects(0);
            check(track.speed == 100 && track.pitch == 0 && track.reverb == 0 && track.distortion == 0, "Track reset restores neutral effects");
            Sequencer perStep = new Sequencer(tape);
            perStep.tracks[0].end = tone.length;
            Sequencer.Effects pad = perStep.editStep(0, 2);
            pad.distortion = 2; pad.drive = 100;
            perStep.previewStep(0, 2); perStep.render(a);
            perStep.stop(); perStep.previewStep(0, 3); perStep.render(b);
            check(!Arrays.equals(a, b) && a[100] != b[100], "Pad distortion is isolated from the next pad");
            Sequencer baseline = new Sequencer(tape);
            baseline.tracks[0].end = tone.length;
            baseline.preview(0); baseline.render(a);
            check(Arrays.equals(a, b), "Pads without overrides inherit clean track effects");
            perStep.stop(); perStep.previewStep(0, 3); perStep.render(new short[512]);
            perStep.tracks[0].speed = 50;
            perStep.apply(0, perStep.prepare(0, 50, 0));
            perStep.render(a);
            check(!allZero(a), "Applying speed while a voice plays keeps audio flowing");
            perStep.tracks[0].speed = 25;
            perStep.prepareAll();
            perStep.render(a);
            check(!allZero(a) && !perStep.needsPreparation(), "Background speed preparation updates a playing voice");
            perStep.setSlice(0, 1000, tone.length - 1000); perStep.render(a);
            check(!allZero(a), "Changing a running slice does not silence the active voice");
            perStep.resetEffects(0);
            check(perStep.tracks[0].padEffects[2] == null && perStep.tracks[0].speed == 100, "Track reset clears individual pad overrides");
            timingAndEnvelope(tape);
            livePitch(tape, tone.length);
            padPitchIsolation(tape, tone.length);
        } finally { Files.deleteIfExists(file.toPath()); }
        System.out.println(checks + " effects checks passed");
    }

    private static void livePitch(Tape tape, int length) {
        Sequencer seq = new Sequencer(tape);
        seq.tracks[0].end = length;
        for (int pitch : new int[]{-24, -12, 7, 12, 24}) {
            seq.stop(); seq.preview(0); seq.render(new short[12000]);
            seq.tracks[0].pitch = pitch;
            seq.refresh(0); seq.prepareAll();
            short[] output = new short[72000];
            seq.render(output);
            check(Math.abs(frequency(output) - 440 * Math.pow(2, pitch / 12.0)) < 6, "Live pitch preparation updates playing audio at " + pitch);
        }
    }

    private static void padPitchIsolation(Tape tape, int length) {
        Sequencer seq = new Sequencer(tape);
        Sequencer.Track track = seq.tracks[0];
        track.end = length; track.pitch = -1;
        track.power(Sequencer.Effects.AUTOTUNE, true);
        track.attack = 1000;
        seq.prepareAll();
        Sequencer baseline = new Sequencer(tape);
        baseline.tracks[0].end = length; baseline.tracks[0].copyFrom(track);
        seq.previewStep(0, 0); baseline.previewStep(0, 0);
        short[] actual = new short[4096], expected = new short[4096];
        seq.render(actual); baseline.render(expected);
        Sequencer.Effects pad = seq.editStep(0, 4); pad.pitch = 1;
        seq.apply(0, 4, seq.prepare(0, 4, pad.speed, pad.pitch));
        seq.render(actual); baseline.render(expected);
        check(Arrays.equals(actual, expected), "Pad Pitch preparation leaves other playing pads and their envelopes untouched");
    }

    private static void extendedEffects() {
        Sequencer.Effects controls = new Sequencer.Effects();
        controls.lowPass = 1000;
        check(power(controls, 100) > power(controls, 5000) * 20, "Low pass attenuates treble and preserves bass");
        controls.lowPass = 0; controls.highPass = 1000;
        check(power(controls, 5000) > power(controls, 100) * 20, "High pass attenuates bass and preserves treble");
        controls.highPass = 0; controls.delayTime = 10; controls.delayMix = 50; controls.delayFeedback = 50;
        TrackEffects effects = new TrackEffects();
        float[] echo = new float[1441];
        for (int i = 0; i < echo.length; i++) echo[i] = effects.process(i == 0 ? 1 : 0, controls);
        check(echo[0] == .5f && echo[480] == .5f && echo[960] == .25f && echo[1440] == .125f, "Delay repeats at the chosen time with decaying feedback");
        effects.reset();
        check(effects.process(0, controls) == 0, "Reset clears delay tails");
        controls.wetMix = 0; controls.highPass = 500; controls.distortion = 3;
        for (short sample : tone(440, 2000)) {
            float dry = sample / 32768f;
            check(effects.process(dry, controls) == dry, "Zero wet mix preserves dry audio");
        }
    }

    private static double power(Sequencer.Effects controls, int frequency) {
        TrackEffects effects = new TrackEffects();
        short[] signal = tone(frequency, Tape.RATE);
        double power = 0;
        for (int i = 0; i < signal.length; i++) {
            float output = effects.process(signal[i] / 32768f, controls);
            checkFinite(output);
            if (i >= Tape.RATE / 4) power += output * output;
        }
        return power;
    }

    private static void powerSwitches() {
        Sequencer.Effects controls = new Sequencer.Effects();
        for (int group : OPTIONAL) {
            check(!controls.powered(group), "Optional effect defaults to powered off: " + group);
            controls.power(group, true);
            check(controls.powered(group), "Power on enables effect " + group);
            Sequencer.Effects saved = controls.copy();
            controls.power(group, false);
            check(!controls.powered(group) && saved.powered(group), "Power state copies independently for effect " + group);
            controls.power(group, true);
        }
        check(controls.autoStrength == 100 && controls.lowPass == 12000 && controls.delayMix == 25 && controls.distortion == 1 && controls.release == 50 && controls.reverb == 25, "First activation supplies usable starting settings");
        controls.highPass = 800; controls.lowPass = 3000; controls.delayMix = 50; controls.reverb = 70;
        controls.attack = 20; controls.decay = 100; controls.sustain = 25; controls.release = 200;
        for (int group : OPTIONAL) controls.power(group, false);
        TrackEffects effects = new TrackEffects(); boolean dry = true;
        short[] tone = tone(430, 4096);
        for (short sample : tone) {
            float input = sample / 32768f;
            dry &= effects.process(input, controls) == input;
        }
        check(dry, "Power off bypasses filters, delay, distortion and reverb despite stored nonzero settings");
        check(TrackEffects.envelope(5000, 5000, controls) == 1, "Power off bypasses stored ADSR settings");
        check(!controls.hasAutotune() && PitchCorrector.process(tone, controls) == tone, "Power off bypasses stored autotune settings");
        for (int group : OPTIONAL) controls.power(group, true);
        check(controls.highPass == 800 && controls.lowPass == 3000 && controls.delayMix == 50 && controls.reverb == 70 && controls.attack == 20 && controls.sustain == 25, "Power cycling preserves edited effect settings");
        Sequencer.Effects legacy = new Sequencer.Effects(); legacy.reverb = 50; legacy.autoStrength = 80;
        check(legacy.powered(Sequencer.Effects.REVERB) && legacy.hasAutotune(), "Existing active effects remain enabled without explicit power metadata");
    }

    private static void effectStack() {
        Sequencer.Effects controls = new Sequencer.Effects();
        check(controls.stack().length == 0, "A new effect stack is blank");
        int filters = Sequencer.Effects.LOW_PASS, distortion = Sequencer.Effects.DISTORTION, reverb = Sequencer.Effects.REVERB, delay = Sequencer.Effects.DELAY;
        for (int group = Sequencer.Effects.SAMPLE; group < Sequencer.Effects.GROUPS; group++) {
            Sequencer.Effects added = new Sequencer.Effects();
            added.power(group, true);
            check(added.stack().length == 1 && added.stack()[0] == group, "Adding shows effect " + group + " even at neutral values");
        }
        controls.power(delay, true); controls.place(delay, Integer.MAX_VALUE);
        controls.power(filters, true); controls.place(filters, Integer.MAX_VALUE);
        controls.power(Sequencer.Effects.ENVELOPE, true);
        controls.power(Sequencer.Effects.VOLUME, true);
        check(Arrays.equals(controls.stack(), new int[]{Sequencer.Effects.ENVELOPE, delay, filters, Sequencer.Effects.VOLUME}), "New effects join the bottom of the movable chain");
        check(controls.move(filters, -1) && Arrays.equals(controls.stack(), new int[]{Sequencer.Effects.ENVELOPE, filters, delay, Sequencer.Effects.VOLUME}), "Moving steps past visible effects");
        check(!controls.move(filters, -1) && !controls.move(delay, 1), "Movable effects stop at their stage edges");
        Sequencer.Effects copied = controls.copy(); copied.move(delay, -1);
        check(controls.stack()[1] == filters && copied.stack()[1] == delay, "Effect order copies independently");
        Sequencer.Effects before = controls.copy();
        int position = controls.position(filters);
        controls.resetGroup(filters);
        check(controls.stack().length == 3 && controls.lowPass == 0, "Deleting removes an effect and its values");
        controls.restoreGroup(filters, before); controls.place(filters, position);
        check(Arrays.equals(controls.stack(), before.stack()) && controls.lowPass == 12000, "Undo restores an effect's values and position");
        Sequencer.Effects bad = new Sequencer.Effects();
        for (int[] saved : new int[][]{{filters, filters, reverb, delay}, {filters, distortion}, {0, distortion, reverb, delay}, null}) {
            bad.restoreOrder(saved);
            check(Arrays.equals(bad.chain, new Sequencer.Effects().chain), "Invalid saved order is ignored");
        }
        Sequencer.Effects migrated = new Sequencer.Effects();
        migrated.restoreOrder(new int[]{Sequencer.Effects.HIGH_PASS, reverb, distortion, delay});
        check(Arrays.equals(migrated.chain, new int[]{Sequencer.Effects.HIGH_PASS, Sequencer.Effects.LOW_PASS, reverb, distortion, delay}), "Saved Filters order keeps High pass before Low pass");
        Sequencer.Effects legacyOn = new Sequencer.Effects(); legacyOn.lowPass = 900;
        legacyOn.effectPower = legacyOn.effectPowerSet = 1 << Sequencer.Effects.HIGH_PASS;
        legacyOn.splitGroups(); legacyOn.normalize();
        check(Arrays.equals(legacyOn.stack(), new int[]{Sequencer.Effects.LOW_PASS}), "Saved Filters shows only the filter in use");
        Sequencer.Effects legacyOff = new Sequencer.Effects(); legacyOff.lowPass = 900; legacyOff.highPass = 300;
        legacyOff.effectPowerSet = 1 << Sequencer.Effects.HIGH_PASS;
        legacyOff.splitGroups(); legacyOff.normalize();
        check(legacyOff.stack().length == 0, "Saved powered-off Filters stays off for both filters");
        Sequencer.Effects separate = new Sequencer.Effects();
        separate.power(Sequencer.Effects.HIGH_PASS, true); separate.lowPass = 900; separate.normalize();
        separate.resetGroup(Sequencer.Effects.HIGH_PASS);
        check(separate.highPass == 0 && separate.lowPass == 900 && separate.stack().length == 1, "Deleting High pass leaves Low pass alone");
        Sequencer.Effects conditional = new Sequencer.Effects();
        conditional.power(Sequencer.Effects.REVERB, true);
        conditional.power(Sequencer.Effects.CHANCE, true); conditional.power(Sequencer.Effects.EVERY, true);
        check(conditional.chance == 50 && conditional.every == 2 && conditional.stack()[0] == Sequencer.Effects.CHANCE && conditional.stack()[1] == Sequencer.Effects.EVERY, "Trigger conditions sit at the top of the stack with useful defaults");
        Sequencer.Effects conditionCopy = conditional.copy();
        check(conditionCopy.chance == 50 && conditionCopy.every == 2, "Trigger conditions copy");
        conditional.resetGroup(Sequencer.Effects.CHANCE); conditional.resetGroup(Sequencer.Effects.EVERY);
        check(conditional.chance == 100 && conditional.every == 1 && conditional.stack().length == 1, "Deleting conditions restores every hit");
        conditional.restoreGroup(Sequencer.Effects.CHANCE, conditionCopy); conditional.restoreGroup(Sequencer.Effects.EVERY, conditionCopy);
        check(conditional.chance == 50 && conditional.every == 2, "Undo restores conditions");
        check(Sequencer.Effects.movable(Sequencer.Effects.HIGH_PASS) && Sequencer.Effects.movable(Sequencer.Effects.LOW_PASS), "Both filters can be reordered");
        Sequencer.Effects bandFirst = new Sequencer.Effects(), bandSecond = new Sequencer.Effects();
        bandFirst.highPass = bandSecond.highPass = 500; bandFirst.lowPass = bandSecond.lowPass = 2000;
        bandSecond.move(Sequencer.Effects.LOW_PASS, -1);
        check(bandSecond.stack()[0] == Sequencer.Effects.LOW_PASS && power(bandFirst, 1000) > 0 && power(bandSecond, 1000) > 0, "Low pass can run before High pass");
        Sequencer.Effects nudged = new Sequencer.Effects(); nudged.swingOverride = 60; nudged.nudge = 10;
        check(Arrays.equals(nudged.stack(), new int[]{Sequencer.Effects.SWING, Sequencer.Effects.NUDGE}), "Swing and Nudge are separate effects");
        nudged.resetGroup(Sequencer.Effects.SWING);
        check(nudged.nudge == 10 && nudged.swingOverride == 0 && nudged.stack().length == 1, "Deleting Swing leaves Nudge alone");
        Sequencer.Effects legacyTiming = new Sequencer.Effects(); legacyTiming.nudge = 10;
        legacyTiming.effectPower = legacyTiming.effectPowerSet = 1 << Sequencer.Effects.SWING;
        legacyTiming.splitGroups(); legacyTiming.normalize();
        check(Arrays.equals(legacyTiming.stack(), new int[]{Sequencer.Effects.NUDGE}), "Saved Timing shows only the value in use");
        Sequencer.Effects legacyPitch = new Sequencer.Effects(); legacyPitch.pitch = 3; legacyPitch.autoStrength = 90;
        legacyPitch.power(Sequencer.Effects.AUTOTUNE, false); legacyPitch.normalize();
        check(Arrays.equals(legacyPitch.stack(), new int[]{Sequencer.Effects.PITCH}) && !legacyPitch.hasAutotune(), "Saved manual Pitch becomes its own effect without enabling correction");
        Sequencer.Effects pitched = new Sequencer.Effects(); pitched.pitch = 5; pitched.autoStrength = 80;
        Sequencer.Effects savedPitch = pitched.copy();
        pitched.resetGroup(Sequencer.Effects.PITCH);
        check(pitched.pitch == 0 && pitched.autoStrength == 80, "Deleting Pitch leaves Autotune alone");
        pitched.restoreGroup(Sequencer.Effects.PITCH, savedPitch);
        check(pitched.pitch == 5, "Undo restores Pitch");
        pitched.resetGroup(Sequencer.Effects.AUTOTUNE);
        check(pitched.pitch == 5 && pitched.autoStrength == 0, "Deleting Autotune leaves Pitch alone");
        Sequencer.Effects sourceOrder = new Sequencer.Effects(); sourceOrder.speed = 90; sourceOrder.pitch = 1; sourceOrder.autoStrength = 50;
        check(Arrays.equals(sourceOrder.stack(), new int[]{Sequencer.Effects.SAMPLE, Sequencer.Effects.PITCH, Sequencer.Effects.AUTOTUNE}), "Stack shows the real sample processing order");
        Sequencer.Effects legacyMix = new Sequencer.Effects(); legacyMix.reverb = 40; legacyMix.normalize(); legacyMix.reverb = 0;
        check(legacyMix.powered(reverb), "Loaded effects stay in the stack when their value reaches zero");
        Sequencer.Effects first = new Sequencer.Effects(), second = new Sequencer.Effects();
        first.lowPass = second.lowPass = 400; first.distortion = second.distortion = 2; first.drive = second.drive = 100;
        check(first.stack()[0] == filters, "Default order filters before distortion");
        second.move(distortion, -1);
        check(second.stack()[0] == distortion, "Distortion can run before filters");
        TrackEffects a = new TrackEffects(), b = new TrackEffects();
        short[] signal = tone(220, 4800);
        boolean different = false;
        for (short sample : signal) {
            float value = sample / 32768f;
            different |= Math.abs(a.process(value, first) - b.process(value, second)) > .01f;
        }
        check(different, "Stack order changes the processed audio");
        Sequencer.Effects order = new Sequencer.Effects(), reversed = new Sequencer.Effects();
        order.reverb = reversed.reverb = 60; order.delayMix = reversed.delayMix = 60; order.delayTime = reversed.delayTime = 20;
        reversed.move(Sequencer.Effects.DELAY, -1);
        check(reversed.stack()[0] == delay && order.stack()[0] == reverb, "Delay and reverb swap in the stack");
    }

    private static void groupResets() {
        Sequencer.Effects source = new Sequencer.Effects();
        source.gain = .6f; source.mute = true; source.wetMix = 35;
        source.speed = 75; source.pitch = 7; source.highPass = 500; source.lowPass = 5000;
        source.delayTime = 480; source.delayFeedback = 70; source.delayMix = 65;
        source.distortion = 2; source.drive = 80; source.distortionMix = 40; source.reverb = 60;
        source.attack = 30; source.decay = 60; source.sustain = 40; source.release = 90;
        source.autoKey = 4; source.autoScale = 2; source.autoStrength = 65; source.autoRetune = 125; source.autoMix = 75;
        source.swingOverride = 70; source.nudge = -12;
        for (int group : OPTIONAL) source.power(group, true);
        for (int group = Sequencer.Effects.SAMPLE; group <= Sequencer.Effects.SWING; group++) {
            Sequencer.Effects controls = source.copy(); controls.resetGroup(group);
            boolean reset;
            switch (group) {
                case Sequencer.Effects.SAMPLE: reset = controls.speed == 100 && controls.pitch == source.pitch; break;
                case Sequencer.Effects.AUTOTUNE: reset = controls.pitch == source.pitch && controls.speed == source.speed && controls.autoKey == 0 && controls.autoScale == 0 && controls.autoStrength == 0 && controls.autoRetune == 40 && controls.autoMix == 100; break;
                case Sequencer.Effects.HIGH_PASS: reset = controls.highPass == 0 && controls.lowPass == source.lowPass; break;
                case Sequencer.Effects.DELAY: reset = controls.delayTime == 250 && controls.delayFeedback == 35 && controls.delayMix == 0; break;
                case Sequencer.Effects.DISTORTION: reset = controls.distortion == 0 && controls.drive == 40 && controls.distortionMix == 100; break;
                case Sequencer.Effects.ENVELOPE: reset = controls.attack == 0 && controls.decay == 0 && controls.sustain == 100 && controls.release == 0; break;
                case Sequencer.Effects.REVERB: reset = controls.reverb == 0; break;
                case Sequencer.Effects.SWING: reset = controls.swingOverride == 0 && controls.nudge == source.nudge; break;
                default: throw new AssertionError("Unknown group");
            }
            check(reset, "Submenu Reset restores defaults for group " + group);
            check(controls.gain == .6f && controls.mute && controls.wetMix == 35, "Group Reset preserves volume, mute and overall mix");
            for (int other : OPTIONAL) {
                check(controls.powered(other) == (other != group), "Group Reset preserves unrelated power states");
            }
            check(source.speed == 75 && source.delayMix == 65 && source.autoStrength == 65 && source.swingOverride == 70, "Resetting copied settings leaves their source intact");
        }
    }

    private static void timingAndEnvelope(Tape tape) throws Exception {
        short[] signal = new short[9600]; Arrays.fill(signal, (short) 12000); tape.write(0, signal, signal.length);
        Sequencer envelope = new Sequencer(tape);
        Sequencer.Track track = envelope.tracks[0];
        track.end = signal.length; track.attack = 20; track.decay = 40; track.sustain = 25; track.release = 40;
        envelope.preview(0);
        short[] shaped = new short[signal.length]; envelope.render(shaped);
        check(shaped[480] == 4800 && shaped[960] == 9600 && shaped[3840] == 2400 && shaped[8640] == 1200, "Rendered ADSR ramps through attack, decay, sustain and release");
        for (int nudge : new int[]{-25, 0, 25}) {
            Sequencer seq = pulseSequence(tape, 2);
            seq.tracks[0].nudge = nudge; seq.play();
            short[] audio = new short[12000]; seq.render(audio);
            check(firstSound(audio) == 6001 + nudge * 48, "Nudge shifts the entire hit by " + nudge + " ms");
            seq.playSynced(); seq.renderSynced(audio, 0, 1_000_000_000.0 / Tape.RATE, true);
            check(firstSound(audio) == 6001 + nudge * 48, "Synced nudge matches free playback at " + nudge + " ms");
        }
        Sequencer groove = pulseSequence(tape, 2);
        groove.swing(75); groove.tracks[0].swingOverride = 50; groove.play();
        short[] audio = new short[12000]; groove.render(audio);
        check(firstSound(audio) == 6001, "Track swing overrides global swing");
        groove.tracks[0].swingOverride = 0; groove.play(); groove.render(audio);
        check(firstSound(audio) == 9001, "Global swing is restored when the override is disabled");
        for (int swing : new int[]{10, 25, 90}) {
            groove.tracks[0].swingOverride = swing; groove.play(); groove.render(audio);
            check(firstSound(audio) == 120 * swing + 1, "Track swing supports " + swing + "%");
            groove.playSynced(); groove.renderSynced(audio, 0, 1_000_000_000.0 / Tape.RATE, true);
            check(firstSound(audio) == 120 * swing + 1, "Synced track swing supports " + swing + "%");
        }
        groove.swing(Integer.MIN_VALUE);
        check(groove.swing() == 10, "Global swing clamps to the new lower bound");
        groove.swing(Integer.MAX_VALUE);
        check(groove.swing() == 90, "Global swing clamps to the new upper bound");
        groove.swing(75);
        groove.tracks[0].nudge = 25; groove.tracks[0].resetGroup(Sequencer.Effects.SWING); groove.tracks[0].resetGroup(Sequencer.Effects.NUDGE);
        groove.play(); groove.render(audio);
        check(groove.swing() == 75 && firstSound(audio) == 9001, "Timing Reset restores inherited global swing and removes nudge");
        groove.tracks[0].swingOverride = 60; groove.tracks[0].nudge = -20;
        groove.play(); groove.render(audio);
        check(firstSound(audio) == 6241, "Swing and negative nudge combine");
        groove.playSynced(); groove.renderSynced(audio, 0, 1_000_000_000.0 / Tape.RATE, true);
        check(firstSound(audio) == 6241, "Synced track swing and nudge combine");
        Sequencer early = pulseSequence(tape, 0);
        early.selectPage(1); early.tracks[0].steps = 1; early.tracks[0].nudge = -25; early.selectPage(0);
        early.tracks[0].nudge = -25; early.play();
        audio = new short[96000]; early.render(audio);
        check(firstSound(audio) == 94801 && early.playingPage() == 0, "Negative nudge can anticipate the next page");
        Sequencer late = pulseSequence(tape, 1 << 15);
        late.selectPage(1); late.selectPage(0); late.tempo(240); late.tracks[0].nudge = 100; late.play();
        audio = new short[52000]; late.render(audio);
        check(firstSound(audio) == 49801 && late.playingPage() == 1, "Delayed hits retain their source page after the page changes");
        late.playSynced(); late.renderSynced(audio, 0, 1_000_000_000.0 / Tape.RATE, true);
        check(firstSound(audio) == 49801, "Synced playback retains delayed hits from the previous page");
        early.playSynced(); audio = new short[96000]; early.renderSynced(audio, 0, 1_000_000_000.0 / Tape.RATE, true);
        check(firstSound(audio) == 94801, "Synced negative nudge anticipates the next page");
        Sequencer blocked = pulseSequence(tape, 0xaaaa);
        blocked.swing(73); blocked.tracks[0].swingOverride = 61; blocked.tracks[0].nudge = -37; blocked.play();
        short[] whole = new short[100000]; blocked.render(whole);
        blocked.play(); short[] chunked = new short[whole.length];
        for (int start = 0; start < chunked.length; start += 127) {
            short[] chunk = new short[Math.min(127, chunked.length - start)];
            blocked.render(chunk); System.arraycopy(chunk, 0, chunked, start, chunk.length);
        }
        check(Arrays.equals(whole, chunked), "Track timing is independent of audio buffer size");
        java.io.ByteArrayOutputStream midi = new java.io.ByteArrayOutputStream();
        groove.tracks[0].swingOverride = 60; groove.tracks[0].nudge = -20;
        MidiExporter.export(midi, groove);
        javax.sound.midi.Sequence exported = javax.sound.midi.MidiSystem.getSequence(new java.io.ByteArrayInputStream(midi.toByteArray()));
        javax.sound.midi.Track notes = exported.getTracks()[1];
        check(notes.get(0).getTick() == 125 && notes.get(1).getTick() == 201, "MIDI note starts and ends include track swing and nudge");
        for (int swing : new int[]{10, 90}) {
            groove.tracks[0].swingOverride = swing; midi.reset(); MidiExporter.export(midi, groove);
            exported = javax.sound.midi.MidiSystem.getSequence(new java.io.ByteArrayInputStream(midi.toByteArray()));
            notes = exported.getTracks()[1];
            check(notes.get(0).getTick() == 240 * swing / 100 - 19 && notes.get(1).getTick() > notes.get(0).getTick(), "MIDI export preserves extended swing at " + swing + "%");
        }
        triggerConditions(tape);
        wobble(tape);
        limiter(tape);
        Sequencer copied = pulseSequence(tape, 1);
        Sequencer.Track source = copied.tracks[0];
        source.highPass = 500; source.delayMix = 30; source.attack = 25; source.swingOverride = 65; source.nudge = -10;
        source.mute = true;
        Sequencer.Effects pad = copied.editStep(0, 0);
        check(!pad.mute, "A new pad override does not retain the parent track's temporary mute");
        source.power(Sequencer.Effects.VOLUME, true);
        Sequencer.Effects volume = copied.editStep(0, 1);
        check(!volume.powered(Sequencer.Effects.VOLUME) && volume.gain == 1, "Pad overrides start with a neutral volume");
        volume.gain = .5f; volume.resetGroup(Sequencer.Effects.VOLUME);
        check(volume.gain == 1, "Deleting pad Volume restores its neutral multiplier");
        check(pad.highPass == 500 && pad.delayMix == 30 && pad.attack == 25, "Pad overrides inherit the new audio effects");
        copied.resetEffects(0);
        check(source.highPass == 0 && source.delayMix == 0 && source.attack == 0 && source.sustain == 100 && source.wetMix == 100 && source.swingOverride == 0 && source.nudge == 0, "Reset restores neutral audio effects and track timing");
    }

    private static boolean[] passes(Sequencer seq, int loops) {
        short[] audio = new short[48000 * loops];
        seq.play(); seq.render(audio);
        boolean[] heard = new boolean[loops];
        for (int loop = 0; loop < loops; loop++) for (int i = 0; i < 1000; i++) heard[loop] |= audio[loop * 48000 + i] != 0;
        return heard;
    }

    private static void triggerConditions(Tape tape) {
        Sequencer every = pulseSequence(tape, 1); every.tempo(240); every.tracks[0].every = 2;
        check(Arrays.equals(passes(every, 4), new boolean[]{false, true, false, true}), "Every 2nd plays on the second and fourth pass");
        check(Arrays.equals(passes(every, 2), new boolean[]{false, true}), "Pass counts restart with playback");
        every.tracks[0].every = 3;
        check(Arrays.equals(passes(every, 6), new boolean[]{false, false, true, false, false, true}), "Every 3rd plays on each third pass");
        Sequencer pad = pulseSequence(tape, 3); pad.tempo(240);
        pad.editStep(0, 0).every = 2;
        pad.tracks[0].steps = 1;
        check(Arrays.equals(passes(pad, 2), new boolean[]{false, true}) && pad.tracks[0].every == 1, "Pad override has its own trigger condition");
        Sequencer pages = pulseSequence(tape, 1); pages.tempo(240); pages.tracks[0].every = 2;
        pages.selectPage(1); pages.tracks[0].steps = 0; pages.selectPage(0);
        check(Arrays.equals(passes(pages, 4), new boolean[]{false, false, true, false}), "Every Nth counts passes over the pad, not total loops");
        Sequencer muted = pulseSequence(tape, 1); muted.tempo(240); muted.tracks[0].every = 2; muted.tracks[0].mute = true;
        check(Arrays.equals(passes(muted, 2), new boolean[]{false, false}), "Muted tracks stay silent on passing conditions");
        Sequencer never = pulseSequence(tape, 1); never.tempo(240); never.tracks[0].chance = 0;
        check(Arrays.equals(passes(never, 4), new boolean[4]), "Chance 0 never plays");
        Sequencer always = pulseSequence(tape, 1); always.tempo(240); always.tracks[0].chance = 100;
        check(Arrays.equals(passes(always, 3), new boolean[]{true, true, true}), "Chance 100 always plays");
        Sequencer half = pulseSequence(tape, 1); half.tempo(240); half.tracks[0].chance = 50;
        Sequencer same = pulseSequence(tape, 1); same.tempo(240); same.tracks[0].chance = 50;
        boolean[] rolls = passes(half, 60);
        int hits = 0;
        for (boolean hit : rolls) if (hit) hits++;
        check(hits > 15 && hits < 45, "Chance 50 plays about half the hits: " + hits);
        check(Arrays.equals(rolls, passes(same, 60)), "Chance rolls match between synced devices");
    }

    private static double crossings(short[] audio, int from, int to) {
        int count = 0;
        for (int i = from + 1; i < to; i++) if (audio[i - 1] <= 0 && audio[i] > 0) count++;
        return count * Tape.RATE / (double) (to - from);
    }

    private static int lastSound(short[] audio) {
        for (int i = audio.length - 1; i >= 0; i--) if (audio[i] != 0) return i;
        return -1;
    }

    private static short[] wobbled(Tape tape, int length, int mode, int depth) {
        Sequencer seq = new Sequencer(tape);
        seq.tracks[0].end = length;
        seq.tracks[0].wobbleMode = mode; seq.tracks[0].wobbleRate = 10; seq.tracks[0].wobbleDepth = depth;
        seq.preview(0);
        short[] audio = new short[length + 48000];
        seq.render(audio);
        return audio;
    }

    private static void wobble(Tape tape) throws Exception {
        short[] original = tape.slice(0, 96000);
        Sequencer.Effects controls = new Sequencer.Effects();
        check(!controls.wobbles(), "Wobble starts off");
        controls.power(Sequencer.Effects.WOBBLE, true);
        check(controls.wobbles() && controls.wobbleDepth == 50 && !Sequencer.Effects.movable(Sequencer.Effects.WOBBLE), "Wobble adds with usable defaults and a fixed position");
        Sequencer.Effects saved = controls.copy();
        controls.wobbleMode = 2; controls.wobbleRate = 90; controls.wobbleDepth = 70;
        Sequencer.Effects before = controls.copy();
        controls.resetGroup(Sequencer.Effects.WOBBLE);
        check(controls.wobbleMode == 0 && controls.wobbleRate == 50 && controls.wobbleDepth == 0 && !controls.wobbles(), "Deleting Wobble restores its defaults");
        controls.restoreGroup(Sequencer.Effects.WOBBLE, before);
        check(controls.wobbleMode == 2 && controls.wobbleRate == 90 && controls.wobbleDepth == 70 && saved.wobbleRate == 50, "Undo restores Wobble");
        short[] flat = new short[96000]; Arrays.fill(flat, (short) 10000);
        tape.write(0, flat, flat.length);
        short[] volume = wobbled(tape, flat.length, Sequencer.Effects.WOBBLE_VOLUME, 100);
        check(Math.abs(volume[1000] - 8000) < 40 && Math.abs(volume[24000]) < 20 && Math.abs(volume[48000] - 8000) < 40, "Volume mode dips to silence and returns");
        short[] half = wobbled(tape, flat.length, Sequencer.Effects.WOBBLE_VOLUME, 50);
        check(Math.abs(half[24000] - 4000) < 40, "Depth sets how far Volume dips");
        short[] signal = tone(880, 96000); tape.write(0, signal, signal.length);
        short[] dry = wobbled(tape, signal.length, Sequencer.Effects.WOBBLE_VOLUME, 0);
        short[] pitch = wobbled(tape, signal.length, Sequencer.Effects.WOBBLE_PITCH, 100);
        double rising = crossings(pitch, 2000, 22000), falling = crossings(pitch, 26000, 46000);
        check(rising > 930 && falling < 830, "Pitch mode bends above and below the note: " + rising + " / " + falling);
        check(lastSound(pitch) == lastSound(dry), "Pitch mode keeps note length");
        short[] speed = wobbled(tape, signal.length, Sequencer.Effects.WOBBLE_SPEED, 100);
        double fast = crossings(speed, 2000, 20000), slow = crossings(speed, 28000, 44000);
        check(fast > 1000 && slow < 800, "Speed mode runs the sample fast then slow: " + fast + " / " + slow);
        check(lastSound(speed) != lastSound(dry) || !Arrays.equals(speed, dry), "Speed mode changes playback");
        check(Math.abs(lastSound(speed) - lastSound(dry)) < 24000, "Speed mode stays near the note length");
        short[] tiny = new short[64]; Arrays.fill(tiny, (short) 9000); tape.write(0, tiny, tiny.length);
        for (int mode = 0; mode < 3; mode++) check(wobbled(tape, tiny.length, mode, 100).length > 0, "Wobble handles very short samples in mode " + mode);
        tape.write(0, original, original.length);
    }

    private static void limiter(Tape tape) throws Exception {
        short[] loud = new short[24000];
        for (int i = 0; i < loud.length; i++) loud[i] = (short) (i % 200 < 100 ? 32000 : -32000);
        tape.write(0, loud, loud.length);
        Sequencer seq = new Sequencer(tape);
        for (int t = 0; t < 8; t++) { seq.tracks[t].end = loud.length; seq.tracks[t].gain = 1; seq.preview(t); }
        short[] audio = new short[20000]; seq.render(audio);
        int peak = 0;
        for (short sample : audio) peak = Math.max(peak, Math.abs(sample));
        check(peak > 30000 && peak <= 31131, "Limiter holds summed tracks under the ceiling: " + peak);
        short[] quiet = new short[24000];
        for (int i = 0; i < quiet.length; i++) quiet[i] = (short) (i % 200 < 100 ? 8000 : -8000);
        tape.write(0, quiet, quiet.length);
        Sequencer clean = new Sequencer(tape);
        clean.tracks[0].end = quiet.length; clean.tracks[0].gain = 1; clean.preview(0);
        short[] passed = new short[5000]; clean.render(passed);
        check(passed[1000] == quiet[1000] && passed[1150] == quiet[1150], "Limiter leaves normal levels untouched");
        seq.stop();
        short[] restored = new short[9600]; Arrays.fill(restored, (short) 12000); tape.write(0, restored, restored.length);
    }

    private static Sequencer pulseSequence(Tape tape, int steps) {
        Sequencer seq = new Sequencer(tape);
        seq.tracks[0].end = 480; seq.tracks[0].steps = steps;
        return seq;
    }

    private static int firstSound(short[] audio) {
        for (int i = 0; i < audio.length; i++) if (audio[i] != 0) return i;
        return -1;
    }

    private static short[] tone(double frequency, int length) {
        short[] data = new short[length];
        for (int i = 0; i < length; i++) data[i] = (short) Math.round(Math.sin(2 * Math.PI * frequency * i / Tape.RATE) * 12000);
        return data;
    }

    private static double frequency(short[] data) {
        int from = data.length / 5, to = data.length * 4 / 5, crossings = 0;
        for (int i = from + 1; i < to; i++) if (data[i - 1] <= 0 && data[i] > 0) crossings++;
        return crossings * Tape.RATE / (double) (to - from);
    }

    private static void checkFinite(float value) {
        if (!Float.isFinite(value) || Math.abs(value) > 4) throw new AssertionError("Unstable effects output");
    }

    private static boolean allZero(short[] data) {
        for (short sample : data) if (sample != 0) return false;
        return true;
    }

    private static void check(boolean valid, String name) {
        if (!valid) throw new AssertionError(name);
        checks++;
    }
}
