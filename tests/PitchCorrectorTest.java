package com.tapedecksampler.app;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

public final class PitchCorrectorTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Sequencer.Effects controls = new Sequencer.Effects();
        short[] dry = tone(430, Tape.RATE * 2);
        check(PitchCorrector.process(dry, controls) == dry, "Default autotune is an exact bypass");
        controls.autoStrength = 100; controls.autoRetune = 0;
        for (double[] test : new double[][]{{430, 440}, {452, 440}, {260, 261.6256}, {180, 184.9972}, {73, 73.4162}, {1100, 1108.7305}}) {
            short[] source = tone(test[0], Tape.RATE * 2);
            short[] tuned = PitchCorrector.process(source, controls);
            double actual = frequency(tuned, Tape.RATE / 2, Tape.RATE * 3 / 2);
            check(tuned.length == source.length, "Correction preserves sample duration");
            check(Math.abs(actual - test[1]) < 2, "Chromatic correction at " + test[0] + " Hz: " + actual);
        }
        controls.autoScale = 1;
        short[] major = PitchCorrector.process(tone(375, Tape.RATE * 2), controls);
        check(Math.abs(frequency(major, Tape.RATE / 2, Tape.RATE * 3 / 2) - 391.9954) < 2, "Correction respects the selected major scale");
        check(PitchCorrector.nearestNote(64, 0, 2) == 63 && PitchCorrector.nearestNote(61, 2, 1) == 61, "Key and minor scale choose their allowed notes");
        controls.autoScale = 0; controls.autoStrength = 50;
        short[] partial = PitchCorrector.process(dry, controls);
        check(Math.abs(frequency(partial, Tape.RATE / 2, Tape.RATE * 3 / 2) - Math.sqrt(430 * 440)) < 2, "Correction strength moves pitch partway toward the note");
        controls.autoStrength = 100; controls.autoRetune = 500;
        short[] gentle = PitchCorrector.process(dry, controls);
        double early = frequency(gentle, 2400, 9600), late = frequency(gentle, 60000, 84000);
        check(Math.abs(late - 440) < Math.abs(early - 440) && late > 438, "Slow retuning approaches the target gradually: " + early + ", " + late);
        controls.autoRetune = 0; controls.autoMix = 0;
        check(PitchCorrector.process(dry, controls) == dry, "Zero autotune mix is an exact bypass");
        controls.autoMix = 100;
        short[] wet = PitchCorrector.process(dry, controls);
        controls.autoMix = 50;
        short[] blend = PitchCorrector.process(dry, controls);
        boolean mixed = true;
        for (int i = 0; i < dry.length; i++) mixed &= Math.abs(blend[i] - Math.round((dry[i] + wet[i]) / 2.0)) <= 1;
        check(mixed, "Autotune mix blends corrected and original samples");
        controls.autoMix = 100;
        short[] silence = new short[Tape.RATE];
        check(PitchCorrector.process(silence, controls) == silence, "Silence remains unchanged");
        short[] noise = new short[Tape.RATE]; Random random = new Random(42);
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (random.nextInt(12000) - 6000);
        check(Arrays.equals(PitchCorrector.process(noise, controls), noise), "Unpitched noise remains unchanged");
        short[] harmonics = new short[Tape.RATE * 2];
        for (int i = 0; i < harmonics.length; i++) {
            double phase = 2 * Math.PI * 430 * i / Tape.RATE;
            harmonics[i] = (short) Math.round(6000 * (.2 * Math.sin(phase) + Math.sin(phase * 2) + .6 * Math.sin(phase * 3)));
        }
        short[] harmonicTune = PitchCorrector.process(harmonics, controls);
        double targetPower = spectralPower(harmonicTune, 880) + spectralPower(harmonicTune, 1320);
        double originalPower = spectralPower(harmonicTune, 860) + spectralPower(harmonicTune, 1290);
        check(targetPower > originalPower * 10, "Correction follows the fundamental when upper harmonics are stronger");
        short[] melody = new short[Tape.RATE * 2];
        System.arraycopy(tone(430, Tape.RATE), 0, melody, 0, Tape.RATE);
        System.arraycopy(tone(515, Tape.RATE), 0, melody, Tape.RATE, Tape.RATE);
        short[] corrected = PitchCorrector.process(melody, controls);
        check(Math.abs(frequency(corrected, 12000, 36000) - 440) < 2 && Math.abs(frequency(corrected, 60000, 84000) - 523.2511) < 2, "Correction follows changing notes within a sample");
        hardLock(controls);
        vocalBoundaries(controls);
        integration(dry);
        System.out.println(checks + " pitch correction checks passed");
    }

    private static void hardLock(Sequencer.Effects controls) {
        for (double target : new double[]{110, 220, 440}) {
            short[] source = new short[Tape.RATE * 3];
            double phase = 0;
            for (int i = 0; i < source.length; i++) {
                double time = i / (double) Tape.RATE;
                double cents = -25 + 20 * Math.sin(2 * Math.PI * 6 * time);
                phase += 2 * Math.PI * target * Math.pow(2, cents / 1200) / Tape.RATE;
                source[i] = (short) Math.round(20000 * Math.sin(phase));
            }
            short[] tuned = PitchCorrector.process(source, controls);
            double last = -1, totalError = 0, worstError = 0;
            int periods = 0;
            for (int i = Tape.RATE / 2; i < tuned.length - Tape.RATE / 2; i++) {
                if (tuned[i - 1] > 0 || tuned[i] <= 0) continue;
                double crossing = i - 1 - tuned[i - 1] / (double) (tuned[i] - tuned[i - 1]);
                if (last >= 0) {
                    double cents = Math.abs(1200 * Math.log(Tape.RATE / (crossing - last) / target) / Math.log(2));
                    totalError += cents;
                    worstError = Math.max(worstError, cents);
                    periods++;
                }
                last = crossing;
            }
            check(periods > target, "Hard tuning retains a continuous vocal pitch");
            check(totalError / periods < 1 && worstError < 3, "Instant retune removes vibrato at " + target + " Hz: " + worstError + " cents maximum");
        }
    }

    private static void vocalBoundaries(Sequencer.Effects controls) {
        for (double fundamental : new double[]{75, 110, 180, 220, 300, 440}) {
            short[] source = vowel(fundamental, Tape.RATE * 3);
            short[] tuned = PitchCorrector.process(source, controls);
            check(tuned.length == source.length, "Vocal correction retains the complete sample");
            check(maxJump(tuned) <= maxJump(source) * 1.5, "Vocal grain boundaries do not introduce clicks at " + fundamental + " Hz");
            int sourcePeak = 0, tunedPeak = 0;
            for (int i = 0; i < source.length; i++) {
                sourcePeak = Math.max(sourcePeak, Math.abs((int) source[i]));
                tunedPeak = Math.max(tunedPeak, Math.abs((int) tuned[i]));
            }
            check(tunedPeak <= sourcePeak, "Vocal correction does not add clipping or boost peaks");
        }
        int syllable = Tape.RATE / 2, gap = Tape.RATE / 3;
        short[] phrase = new short[syllable * 2 + gap];
        System.arraycopy(tone(173, syllable), 0, phrase, 0, syllable);
        System.arraycopy(tone(211, syllable), 0, phrase, syllable + gap, syllable);
        short[] tuned = PitchCorrector.process(phrase, controls);
        int margin = Tape.RATE / 10;
        check(Arrays.equals(Arrays.copyOfRange(phrase, syllable + margin, syllable + gap - margin),
                Arrays.copyOfRange(tuned, syllable + margin, syllable + gap - margin)), "Pauses between syllables remain silent");
    }

    private static short[] vowel(double fundamental, int length) {
        double[] signal = new double[length];
        double[] formants = {700, 1200, 2600};
        double bandwidth = 80, phase = 0, peak = 0;
        Random random = new Random(1);
        for (int i = 0; i < signal.length; i++) {
            double time = i / (double) Tape.RATE;
            double semitones = .8 * Math.sin(2 * Math.PI * .7 * time) + .25 * Math.sin(2 * Math.PI * 6 * time);
            double hz = fundamental * Math.pow(2, semitones / 12);
            phase += 2 * Math.PI * hz / Tape.RATE;
            double value = 0;
            for (int harmonic = 1; harmonic * hz < 6000; harmonic++) {
                double amplitude = 0;
                for (double formant : formants) amplitude += 1 / (1 + Math.pow((harmonic * hz - formant) / bandwidth, 2));
                value += Math.cos(phase * harmonic) * amplitude / Math.sqrt(harmonic);
            }
            signal[i] = value + .01 * random.nextGaussian();
            peak = Math.max(peak, Math.abs(signal[i]));
        }
        short[] samples = new short[length];
        for (int i = 0; i < length; i++) samples[i] = (short) Math.round(28000 * signal[i] / peak);
        return samples;
    }

    private static int maxJump(short[] samples) {
        int largest = 0;
        for (int i = 1; i < samples.length; i++) largest = Math.max(largest, Math.abs(samples[i] - samples[i - 1]));
        return largest;
    }

    private static void integration(short[] source) throws Exception {
        File path = Files.createTempFile("pitch-tape-", ".wav").toFile();
        File output = Files.createTempFile("pitch-export-", ".wav").toFile();
        try (Tape tape = new Tape(path)) {
            tape.write(0, source, source.length);
            Sequencer seq = new Sequencer(tape);
            Sequencer.Track track = seq.tracks[0]; track.end = source.length; track.steps = 1;
            track.autoStrength = 100; track.autoRetune = 0;
            check(seq.needsPreparation(), "Enabling autotune invalidates sample preparation");
            seq.prepareAll(); seq.preview(0);
            short[] audio = new short[source.length]; seq.render(audio);
            check(Math.abs(frequency(audio, 24000, 72000) - 440) < 2, "Track preview plays the corrected sample");
            seq.stop(); track.resetGroup(Sequencer.Effects.AUTOTUNE); track.autoRetune = 0; seq.preview(0);
            seq.render(new short[12000]);
            track.autoStrength = 100;
            seq.prepareAll(); seq.render(audio);
            check(Math.abs(frequency(audio, 12000, 60000) - 440) < 2, "Background preparation corrects an already playing voice");
            track.autoKey = 11; track.autoScale = 1;
            seq.stop(); seq.preview(0); seq.render(audio);
            check(Math.abs(frequency(audio, 24000, 72000) - 440) < 2, "Playback holds the last render until new settings are prepared");
            track.autoKey = 0; track.autoScale = 0;
            track.power(Sequencer.Effects.AUTOTUNE, false);
            seq.stop(); seq.preview(0); seq.render(audio);
            check(Math.abs(frequency(audio, 24000, 72000) - 430) < 1 && track.autoStrength == 100, "Autotune power off restores original pitch and retains strength");
            track.power(Sequencer.Effects.AUTOTUNE, true);
            seq.prepareAll();
            Sequencer.Effects pad = seq.editStep(0, 1); pad.autoStrength = 0;
            seq.stop(); seq.previewStep(0, 1); seq.render(audio);
            check(Math.abs(frequency(audio, 24000, 72000) - 430) < 1, "A pad can bypass track autotune");
            pad.autoStrength = 100; pad.autoScale = 1; pad.autoKey = 11;
            seq.prepareAll(); seq.stop(); seq.previewStep(0, 1); seq.render(audio);
            check(Math.abs(frequency(audio, 24000, 72000) - 415.3047) < 2, "Pad preparation uses the pad's own key and scale");
            WavExporter.export(output, seq);
            byte[] bytes = Files.readAllBytes(output.toPath());
            ByteBuffer.wrap(bytes, 44, source.length * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(audio);
            check(Math.abs(frequency(audio, 24000, 72000) - 440) < 2, "WAV export includes track pitch correction");
            check(Arrays.equals(source, tape.slice(0, source.length)), "Pitch correction preserves the source tape");
            track.autoScale = 2;
            check(seq.needsPreparation(), "Changing scale invalidates the prepared correction");
            Sequencer.PreparedSample stale = seq.prepare(0, 75, 0);
            track.autoKey = 2;
            boolean rejected = false;
            try { seq.apply(0, stale); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected && track.speed == 100, "Stale correction cannot overwrite newer effect settings");
            seq.resetEffects(0);
            check(track.autoStrength == 0 && !seq.needsPreparation() && track.padEffects[1] == null, "Reset disables autotune and removes pad overrides");
        } finally { Files.deleteIfExists(path.toPath()); Files.deleteIfExists(output.toPath()); }
    }

    private static short[] tone(double hz, int length) {
        short[] samples = new short[length];
        for (int i = 0; i < length; i++) samples[i] = (short) Math.round(10000 * Math.sin(2 * Math.PI * hz * i / Tape.RATE));
        return samples;
    }

    private static double frequency(short[] samples, int from, int to) {
        double first = -1, last = -1;
        int crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if (samples[i - 1] <= 0 && samples[i] > 0) {
                double position = i - 1 - samples[i - 1] / (double) (samples[i] - samples[i - 1]);
                if (first < 0) first = position;
                last = position; crossings++;
            }
        }
        return crossings < 2 ? 0 : (crossings - 1) * Tape.RATE / (last - first);
    }

    private static double spectralPower(short[] samples, double frequency) {
        double real = 0, imaginary = 0;
        for (int i = 24000; i < 72000; i++) {
            double phase = 2 * Math.PI * frequency * i / Tape.RATE;
            real += samples[i] * Math.cos(phase); imaginary += samples[i] * Math.sin(phase);
        }
        return real * real + imaginary * imaginary;
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
