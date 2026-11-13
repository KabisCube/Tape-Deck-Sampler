package com.tapedecksampler.app;

public final class RobotEffect {
    public static final int DEFAULT_MORPH = 50;
    public static final int ROBOT = 0, GIANT = 1, CHIP = 2, WHISPER = 3, PROFILE_VERSION = 3;
    public static final int DEFAULT_PROFILE = ROBOT;
    public static final String[] PROFILES = {"Robot", "Giant", "Chip", "Whisper"};
    private static final int DECIMATION = 3, RATE = Tape.RATE / DECIMATION, WARMUP = RATE / 10;
    private static final int CANCEL_INTERVAL = 1024;
    private static final double REST_HZ = 130.81, MIN_HZ = 50, MAX_HZ = 1200, GLIDE = coefficient(12);
    private static final double PCM_SCALE = 32768, MIN_FREQUENCY = 100, MAX_FREQUENCY = 6500;
    private static final double ATTACK = coefficient(2), RELEASE = coefficient(18), CARRIER_FOLLOW = coefficient(60);
    private static final double MIN_CARRIER_POWER = .000001, HEADROOM = .96, MAX_GAIN = 8, CASCADE_BANDWIDTH = .75;
    private static final int[] BANDS = {20, 24, 24, 24};
    private static final double[] FORMANTS = {1, .72, 1.35, 1};
    private static final double[] REGISTERS = {1, .5, 2, 1};
    private static final int THREE_VOICE_VERSION = 2;
    private static final int[] LEGACY_PROFILES = {ROBOT, ROBOT, GIANT, CHIP, WHISPER, ROBOT, ROBOT, ROBOT};
    private static final int[] THREE_VOICE_PROFILES = {ROBOT, WHISPER, ROBOT};

    private RobotEffect() {}

    public static int savedProfile(int profile, int version) {
        if (version >= PROFILE_VERSION) return Math.max(ROBOT, Math.min(PROFILES.length - 1, profile));
        int[] profiles = version == THREE_VOICE_VERSION ? THREE_VOICE_PROFILES : LEGACY_PROFILES;
        return profile >= 0 && profile < profiles.length ? profiles[profile] : ROBOT;
    }

    public static short[] process(short[] source, Sequencer.Effects controls) {
        if (!controls.hasRobot() || source.length == 0) return source;
        int profile = controls.robotProfile;
        int count = (source.length + DECIMATION - 1) / DECIMATION;
        double[] wet = new double[count];
        Band[] bands = new Band[BANDS[profile]];
        double shift = FORMANTS[profile] * Math.pow(2, (controls.robotMorph - DEFAULT_MORPH) / 100.0);
        double ratio = Math.pow(MAX_FREQUENCY / MIN_FREQUENCY, 1.0 / (bands.length - 1));
        double quality = CASCADE_BANDWIDTH / (Math.sqrt(ratio) - 1 / Math.sqrt(ratio));
        for (int i = 0; i < bands.length; i++) {
            double frequency = MIN_FREQUENCY * Math.pow(ratio, i);
            bands[i] = new Band(frequency, Math.min(MAX_FREQUENCY, frequency * shift), quality);
        }
        double[] periods = profile == WHISPER ? new double[0] : PitchCorrector.periods(source);
        double hz = REST_HZ;
        for (double period : periods) if (period > 0) { hz = Tape.RATE / period; break; }
        hz = Math.max(MIN_HZ, Math.min(MAX_HZ, hz));
        Carrier carrier = new Carrier(profile, hz * REGISTERS[profile]);
        for (int i = 0; i < WARMUP; i++) {
            double value = carrier.next();
            for (Band band : bands) band.carrier(value);
        }
        double inputPower = 0, wetPower = 0, peak = 0;
        for (int i = 0; i < count; i++) {
            if (i % CANCEL_INTERVAL == 0 && Thread.currentThread().isInterrupted()) throw new IllegalStateException("Robot processing cancelled");
            double sample = 0;
            int start = i * DECIMATION, end = Math.min(source.length, start + DECIMATION);
            for (int j = start; j < end; j++) sample += source[j] / PCM_SCALE;
            sample /= end - start;
            inputPower += sample * sample;
            double period = PitchCorrector.periodAt(periods, start);
            if (period > 0) hz += (Math.max(MIN_HZ, Math.min(MAX_HZ, Tape.RATE / period)) - hz) * GLIDE;
            carrier.tune(hz * REGISTERS[profile]);
            double excitation = carrier.next(), output = 0;
            for (Band band : bands) output += band.process(sample, excitation);
            wet[i] = output;
            wetPower += output * output;
            peak = Math.max(peak, Math.abs(output));
        }
        double gain = wetPower == 0 ? 0 : Math.min(MAX_GAIN, Math.sqrt(inputPower / wetPower));
        if (peak > 0) gain = Math.min(gain, HEADROOM / peak);
        short[] result = new short[source.length];
        double mix = controls.robotMix / 100.0;
        for (int i = 0; i < result.length; i++) {
            int index = i / DECIMATION, next = Math.min(count - 1, index + 1);
            double value = wet[index] + (wet[next] - wet[index]) * (i % DECIMATION) / DECIMATION;
            result[i] = (short) Math.round(source[i] * (1 - mix) + value * gain * PCM_SCALE * mix);
        }
        return result;
    }

