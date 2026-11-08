package com.tapedecksampler.app;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

public final class CaptureTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File path = Files.createTempFile("sampler-device-capture", ".wav").toFile();
        try {
            short[] samples = new short[9000];
            for (int i = 0; i < samples.length; i++) samples[i] = (short) (i * 37);
            try (CaptureFile capture = new CaptureFile(path)) {
                capture.write(new short[]{Short.MIN_VALUE, Short.MAX_VALUE, 0}, 3);
                capture.write(samples, samples.length);
                check(capture.frames() == 9003, "Recording accumulates exact frame counts across blocks");
            }
            byte[] recorded = Files.readAllBytes(path.toPath());
            ByteBuffer wav = ByteBuffer.wrap(recorded).order(ByteOrder.LITTLE_ENDIAN);
            check(recorded.length == 44 + 9003 * 2, "Stopped recording contains only the captured audio");
            check(wav.getInt(4) == recorded.length - 8 && wav.getInt(40) == 9003 * 2, "WAV header matches the stopped recording length");
            check(wav.getInt(24) == Tape.RATE && wav.getShort(22) == 1 && wav.getShort(34) == 16, "Capture produces tape-compatible mono PCM");
            check(wav.getShort(44) == Short.MIN_VALUE && wav.getShort(46) == Short.MAX_VALUE && wav.getShort(48) == 0, "Capture preserves signed PCM samples");
            for (int i = 0; i < samples.length; i++) check(wav.getShort(50 + i * 2) == samples[i], "Chunk boundaries preserve captured samples");
            try (CaptureFile capture = new CaptureFile(path)) {
                check(capture.frames() == 0 && path.length() == 44, "A new capture replaces the previous staging file");
                short[] longRecording = new short[Tape.LENGTH + 48]; Arrays.fill(longRecording, (short) 1234);
                capture.write(longRecording, longRecording.length);
                capture.write(samples, samples.length);
                check(capture.frames() == Tape.LENGTH, "Device recording stops at the twenty-second tape limit");
            }
            check(path.length() == 44 + Tape.LENGTH * 2L, "A full recording fits the sample tape exactly");
            try (CaptureFile capture = new CaptureFile(path)) { capture.write(samples, 0); }
            check(path.length() == 44 && ByteBuffer.wrap(Files.readAllBytes(path.toPath())).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == 0, "Empty captures have a valid empty WAV header");
        } finally { Files.deleteIfExists(path.toPath()); }
        System.out.println(checks + " capture checks passed");
    }

    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); checks++; }
}
