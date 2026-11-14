package com.tapedecksampler.app;

public final class ChoirEffect {
    public static final int MIN_COPIES = 1, MAX_COPIES = 8, DEFAULT_COPIES = 3;
    public static final int MAX_DETUNE = 1200, DEFAULT_DETUNE = 20;
    public static final int MAX_OFFSET_MS = 500, DEFAULT_OFFSET_MS = 30;
    private static final float COPY_GAIN = 0.7f;
    private static final int FRAMES_PER_MS = Tape.RATE / 1000;

    private ChoirEffect() {}

    public static double copyCents(int copy, int copies, int detune) {
        int pairs = (copies + 1) / 2;
        double cents = detune * ((copy + 2) / 2) / (double) pairs;
        return copy % 2 == 0 ? cents : -cents;
    }

    public static int copyDelay(int copy, int offsetMs) {
        return (copy + 1) * offsetMs * FRAMES_PER_MS;
    }

    public static short[] process(short[] source, Sequencer.Effects controls) {
        if (!controls.hasChoir() || source.length == 0) return source;
        int copies = Math.max(MIN_COPIES, Math.min(MAX_COPIES, controls.choirCopies));
        int detune = Math.max(0, Math.min(MAX_DETUNE, controls.choirDetune));
        int offset = Math.max(0, Math.min(MAX_OFFSET_MS, controls.choirOffset));
        float[] mix = new float[source.length + copyDelay(copies - 1, offset)];
        for (int i = 0; i < source.length; i++) mix[i] = source[i];
        for (int copy = 0; copy < copies; copy++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Choir processing cancelled");
            short[] voice = SampleProcessor.shift(source, copyCents(copy, copies, detune));
            int delay = copyDelay(copy, offset);
            for (int i = 0; i < voice.length && delay + i < mix.length; i++) mix[delay + i] += voice[i] * COPY_GAIN;
        }
        float scale = (float) (1 / Math.sqrt(1 + copies * COPY_GAIN * COPY_GAIN));
        short[] result = new short[mix.length];
        for (int i = 0; i < mix.length; i++) result[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(mix[i] * scale)));
        return result;
    }

    public static short[] reverse(short[] source, Sequencer.Effects controls) {
        if (!controls.hasReverse()) return source;
        short[] result = new short[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[source.length - 1 - i];
        return result;
    }
}
