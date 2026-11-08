package com.tapedecksampler.app;

public final class ClapDetector {
    private static final double INITIAL_NOISE = 100;
    private static final int MIN_ONSET_LEVEL = 1800;
    private static final double ONSET_NOISE_RATIO = 8;
    private static final int MIN_PEAK_LEVEL = 3500;
    private static final double PEAK_NOISE_RATIO = 16;
    private static final double IDLE_NOISE_SECONDS = .15;
    private static final double ARMED_NOISE_SECONDS = .5;
    private static final double PEAK_WINDOW_SECONDS = .01;
    private static final double QUIET_BEFORE_CLAP_SECONDS = .05;

    private final int rate, peakWindow, quietBeforeClap;
    private long frames, onset = -1;
    private double noise = INITIAL_NOISE;
    private int quiet;

    public ClapDetector(int rate) {
        this.rate = rate;
        peakWindow = (int) (rate * PEAK_WINDOW_SECONDS);
        quietBeforeClap = (int) (rate * QUIET_BEFORE_CLAP_SECONDS);
    }

    public long accept(short[] samples, int count, boolean armed) {
        for (int i = 0; i < count; i++, frames++) {
            int amplitude = Math.abs((int) samples[i]);
            double threshold = Math.max(MIN_ONSET_LEVEL, noise * ONSET_NOISE_RATIO);
            double peak = Math.max(MIN_PEAK_LEVEL, noise * PEAK_NOISE_RATIO);
            if (!armed) {
                noise += (amplitude - noise) / (rate * IDLE_NOISE_SECONDS);
                quiet = 0;
                onset = -1;
                continue;
            }
            if (onset >= 0) {
                if (amplitude >= peak) return onset;
                if (frames - onset > peakWindow) { onset = -1; quiet = 0; }
            } else if (amplitude >= threshold && quiet >= quietBeforeClap) {
                onset = frames;
                if (amplitude >= peak) return onset;
            } else if (amplitude < threshold) {
                quiet++;
                noise += (amplitude - noise) / (rate * ARMED_NOISE_SECONDS);
            } else quiet = 0;
        }
        return -1;
    }
}
