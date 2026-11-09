package com.tapedecksampler.app;

import java.util.Arrays;

public final class TrackEffects {
    public static final String[] DISTORTIONS = {"Soft clip", "Hard clip", "Foldback", "Bitcrush"};
    public static final String[] DELAY_BEATS = {"1/32", "1/16T", "1/16", "1/8T", "1/16D", "1/8", "1/4T", "1/8D", "1/4", "1/2T", "1/4D", "1/2", "1/2D", "1 bar"};
    private static final int[] DELAY_24THS = {3, 4, 6, 8, 9, 12, 16, 18, 24, 32, 36, 48, 72, 96};
    public static final int MAX_ECHOES = 8;
    private static final int MAX_DELAY_BUFFER = Tape.RATE * 16;
    private static final int SOFT_CLIP = 1, HARD_CLIP = 2, FOLDBACK = 3, BITCRUSH = 4;
    private static final int[] COMB_LENGTHS = {1499, 1601, 1747, 1867};
    private static final int[] ALLPASS_LENGTHS = {241, 83};
    private static final float COMB_DAMPING = 0.7f, COMB_INPUT = 0.22f, COMB_FEEDBACK = 0.79f;
    private static final float ALLPASS_GAIN = 0.5f;
    private static final float REVERB_DRY_DUCK = 0.25f, REVERB_WET_GAIN = 0.6f;
    private static final int MAX_FILTER_HZ = 20000;
    private static final float DECLICK_FRAMES = 48f;
    private static final int LEVEL_SMOOTHING_FRAMES = 2400;
    private static final double MIN_LEVEL_POWER = 1e-15, MAX_LEVEL_GAIN = 8;
    private static final int SOFT_DRIVE_RANGE = 15, HARD_DRIVE_RANGE = 25, FOLD_PERIOD = 4;
    private static final int PCM_BITS = 15, MAX_CRUSH_BITS = 12, MAX_CRUSH_HOLD = 15;

    private final float[][] combs = new float[COMB_LENGTHS.length][];
    private final int[] positions = new int[COMB_LENGTHS.length];
    private final float[] damping = new float[COMB_LENGTHS.length];
    private final float[][] allpass = new float[ALLPASS_LENGTHS.length][];
    private final int[] diffusion = new int[ALLPASS_LENGTHS.length];
    private int crushCounter;
    private float held;
    private boolean wet;
    private double inputPower, outputPower;
    private int previousType, previousDrive, previousMix;
    private int highPass = -1, lowPass = -1;
    private float highCoefficient, lowCoefficient, highState, lowState;
    private float[] delay;
    private int delayPosition;
    private boolean delayActive;

    public TrackEffects() {
        for (int i = 0; i < combs.length; i++) combs[i] = new float[COMB_LENGTHS[i]];
        for (int i = 0; i < allpass.length; i++) allpass[i] = new float[ALLPASS_LENGTHS[i]];
    }

    public boolean prepareBlock(Sequencer.Effects controls) {
        if (controls.powered(Sequencer.Effects.HIGH_PASS) && controls.highPass > 0
                || controls.powered(Sequencer.Effects.LOW_PASS) && controls.lowPass > 0
                || controls.powered(Sequencer.Effects.DISTORTION) && controls.distortion != 0 && controls.distortionMix > 0
                || controls.powered(Sequencer.Effects.REVERB) && controls.reverb > 0
                || controls.powered(Sequencer.Effects.DELAY) && controls.delayMix > 0) return true;
        process(0, controls, Sequencer.DEFAULT_BPM);
        return false;
    }

    public static int delayFrames(int beat, int bpm) {
        int index = Math.max(0, Math.min(DELAY_24THS.length - 1, beat));
        return Math.max(1, (int) Math.round(Tape.RATE * 60.0 / Math.max(1, bpm) * DELAY_24THS[index] / 24.0));
    }

    public static float echoGain(int echo, int echoes) {
        return (echoes + 1 - echo) / (float) (echoes + 1);
    }

