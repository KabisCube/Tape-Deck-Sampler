package com.tapedecksampler.app;

import java.util.Arrays;

/**
 * Live circuit-bending emulation, modelled on bent kids' toys.
 * The circuit picks which "pins are bridged" (what kind of glitch events fire), while the
 * continuous knobs (clock, grit, feedback) act like pots soldered across the board.
 * Everything is deterministic from a fixed seed so exports and re-renders match.
 */
public final class CircuitBend {
	public static final String[] CIRCUITS = {"Stutter", "Clock", "Short", "Howl", "Battery", "Chaos"};
	public static final String[] CIRCUIT_HINTS = {"LOOP-LOCKS SHORT CHUNKS", "CLOCK JUMPS AND DIVES", "SHORTED DATA LINES / CRUNCH", "OUTPUT WIRED BACK IN / SQUEALS", "DYING BATTERY SAG AND CRACKLE", "EVERY BRIDGE AT ONCE"};
	public static final String[] SCOPES = {"Track", "Device"};
	public static final int STUTTER = 0, CLOCK = 1, SHORT = 2, HOWL = 3, BATTERY = 4, CHAOS = 5;
	public static final int TRACK = 0, DEVICE = 1;
	public static final int DEFAULT_CIRCUIT = CHAOS, DEFAULT_SCOPE = TRACK;
	public static final int DEFAULT_CHAOS = 35, NEUTRAL_CLOCK = 50, DEFAULT_GRIT = 15, DEFAULT_FEEDBACK = 0, DEFAULT_MIX = 100;
	public static final int MIN_SIZE_MS = 5, MAX_SIZE_MS = 500, DEFAULT_SIZE_MS = 80;

	private static final int HISTORY = 1 << 16, HISTORY_MASK = HISTORY - 1;
	private static final int WIRE = 1 << 12, WIRE_MASK = WIRE - 1;
	private static final int MIN_SPAN = 64, MAX_EVENT = HISTORY / 2;
	private static final double MAX_EVENTS_PER_SECOND = 12;
	/** The clock knob is lopsided: the lower half bends down further than the upper half bends up. */
	private static final double CLOCK_OCTAVES_DOWN = 4, CLOCK_OCTAVES_UP = 2;
	private static final float FEEDBACK_CEILING = 0.95f, HOWL_GAIN = 1.08f, DC_POLE = 0.995f;
	private static final float DROP_RATE = 0.002f, RECOVER_RATE = 0.0002f, FOLLOW_DECAY = 0.9995f;
	private static final int GATE_FRAMES = 48, MAX_GRIT_BITS_LOST = 11, MAX_SHORT_HOLD = 24;
	private static final double[] CLOCK_JUMPS = {0.25, 0.5, 0.75, 1.5, 2, 3};
	private static final long SEED = 0x9e3779b97f4a7c15L;

	private float[] history, wire;
	private int write, wirePos, wireLength = MIN_SPAN;
	private double lag;
	private long seed = SEED;
	private int event = -1, eventLeft;
	private double eventSpeed = 1, eventRamp = 1;
	private int loopStart, loopLength;
	private double loopPos;
	private boolean loopReverse;
	private int shortBits = 16, shortHold = 1, stuckOr, stuckAnd = -1, flip;
	private int holdCount;
	private float held, dcIn, dcOut;
	private float voltage = 1, follower;
	private int gateCount;
	private boolean gateOpen = true;
	private final int[] events = new int[CHAOS];

	/** Playback rate for the clock knob: 0 = x1/16, 50 = x1 (stock), 100 = x4. */
	public static double clockRate(int clock) {
		double position = (Math.max(0, Math.min(100, clock)) - NEUTRAL_CLOCK) / (double) NEUTRAL_CLOCK;
		return Math.pow(2, position * (position < 0 ? CLOCK_OCTAVES_DOWN : CLOCK_OCTAVES_UP));
	}

	public static int sizeFrames(int ms) {
		return Math.max(MIN_SPAN, Math.max(MIN_SIZE_MS, Math.min(MAX_SIZE_MS, ms)) * (Tape.RATE / 1000));
	}

