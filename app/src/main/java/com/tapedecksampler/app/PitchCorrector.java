package com.tapedecksampler.app;

public final class PitchCorrector {
    public static final int CHROMATIC = 0;
    public static final String[] KEYS = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    public static final String[] SCALES = {"Chromatic", "Major", "Minor", "Major pentatonic", "Minor pentatonic"};
    private static final int[][] NOTES = {{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, {0, 2, 4, 5, 7, 9, 11}, {0, 2, 3, 5, 7, 8, 10}, {0, 2, 4, 7, 9}, {0, 3, 5, 7, 10}};
    private static final int HOP = 240, WINDOW = 512, MIN_LAG = 10, MAX_LAG = 184, RING = 4096;
    private static final int DECIMATION = 4, SEMITONES = 12, A4_MIDI = 69;
    private static final double A4_HZ = 440, MIN_POWER = 10000, MIN_CORRECTION = .01, MARK_SEARCH = .15, PERIOD_THRESHOLD = .15, MIN_RATIO = .5, MIN_WEIGHT = .0001, FADE_WEIGHT = .25, HYSTERESIS = .15, OCTAVE_JUMP = 1.6;

    private PitchCorrector() {}

    public static short[] process(short[] source, Sequencer.Effects controls) {
        if (!controls.hasAutotune() || source.length < WINDOW) return source;
        double[] periods = periods(source);
        int count = periods.length;
        double[] ratios = new double[count];
        double follow = controls.autoRetune == 0 ? 1 : 1 - Math.exp(-HOP * 1000.0 / (Tape.RATE * controls.autoRetune));
        double output = 0;
        int note = Integer.MIN_VALUE;
        boolean changed = false;
        for (int frame = 0; frame < count; frame++) {
            double period = periods[frame];
            if (period == 0) { note = Integer.MIN_VALUE; ratios[frame] = 1; continue; }
            double midi = A4_MIDI + SEMITONES * Math.log(Tape.RATE / period / A4_HZ) / Math.log(2);
            int nearest = nearestNote(midi, controls.autoKey, controls.autoScale);
            if (note == Integer.MIN_VALUE) { note = nearest; output = controls.autoRetune == 0 ? nearest : midi; }
            else if (nearest != note && Math.abs(midi - nearest) + HYSTERESIS < Math.abs(midi - note)) note = nearest;
            output += (note - output) * follow;
            double correction = (output - midi) * controls.autoStrength / 100.0;
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
                double ratio = interpolate(ratios, position);
                ratio = Math.max(MIN_RATIO, ratio);
                double spacing = period / ratio;
                double window = period;
                int from = Math.max(written, (int) Math.ceil(position - window));
                int to = Math.min(source.length - 1, (int) Math.floor(position + window));
                for (int i = from; i <= to; i++) {
                    double offset = i - position;
                    double samplePosition = Math.max(0, Math.min(source.length - 1, mark + offset));
                    int base = (int) samplePosition;
                    int next = Math.min(source.length - 1, base + 1);
                    double sample = source[base] + (source[next] - source[base]) * (samplePosition - base);
                    double weight = .5 + .5 * Math.cos(Math.PI * offset / window);
                    sums[i % RING] += sample * weight; weights[i % RING] += weight;
                }
                position += spacing;
            }
            int limit = Math.min(source.length, (int) position - MAX_LAG * DECIMATION - DECIMATION);
            while (written < limit) written = flush(source, result, sums, weights, written, controls.autoMix);
        }
        while (written < source.length) written = flush(source, result, sums, weights, written, controls.autoMix);
        return result;
    }

    public static double[] periods(short[] source) {
        int count = (source.length + HOP - 1) / HOP;
        double[] periods = new double[count];
        if (source.length < WINDOW * DECIMATION) return periods;
        double[] signal = new double[WINDOW], difference = new double[MAX_LAG + 1];
        for (int frame = 0; frame < count; frame++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Pitch correction cancelled");
            int start = Math.max(0, Math.min(source.length - WINDOW * DECIMATION, frame * HOP - WINDOW * DECIMATION / 2));
            double power = 0;
            for (int i = 0; i < WINDOW; i++) {
                int offset = start + i * DECIMATION;
                signal[i] = offset + 3 < source.length ? (source[offset] + source[offset + 1] + source[offset + 2] + source[offset + 3]) / (double) DECIMATION : 0;
                power += signal[i] * signal[i];
            }
            periods[frame] = power / WINDOW < MIN_POWER ? 0 : detect(signal, difference);
        }
        smooth(periods);
        return periods;
    }

    public static double periodAt(double[] periods, double position) {
        return periods.length == 0 ? 0 : interpolate(periods, position);
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

    private static void smooth(double[] periods) {
        for (int i = 1; i + 1 < periods.length; i++) {
            if (periods[i] == 0 && periods[i - 1] > 0 && periods[i + 1] > 0) periods[i] = (periods[i - 1] + periods[i + 1]) / 2;
        }
        double[] copy = periods.clone();
        for (int i = 1; i + 1 < periods.length; i++) {
            double a = copy[i - 1], b = copy[i], c = copy[i + 1];
            if (a == 0 || b == 0 || c == 0) continue;
            double median = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
            if (b / median > OCTAVE_JUMP || median / b > OCTAVE_JUMP) periods[i] = median;
        }
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
        if (best > 0 && best + 1 < source.length && source[best] >= source[best - 1] && source[best] >= source[best + 1]) {
            double curvature = source[best - 1] - 2.0 * source[best] + source[best + 1];
            if (curvature != 0) return best + .5 * (source[best - 1] - source[best + 1]) / curvature;
        }
        return best;
    }

    private static int flush(short[] source, short[] result, double[] sums, double[] weights, int index, int mix) {
        int slot = index % RING;
        double wet = weights[slot] > MIN_WEIGHT ? sums[slot] / weights[slot] : source[index];
        double blend = mix / 100.0 * Math.min(1, weights[slot] / FADE_WEIGHT);
        result[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(source[index] + (wet - source[index]) * blend)));
        sums[slot] = weights[slot] = 0;
        return index + 1;
    }
}
