package com.tapedecksampler.app;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

public final class ExportTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File path = Files.createTempFile("export-tape-", ".wav").toFile();
        File output = Files.createTempFile("export-mix-", ".wav").toFile();
        try (Tape tape = new Tape(path)) {
            short[] signal = new short[480]; Arrays.fill(signal, (short) 10000); tape.write(0, signal, signal.length);
            Sequencer source = new Sequencer(tape);
            source.tracks[0].end = signal.length; source.tracks[0].steps = 1;
            source.play(); source.render(new short[64]);
            WavExporter.export(output, source);
            ByteBuffer wav = read(output);
            check(wav.getInt(40) == 96000 * 2, "WAV contains one sequence loop rather than the source tape");
            check(wav.getShort(44 + 100 * 2) == 8000, "WAV renders track volume");
            check(source.isPlaying() && source.step() == 0 && source.selectedPage() == 0, "Export leaves live playback and selection untouched");
            check(Arrays.equals(tape.slice(0, 480), signal), "Export preserves source samples");
            source.selectPage(1); source.tracks[0].steps = 1; source.editStep(0, 0).gain = .5f;
            source.selectPage(2); source.tracks[0].steps = 1; source.page(2).enabled = false;
            source.restoreSelectedPage(1);
            WavExporter.export(output, source); wav = read(output);
            check(wav.getInt(40) == 192000 * 2, "Export includes enabled pages exactly once and skips disabled pages");
            check(wav.getShort(44 + 100 * 2) == 8000 && wav.getShort(44 + (96000 + 100) * 2) == 4000, "Export uses numerical page order and pad effects");
            check(source.selectedPage() == 1 && !source.page(2).enabled, "Export does not change page state");
            Sequencer later = new Sequencer(tape);
            later.tracks[0].end = signal.length; later.tracks[0].steps = 1; later.tracks[0].every = 3;
            later.tracks[1].end = signal.length; later.tracks[1].steps = 2; later.tracks[1].every = 2;
            check(later.passesForAllPads() == 3, "All pads length follows the highest Every Nth");
            WavExporter.export(output, later, 1); wav = read(output);
            check(wav.getInt(40) == 96000 * 2 && wav.getShort(44 + 100 * 2) == 0, "One loop leaves later pads out");
            WavExporter.export(output, later, 3); wav = read(output);
            check(wav.getInt(40) == 288000 * 2 && wav.getShort(44 + 100 * 2) == 0 && wav.getShort(44 + (192000 + 100) * 2) == 8000, "All pads export reaches every pad");
            later.tracks[0].mute = true;
            check(later.passesForAllPads() == 2, "Muted tracks do not extend the export");
            later.tracks[0].mute = false; later.tracks[0].chance = 0;
            check(later.passesForAllPads() == 2, "Pads that can never play do not extend the export");
            later.page(1).enabled = true;
            later.selectPage(1); later.tracks[0].steps = 1; later.tracks[0].chance = 100; later.editStep(0, 0).every = 4; later.selectPage(0);
            check(later.passesForAllPads() == 4, "Other enabled pages count toward the length");
            Sequencer tail = new Sequencer(tape);
            tail.tracks[0].end = signal.length; tail.tracks[0].steps = 1 << 15;
            tail.tracks[0].delayTime = 500; tail.tracks[0].delayMix = 50; tail.tracks[0].delayFeedback = 0;
            WavExporter.export(output, tail); wav = read(output);
            check(wav.getInt(40) > 96000 * 2 && wav.getShort(44 + (90000 + 24000 + 100) * 2) == 4000, "WAV includes delay tails past the last page");
            check(wav.getInt(40) < 120000 * 2, "WAV trims silence after the effects tail");
            Sequencer nudged = new Sequencer(tape);
            nudged.tempo(240); nudged.tracks[0].end = signal.length; nudged.tracks[0].steps = 1 << 15; nudged.tracks[0].nudge = 100;
            WavExporter.export(output, nudged); wav = read(output);
            check(wav.getInt(40) > 49800 * 2 && wav.getShort(44 + (49800 + 100) * 2) == 8000, "Export retains hits nudged past the final page boundary");
            source.stop();
        } finally { Files.deleteIfExists(path.toPath()); Files.deleteIfExists(output.toPath()); }
        System.out.println(checks + " export checks passed");
    }

    private static ByteBuffer read(File file) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        ByteBuffer wav = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        check(data[0] == 'R' && data[8] == 'W' && wav.getInt(4) == data.length - 8 && wav.getInt(40) == data.length - 44, "WAV header matches its audio length");
        check(wav.getInt(24) == Tape.RATE && wav.getShort(22) == 1 && wav.getShort(34) == 16, "WAV is 48 kHz mono PCM16");
        return wav;
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
