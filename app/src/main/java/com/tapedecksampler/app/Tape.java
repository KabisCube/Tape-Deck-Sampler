package com.tapedecksampler.app;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Tape implements AutoCloseable {
    public static final int RATE = 48000;
    public static final int SECONDS = 20;
    public static final int LENGTH = RATE * SECONDS;
    public static final float PCM_SCALE = 32768f;
    public static final int HEADER_BYTES = 44, BYTES_PER_SAMPLE = 2, RIFF_PREFIX_BYTES = 8;
    private static final int FMT_CHUNK_BYTES = 16, PCM_FORMAT = 1, CHANNELS = 1, BITS_PER_SAMPLE = 16;
    private static final int DEFAULT_PEAK_PERCENT = 95, FADE_DIVISOR = 100, EXPORT_BLOCK_BYTES = 8192;
    private static final long FILE_BYTES = LENGTH * (long) BYTES_PER_SAMPLE + HEADER_BYTES;
    static final int[] LEGACY_LENGTHS = {RATE * 60, RATE * 30};
    final short[] samples = new short[LENGTH];
    private final RandomAccessFile file;
    private short[] undo;
    private volatile long revision;

    public Tape(File path) throws IOException {
        migrateLegacy(path);
        file = new RandomAccessFile(path, "rw");
        if (file.length() == FILE_BYTES) {
            byte[] existingHeader = new byte[HEADER_BYTES];
            file.readFully(existingHeader);
            if (!Arrays.equals(existingHeader, header())) {
                file.close();
                throw new IOException("Sample tape header is damaged");
            }
            byte[] data = new byte[LENGTH * BYTES_PER_SAMPLE];
            file.readFully(data);
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        } else if (file.length() == 0) {
            file.setLength(FILE_BYTES);
            file.seek(0);
            file.write(header());
            persist(0, LENGTH);
        } else {
            file.close();
            throw new IOException("Sample tape size is invalid; audio has been preserved");
        }
    }

    private static void migrateLegacy(File path) throws IOException {
        int legacyLength = 0;
        for (int length : LEGACY_LENGTHS) if (path.length() == length * (long) BYTES_PER_SAMPLE + HEADER_BYTES) legacyLength = length;
        if (legacyLength == 0) return;
        java.nio.file.Files.copy(path.toPath(), new File(path.getParentFile(), path.getName() + "." + legacyLength / RATE + "s-backup").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile legacy = new RandomAccessFile(path, "rw")) {
            byte[] data = new byte[LENGTH * BYTES_PER_SAMPLE];
            legacy.seek(HEADER_BYTES);
            legacy.readFully(data);
            legacy.setLength(FILE_BYTES);
            legacy.seek(0);
            legacy.write(header());
            legacy.write(data);
            legacy.getFD().sync();
        }
    }

    public static byte[] header() { return header(LENGTH); }

    public static byte[] header(long frames) {
        int dataBytes = (int) (frames * BYTES_PER_SAMPLE);
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(dataBytes + HEADER_BYTES - RIFF_PREFIX_BYTES);
        b.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(FMT_CHUNK_BYTES);
        b.putShort((short) PCM_FORMAT).putShort((short) CHANNELS).putInt(RATE).putInt(RATE * CHANNELS * BYTES_PER_SAMPLE);
        b.putShort((short) (CHANNELS * BYTES_PER_SAMPLE)).putShort((short) BITS_PER_SAMPLE);
        b.put(new byte[]{'d','a','t','a'}).putInt(dataBytes);
        return b.array();
    }

    public synchronized void checkpoint() {
        undo = samples.clone();
    }

    public synchronized boolean undo() throws IOException {
        if (undo == null) return false;
        short[] previous = samples.clone();
        System.arraycopy(undo, 0, samples, 0, LENGTH);
        undo = previous;
        persist(0, LENGTH);
        return true;
    }

    public synchronized int write(int position, short[] data, int count) throws IOException {
        int size = Math.min(Math.min(count, data.length), LENGTH - position);
        if (position < 0 || size <= 0) return 0;
        System.arraycopy(data, 0, samples, position, size);
        persist(position, size);
        return size;
    }

    public synchronized void silence(int start, int end) throws IOException {
        attenuate(start, end, 100);
    }

    public synchronized void attenuate(int start, int end, int amount) throws IOException {
        validate(start, end);
        if (amount < 0 || amount > 100) throw new IllegalArgumentException("Invalid silence amount");
        checkpoint();
        float gain = 1 - amount / 100f;
        for (int i = start; i < end; i++) samples[i] = (short) Math.round(samples[i] * gain);
        persist(start, end - start);
    }

    public synchronized void normalize(int start, int end) throws IOException {
        normalize(start, end, DEFAULT_PEAK_PERCENT);
    }

    public synchronized void normalize(int start, int end, int percent) throws IOException {
        validate(start, end);
        if (percent < 1 || percent > 100) throw new IllegalArgumentException("Invalid peak level");
        checkpoint();
        int peak = 0;
        for (int i = start; i < end; i++) peak = Math.max(peak, Math.abs((int) samples[i]));
        if (peak == 0) return;
        float gain = (int) (Short.MAX_VALUE * percent / 100f) / (float) peak;
        for (int i = start; i < end; i++) samples[i] = (short) Math.round(samples[i] * gain);
        persist(start, end - start);
    }

    public synchronized void fade(int start, int end) throws IOException {
        int ramp = Math.min(RATE / FADE_DIVISOR, (end - start) / 2);
        fade(start, end, ramp, ramp);
    }

    public synchronized void fade(int start, int end, int fadeIn, int fadeOut) throws IOException {
        validate(start, end);
        if (fadeIn < 0 || fadeOut < 0 || fadeIn > end - start || fadeOut > end - start) throw new IllegalArgumentException("Invalid fade length");
        checkpoint();
        for (int i = 0; i < fadeIn; i++) samples[start + i] = (short) (samples[start + i] * (float) i / fadeIn);
        for (int i = 0; i < fadeOut; i++) samples[end - 1 - i] = (short) (samples[end - 1 - i] * (float) i / fadeOut);
        persist(start, end - start);
    }

    private void validate(int start, int end) {
        if (start < 0 || end > LENGTH || end <= start) throw new IllegalArgumentException("Invalid tape selection");
    }

    private void persist(int start, int count) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(count * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN);
        b.asShortBuffer().put(samples, start, count);
        file.seek(HEADER_BYTES + start * (long) BYTES_PER_SAMPLE);
        file.write(b.array());
        revision++;
    }

    public long revision() { return revision; }

    public synchronized short[] slice(int start, int end) {
        validate(start, end);
        return Arrays.copyOfRange(samples, start, end);
    }

    public synchronized float[] waveform(int start, int end, int bins) {
        float[] peaks = new float[bins];
        for (int x = 0; x < bins; x++) {
            int a = start + (int) ((long) (end - start) * x / bins);
            int b = start + (int) ((long) (end - start) * (x + 1) / bins);
            int peak = 0;
            for (int i = a; i < b; i++) peak = Math.max(peak, Math.abs((int) samples[i]));
            peaks[x] = peak / PCM_SCALE;
        }
        return peaks;
    }

    public void export(OutputStream out) throws IOException {
        short[] snapshot;
        synchronized (this) { snapshot = samples.clone(); }
        out.write(header());
        byte[] block = new byte[EXPORT_BLOCK_BYTES];
        for (int i = 0; i < LENGTH; ) {
            int count = Math.min(block.length / BYTES_PER_SAMPLE, LENGTH - i);
            for (int j = 0; j < count; j++) {
                short s = snapshot[i++];
                block[j * 2] = (byte) s;
                block[j * 2 + 1] = (byte) (s >> 8);
            }
            out.write(block, 0, count * BYTES_PER_SAMPLE);
        }
    }

    public synchronized void flush() throws IOException {
        file.getFD().sync();
    }

    @Override public synchronized void close() throws IOException {
        file.close();
    }
}