    private static double coefficient(double milliseconds) {
        return 1 - Math.exp(-1000 / (RATE * milliseconds));
    }

    private static final class Band {
        private final Filter analysis, analysisDetail, synthesis, synthesisDetail;
        private double envelope, power;

        Band(double frequency, double shifted, double quality) {
            analysis = new Filter(frequency, quality);
            analysisDetail = new Filter(frequency, quality);
            synthesis = new Filter(shifted, quality);
            synthesisDetail = new Filter(shifted, quality);
        }

        double carrier(double value) {
            double filtered = synthesisDetail.process(synthesis.process(value));
            power += (filtered * filtered - power) * CARRIER_FOLLOW;
            return filtered / Math.sqrt(Math.max(MIN_CARRIER_POWER, power));
        }

        double process(double sample, double excitation) {
            double value = Math.abs(analysisDetail.process(analysis.process(sample)));
            envelope += (value - envelope) * (value > envelope ? ATTACK : RELEASE);
            return envelope * carrier(excitation);
        }
    }

    private static final class Filter {
        private final double b, a1, a2;
        private double z1, z2;

        Filter(double frequency, double quality) {
            double angle = 2 * Math.PI * frequency / RATE;
            double alpha = Math.sin(angle) / (2 * quality);
            b = alpha / (1 + alpha);
            a1 = -2 * Math.cos(angle) / (1 + alpha);
            a2 = (1 - alpha) / (1 + alpha);
        }

        double process(double value) {
            double output = b * value + z1;
            z1 = -a1 * output + z2;
            z2 = -b * value - a2 * output;
            return output;
        }
    }

    private static final class Carrier {
        private static final double NOISE_MIX = .08, PULSE_WIDTH = .2;
        private final int profile;
        private double step, phase;
        private int random = 0x13579bdf;

        Carrier(int profile, double frequency) {
            this.profile = profile;
            step = frequency / RATE;
        }

        void tune(double frequency) { step = frequency / RATE; }

        double next() {
            phase = (phase + step) % 1;
            random ^= random << 13;
            random ^= random >>> 17;
            random ^= random << 5;
            double noise = random / (double) Integer.MAX_VALUE;
            double saw = phase * 2 - 1;
            switch (profile) {
                case ROBOT: return (phase < PULSE_WIDTH ? 1 : -PULSE_WIDTH / (1 - PULSE_WIDTH)) + noise * NOISE_MIX;
                case WHISPER: return noise;
                case GIANT:
                case CHIP: return saw + noise * NOISE_MIX;
                default: throw new IllegalArgumentException("Unknown Robot profile");
            }
        }
    }
}