	/** Bits left on the data bus at a given grit amount (16 = clean). */
	public static int gritBits(int grit) {
		return 16 - Math.round(Math.max(0, Math.min(100, grit)) / 100f * MAX_GRIT_BITS_LOST);
	}

	/** Glitch events of one kind (STUTTER..BATTERY) started since the last call; drives the rare display glitches. */
	public int takeEvents(int type) {
		int count = events[type];
		events[type] = 0;
		return count;
	}

	/** All glitch events started since the last call, of every kind. */
	public int takeEvents() {
		int count = 0;
		for (int type = 0; type < events.length; type++) count += takeEvents(type);
		return count;
	}

	public float process(float input, Sequencer.Effects controls) {
		int circuit = Math.max(0, Math.min(CIRCUITS.length - 1, controls.bendCircuit));
		float chaos = clamp01(controls.bendChaos / 100f);
		float grit = clamp01(controls.bendGrit / 100f);
		float feedback = clamp01(controls.bendFeedback / 100f);
		float mix = clamp01(controls.bendMix / 100f);
		int size = sizeFrames(controls.bendSize);
		if (history == null) { history = new float[HISTORY]; wire = new float[WIRE]; }
		if (event != HOWL) wireLength = Math.max(16, Math.min(WIRE - 1, size / 8));

		float value = input;
		float loop = event == HOWL ? Math.max(HOWL_GAIN, feedback * FEEDBACK_CEILING) : feedback * FEEDBACK_CEILING;
		if (loop > 0) value += (float) Math.tanh(wire[(wirePos - wireLength) & WIRE_MASK] * loop);
		history[write & HISTORY_MASK] = value;

		advanceEvents(circuit, chaos, size);

		double speed = clockRate(controls.bendClock);
		float sag = circuit == BATTERY ? 0.3f + 0.7f * chaos : circuit == CHAOS ? 0.5f * chaos : 0;
		if (sag > 0 || voltage < 1) {
			follower = Math.max(Math.abs(input), follower * FOLLOW_DECAY);
			float target = event == BATTERY ? 0.1f : 1 - sag * Math.min(1, follower * 1.5f);
			voltage += (target - voltage) * (target < voltage ? DROP_RATE : RECOVER_RATE);
			if (voltage > 0.9999f && sag == 0) voltage = 1;
			speed *= 0.5 + 0.5 * voltage;
		}
		if (event == CLOCK) { speed *= eventSpeed; eventSpeed *= eventRamp; }
		if (Math.abs(speed - 1) < 1e-4) speed = 1;

		float wet;
		if (event == STUTTER) {
			int offset = (int) loopPos;
			wet = read(loopStart + (loopReverse ? loopLength - 1 - offset : offset));
			loopPos += speed;
			while (loopPos >= loopLength) loopPos -= loopLength;
		} else if (speed != 1) {
			int span = Math.max(MIN_SPAN, size);
			lag += 1 - speed;
			while (lag < 0) lag += span;
			while (lag > span) lag -= span;
			wet = read(write - lag);
		} else {
			lag = 0;
			wet = value;
		}

		// The feedback wire taps the signal before the data-line damage: a quantizer inside the loop
		// can push loop gain above 1 and make it howl forever, even from silence.
		wire[wirePos] = Math.max(-2, Math.min(2, wet));
		wirePos = (wirePos + 1) & WIRE_MASK;
		if (grit > 0 || event == SHORT) wet = damage(wet, grit);
		else { holdCount = 0; dcIn = dcOut = 0; }

		if (voltage < 0.999f) {
			float rail = 0.25f + 0.75f * voltage;
			wet = Math.max(-rail, Math.min(rail, wet));
			if (--gateCount <= 0) { gateCount = GATE_FRAMES; gateOpen = voltage > 0.45f || nextFloat() < voltage * 1.8f; }
			if (!gateOpen) wet = 0;
		}

		wet = Math.max(-2, Math.min(2, wet));
		if (!Float.isFinite(wet)) { reset(); return input; }
		write++;
		return input + (wet - input) * mix;
	}