    public float process(float input, Sequencer.Effects controls, int bpm) {
        float value = input;
        for (int group : controls.chain) {
            switch (group) {
                case Sequencer.Effects.HIGH_PASS: value = highPassed(value, controls.powered(group) ? controls.highPass : 0); break;
                case Sequencer.Effects.LOW_PASS: value = lowPassed(value, controls.powered(group) ? controls.lowPass : 0); break;
                case Sequencer.Effects.DISTORTION:
                    value = distortion(value, controls.powered(group) ? controls.distortion : 0, controls.drive, controls.distortionMix);
                    break;
                case Sequencer.Effects.REVERB: value = reverb(value, controls.powered(group) ? controls.reverb : 0); break;
                case Sequencer.Effects.DELAY: value = delay(value, controls, bpm); break;
                default: break;
            }
        }
        return value;
    }

    private float highPassed(float input, int frequency) {
        if (highPass != frequency) {
            highPass = frequency; highState = 0;
            highCoefficient = coefficient(highPass);
        }
        if (highPass <= 0) return input;
        highState += highCoefficient * (input - highState);
        return input - highState;
    }

    private float lowPassed(float input, int frequency) {
        if (lowPass != frequency) {
            lowPass = frequency; lowState = 0;
            lowCoefficient = coefficient(lowPass);
        }
        if (lowPass <= 0) return input;
        lowState += lowCoefficient * (input - lowState);
        return lowState;
    }

    private float delay(float input, Sequencer.Effects controls, int bpm) {
        float value = input;
        if (controls.powered(Sequencer.Effects.DELAY) && controls.delayMix > 0) {
            int echoes = Math.max(1, Math.min(MAX_ECHOES, controls.delayEchoes));
            int frames = delayFrames(controls.delayBeat, bpm);
            int needed = Math.min(MAX_DELAY_BUFFER, frames * echoes + 1);
            if (delay == null || delay.length < needed) growDelay(needed);
            delay[delayPosition] = value;
            float echo = 0;
            for (int k = 1; k <= echoes; k++) {
                int age = frames * k;
                if (age >= delay.length) break;
                int read = delayPosition - age;
                if (read < 0) read += delay.length;
                echo += delay[read] * echoGain(k, echoes);
            }
            if (++delayPosition == delay.length) delayPosition = 0;
            value += echo * controls.delayMix / 100f;
            delayActive = true;
        } else if (delayActive) {
            Arrays.fill(delay, 0); delayPosition = 0; delayActive = false;
        }
        return value;
    }

    private static float coefficient(int frequency) {
        return (float) (1 - Math.exp(-2 * Math.PI * Math.max(0, Math.min(MAX_FILTER_HZ, frequency)) / Tape.RATE));
    }

    private void growDelay(int length) {
        float[] buffer = new float[length];
        if (delay != null) {
            for (int age = 1; age <= delay.length; age++) {
                int read = delayPosition - age;
                if (read < 0) read += delay.length;
                buffer[length - age] = delay[read];
            }
        }
        delay = buffer; delayPosition = 0;
    }

    public static float envelope(int age, int remaining, Sequencer.Effects controls) {
        if (!controls.powered(Sequencer.Effects.ENVELOPE)) return Math.min(1f, Math.min(age / DECLICK_FRAMES, remaining / DECLICK_FRAMES));
        float framesPerMs = Tape.RATE / 1000f;
        float attackFrames = controls.attack * framesPerMs;
        float decayFrames = controls.decay * framesPerMs;
        float sustain = controls.sustain / 100f;
        float level = age < attackFrames ? age / attackFrames
            : age < attackFrames + decayFrames ? 1 - (1 - sustain) * (age - attackFrames) / decayFrames : sustain;
        float releaseFrames = Math.max(DECLICK_FRAMES, controls.release * framesPerMs);
        return level * Math.min(1f, Math.min(age / DECLICK_FRAMES, remaining / releaseFrames));
    }

    public float process(float input, int type, int drive, int reverb) {
        return process(input, type, drive, 100, reverb);
    }

    public float process(float input, int type, int drive, int mix, int reverb) {
        return reverb(distortion(input, type, drive, mix), reverb);
    }

