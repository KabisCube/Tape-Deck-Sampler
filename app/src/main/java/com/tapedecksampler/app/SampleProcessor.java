package com.tapedecksampler.app;

public final class SampleProcessor {
    public static final int MIN_SPEED = 25, MAX_SPEED = 400, MIN_PITCH = -24, MAX_PITCH = 24;
    private static final double SEMITONES = 12;
    private static final int CANCEL_CHECK_MASK = 4095, MAX_WINDOW = 2048, MAX_SEARCH = 512;
    private static final int CORRELATION_POINTS = 128, COARSE_STEPS = 16;
    private static final double DRIFT_PENALTY = 0.000002;

    public static short[] process(short[] source, int speed, int semitones) {
        if (speed < MIN_SPEED || speed > MAX_SPEED || semitones < MIN_PITCH || semitones > MAX_PITCH) throw new IllegalArgumentException("Invalid sample transform");
        if (source.length == 0 || (speed == 100 && semitones == 0)) return source;
        double pitch = Math.pow(2, semitones / SEMITONES);
        short[] shifted = semitones == 0 ? source : resample(source, pitch);
        return stretch(shifted, Math.max(1, (int) Math.round(source.length * 100.0 / speed)));
    }

    private static short[] resample(short[] source, double ratio) {
        short[] result = new short[Math.max(1, (int) Math.round(source.length / ratio))];
        for (int i = 0; i < result.length; i++) {
            if ((i & CANCEL_CHECK_MASK) == 0 && Thread.currentThread().isInterrupted()) throw new IllegalStateException("Sample processing cancelled");
            double position = i * ratio;
            int base = Math.min(source.length - 1, (int) position);
            int next = Math.min(source.length - 1, base + 1);
            result[i] = (short) Math.round(source[base] + (source[next] - source[base]) * (position - base));
        }
        return result;
    }

    private static short[] stretch(short[] source, int length) {
        if (length == source.length) return source;
        short[] result = new short[length];
        int window = Integer.highestOneBit(Math.min(MAX_WINDOW, Math.max(2, source.length / 2)));
        int hop = window / 2;
        int search = Math.min(MAX_SEARCH, hop / 2);
        System.arraycopy(source, 0, result, 0, Math.min(window, Math.min(source.length, length)));
        double ratio = source.length / (double) length;
        for (int out = hop; out < length; out += hop) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Sample processing cancelled");
            int expected = Math.min(Math.max(0, source.length - window), (int) Math.round(out * ratio));
            int overlap = Math.min(hop, length - out);
            int low = Math.max(0, expected - search);
            int high = Math.min(Math.max(0, source.length - window), expected + search);
            int best = expected;
            double score = -Double.MAX_VALUE;
            int stride = Math.max(1, overlap / CORRELATION_POINTS);
            int coarse = Math.max(1, search / COARSE_STEPS);
            for (int candidate = low; candidate <= high; candidate += coarse) {
                double correlation = correlation(source, candidate, result, out, overlap, stride) - Math.abs(candidate - expected) * DRIFT_PENALTY;
                if (correlation > score) { best = candidate; score = correlation; }
            }
            low = Math.max(low, best - coarse);
            high = Math.min(high, best + coarse);
            for (int candidate = low; candidate <= high; candidate++) {
                double correlation = correlation(source, candidate, result, out, overlap, stride) - Math.abs(candidate - expected) * DRIFT_PENALTY;
                if (correlation > score) { best = candidate; score = correlation; }
            }
            int count = Math.min(window, Math.min(length - out, source.length - best));
            for (int i = 0; i < count; i++) {
                if (i < hop) {
                    float mix = i / (float) hop;
                    result[out + i] = (short) Math.round(result[out + i] * (1 - mix) + source[best + i] * mix);
                } else result[out + i] = source[best + i];
            }
        }
        return result;
    }

    private static double correlation(short[] source, int from, short[] target, int to, int count, int stride) {
        double cross = 0, a = 1, b = 1;
        for (int i = 0; i < count && from + i < source.length; i += stride) {
            double x = source[from + i], y = target[to + i];
            cross += x * y; a += x * x; b += y * y;
        }
        return cross / Math.sqrt(a * b);
    }
}
