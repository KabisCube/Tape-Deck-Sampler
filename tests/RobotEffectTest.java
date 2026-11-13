package com.tapedecksampler.app;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

public final class RobotEffectTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        int[] migrated = {RobotEffect.ROBOT, RobotEffect.ROBOT, RobotEffect.GIANT, RobotEffect.CHIP, RobotEffect.WHISPER, RobotEffect.ROBOT, RobotEffect.ROBOT, RobotEffect.ROBOT};
        for (int i = 0; i < migrated.length; i++) check(RobotEffect.savedProfile(i, 1) == migrated[i], "Old profiles migrate to the consolidated voices");
        int[] previous = {RobotEffect.ROBOT, RobotEffect.WHISPER, RobotEffect.ROBOT};
        for (int i = 0; i < previous.length; i++) check(RobotEffect.savedProfile(i, 2) == previous[i], "Three-voice projects migrate to the retained voices");
        for (int i = 0; i < RobotEffect.PROFILES.length; i++) check(RobotEffect.savedProfile(i, RobotEffect.PROFILE_VERSION) == i, "Current profile IDs survive saving");
        Sequencer.Effects controls = new Sequencer.Effects();
        short[] source = noise(Tape.RATE);
        short[] original = source.clone();
        check(RobotEffect.process(source, controls) == source, "Default Robot bypasses exactly");
        controls.power(Sequencer.Effects.ROBOT, true);
        short[][] voices = new short[RobotEffect.PROFILES.length][];
        for (int profile = 0; profile < voices.length; profile++) {
            controls.robotProfile = profile;
            voices[profile] = RobotEffect.process(source, controls);
            check(voices[profile].length == source.length, "Voice preserves duration");
            check(rms(voices[profile]) > rms(source) / 4, "Voice produces audible processed noise");
            check(Math.abs(correlation(source, voices[profile])) < .1, "Unvoiced input is resynthesized without a dry layer");
            check(peak(voices[profile]) < Short.MAX_VALUE, "Voice has headroom");
            for (int other = 0; other < profile; other++) check(!Arrays.equals(voices[other], voices[profile]), "Every voice is distinct");
            check(peak(RobotEffect.process(new short[Tape.RATE / 4], controls)) == 0, "Silence stays silent");
        }
        check(Arrays.equals(source, original), "Processing leaves the recording unchanged");
        controls.robotProfile = RobotEffect.ROBOT;
        short[] followLow = RobotEffect.process(tone(220), controls), followHigh = RobotEffect.process(tone(330), controls);
        check(power(followLow, 220) > power(followLow, 330) * 10 && power(followHigh, 330) > power(followHigh, 220) * 10, "Carrier follows the pitch of the recording");
        controls.robotProfile = RobotEffect.GIANT;
        short[] giant = RobotEffect.process(tone(220), controls);
        check(power(giant, 110) > power(giant, 105) * 100, "Giant retains its lower carrier register");
        controls.robotProfile = RobotEffect.CHIP;
        short[] chip = RobotEffect.process(tone(220), controls);
        check(power(chip, 440) > power(chip, 220) * 10, "Chip retains its higher carrier register");
        controls.robotProfile = RobotEffect.ROBOT;
        short[] lowTone = tone(880), highTone = tone(2640);
        short[] lowVoice = RobotEffect.process(lowTone, controls), highVoice = RobotEffect.process(highTone, controls);
        check(power(lowVoice, 880) > power(lowVoice, 2640) * 10, "Low source resonance shapes the synthetic voice");
        check(power(highVoice, 2640) > power(highVoice, 880) * 10, "High source resonance shapes the synthetic voice");
        controls.robotMorph = 0;
        short[] low = RobotEffect.process(source, controls);
        controls.robotMorph = 100;
        check(!Arrays.equals(low, RobotEffect.process(source, controls)), "Morph changes spectral shape");
        controls.robotMorph = RobotEffect.DEFAULT_MORPH;
        short[] wet = RobotEffect.process(source, controls);
        controls.robotMix = 50;
        short[] mixed = RobotEffect.process(source, controls);
        for (int i = 0; i < mixed.length; i++) {
            if (Math.abs(mixed[i] - (source[i] + wet[i]) / 2.0) > 1) throw new AssertionError("Mix adds dry audio only in proportion to Mix");
        }
        checks++;
        controls.robotMix = 0;
        check(RobotEffect.process(source, controls) == source, "Zero Mix bypasses exactly");
        controls.robotMix = 100;
        controls.power(Sequencer.Effects.ROBOT, false);
        check(RobotEffect.process(source, controls) == source, "Power off bypasses exactly");
        controls.power(Sequencer.Effects.ROBOT, true);
        for (int length : new int[]{0, 1, 2, 3, 4, 511}) check(RobotEffect.process(noise(length), controls).length == length, "Short input is supported");
        short[] impulse = new short[Tape.RATE]; impulse[Tape.RATE / 4] = Short.MAX_VALUE;
        check(peak(RobotEffect.process(impulse, controls)) > 0, "Percussive input drives synthesis");
        short[] phrase = noise(Tape.RATE); Arrays.fill(phrase, Tape.RATE / 2, phrase.length, (short) 0);
        short[] gated = RobotEffect.process(phrase, controls);
        check(peak(Arrays.copyOfRange(gated, Tape.RATE * 3 / 4, gated.length)) < 4, "Carrier stops in pauses");
        Sequencer.Effects copy = controls.copy();
        controls.robotMorph = 73;
        check(!copy.sameGroup(Sequencer.Effects.ROBOT, controls), "Morph invalidates inherited Robot settings");
        check(copy.sameGroup(Sequencer.Effects.AUTOTUNE, controls), "Robot settings are independent of Autotune");
        copy.restoreGroup(Sequencer.Effects.ROBOT, controls);
        check(copy.robotMorph == 73, "Pad inheritance retains Robot controls");
        copy.resetGroup(Sequencer.Effects.ROBOT);
        check(!copy.hasRobot() && copy.robotMorph == RobotEffect.DEFAULT_MORPH, "Reset disables Robot");
        integration(source);
        System.out.println(checks + " robot checks passed");
    }

    private static void integration(short[] source) throws Exception {
        File path = Files.createTempFile("robot-tape-", ".wav").toFile();
        File output = Files.createTempFile("robot-export-", ".wav").toFile();
        try (Tape tape = new Tape(path)) {
            tape.write(0, source, source.length);
            Sequencer seq = new Sequencer(tape);
            Sequencer.Track track = seq.tracks[0]; track.end = source.length; track.steps = 1;
            track.power(Sequencer.Effects.ROBOT, true);
            check(seq.needsPreparation(), "Robot triggers preparation");
            seq.prepareAll(); seq.preview(0);
            short[] audio = new short[source.length]; seq.render(audio);
            check(Math.abs(correlation(source, audio)) < .1 && rms(audio) > 100, "Track preview plays synthetic audio");
            WavExporter.export(output, seq);
            ByteBuffer.wrap(Files.readAllBytes(output.toPath()), 44, source.length * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(audio);
            check(Math.abs(correlation(source, audio)) < .1 && rms(audio) > 100, "Export includes Robot");
            track.robotProfile = RobotEffect.CHIP;
            check(seq.needsPreparation(), "Profile edits invalidate cached audio");
            Sequencer.PreparedSample stale = seq.prepare(0, 100, 0);
            track.robotMorph = 60;
            boolean rejected = false;
            try { seq.apply(0, stale); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "Stale Robot processing cannot overwrite newer controls");
            seq.prepareAll();
            Sequencer.Effects pad = seq.editStep(0, 1); pad.power(Sequencer.Effects.ROBOT, false);
            seq.stop(); seq.previewStep(0, 1); seq.render(audio);
            check(correlation(source, audio) > .99, "A pad can bypass track Robot");
            check(Arrays.equals(source, tape.slice(0, source.length)), "Robot preserves the source tape");
        } finally { Files.deleteIfExists(path.toPath()); Files.deleteIfExists(output.toPath()); }
    }

    private static short[] noise(int length) {
        Random random = new Random(42);
        short[] source = new short[length];
        for (int i = 0; i < length; i++) source[i] = (short) (random.nextInt(20001) - 10000);
        return source;
    }

    private static short[] tone(double hz) {
        short[] source = new short[Tape.RATE];
        for (int i = 0; i < source.length; i++) source[i] = (short) Math.round(10000 * Math.sin(2 * Math.PI * hz * i / Tape.RATE));
        return source;
    }

    private static double correlation(short[] a, short[] b) {
        double cross = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { cross += (double) a[i] * b[i]; aa += (double) a[i] * a[i]; bb += (double) b[i] * b[i]; }
        return cross / Math.sqrt(aa * bb);
    }

    private static double rms(short[] source) {
        double power = 0;
        for (short sample : source) power += (double) sample * sample;
        return Math.sqrt(power / source.length);
    }

    private static int peak(short[] source) {
        int peak = 0;
        for (short sample : source) peak = Math.max(peak, Math.abs((int) sample));
        return peak;
    }

    private static double power(short[] source, double hz) {
        double real = 0, imaginary = 0;
        for (int i = source.length / 4; i < source.length; i++) {
            double phase = 2 * Math.PI * hz * i / Tape.RATE;
            real += source[i] * Math.cos(phase); imaginary += source[i] * Math.sin(phase);
        }
        return real * real + imaginary * imaginary;
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