    private float distortion(float input, int type, int drive, int mix) {
        float value = input;
        if (type != 0 && mix > 0) {
            if (type != previousType || drive != previousDrive || mix != previousMix) {
                inputPower = outputPower = 0;
                held = 0; crushCounter = 0;
            }
            float blend = Math.max(0, Math.min(100, mix)) / 100f;
            value = input + (distort(input, type, drive) - input) * blend;
            inputPower += (input * input - inputPower) / LEVEL_SMOOTHING_FRAMES;
            outputPower += (value * value - outputPower) / LEVEL_SMOOTHING_FRAMES;
            if (outputPower > MIN_LEVEL_POWER) value *= (float) Math.min(MAX_LEVEL_GAIN, Math.sqrt(inputPower / outputPower));
        }
        previousType = type; previousDrive = drive; previousMix = mix;
        return value;
    }

    private float reverb(float value, int reverb) {
        if (reverb <= 0) {
            if (wet) resetReverb();
            return value;
        }
        wet = true;
        float reflections = 0;
        for (int i = 0; i < combs.length; i++) {
            float delayed = combs[i][positions[i]];
            damping[i] = delayed * COMB_DAMPING + damping[i] * (1 - COMB_DAMPING);
            combs[i][positions[i]] = value * COMB_INPUT + damping[i] * COMB_FEEDBACK;
            positions[i] = (positions[i] + 1) % combs[i].length;
            reflections += delayed;
        }
        for (int i = 0; i < allpass.length; i++) {
            float delayed = allpass[i][diffusion[i]];
            float output = delayed - reflections * ALLPASS_GAIN;
            allpass[i][diffusion[i]] = reflections + output * ALLPASS_GAIN;
            diffusion[i] = (diffusion[i] + 1) % allpass[i].length;
            reflections = output;
        }
        float reverberation = reverb / 100f;
        return value * (1 - reverberation * REVERB_DRY_DUCK) + reflections * reverberation * REVERB_WET_GAIN;
    }

    public static float shape(float sample, int type, int drive) {
        float amount = Math.max(0, Math.min(100, drive)) / 100f;
        switch (type) {
            case SOFT_CLIP: return (float) (Math.tanh(sample * (1 + SOFT_DRIVE_RANGE * amount)) / Math.tanh(1 + SOFT_DRIVE_RANGE * amount));
            case HARD_CLIP: return Math.max(-1, Math.min(1, sample * (1 + HARD_DRIVE_RANGE * amount)));
            case FOLDBACK:
                float folded = sample * (1 + SOFT_DRIVE_RANGE * amount) + 1;
                folded -= FOLD_PERIOD * (float) Math.floor(folded / FOLD_PERIOD);
                return 1 - Math.abs(folded - FOLD_PERIOD / 2);
            case BITCRUSH:
                float levels = Math.max(2, 8 - Math.round(amount * 6));
                return Math.round(sample * levels) / levels;
            default: return sample;
        }
    }

    private float distort(float sample, int type, int drive) {
        if (type != BITCRUSH) return shape(sample, type, drive);
        float amount = Math.max(0, Math.min(100, drive)) / 100f;
        if (crushCounter-- <= 0) {
            int levels = 1 << (PCM_BITS - Math.round(amount * MAX_CRUSH_BITS));
            held = Math.round(Math.max(-1, Math.min(1, sample)) * levels) / (float) levels;
            crushCounter = Math.round(amount * MAX_CRUSH_HOLD);
        }
        return held;
    }

    private void resetReverb() {
        for (float[] buffer : combs) Arrays.fill(buffer, 0);
        for (float[] buffer : allpass) Arrays.fill(buffer, 0);
        Arrays.fill(positions, 0); Arrays.fill(damping, 0); Arrays.fill(diffusion, 0);
        wet = false;
    }

    public void reset() {
        resetReverb();
        if (delay != null) Arrays.fill(delay, 0);
        delayPosition = 0; delayActive = false;
        highPass = lowPass = -1; highState = lowState = 0;
        held = 0; crushCounter = 0;
        inputPower = outputPower = 0;
        previousType = previousDrive = previousMix = -1;
    }
}