	private float damage(float sample, float grit) {
		if (--holdCount <= 0) {
			int bits = 16 - Math.round(grit * MAX_GRIT_BITS_LOST);
			if (event == SHORT) bits = Math.min(bits, shortBits);
			holdCount = event == SHORT ? shortHold : 1 + (int) (grit * grit * 6);
			int levels = 1 << (bits - 1);
			int code = Math.round(Math.max(-1, Math.min(1, sample)) * levels) + levels;
			if (event == SHORT) code = ((code ^ flip) | stuckOr) & stuckAnd;
			if (grit > 0 && code != levels && nextFloat() < grit * grit * grit * 0.02f) code ^= 1 << nextInt(bits);
			held = (code - levels) / (float) levels;
		}
		float difference = held - sample;
		float blocked = difference - dcIn + DC_POLE * dcOut;
		dcIn = difference; dcOut = blocked;
		return sample + blocked;
	}

	private void advanceEvents(int circuit, float chaos, int size) {
		if (event >= 0) {
			if (--eventLeft <= 0) event = -1;
			return;
		}
		if (chaos <= 0 || nextDouble() >= chaos * chaos * MAX_EVENTS_PER_SECOND / Tape.RATE) return;
		start(circuit == CHAOS ? nextInt(CHAOS) : circuit, size);
	}

	private void start(int type, int size) {
		event = type;
		events[type]++;
		switch (type) {
			case STUTTER:
				loopLength = Math.max(MIN_SPAN / 2, size >> nextInt(3));
				loopStart = write - loopLength;
				loopPos = 0;
				loopReverse = nextFloat() < 0.25f;
				eventLeft = Math.min(MAX_EVENT, loopLength * (2 + nextInt(6)));
				break;
			case CLOCK:
				eventSpeed = CLOCK_JUMPS[nextInt(CLOCK_JUMPS.length)];
				eventLeft = Math.min(MAX_EVENT, size * (1 + nextInt(4)));
				eventRamp = nextFloat() < 0.4f ? Math.pow(0.25, 1.0 / eventLeft) : 1;
				break;
			case SHORT:
				shortBits = 3 + nextInt(6);
				shortHold = 1 + nextInt(MAX_SHORT_HOLD);
				stuckOr = nextFloat() < 0.5f ? 1 << nextInt(shortBits) : 0;
				stuckAnd = ~(1 << nextInt(shortBits));
				flip = nextFloat() < 0.5f ? 1 << nextInt(shortBits) : 0;
				holdCount = 0;
				eventLeft = Math.min(MAX_EVENT, size * (1 + nextInt(3)));
				break;
			case HOWL:
				wireLength = 20 + nextInt(780);
				eventLeft = Math.min(MAX_EVENT, size * (2 + nextInt(4)));
				break;
			default:
				eventLeft = Math.min(MAX_EVENT, size * (1 + nextInt(3)));
				break;
		}
	}

	private float read(double position) {
		double floor = Math.floor(position);
		int index = (int) floor;
		float blend = (float) (position - floor);
		float a = history[index & HISTORY_MASK], b = history[(index + 1) & HISTORY_MASK];
		return a + (b - a) * blend;
	}

	private long next() {
		seed ^= seed >>> 12; seed ^= seed << 25; seed ^= seed >>> 27;
		return seed * 0x2545f4914f6cdd1dL;
	}

	private double nextDouble() { return (next() >>> 11) * 0x1.0p-53; }
	private float nextFloat() { return (next() >>> 40) * 0x1.0p-24f; }
	private int nextInt(int bound) { return (int) ((next() >>> 33) % bound); }
	private static float clamp01(float value) { return Math.max(0, Math.min(1, value)); }

	public void reset() {
		if (history != null) { Arrays.fill(history, 0); Arrays.fill(wire, 0); }
		write = wirePos = 0; wireLength = MIN_SPAN; lag = 0;
		seed = SEED; event = -1; eventLeft = 0; eventSpeed = eventRamp = 1;
		loopStart = loopLength = 0; loopPos = 0; loopReverse = false;
		shortBits = 16; shortHold = 1; stuckOr = 0; stuckAnd = -1; flip = 0;
		holdCount = 0; held = dcIn = dcOut = 0;
		voltage = 1; follower = 0; gateCount = 0; gateOpen = true;
	}
}
