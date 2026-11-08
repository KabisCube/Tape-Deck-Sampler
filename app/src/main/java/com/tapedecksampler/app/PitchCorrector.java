package com.tapedecksampler.app;

public final class PitchCorrector {
    public static final String[] KEYS = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    public static final String[] SCALES = {"Chromatic", "Major", "Minor", "Major pentatonic", "Minor pentatonic"};
    private static final int[][] NOTES = {{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, {0, 2, 4, 5, 7, 9, 11}, {0, 2, 3, 5, 7, 8, 10}, {0, 2, 4, 7, 9}, {0, 3, 5, 7, 10}};
    private static final int HOP = 960, WINDOW = 1024, MIN_LAG = 10, MAX_LAG = 184, RING = 4096;
    private static final int DECIMATION = 4, SEMITONES = 12, A4_MIDI = 69;
    private static final double A4_HZ = 440, MIN_POWER = 10000, MIN_CORRECTION = .01, MARK_SEARCH = .15, PERIOD_THRESHOLD = .15, MIN_RATIO = .5, MIN_WEIGHT = .0001;

    private PitchCorrector() {}

    public static short[] process(short[] source, Sequencer.Effects controls) {
        if (!controls.hasAutotune() || source.length < WINDOW) return source;
        int count = (source.length + HOP - 1) / HOP;
        double[] periods = new double[count], ratios = new double[count];
        double[] signal = new double[WINDOW], difference = new double[MAX_LAG + 1];
        double correction = 0;
        boolean changed = false;
        double follow = controls.autoRetune == 0 ? 1 : 1 - Math.exp(-HOP * 1000.0 / (Tape.RATE * controls.autoRetune));
        for (int frame = 0; frame < count; frame++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Pitch correction cancelled");
            int start = Math.max(0, Math.min(source.length - WINDOW * DECIMATION, frame * HOP - WINDOW * DECIMATION / 2));
            double power = 0;
            for (int i = 0; i < WINDOW; i++) {
                int offset = start + i * DECIMATION;
                signal[i] = offset + 3 < source.length ? (source[offset] + source[offset + 1] + source[offset + 2] + source[offset + 3]) / (double) DECIMATION : 0;
                power += signal[i] * signal[i];
            }
            double period = power / WINDOW < MIN_POWER ? 0 : detect(signal, difference);
            if (period == 0) { correction = 0; ratios[frame] = 1; continue; }
            double midi = A4_MIDI + SEMITONES * Math.log(Tape.RATE / period / A4_HZ) / Math.log(2);
            double desired = (nearestNote(midi, controls.autoKey, controls.autoScale) - midi) * controls.autoStrength / 100.0;
            correction += (desired - correction) * follow;
            periods[frame] = period;
            ratios[frame] = Math.pow(2, correction / SEMITONES);
            changed |= Math.abs(correction) >= MIN_CORRECTION;
        }
        if (!changed) return source;
        short[] result = new short[source.length];
        double[] sums = new double[RING], weights = new double[RING];
        double position = 0, previousMark = -1, nextMark = -1;
        int written = 0;
        while (position < source.length) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Pitch correction cancelled");
            double period = interpolate(periods, position);
            if (period < 1) {
                previousMark = nextMark = -1;
                position += HOP;
            } else {
                if (nextMark < 0) {
                    nextMark = peak(source, position, period / 2);
                    previousMark = nextMark;
                    position = Math.max(position, nextMark);
                }
                while (nextMark < position) {
                    previousMark = nextMark;
                    double local = interpolate(periods, nextMark);
                    nextMark = peak(source, nextMark + (local > 0 ? local : period), period * MARK_SEARCH);
                    if (nextMark <= previousMark) nextMark = previousMark + period;
                }
                double mark = position - previousMark < nextMark - position ? previousMark : nextMark;
                int from = Math.max(written, (int) Math.ceil(position - period));
                int to = Math.min(source.length - 1, (int) Math.floor(position + period));
                for (int i = from; i <= to; i++) {
                    double offset = i - position, samplePosition = mark + offset;
                    if (samplePosition < 0 || samplePosition >= source.length - 1) continue;
                    int base = (int) samplePosition;
                    double sample = source[base] + (source[base + 1] - source[base]) * (samplePosition - base);
                    double weight = .5 + .5 * Math.cos(Math.PI * offset / period);
                    sums[i % RING] += sample * weight; weights[i % RING] += weight;
                }
                position += period / Math.max(MIN_RATIO, interpolate(ratios, position));
            }
            int limit = Math.min(source.length, (int) position - MAX_LAG * DECIMATION - DECIMATION);
            while (written < limit) written = flush(source, result, sums, weights, written, controls.autoMix);
        }
        while (written < source.length) written = flush(source, result, sums, weights, written, controls.autoMix);
        return result;
    }

    public static int nearestNote(double midi, int key, int scale) {
        int best = (int) Math.round(midi);
        double distance = Double.MAX_VALUE;
        for (int note = (int) Math.floor(midi) - SEMITONES; note <= (int) Math.ceil(midi) + SEMITONES; note++) {
            int pitch = Math.floorMod(note - key, SEMITONES);
            for (int allowed : NOTES[scale]) {
                if (pitch == allowed && Math.abs(note - midi) < distance) { best = note; distance = Math.abs(note - midi); }
            }
        }
        return best;
    }

    private static double detect(double[] signal, double[] difference) {
        double total = 0;
        difference[0] = 1;
        for (int lag = 1; lag <= MAX_LAG; lag++) {
            double sum = 0;
            for (int i = 0; i < WINDOW - MAX_LAG; i++) {
                double delta = signal[i] - signal[i + lag]; sum += delta * delta;
            }
            total += sum;
            difference[lag] = total == 0 ? 1 : sum * lag / total;
        }
        for (int lag = MIN_LAG; lag < MAX_LAG; lag++) {
            if (difference[lag] >= PERIOD_THRESHOLD) continue;
            while (lag < MAX_LAG - 1 && difference[lag + 1] < difference[lag]) lag++;
            double denominator = difference[lag - 1] - 2 * difference[lag] + difference[lag + 1];
            double refined = lag + (denominator == 0 ? 0 : .5 * (difference[lag - 1] - difference[lag + 1]) / denominator);
            return refined * DECIMATION;
        }
        return 0;
    }

    private static double interpolate(double[] values, double position) {
        double frame = position / HOP;
        int base = Math.min(values.length - 1, (int) frame), next = Math.min(values.length - 1, base + 1);
        if (values[base] == 0 || values[next] == 0) return values[base];
        return values[base] + (values[next] - values[base]) * (frame - base);
    }

    private static double peak(short[] source, double expected, double radius) {
        int from = Math.max(0, (int) Math.ceil(expected - radius));
        int to = Math.min(source.length - 1, (int) Math.floor(expected + radius));
        int best = Math.min(source.length - 1, Math.max(0, (int) Math.round(expected)));
        for (int i = from; i <= to; i++) if (source[i] > source[best]) best = i;
        return best;
    }

    private static int flush(short[] source, short[] result, double[] sums, double[] weights, int index, int mix) {
        int slot = index % RING;
        double wet = weights[slot] > MIN_WEIGHT ? sums[slot] / weights[slot] : source[index];
        result[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(source[index] + (wet - source[index]) * mix / 100)));
        sums[slot] = weights[slot] = 0;
        return index + 1;
    }
}
